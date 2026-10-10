"""健康检查路由。

设计原则：`/health` **绝不触发模型加载**，只报告「是否已加载」的状态，
保证探活接口在任何情况下都能秒回（K8s/网关探针友好）。
"""

from __future__ import annotations

from fastapi import APIRouter, status

from app.core.config import get_settings
from app.core.llm import check_llm
from app.core.logger import logger
from app.core.trace import get_trace_id
from app.models.schemas import (
    DBHealthResponse,
    HealthResponse,
    LLMHealthResponse,
    ModelsStatus,
)
from app.services.embedding import is_embedder_loaded
from app.services.reranker import is_reranker_loaded

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse, summary="服务健康检查")
async def health() -> HealthResponse:
    """返回服务状态、本地模型加载状态与 LLM 配置状态。"""
    settings = get_settings()
    return HealthResponse(
        status="UP",
        service=settings.app_name,
        version=settings.app_version,
        traceId=get_trace_id(),
        models=ModelsStatus(
            embedding=is_embedder_loaded(),
            reranker=is_reranker_loaded(),
        ),
        llm_configured=settings.llm_configured,
    )


@router.get(
    "/health/llm",
    response_model=LLMHealthResponse,
    summary="大模型连通性检查",
    responses={503: {"description": "未配置 DASHSCOPE_API_KEY"}},
)
async def health_llm() -> LLMHealthResponse:
    """调用百炼 qwen3.7-flash 发一句「只回复两个字：在线」，校验连通性。"""
    settings = get_settings()
    if not settings.llm_configured:
        # 未配置 Key：用 503 表达「依赖不可用」，但不用异常处理器，
        # 以便把原因直接放在响应体里
        from fastapi.responses import JSONResponse

        return JSONResponse(  # type: ignore[return-value]
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "code": 5032,
                "msg": "未配置 DASHSCOPE_API_KEY，大模型不可用",
                "data": {"configured": False, "model": settings.dashscope_model},
            },
        )

    result = check_llm()
    if not result.get("ok"):
        return LLMHealthResponse(code=5001, msg="大模型调用失败", data=result)
    return LLMHealthResponse(code=0, msg="ok", data=result)


@router.get(
    "/health/db",
    response_model=DBHealthResponse,
    summary="MySQL 连通性检查",
)
async def health_db() -> DBHealthResponse:
    """用 pymysql 连接 MySQL 执行 `select 1`；失败不抛 500，返回 DOWN 与原因。"""
    settings = get_settings()
    detail = {
        "host": settings.mysql_host,
        "port": settings.mysql_port,
        "database": settings.mysql_database,
    }
    try:
        import pymysql

        conn = pymysql.connect(
            host=settings.mysql_host,
            port=settings.mysql_port,
            user=settings.mysql_user,
            password=settings.mysql_password,
            database=settings.mysql_database,
            connect_timeout=3,
            read_timeout=3,
            charset="utf8mb4",
        )
        try:
            with conn.cursor() as cursor:
                cursor.execute("select 1")
                row = cursor.fetchone()
        finally:
            conn.close()
        logger.info("MySQL 健康检查通过: {} -> {}", detail, row)
        return DBHealthResponse(status="UP", error=None, detail=detail)
    except Exception as exc:  # noqa: BLE001 - 探活接口必须吞掉异常
        logger.warning("MySQL 健康检查失败: {} | {!r}", detail, exc)
        return DBHealthResponse(
            status="DOWN", error=f"{type(exc).__name__}: {exc}", detail=detail
        )
