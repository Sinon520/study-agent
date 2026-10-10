"""loguru 日志配置。

- 控制台彩色输出；
- 文件 `logs/server-ai.log`：按天滚动（00:00）、保留 15 天、utf-8、enqueue=True；
- 通过 `logger.configure(patcher=...)` 给每条日志注入 `trace_id`，
  日志格式中必须包含 `{extra[trace_id]}`，便于按链路检索。
"""

from __future__ import annotations

import sys
from pathlib import Path

from loguru import logger

from app.core.trace import get_trace_id

#: 日志目录（相对服务工作目录）
LOG_DIR = Path("logs")
LOG_FILE = LOG_DIR / "server-ai.log"

#: 控制台格式（含 trace_id）
CONSOLE_FORMAT = (
    "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
    "<level>{level: <8}</level> | "
    "<cyan>{extra[trace_id]}</cyan> | "
    "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - "
    "<level>{message}</level>"
)

#: 文件格式（含 trace_id，无颜色标记）
FILE_FORMAT = (
    "{time:YYYY-MM-DD HH:mm:ss.SSS} | {level: <8} | {extra[trace_id]} | "
    "{name}:{function}:{line} - {message}"
)

_configured = False


def _patch_trace_id(record: dict) -> None:
    """loguru patcher：把当前 ContextVar 中的 traceId 注入 record.extra。"""
    record["extra"]["trace_id"] = get_trace_id()


def setup_logger(level: str = "INFO", *, force: bool = False) -> "logger.__class__":
    """初始化 loguru（幂等，重复调用不会重复挂 handler）。

    Args:
        level: 日志级别，默认 INFO。
        force: 为 True 时强制重建 handler。

    Returns:
        配置好的 loguru logger。
    """
    global _configured
    if _configured and not force:
        return logger

    logger.remove()
    logger.configure(patcher=_patch_trace_id)

    LOG_DIR.mkdir(parents=True, exist_ok=True)

    # 控制台：彩色；Windows 下 loguru 能正确处理 ANSI
    logger.add(
        sys.stderr,
        level=level,
        format=CONSOLE_FORMAT,
        colorize=True,
        backtrace=False,
        diagnose=False,
        enqueue=False,
    )

    # 文件：按天滚动、保留 15 天、utf-8、异步队列写入
    logger.add(
        LOG_FILE,
        level=level,
        format=FILE_FORMAT,
        rotation="00:00",       # 每天 0 点切分
        retention="15 days",    # 保留 15 天
        encoding="utf-8",
        enqueue=True,
        backtrace=True,
        diagnose=False,
        colorize=False,
    )

    _configured = True
    return logger


__all__ = ["logger", "setup_logger", "LOG_DIR", "LOG_FILE"]
