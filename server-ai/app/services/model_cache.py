"""本地模型缓存预检。

用途：在真正调用 `BGEM3FlagModel(...)` / `FlagLLMReranker(...)` 之前，
先确认本地 HF 缓存里已经有必需文件。这样「模型没下完」会在**毫秒级**
抛出 `ModelNotReadyError`（HTTP 503），而不是在请求里傻等一个几 GB 的下载。

配合 `app.services.model_cache.assert_model_cached()` 预检形成「快速失败」：

**为什么不用 `HF_HUB_OFFLINE=1` 兜底**（踩坑记录）：
bge-m3 仓库里的 `imgs/.DS_Store` 在 hf-mirror 上恒返回 403、`onnx/model.onnx_data`
又被下载脚本刻意跳过，因此该仓库的 snapshot **永远不可能「完整」**。
在离线模式下，`transformers`/`FlagEmbedding` 一旦做完整性校验就会抛
`IncompleteSnapshotError`，**即使权重其实已经下好了**。
所以这里只做「按文件预检」，不强制全局离线模式。
"""

from __future__ import annotations

from app.core.exceptions import ModelNotReadyError
from app.core.logger import logger

#: 必需文件
ESSENTIAL_FILES: tuple[str, ...] = (
    "config.json",
    "tokenizer.json",
)

#: 权重文件候选（命中任意一个即可）
#: 注意：**不能**把 onnx/model.onnx 算进来。bge-m3 的 onnx/model.onnx 只有 ~700KB
#: （真正的权重在 onnx/model.onnx_data 里），对 FlagEmbedding 完全无用，
#: 却会让预检误判为「权重已就绪」，从而在请求里触发 20 秒的加载尝试。
WEIGHT_CANDIDATES: tuple[str, ...] = (
    "model.safetensors",
    "pytorch_model.bin",
)

#: 有则更好、没有也不阻塞的文件（用于给出更友好的提示）
OPTIONAL_HINT_FILES: tuple[str, ...] = (
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "sentencepiece.bpe.model",
)


def cached_path(model_name: str, filename: str, cache_dir: str | None = None) -> str | None:
    """查询某文件在本地缓存中的路径；未缓存返回 None（不联网）。"""
    try:
        from huggingface_hub import try_to_load_from_cache

        result = try_to_load_from_cache(model_name, filename, cache_dir=cache_dir)
        # 未命中时返回 None 或 _CACHED_NO_EXIST 哨兵，只有 str 才算命中
        return result if isinstance(result, str) else None
    except Exception:  # noqa: BLE001 - 预检失败不应阻断业务
        return None


def missing_report(model_name: str, cache_dir: str | None = None) -> dict:
    """返回模型缓存完整性报告（不联网）。"""
    missing = [f for f in ESSENTIAL_FILES if not cached_path(model_name, f, cache_dir)]

    weight_hit = next(
        (w for w in WEIGHT_CANDIDATES if cached_path(model_name, w, cache_dir)), None
    )
    if weight_hit is None:
        missing.append("权重文件（任一）: " + " / ".join(WEIGHT_CANDIDATES))

    optional_present = [f for f in OPTIONAL_HINT_FILES if cached_path(model_name, f, cache_dir)]

    return {
        "model": model_name,
        "missing": missing,
        "weight": weight_hit,
        "optional_present": optional_present,
        "ready": not missing,
    }


def assert_model_cached(model_name: str, cache_dir: str | None = None) -> dict:
    """断言模型已完整缓存；否则抛 `ModelNotReadyError`。

    Raises:
        ModelNotReadyError: 缺少 config.json 或任一权重文件。
    """
    report = missing_report(model_name, cache_dir)
    if report["missing"]:
        detail = "、".join(report["missing"])
        message = (
            f"模型未下载完整，缺少: {detail}；"
            "请先运行 scripts/download_models.py 完成下载（该脚本支持断点续传，可反复重跑）"
        )
        logger.warning("模型预检未通过: {} -> {}", model_name, detail)
        raise ModelNotReadyError(message, model=model_name)
    return report


__all__ = [
    "ESSENTIAL_FILES",
    "WEIGHT_CANDIDATES",
    "cached_path",
    "missing_report",
    "assert_model_cached",
]
