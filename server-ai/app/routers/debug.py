"""调试 / 自检路由。

`POST /debug/embed` 用于部署后快速验证本地嵌入模型是否可用及其向量维度。
注意：该接口会触发模型懒加载（首次调用较慢，之后常驻内存）。

`GET /debug/stream` 是 **SSE 流式探针**，用于验证 qwen3.7-flash 思考模型的
`reasoning_content`（思维链）与 `content`（正式回答）能够分段下发；
它同时验证「纯 ASGI traceId 中间件不会缓冲 SSE」这一设计前提。
"""

from __future__ import annotations

import json
from typing import AsyncIterator

from fastapi import APIRouter, Query
from sse_starlette.sse import EventSourceResponse

from app.core.config import get_settings
from app.core.llm import get_async_openai_client
from app.core.logger import logger
from app.core.trace import get_trace_id
from app.models.schemas import (
    EmbedRequest,
    EmbedResponse,
    RerankItem,
    RerankRequest,
    RerankResponse,
)
from app.services.embedding import get_embedder
from app.services.reranker import get_reranker

router = APIRouter(prefix="/debug", tags=["debug"])


@router.post("/embed", response_model=EmbedResponse, summary="嵌入探针（返回向量维度）")
async def debug_embed(payload: EmbedRequest) -> EmbedResponse:
    """对输入文本编码，返回维度与向量（用于验证 BGE-M3 是否就绪）。"""
    vectors = get_embedder().encode(payload.texts)
    dimension = len(vectors[0]) if vectors else 0
    logger.info("调试嵌入完成: {} 条, 维度 {}", len(vectors), dimension)
    return EmbedResponse(
        code=0,
        msg="ok",
        dimension=dimension,
        count=len(vectors),
        vectors=vectors,
        sample=vectors[0][:8] if vectors else [],
    )


@router.post("/rerank", response_model=RerankResponse, summary="重排探针")
async def debug_rerank(payload: RerankRequest) -> RerankResponse:
    """对候选段落重排，返回 (原始下标, 分数) 降序结果。"""
    ranked = get_reranker().rerank(payload.query, payload.passages, payload.top_k)
    return RerankResponse(
        code=0,
        msg="ok",
        results=[
            RerankItem(index=idx, score=score, passage=payload.passages[idx])
            for idx, score in ranked
        ],
    )


@router.get(
    "/stream",
    summary="SSE 流式探针（思考链 / 正式回答分离）",
    response_class=EventSourceResponse,
)
async def debug_stream(
    prompt: str = Query(default="只回复两个字：在线", description="用户提问"),
) -> EventSourceResponse:
    """以 SSE 逐块下发大模型输出，事件类型区分思维链与正式回答。

    事件：
    - `start`     ：本次请求的 traceId / 模型名；
    - `reasoning` ：思维链增量（`delta.reasoning_content`）；
    - `content`   ：正式回答增量（`delta.content`）；
    - `done`      ：结束标记。

    实现说明：这里走**原生 openai SDK**，因为 LangChain 会丢弃
    `reasoning_content`（详见 app/core/llm.py 顶部实测说明）。
    """
    settings = get_settings()

    async def event_generator() -> AsyncIterator[dict]:
        trace_id = get_trace_id()
        yield {
            "event": "start",
            "data": json.dumps(
                {"traceId": trace_id, "model": settings.dashscope_model},
                ensure_ascii=False,
            ),
        }
        if not settings.llm_configured:
            yield {
                "event": "error",
                "data": json.dumps(
                    {"code": 5032, "msg": "未配置 DASHSCOPE_API_KEY"}, ensure_ascii=False
                ),
            }
            yield {"event": "done", "data": "{}"}
            return

        try:
            client = get_async_openai_client()
            stream = await client.chat.completions.create(
                model=settings.dashscope_model,
                messages=[{"role": "user", "content": prompt}],
                stream=True,
            )
            reasoning_len = content_len = 0
            async for chunk in stream:
                if not chunk.choices:
                    continue
                delta = chunk.choices[0].delta
                reasoning = getattr(delta, "reasoning_content", None)
                if reasoning:
                    reasoning_len += len(reasoning)
                    yield {
                        "event": "reasoning",
                        "data": json.dumps({"text": reasoning}, ensure_ascii=False),
                    }
                if delta.content:
                    content_len += len(delta.content)
                    yield {
                        "event": "content",
                        "data": json.dumps({"text": delta.content}, ensure_ascii=False),
                    }
            logger.info(
                "SSE 探针完成: reasoning {} 字, content {} 字", reasoning_len, content_len
            )
            yield {
                "event": "done",
                "data": json.dumps(
                    {"reasoning_length": reasoning_len, "content_length": content_len},
                    ensure_ascii=False,
                ),
            }
        except Exception as exc:  # noqa: BLE001 - 探针接口需要把失败原因推给调用方
            logger.error("SSE 探针失败: {!r}", exc)
            yield {
                "event": "error",
                "data": json.dumps(
                    {"code": 5001, "msg": f"{type(exc).__name__}: {exc}"},
                    ensure_ascii=False,
                ),
            }
            yield {"event": "done", "data": "{}"}

    return EventSourceResponse(event_generator())
