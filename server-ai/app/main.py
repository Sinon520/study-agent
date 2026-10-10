"""study-agent AI 服务入口。

职责：
- 组装 FastAPI 应用（标题/版本/描述）；
- `lifespan` 生命周期：启动打印脱敏配置摘要、创建 logs/data 目录、
  **不预加载模型**（懒加载，避免启动慢或启动直接失败）；关闭打印服务停止；
- 注册 traceId 中间件与全局异常处理器；
- 挂载 health / debug 路由；
- 支持 `python -m app.main` 直接启动（默认 0.0.0.0:8000）。
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.core.config import get_settings
from app.core.exceptions import register_exception_handlers
from app.core.logger import logger, setup_logger
from app.core.trace import TraceIdMiddleware, new_trace_id, set_trace_id
from app.routers import debug, health

DESCRIPTION = """
**study-agent AI 服务**（FastAPI + LangChain 工程骨架）

- 本地向量模型：BGE-M3（稠密 1024 维，懒加载常驻）
- 本地重排模型：BGE-Reranker-v2-m3（懒加载常驻）
- 大模型：阿里云百炼 `qwen3.7-flash`（OpenAI 兼容协议，思考模型）
- 向量库：Milvus Lite（本地文件模式，免 Docker）

所有接口响应头都会回写 `X-Trace-Id`，日志中每行都带 traceId，便于全链路排障。
"""


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理。"""
    settings = get_settings()
    setup_logger(level="DEBUG" if settings.debug else "INFO")
    set_trace_id(new_trace_id())

    # 目录准备（日志与 Milvus Lite 数据文件都落在服务目录下）
    Path("logs").mkdir(parents=True, exist_ok=True)
    Path("data").mkdir(parents=True, exist_ok=True)

    logger.info("=" * 72)
    logger.info("{} v{} 启动中 ...", settings.app_name, settings.app_version)
    for key, value in settings.summary().items():
        logger.info("  {:<20} = {}", key, value)
    logger.info("本地模型为懒加载：首次调用 /debug/embed 或 /debug/rerank 时才加载")

    # qwen3.7-flash 是思考模型，而 LangChain 会丢弃 reasoning_content；
    # 这里启动时打透传补丁（幂等、失败不影响启动），使日志/SSE 能拿到思维链
    if settings.preserve_reasoning:
        from app.core.llm import enable_reasoning_passthrough

        enable_reasoning_passthrough()
    logger.info("=" * 72)

    yield

    logger.info("=" * 72)
    logger.info("{} 服务停止", settings.app_name)
    logger.info("=" * 72)


def create_app() -> FastAPI:
    """构建 FastAPI 应用实例。"""
    settings = get_settings()

    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        description=DESCRIPTION,
        lifespan=lifespan,
    )

    # 纯 ASGI 中间件，避免 BaseHTTPMiddleware 对 SSE 的缓冲问题
    app.add_middleware(TraceIdMiddleware)
    register_exception_handlers(app)

    app.include_router(health.router)
    app.include_router(debug.router)

    @app.get("/", tags=["root"], summary="服务根路径")
    async def root() -> JSONResponse:
        return JSONResponse(
            {
                "service": settings.app_name,
                "version": settings.app_version,
                "docs": "/docs",
                "openapi": "/openapi.json",
                "health": "/health",
                "message": f"study-agent AI 服务已启动，接口文档见 http://127.0.0.1:{settings.port}/docs",
            }
        )

    return app


app = create_app()


if __name__ == "__main__":
    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        log_level="debug" if settings.debug else "info",
    )
