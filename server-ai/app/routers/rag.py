"""RAG 路由：文档入库（/rag/ingest）与流式问答（/rag/chat）。

SSE 帧协议（PC 端与小程序端都已按这个协议写好前端解析，改动要同步）:

    event: start      data: {"sessionId": 12, "hasMaterial": true, "chunkCount": 5}
    event: reasoning  data: {"text": "..."}      思考过程增量（可多次）
    event: message    data: {"text": "..."}      正式回答增量（可多次）
    event: citations  data: [{index, documentId, documentName, pageNo, snippet, score}]
    event: error      data: {"code": 5001, "message": "..."}
    data: [DONE]                                 结束标记

为什么结束要用 `[DONE]`：和 OpenAI 流式协议保持一致，前端解析器可以直接复用同一套判断。
"""

from __future__ import annotations

import json
import time
from collections.abc import AsyncIterator

from fastapi import APIRouter, BackgroundTasks
from fastapi.responses import StreamingResponse

from app.core.config import get_settings
from app.core.exceptions import BizException
from app.core.logger import logger
from app.core.trace import get_trace_id
from app.models.rag_schemas import (
    ChatRequest,
    IngestRequest,
    IngestResponse,
    KbStatsResponse,
)
from app.services import milvus_store
from app.services.document import parse_document, split_into_chunks
from app.services.embedding import get_embedder
from app.services.java_client import get_java_client
from app.services.rag import build_context, stream_answer

router = APIRouter(prefix="/rag", tags=["rag"])


# --------------------------------------------------------------------- 入库


@router.post("/ingest", response_model=IngestResponse, summary="文档入库（解析→分块→向量化→写 Milvus）")
def ingest(request: IngestRequest, background_tasks: BackgroundTasks) -> IngestResponse:
    """把本地文件解析、分块、向量化并写入 Milvus，同时回写 MySQL 的 kb_chunk。

    流程（对应执行计划 Day 15）：
    1. 解析 PDF/Word/txt（pypdf + python-docx，不上 unstructured）；
    2. 分块（默认 chunk_size=500、overlap=50，带页码）；
    3. 用本地 BGE-M3 批量向量化（模型常驻内存，只加载一次）；
    4. 写入 Milvus collection `kb_chunk`（带 user_id 做隔离）；
    5. 回写 Java（MySQL `kb_chunk` 的 vector_id/content/page_no）；
    6. 更新 kb_document 状态为「完成」。

    任一步失败都会把文档状态置为「失败」并带上原因，避免前端一直转圈。

    ⚠️ 同步 `def` 端点：解析/向量化是分钟级阻塞操作，跑在 FastAPI 线程池里 ——
    否则 async 端点会在入库全程占死事件循环，上传资料期间问答/健康检查全部卡住。
    """
    settings = get_settings()
    document_id = request.document_id
    user_id = request.user_id
    java = get_java_client()
    started = time.perf_counter()

    logger.info(
        "开始入库 document_id={} user_id={} file={}", document_id, user_id, request.file_path
    )

    try:
        java.update_document_status(document_id, "解析中")

        # 1) 解析
        pages = parse_document(request.file_path)
        if not pages:
            raise BizException(code=4004, msg="文档解析后没有任何文本（可能是扫描版 PDF 或空文件）")

        # 2) 分块
        chunk_size = request.chunk_size or settings.chunk_size
        overlap = request.chunk_overlap if request.chunk_overlap is not None else settings.chunk_overlap
        chunks = split_into_chunks(pages, chunk_size=chunk_size, overlap=overlap)
        if not chunks:
            raise BizException(code=4004, msg="分块后没有有效内容")

        # 3) 向量化（批量，避免逐条推理）
        embedder = get_embedder()
        embed_started = time.perf_counter()
        vectors = embedder.encode([c.content for c in chunks])
        embed_seconds = time.perf_counter() - embed_started

        # 4) 写向量库
        vector_ids = milvus_store.insert_chunks(
            user_id=user_id, document_id=document_id, chunks=chunks, vectors=vectors
        )

        # 5) 回写 Java（保持单一写入口）
        payload = [
            {
                "chunkIndex": chunk.index,
                "content": chunk.content,
                "pageNo": chunk.page_no,
                "vectorId": vector_ids[i],
            }
            for i, chunk in enumerate(chunks)
        ]
        saved = java.save_chunks(document_id, payload)

        # 6) 更新状态
        java.update_document_status(document_id, "完成", chunk_count=len(chunks))

        elapsed = time.perf_counter() - started
        logger.info(
            "入库完成 document_id={} 分块={} 向量={} 回写={} | 向量化 {:.2f}s 总耗时 {:.2f}s",
            document_id,
            len(chunks),
            len(vector_ids),
            saved,
            embed_seconds,
            elapsed,
        )

        return IngestResponse(
            code=0,
            msg="入库成功",
            documentId=document_id,
            pageCount=len(pages),
            chunkCount=len(chunks),
            vectorCount=len(vector_ids),
            chunkSize=chunk_size,
            chunkOverlap=overlap,
            embedSeconds=round(embed_seconds, 3),
            totalSeconds=round(elapsed, 3),
            samples=[
                {
                    "chunkIndex": c.index,
                    "pageNo": c.page_no,
                    "charCount": c.char_count,
                    "preview": c.content[:120],
                }
                for c in chunks[:3]
            ],
            traceId=get_trace_id(),
        )

    except BizException as exc:
        logger.error("入库失败 document_id={}: {}", document_id, exc.msg)
        _safe_mark_failed(document_id, exc.msg)
        raise
    except Exception as exc:  # noqa: BLE001
        logger.exception("入库异常 document_id={}: {!r}", document_id, exc)
        _safe_mark_failed(document_id, f"{type(exc).__name__}: {exc}")
        raise


def _safe_mark_failed(document_id: int, reason: str) -> None:
    """把文档状态标为失败；这一步本身失败也不能盖掉原始异常。"""
    try:
        get_java_client().update_document_status(
            document_id, "失败", error_msg=reason[:500]
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("回写失败状态时出错 document_id={}: {!r}", document_id, exc)


@router.get("/stats", response_model=KbStatsResponse, summary="向量库统计（验收用）")
async def stats() -> KbStatsResponse:
    """返回 Milvus collection 的向量数，用于与 MySQL `kb_chunk` 行数对账。"""
    info = milvus_store.collection_stats()
    return KbStatsResponse(
        code=0,
        msg="ok",
        collection=info.get("collection", ""),
        vectorCount=int(info.get("row_count", -1)),
        error=info.get("error"),
        traceId=get_trace_id(),
    )


@router.delete("/document/{document_id}", summary="删除某文档的全部向量")
async def delete_document(document_id: int) -> dict:
    """文档被删除时清理向量，避免检索到已删除资料的内容。"""
    count = milvus_store.delete_document(document_id)
    return {"code": 0, "msg": "已删除", "data": {"documentId": document_id, "removed": count}}


# --------------------------------------------------------------------- 问答


@router.post(
    "/chat",
    summary="RAG 流式问答（SSE）",
    response_class=StreamingResponse,
    responses={200: {"content": {"text/event-stream": {}}}},
)
async def chat(request: ChatRequest) -> StreamingResponse:
    """检索 → 重排 → 拼 prompt → 流式生成，并以 SSE 返回。

    关键设计：**先做检索，再开流**。
    如果检索阶段就失败（模型未就绪、Milvus 不可用），
    可以在 SSE 开始之前返回正常的 JSON 错误（HTTP 503 等），
    前端 axios 拦截器能给出明确提示；一旦开始吐流，就只能用 `event: error` 帧了。
    """
    user_id = request.user_id
    question = request.question.strip()
    if not question:
        raise BizException(code=4000, msg="问题不能为空")

    started = time.perf_counter()
    context = build_context(user_id=user_id, question=question, session_id=request.session_id)
    prepare_seconds = time.perf_counter() - started

    logger.info(
        "RAG 问答开始: user_id={} session_id={} 问题={!r} 召回={} 有资料={} | 准备耗时 {:.3f}s",
        user_id,
        request.session_id,
        question[:40],
        len(context.chunks),
        context.has_material,
        prepare_seconds,
    )

    return StreamingResponse(
        _sse_stream(request, context),
        media_type="text/event-stream",
        headers={
            # 关掉各级缓冲：Nginx 的 proxy_buffering、浏览器与中间代理的缓存
            "Cache-Control": "no-cache, no-transform",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
            "X-Trace-Id": get_trace_id(),
        },
    )


async def _sse_stream(request: ChatRequest, context) -> AsyncIterator[str]:
    """SSE 生成器：按协议吐帧。

    <h3>这里只吐帧，不落库</h3>
    整轮问答的落库由 **Java 独占完成**：user 消息在开流前写、assistant 消息
    在透传结束后写（连接中断时也会保存已生成的部分内容），会话计数由 Java 维护。

    历史踩坑：Python 侧曾在发 [DONE] 之前调用 ``/internal/chat/messages`` 也写一遍，
    而 Java 的保存没有幂等守卫、且总在这之后执行 —— 结果每条 assistant 消息都被
    存了两份（同一秒、内容完全相同）。"同 session 同 role 同内容不重复插"这样的
    幂等检查挡不住这种竞态：两边都在对方插入之前完成了查询。根治办法只有一个：
    **写入口唯一化**。详见 docs/踩坑记录.md 与 ChatServiceImpl 中的说明。
    """
    citation_list = [c.to_citation() for c in context.chunks]

    yield _frame(
        "start",
        {
            "sessionId": request.session_id,
            "hasMaterial": context.has_material,
            "chunkCount": len(context.chunks),
            "traceId": get_trace_id(),
        },
    )

    try:
        async for piece in stream_answer(context):
            if piece["type"] == "reasoning":
                yield _frame("reasoning", {"text": piece["text"]})
            else:
                yield _frame("message", {"text": piece["text"]})
    except BizException as exc:
        logger.error("RAG 生成失败: code={} msg={}", exc.code, exc.msg)
        yield _frame("error", {"code": exc.code, "message": exc.msg})
    except Exception as exc:  # noqa: BLE001
        logger.exception("RAG 生成未预期异常: {!r}", exc)
        yield _frame("error", {"code": 5000, "message": f"生成失败：{type(exc).__name__}"})

    # 引用帧：即使生成失败也发，便于排查"检索到了什么"
    yield _frame("citations", citation_list)

    yield "data: [DONE]\n\n"


def _frame(event: str, data) -> str:
    """拼一个 SSE 帧。"""
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"


__all__ = ["router"]
