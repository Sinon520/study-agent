"""全链路 traceId：ContextVar + 纯 ASGI 中间件。

为什么用纯 ASGI 中间件而不是 `BaseHTTPMiddleware`：
`BaseHTTPMiddleware` 会在内部对响应体做缓冲，SSE（Server-Sent Events）
流式输出会被攒成一批才下发，直接破坏流式体验。纯 ASGI 中间件只透传
`send` 消息并在 `http.response.start` 上追加响应头，不做任何缓冲。
"""

from __future__ import annotations

from contextvars import ContextVar
from uuid import uuid4

#: 当前请求的 traceId，默认 "-"（非请求上下文，例如启动阶段）
trace_id_var: ContextVar[str] = ContextVar("trace_id", default="-")

TRACE_HEADER = "X-Trace-Id"
_TRACE_HEADER_BYTES = TRACE_HEADER.lower().encode("latin-1")


def get_trace_id() -> str:
    """读取当前上下文的 traceId。"""
    return trace_id_var.get()


def set_trace_id(trace_id: str) -> None:
    """手动设置 traceId（后台任务、脚本入口等场景）。"""
    trace_id_var.set(trace_id)


def new_trace_id() -> str:
    """生成一个新的 traceId（uuid4 十六进制，32 位）。"""
    return uuid4().hex


class TraceIdMiddleware:
    """纯 ASGI 中间件：解析/生成 traceId，并回写到响应头。

    - 优先复用上游传入的 `X-Trace-Id`（网关或 Java 侧透传，便于跨服务追踪）；
    - 缺失时生成 `uuid4().hex`；
    - 把 traceId 写入 ContextVar，供 loguru patcher 与业务代码读取；
    - 响应头回写 `X-Trace-Id`，方便前端与排障。
    """

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        # WebSocket / lifespan 等非 HTTP 请求直接透传
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return

        raw_headers = scope.get("headers") or []
        trace_id = ""
        for key, value in raw_headers:
            if key == _TRACE_HEADER_BYTES:
                try:
                    trace_id = value.decode("latin-1").strip()
                except Exception:  # pragma: no cover - 极端编码异常
                    trace_id = ""
                break
        if not trace_id:
            trace_id = new_trace_id()

        token = trace_id_var.set(trace_id)
        trace_value = trace_id.encode("latin-1", errors="replace")

        async def send_with_trace(message):
            if message.get("type") == "http.response.start":
                headers = list(message.get("headers") or [])
                headers.append((_TRACE_HEADER_BYTES, trace_value))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_with_trace)
        finally:
            trace_id_var.reset(token)
