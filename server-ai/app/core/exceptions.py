"""统一异常与全局异常处理器。

约定：
- `ModelNotReadyError`：本地模型（嵌入/重排）未就绪（未下载或加载失败）
  → HTTP 503 + `{"code": 5031, "msg": "本地模型未就绪: ..."}`；
- `BizException`：业务异常 → HTTP 200 + 业务 code（前端统一按 code 判断）；
- 其他未捕获异常 → HTTP 500 + `{"code": 5000, "msg": "服务器内部错误"}`，
  并用 loguru 打印包含 traceId 的完整堆栈。
"""

from __future__ import annotations

import traceback

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.logger import logger
from app.core.trace import get_trace_id


class ModelNotReadyError(Exception):
    """本地模型未就绪（未下载、文件缺失或加载失败）。"""

    def __init__(self, msg: str = "本地模型未就绪", *, model: str = "") -> None:
        self.msg = msg
        self.model = model
        super().__init__(msg)


class BizException(Exception):
    """业务异常，携带业务状态码与提示信息。"""

    def __init__(self, code: int = 4000, msg: str = "业务处理失败") -> None:
        self.code = code
        self.msg = msg
        super().__init__(msg)


async def model_not_ready_handler(
    request: Request, exc: ModelNotReadyError
) -> JSONResponse:
    """模型未就绪 → 503。"""
    detail = f"{exc.msg}" + (f"（模型: {exc.model}）" if exc.model else "")
    logger.warning("本地模型未就绪: {} | path={}", detail, request.url.path)
    return JSONResponse(
        status_code=503,
        content={"code": 5031, "msg": f"本地模型未就绪: {detail}", "data": None},
    )


async def biz_exception_handler(request: Request, exc: BizException) -> JSONResponse:
    """业务异常 → HTTP 200 + 业务 code。"""
    logger.warning("业务异常: code={} msg={} | path={}", exc.code, exc.msg, request.url.path)
    return JSONResponse(
        status_code=200,
        content={"code": exc.code, "msg": exc.msg, "data": None},
    )


async def http_exception_handler(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    """HTTP 异常（404/405 等）统一包装，保留原状态码。"""
    logger.warning("HTTP 异常: status={} detail={} | path={}", exc.status_code, exc.detail, request.url.path)
    return JSONResponse(
        status_code=exc.status_code,
        content={"code": exc.status_code, "msg": str(exc.detail), "data": None},
    )


async def validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """请求参数校验失败 → 422。"""
    logger.warning("参数校验失败: {} | path={}", exc.errors(), request.url.path)
    return JSONResponse(
        status_code=422,
        content={"code": 4220, "msg": "请求参数校验失败", "data": exc.errors()},
    )


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """兜底异常 → 500，打印完整堆栈（含 traceId）。"""
    trace_id = get_trace_id()
    logger.error(
        "未捕获异常 | traceId={} | path={} | {}\n{}",
        trace_id,
        request.url.path,
        repr(exc),
        traceback.format_exc(),
    )
    return JSONResponse(
        status_code=500,
        content={"code": 5000, "msg": "服务器内部错误", "data": None},
    )


def register_exception_handlers(app: FastAPI) -> None:
    """把上述处理器注册到 FastAPI 应用。"""
    app.add_exception_handler(ModelNotReadyError, model_not_ready_handler)
    app.add_exception_handler(BizException, biz_exception_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)


__all__ = [
    "ModelNotReadyError",
    "BizException",
    "register_exception_handlers",
]
