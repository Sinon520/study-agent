"""RAG 问答核心链路：检索 → 重排 → 拼 prompt → 流式生成 → 引用溯源。

为什么用**原生 AsyncOpenAI** 而不是 LangChain：
实测发现 `langchain_openai.ChatOpenAI` 会**静默丢弃** `reasoning_content`
（流式与非流式都丢），而 qwen3.7-flash 是思考模型，思维链是我们要展示的一部分。
原生 SDK 的 `delta.reasoning_content` 与 `delta.content` 天然分离，
不需要猴补丁，SSE 场景下这是更稳的选择（详见 docs/踩坑记录.md 第 1 条）。

引用溯源的做法：
1. 检索命中的每个 chunk 都带 `document_id / chunk_index / page_no / content`；
2. 拼 prompt 时给每段加编号 `[1] [2] ...`，并在 system prompt 里要求模型
   "回答里用 [n] 标注依据"；
3. 生成结束后**单独发一个 `event: citations` 帧**，把编号与文档名/页码/片段对应表
   发给前端 —— 这样前端既能在正文里看到 [1]，又能在底部展开看原文。
"""

from __future__ import annotations

import time
from collections.abc import AsyncIterator
from dataclasses import dataclass, field

from app.core.config import get_settings
from app.core.exceptions import BizException
from app.core.llm import get_async_openai_client
from app.core.logger import logger
from app.services.embedding import get_embedder
from app.services.java_client import get_java_client
from app.services.milvus_store import search as milvus_search
from app.services.reranker import get_reranker

#: system prompt —— 明确约束"只用资料回答、资料没有就说不知道、标注编号"
SYSTEM_PROMPT = """你是一位严谨的备考学习助手，服务于考研 / 考公的学生。

回答规则（必须严格遵守）：
1. **只依据下面给出的「参考资料」回答**。资料中没有提到的内容，不要凭自己的知识补充。
2. 如果参考资料无法回答这个问题，**只用一句话**回答：「资料里没有相关内容」，
   然后可以简短说明需要补充哪方面的资料。**不要展开讲述、不要编造、不要用你自己的知识作答**。
3. 引用资料时，在相应句子后面用 [编号] 标注依据，例如「定积分的几何意义是曲边梯形的面积 [1]」。
4. 回答用中文，结构清晰：先给结论，再给依据与步骤；数学公式用 LaTeX 写法。
5. 不要复述这些规则，也不要提及"根据参考资料"这类套话，直接回答即可。"""

#: 没有检索到资料时额外叠加的硬约束。
#: 实测发现：只在 system prompt 里写"资料没有就说不知道"，
#: 面对"量子纠缠"这种模型自己很熟的问题，它仍会绕开约束、直接凭记忆长篇作答
#: （这与 RAG 的核心价值相悖：宁可说不知道，也不能让用户以为答案来自他的资料）。
#: 所以在**确认没召回**的情况下，再补一段更强的指令，把行为锁死。
NO_MATERIAL_PROMPT = """【本轮没有检索到任何参考资料】

因此你**不能**使用自己的知识回答这个问题。请严格只输出下面这句话，
不要添加任何解释、不要列举量子计算等无关内容：

资料里没有相关内容，建议先上传相关的复习资料后再提问。"""


@dataclass
class RetrievedChunk:
    """一条召回并（可选）重排后的资料片段。"""

    citation_index: int
    vector_id: str
    document_id: int
    document_name: str
    chunk_index: int
    page_no: int | None
    content: str
    vector_score: float = 0.0
    rerank_score: float | None = None

    def to_citation(self) -> dict:
        """转成给前端的引用结构。"""
        return {
            "index": self.citation_index,
            "documentId": self.document_id,
            "documentName": self.document_name,
            "chunkId": self.vector_id,
            "chunkIndex": self.chunk_index,
            "pageNo": self.page_no or None,
            "score": round(self.rerank_score if self.rerank_score is not None else self.vector_score, 4),
            # 片段截断后再给前端：引用卡片只需要"能定位"的上下文，
            # 完整片段留在库里，前端点开可另调详情接口
            "snippet": self.content[:300],
        }


@dataclass
class RagContext:
    """一次问答的准备结果。"""

    chunks: list[RetrievedChunk] = field(default_factory=list)
    prompt: str = ""
    has_material: bool = False


def retrieve(
    user_id: int,
    question: str,
    top_k: int | None = None,
    rerank_top_k: int | None = None,
) -> list[RetrievedChunk]:
    """向量召回 + 重排。"""
    settings = get_settings()
    recall_k = top_k or settings.retrieve_top_k
    final_k = rerank_top_k or settings.rerank_top_k

    # 1) 查询向量化
    embedder = get_embedder()
    started = time.perf_counter()
    query_vector = embedder.encode([question])[0]
    embed_seconds = time.perf_counter() - started

    # 2) 向量召回（按 user_id 过滤）
    started = time.perf_counter()
    hits = milvus_search(user_id=user_id, query_vector=query_vector, top_k=recall_k)
    search_seconds = time.perf_counter() - started
    logger.info(
        "RAG 召回: user_id={} 问题长度={} 命中={} | 向量化 {:.3f}s 检索 {:.3f}s",
        user_id,
        len(question),
        len(hits),
        embed_seconds,
        search_seconds,
    )

    if not hits:
        return []

    # 3) 重排（本地 BGE-Reranker，常驻内存）
    started = time.perf_counter()
    reranker = get_reranker()
    ranked = reranker.rerank(question, [h["content"] for h in hits], top_k=final_k)
    rerank_seconds = time.perf_counter() - started

    # 4) 组装
    document_ids = {int(h["document_id"]) for h in hits}
    name_map = _load_document_names(document_ids)

    chunks: list[RetrievedChunk] = []
    for order, (original_index, score) in enumerate(ranked, start=1):
        hit = hits[original_index]
        document_id = int(hit["document_id"])
        chunks.append(
            RetrievedChunk(
                citation_index=order,
                vector_id=str(hit["id"]),
                document_id=document_id,
                document_name=name_map.get(document_id, f"文档#{document_id}"),
                chunk_index=int(hit.get("chunk_index") or 0),
                page_no=int(hit["page_no"]) if hit.get("page_no") else None,
                content=hit["content"],
                vector_score=float(hit.get("score") or 0.0),
                rerank_score=float(score),
            )
        )

    logger.info(
        "RAG 重排完成: {} 条 -> top{} | 耗时 {:.3f}s | 最高分 {:.4f}",
        len(hits),
        len(chunks),
        rerank_seconds,
        chunks[0].rerank_score if chunks else 0.0,
    )
    return chunks


def _load_document_names(document_ids: set[int]) -> dict[int, str]:
    """从 Java 取文档名（引用溯源要显示"哪份资料的哪一页"）。

    取不到时降级为"文档#id"，不因为一个展示字段让整条问答链路失败。
    """
    if not document_ids:
        return {}
    try:
        from app.services.doc_registry import get_document_names

        return get_document_names(document_ids)
    except Exception as exc:  # noqa: BLE001
        logger.warning("获取文档名失败，引用将退化为 ID 展示: {!r}", exc)
        return {}


def build_context(
    user_id: int,
    question: str,
    session_id: int | None = None,
) -> RagContext:
    """检索 + 拼 prompt（不含生成）。

    <h3>为什么要按重排分数过滤</h3>
    向量召回**永远会返回 top_k 条**，哪怕库里根本没有相关内容 ——
    余弦相似度对任意两段中文都会是正数，区分不了"相关"与"无关"。
    实测：只有一份《定积分复习要点》的库，问"量子纠缠在量子计算中的应用"，
    依然召回 3 条、还拼进 prompt 让模型去"依据资料回答"。

    BGE-Reranker 是 cross-encoder，对不相关的组合会给明显负分
    （相关 ≈ +0.2~+0.6，不相关 ≈ -6~-11），所以用它的分数当门槛，
    低于 {@code settings.min_score} 的直接丢掉；一条都不剩时
    就走"资料里没有相关内容"的分支。这是 RAG 防幻觉最关键的一道闸。
    """
    settings = get_settings()
    chunks = retrieve(user_id, question)

    if chunks:
        logger.info(
            "RAG 重排分数: 最高 {:.4f} 最低 {:.4f} | 门槛 {:.4f}",
            chunks[0].rerank_score or 0.0,
            min((c.rerank_score or 0.0) for c in chunks),
            settings.min_score,
        )

    # 相似度过低的召回直接丢掉：宁可说"资料里没有"，也不要拿不相关的片段硬编
    if settings.min_score > 0:
        kept = [c for c in chunks if (c.rerank_score or 0.0) >= settings.min_score]
        if len(kept) != len(chunks):
            logger.info(
                "按重排分数过滤: {} 条 -> {} 条（门槛 {:.4f}）",
                len(chunks),
                len(kept),
                settings.min_score,
            )
        chunks = kept

    if not chunks:
        logger.info("没有达到相关度门槛的资料，将引导模型回答「资料里没有相关内容」")
        return RagContext(chunks=[], prompt=_build_no_material_prompt(question), has_material=False)

    # 重新编号：过滤后编号必须连续，否则 prompt 里的 [1][3] 和 citations 对不上
    for index, chunk in enumerate(chunks, start=1):
        chunk.citation_index = index

    return RagContext(
        chunks=chunks,
        prompt=_build_prompt(question, chunks, session_id),
        has_material=True,
    )


def _build_prompt(question: str, chunks: list[RetrievedChunk], session_id: int | None) -> str:
    """拼「历史 + 资料 + 问题」。"""
    parts: list[str] = []

    history = _load_history(session_id)
    if history:
        lines = []
        for item in history:
            role = "学生" if item.get("role") == "user" else "助手"
            content = (item.get("content") or "").strip()
            if content:
                lines.append(f"{role}：{content}")
        if lines:
            parts.append("【对话历史】\n" + "\n".join(lines))

    material_lines = []
    for chunk in chunks:
        location = f"{chunk.document_name}"
        if chunk.page_no:
            location += f" 第 {chunk.page_no} 页"
        material_lines.append(f"[{chunk.citation_index}] （来源：{location}）\n{chunk.content}")
    parts.append("【参考资料】\n" + "\n\n".join(material_lines))

    parts.append(f"【学生的问题】\n{question}")
    return "\n\n".join(parts)


def _build_no_material_prompt(question: str) -> str:
    """没有召回（或全部被门槛过滤）时的 prompt。

    这里刻意把「资料为空」写成资料区的一项内容，而不是只在 system prompt 里
    说一句"没有资料就说不知道"。原因是实测：只在 system prompt 里约束，
    面对模型自己很熟的话题（比如量子纠缠），它会绕开约束、直接凭记忆长篇作答 ——
    这恰恰违背 RAG 的初衷（用户会误以为内容是来自他自己的资料）。
    把事实放进 user 消息里、并给出**唯一允许的回复模板**，行为才锁得住。
    """
    return (
        "【参考资料】\n（本轮没有检索到任何相关资料）\n\n"
        f"【学生的问题】\n{question}\n\n"
        "【硬性要求】\n"
        "由于参考资料为空，你不能使用自己的知识回答这个问题。\n"
        "请严格只输出下面这一句话，不要补充、不要解释、不要举例、不要提及任何专业名词：\n"
        "资料里没有相关内容，建议先上传相关的复习资料后再提问。"
    )


def _load_history(session_id: int | None) -> list[dict]:
    """从 Java 读最近 N 轮会话历史（Redis 里存的），失败则当作没有历史。"""
    if not session_id:
        return []
    try:
        rounds = get_settings().history_rounds
        return get_java_client().get_chat_history(session_id, limit=rounds * 2)
    except Exception as exc:  # noqa: BLE001 - 历史是增强项，不影响主链路
        logger.warning("读取会话历史失败，按无历史处理: {!r}", exc)
        return []


async def stream_answer(context: RagContext) -> AsyncIterator[dict]:
    """调用 qwen3.7-flash 流式生成，逐块 yield。

    Yields:
        `{"type": "reasoning"|"content", "text": "..."}`

    Raises:
        BizException: 未配置 API Key 或调用失败。
    """
    settings = get_settings()
    if not settings.llm_configured:
        raise BizException(code=5032, msg="未配置 DASHSCOPE_API_KEY，无法进行问答")

    client = get_async_openai_client()
    started = time.perf_counter()
    first_reasoning_at: float | None = None
    first_content_at: float | None = None
    reasoning_chars = 0
    content_chars = 0

    try:
        stream = await client.chat.completions.create(
            model=settings.dashscope_model,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": context.prompt},
            ],
            stream=True,
            temperature=0.3,
        )
    except Exception as exc:  # noqa: BLE001
        logger.error("大模型流式调用失败: {!r}", exc)
        raise BizException(code=5001, msg=f"大模型调用失败：{exc!r}") from exc

    try:
        async for chunk in stream:
            if not getattr(chunk, "choices", None):
                continue
            delta = chunk.choices[0].delta
            if delta is None:
                continue

            reasoning = getattr(delta, "reasoning_content", None)
            if reasoning:
                if first_reasoning_at is None:
                    first_reasoning_at = time.perf_counter() - started
                reasoning_chars += len(reasoning)
                yield {"type": "reasoning", "text": reasoning}

            content = getattr(delta, "content", None)
            if content:
                if first_content_at is None:
                    first_content_at = time.perf_counter() - started
                content_chars += len(content)
                yield {"type": "content", "text": content}
    except Exception as exc:  # noqa: BLE001
        logger.error("流式生成中途失败: {!r}", exc)
        raise BizException(code=5001, msg=f"流式生成中断：{exc!r}") from exc

    logger.info(
        "RAG 生成完成 | 思考 {} 字（首块 {}）| 回答 {} 字（首块 {}）| 总耗时 {:.2f}s",
        reasoning_chars,
        f"{first_reasoning_at:.2f}s" if first_reasoning_at is not None else "-",
        content_chars,
        f"{first_content_at:.2f}s" if first_content_at is not None else "-",
        time.perf_counter() - started,
    )


__all__ = [
    "SYSTEM_PROMPT",
    "RetrievedChunk",
    "RagContext",
    "retrieve",
    "build_context",
    "stream_answer",
]
