"""BGE-M3 嵌入服务（单例 + 懒加载 + 线程安全）。

设计要点：
- 模型只在**首次调用 `encode()`** 时加载一次并常驻内存，绝不每次请求重新加载；
- `threading.Lock` 双检锁保证并发首次调用只加载一次；
- FlagEmbedding / torch 在**加载方法内部**导入，因此「未安装依赖」不会导致
  服务启动失败，而会在真正调用时报 `ModelNotReadyError`（HTTP 503）。
"""

from __future__ import annotations

import threading
import time
from typing import Any

from app.core.config import apply_runtime_env, get_settings
from app.core.exceptions import ModelNotReadyError
from app.core.logger import logger
from app.services.model_cache import assert_model_cached

#: 模块级单例
_instance: "BGEM3Embedder | None" = None
_instance_lock = threading.Lock()


class BGEM3Embedder:
    """BAAI/bge-m3 稠密向量编码器封装。"""

    def __init__(self, model_name: str | None = None) -> None:
        settings = get_settings()
        self.model_name: str = model_name or settings.embedding_model
        self._model: Any = None
        self._load_lock = threading.Lock()
        self._load_error: str | None = None
        self._load_seconds: float = 0.0

    # ---------------- 状态 ----------------
    @property
    def is_loaded(self) -> bool:
        """模型是否已成功加载到内存（不触发加载）。"""
        return self._model is not None

    @property
    def load_error(self) -> str | None:
        """上次加载失败原因（若有）。"""
        return self._load_error

    # ---------------- 懒加载 ----------------
    def _ensure_loaded(self) -> None:
        """确保模型已加载（双检锁，只加载一次）。"""
        if self._model is not None:
            return

        with self._load_lock:
            if self._model is not None:  # 双检：并发下其他线程可能已完成加载
                return
            settings = get_settings()
            started = time.perf_counter()
            # 必须在导入 FlagEmbedding/huggingface_hub 之前把 HF_ENDPOINT 写进环境变量
            apply_runtime_env()
            # 预检本地缓存：缺文件就立刻抛 ModelNotReadyError -> HTTP 503，
            # 绝不在请求里傻等几 GB 的下载
            assert_model_cached(self.model_name, settings.model_cache_dir_or_none)
            try:
                from FlagEmbedding import BGEM3FlagModel
            except Exception as exc:  # noqa: BLE001
                self._load_error = (
                    f"FlagEmbedding 导入失败（可能未安装或 torch 缺失）: {exc!r}"
                )
                logger.error("{} | model={}", self._load_error, self.model_name)
                raise ModelNotReadyError(self._load_error, model=self.model_name) from exc

            try:
                logger.info("开始加载 BGE-M3: {} (use_fp16=False, CPU)", self.model_name)
                self._model = BGEM3FlagModel(self.model_name, use_fp16=False)
                self._load_seconds = time.perf_counter() - started
                self._load_error = None
                logger.info(
                    "BGE-M3 加载完成，耗时 {:.2f}s，维度 {}",
                    self._load_seconds,
                    1024,
                )
            except Exception as exc:  # noqa: BLE001
                self._load_error = (
                    f"BGE-M3 加载失败（模型文件可能未下载）: {exc!r}"
                )
                logger.error("{} | model={}", self._load_error, self.model_name)
                raise ModelNotReadyError(self._load_error, model=self.model_name) from exc

    # ---------------- 业务方法 ----------------
    def encode(self, texts: list[str]) -> list[list[float]]:
        """把文本列表编码为稠密向量列表（1024 维）。

        Args:
            texts: 待编码文本列表。

        Returns:
            `list[list[float]]`，与 `texts` 顺序一一对应。

        Raises:
            ModelNotReadyError: 依赖缺失或模型文件不存在。
        """
        if not texts:
            return []

        self._ensure_loaded()
        assert self._model is not None  # 供类型检查

        started = time.perf_counter()
        try:
            output = self._model.encode(
                texts,
                return_dense=True,
                return_sparse=False,
                return_colbert_vecs=False,
            )
        except Exception as exc:  # noqa: BLE001
            logger.error("BGE-M3 编码失败: {!r}", exc)
            raise ModelNotReadyError(f"BGE-M3 编码失败: {exc!r}", model=self.model_name) from exc

        dense = output.get("dense_vecs") if isinstance(output, dict) else output
        vectors = dense.tolist() if hasattr(dense, "tolist") else [list(v) for v in dense]
        logger.debug(
            "BGE-M3 编码完成: {} 条, 耗时 {:.3f}s, 维度 {}",
            len(vectors),
            time.perf_counter() - started,
            len(vectors[0]) if vectors else 0,
        )
        return vectors


def get_embedder() -> BGEM3Embedder:
    """获取 BGEM3Embedder 单例（进程内唯一）。"""
    global _instance
    if _instance is None:
        with _instance_lock:
            if _instance is None:
                _instance = BGEM3Embedder()
    return _instance


def is_embedder_loaded() -> bool:
    """嵌入模型是否已加载（供 /health 使用，不触发加载）。"""
    return _instance is not None and _instance.is_loaded


__all__ = ["BGEM3Embedder", "get_embedder", "is_embedder_loaded"]
