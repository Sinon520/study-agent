"""BGE-Reranker-v2-m3 重排服务（单例 + 懒加载 + 线程安全）。

重要约定：
- 模型缺失 / FlagEmbedding 导入失败**不会**让服务启动崩溃（懒加载）；
- 真正调用 `rerank()` 时若模型不可用，抛 `ModelNotReadyError`，
  由全局异常处理器转成 HTTP 503 + `{"code": 5031, ...}`。

关于加载类：
需求指定 `FlagLLMReranker`。但 `BAAI/bge-reranker-v2-m3` 实际是
XLM-RoBERTa 结构的 cross-encoder 权重，官方推荐类为 `FlagReranker`；
`FlagLLMReranker` 面向 bge-reranker-v2-gemma / minicpm 这类生成式重排模型。
因此这里采用「优先 FlagLLMReranker，失败自动回退 FlagReranker」的兼容策略，
两者调用签名都已在 `_compute_scores()` 中统一。
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
_instance: "BGEReranker | None" = None
_instance_lock = threading.Lock()


class BGEReranker:
    """BAAI/bge-reranker-v2-m3 重排器封装。"""

    def __init__(self, model_name: str | None = None) -> None:
        settings = get_settings()
        self.model_name: str = model_name or settings.reranker_model
        self._model: Any = None
        self._kind: str = ""  # "llm" 或 "cross"
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
            if self._model is not None:
                return

            # 必须在导入 FlagEmbedding/huggingface_hub 之前把 HF_ENDPOINT 写进环境变量
            apply_runtime_env()
            # 预检本地缓存：缺文件就立刻抛 ModelNotReadyError -> HTTP 503
            assert_model_cached(self.model_name, get_settings().model_cache_dir_or_none)

            try:
                import FlagEmbedding  # noqa: F401
            except Exception as exc:  # noqa: BLE001
                self._load_error = (
                    f"FlagEmbedding 导入失败（可能未安装或 torch 缺失）: {exc!r}"
                )
                logger.error("{} | model={}", self._load_error, self.model_name)
                raise ModelNotReadyError(self._load_error, model=self.model_name) from exc

            started = time.perf_counter()
            model = None
            errors: list[str] = []

            # 1) 首选：FlagLLMReranker（需求指定）
            try:
                from FlagEmbedding import FlagLLMReranker

                logger.info("开始加载重排模型(FlagLLMReranker): {}", self.model_name)
                model = FlagLLMReranker(self.model_name, use_fp16=False)
                self._kind = "llm"
            except Exception as exc:  # noqa: BLE001
                errors.append(f"FlagLLMReranker 失败: {exc!r}")
                logger.warning(
                    "FlagLLMReranker 加载 {} 失败，回退 FlagReranker: {!r}",
                    self.model_name,
                    exc,
                )

            # 2) 回退：FlagReranker（bge-reranker-v2-m3 官方推荐类）
            if model is None:
                try:
                    from FlagEmbedding import FlagReranker

                    logger.info("开始加载重排模型(FlagReranker): {}", self.model_name)
                    model = FlagReranker(self.model_name, use_fp16=False)
                    self._kind = "cross"
                except Exception as exc:  # noqa: BLE001
                    errors.append(f"FlagReranker 失败: {exc!r}")

            if model is None:
                self._load_error = (
                    "重排模型加载失败（模型文件可能未下载）: " + " | ".join(errors)
                )
                logger.error("{} | model={}", self._load_error, self.model_name)
                raise ModelNotReadyError(self._load_error, model=self.model_name)

            self._model = model
            self._load_seconds = time.perf_counter() - started
            self._load_error = None
            logger.info(
                "重排模型加载完成，耗时 {:.2f}s，实现类={}",
                self._load_seconds,
                self._kind,
            )

    # ---------------- 内部调用适配 ----------------
    def _compute_scores(self, query: str, passages: list[str]) -> list[float]:
        """统一不同 FlagEmbedding 版本的打分调用签名。"""
        assert self._model is not None
        if self._kind == "llm":
            # FlagLLMReranker.compute_score(query, passages) -> list[float]
            raw = self._model.compute_score(query, passages)
        else:
            # FlagReranker.compute_score([[query, passage], ...]) -> list[float]
            raw = self._model.compute_score([[query, p] for p in passages])

        if isinstance(raw, (int, float)):
            raw = [raw]
        if hasattr(raw, "tolist"):  # numpy 数组
            raw = raw.tolist()
        if isinstance(raw, list) and raw and isinstance(raw[0], list):
            # 少数版本返回 [[score], ...]
            raw = [row[0] if isinstance(row, list) and row else 0.0 for row in raw]
        return [float(x) for x in raw]

    # ---------------- 业务方法 ----------------
    def rerank(
        self, query: str, passages: list[str], top_k: int = 5
    ) -> list[tuple[int, float]]:
        """对候选段落打分并返回 top_k（按分数降序）。

        Args:
            query: 查询语句。
            passages: 候选段落列表。
            top_k: 返回条数。

        Returns:
            `list[tuple[int, float]]`，元素为 (原始下标, 分数)，按分数降序。

        Raises:
            ModelNotReadyError: 依赖缺失或模型文件不存在。
        """
        if not passages:
            return []

        self._ensure_loaded()
        started = time.perf_counter()
        try:
            scores = self._compute_scores(query, passages)
        except Exception as exc:  # noqa: BLE001
            logger.error("重排打分失败: {!r}", exc)
            raise ModelNotReadyError(
                f"重排打分失败: {exc!r}", model=self.model_name
            ) from exc

        if len(scores) != len(passages):
            logger.warning(
                "重排分数数量({})与段落数量({})不一致，按最小值截断",
                len(scores),
                len(passages),
            )

        ranked = sorted(
            enumerate(scores[: len(passages)]), key=lambda item: item[1], reverse=True
        )
        top = ranked[: max(0, top_k)]
        logger.debug(
            "重排完成: {} 段 -> top{}, 耗时 {:.3f}s",
            len(passages),
            len(top),
            time.perf_counter() - started,
        )
        return [(int(idx), float(score)) for idx, score in top]


def get_reranker() -> BGEReranker:
    """获取 BGEReranker 单例（进程内唯一）。"""
    global _instance
    if _instance is None:
        with _instance_lock:
            if _instance is None:
                _instance = BGEReranker()
    return _instance


def is_reranker_loaded() -> bool:
    """重排模型是否已加载（供 /health 使用，不触发加载）。"""
    return _instance is not None and _instance.is_loaded


__all__ = ["BGEReranker", "get_reranker", "is_reranker_loaded"]
