"""文档名缓存：引用溯源要显示「哪份资料的哪一页」。

为什么需要缓存：一次问答可能召回同一个文档的多个切片，
如果每个切片都去 Java 查一次文档名就是 N 次 HTTP。
这里用一个带 TTL 的进程内字典缓存（文档名几乎不会变），
并支持批量查询。

为什么单独一个模块：`rag.py` 已经依赖 `java_client`，
如果把文档名接口直接写在 java_client 里，rag → doc_registry → java_client 的
导入链会更绕；拆出来反而清晰。
"""

from __future__ import annotations

import threading
import time

from app.core.exceptions import BizException
from app.core.logger import logger
from app.services.java_client import get_java_client

#: 缓存 TTL（秒）——文档名基本不变，5 分钟足够
_TTL_SECONDS = 300

_cache: dict[int, tuple[str, float]] = {}
_cache_lock = threading.Lock()


def get_document_names(document_ids: set[int]) -> dict[int, str]:
    """批量取文档名，命中缓存的直接返回，未命中的去 Java 查一次。

    Args:
        document_ids: 文档 ID 集合。

    Returns:
        `{documentId: fileName}`；查不到的文档不会出现在结果里（调用方降级展示）。
    """
    if not document_ids:
        return {}

    now = time.time()
    result: dict[int, str] = {}
    missing: list[int] = []

    with _cache_lock:
        for document_id in document_ids:
            cached = _cache.get(document_id)
            if cached and now - cached[1] < _TTL_SECONDS:
                result[document_id] = cached[0]
            else:
                missing.append(document_id)

    if missing:
        fetched = _fetch(missing)
        result.update(fetched)
        with _cache_lock:
            for document_id, name in fetched.items():
                _cache[document_id] = (name, now)

    return result


def _fetch(document_ids: list[int]) -> dict[int, str]:
    """调 Java 的内部接口批量取文档名。"""
    try:
        data = get_java_client()._request(  # noqa: SLF001 - 内部协作，接口本身就在本包内
            "POST",
            "/internal/kb/document-names",
            {"documentIds": document_ids},
        )
    except BizException as exc:
        logger.warning("批量查询文档名失败（引用将显示为文档#id）: {}", exc.msg)
        return {}
    except Exception as exc:  # noqa: BLE001
        logger.warning("批量查询文档名异常（引用将显示为文档#id）: {!r}", exc)
        return {}

    names: dict[int, str] = {}
    if isinstance(data, dict):
        for key, value in data.items():
            try:
                names[int(key)] = str(value)
            except (TypeError, ValueError):
                continue
    elif isinstance(data, list):
        for item in data:
            if isinstance(item, dict) and item.get("id") is not None:
                names[int(item["id"])] = str(item.get("fileName") or item.get("name") or "")
    return names


def invalidate(document_id: int | None = None) -> None:
    """清除缓存（文档改名或删除后调用；不传则清空全部）。"""
    with _cache_lock:
        if document_id is None:
            _cache.clear()
        else:
            _cache.pop(document_id, None)


__all__ = ["get_document_names", "invalidate"]
