"""BGE-Reranker-v2-m3 重排服务（单例 + 懒加载 + 线程安全）。

重要约定：
- 模型缺失 / FlagEmbedding 导入失败**不会**让服务启动崩溃（懒加载）；
- 真正调用 `rerank()` 时若模型不可用，抛 `ModelNotReadyError`，
  由全局异常处理器转成 HTTP 503 + `{"code": 5031, ...}`。

## 为什么用 `FlagReranker` 而不是 `FlagLLMReranker`（实测修正）

执行计划里写的是 `FlagLLMReranker`，但**实测证明它对这个模型是错的**：

`FlagLLMReranker` 构造时并不报错（它按生成式模型把权重加载进来），
但打分时会抛：

    TypeError: AbsReranker.compute_score() takes 2 positional arguments but 3 were given

因为它的 `compute_score` 签名是 `(self, pairs)`，而调用是 `compute_score(query, passages)`。
更根本的问题是**权重架构不对**：`BAAI/bge-reranker-v2-m3` 是 XLM-RoBERTa 结构的
**cross-encoder**（带 `classifier.dense` / `classifier.out_proj` 分类头），
而 `FlagLLMReranker` 面向 bge-reranker-v2-gemma / minicpm 这类**生成式**重排模型
（靠 lm_head 的 logits 打分）。实测加载日志就是证据：

    XLMRobertaForCausalLM LOAD REPORT
    classifier.dense.weight    | UNEXPECTED
    classifier.out_proj.weight | UNEXPECTED
    lm_head.*                  | MISSING

分类头根本没被用上、lm_head 是随机初始化的 —— 即使不报错，
打出来的分数也是无意义的。所以这里**按架构先判断再加载**：

- `xlm-roberta` / `bert` 这类编码器架构 → `FlagReranker`；
- 确实加载生成式重排模型（bge-reranker-v2-gemma 等）时才用 `FlagLLMReranker`。

判断依据取自模型自己的 `config.json`（`architectures` / `model_type`），
而不是"先试一个、失败再试另一个"—— 后者会像这次一样
**先失败、再静默用错类**，比直接报错更危险。
"""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any

from app.core.config import apply_runtime_env, get_settings
from app.core.exceptions import ModelNotReadyError
from app.core.logger import logger
from app.services.model_cache import assert_model_cached

#: 模块级单例
_instance: "BGEReranker | None" = None
_instance_lock = threading.Lock()

#: 属于"生成式重排"的架构特征（这类才需要 FlagLLMReranker）
_GENERATIVE_ARCH_HINTS = ("ForCausalLM", "ForConditionalGeneration")


def _resolve_model_dir(model_name: str, cache_dir: str | None) -> Path | None:
    """定位模型目录：优先 MODEL_CACHE_DIR（本地目录模式），否则找 HF 缓存 snapshot。"""
    # model_name 本身就是一个本地模型目录（ModelScope 下到 server-ai/models/ 的情形）
    direct = Path(model_name)
    if direct.is_dir() and (direct / "config.json").exists():
        return direct

    leaf = model_name.split("/")[-1]

    if cache_dir:
        base = Path(cache_dir)
        candidate = base / leaf
        if candidate.exists():
            return candidate
        if base.exists():
            # 本地目录模式下 cache_dir 可能直接就是模型根目录
            return base if (base / "config.json").exists() else None
        return None

    hub = Path.home() / ".cache" / "huggingface" / "hub"
    folder = hub / ("models--" + model_name.replace("/", "--"))
    if not folder.exists():
        return None
    snapshots = sorted((folder / "snapshots").glob("*"))
    return snapshots[-1] if snapshots else None


def _detect_model_kind(model_name: str, cache_dir: str | None) -> str:
    """读模型 config.json 判断该用哪种 FlagEmbedding 类。

    Returns:
        "cross"（cross-encoder，用 FlagReranker）或 "llm"（生成式，用 FlagLLMReranker）。
        判断不出来时返回 "cross"：编码器架构是绝大多数重排模型的情况，
        而且真用错时会明确报错，比"静默用错类"安全得多。
    """
    try:
        model_dir = _resolve_model_dir(model_name, cache_dir)
        if model_dir is None:
            logger.warning("未能定位模型目录，按 cross-encoder（FlagReranker）处理")
            return "cross"

        config_file = model_dir / "config.json"
        if not config_file.exists():
            return "cross"

        config = json.loads(config_file.read_text(encoding="utf-8"))
        architectures = config.get("architectures") or []
        model_type = str(config.get("model_type") or "")

        for arch in architectures:
            if any(hint in str(arch) for hint in _GENERATIVE_ARCH_HINTS):
                logger.info("重排模型架构 {} 属于生成式，使用 FlagLLMReranker", arch)
                return "llm"

        logger.info(
            "重排模型 architectures={} model_type={} 属编码器（cross-encoder），使用 FlagReranker",
            architectures,
            model_type,
        )
        return "cross"
    except Exception as exc:  # noqa: BLE001
        logger.warning("读取重排模型架构失败，按 cross-encoder 处理: {!r}", exc)
        return "cross"


class BGEReranker:
    """BAAI/bge-reranker-v2-m3 重排器封装。"""

    def __init__(self, model_name: str | None = None) -> None:
        settings = get_settings()
        self.model_name: str = model_name or settings.reranker_model_path
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
            settings = get_settings()
            # 预检本地缓存：缺文件就立刻抛 ModelNotReadyError -> HTTP 503
            assert_model_cached(self.model_name, settings.model_cache_dir_or_none)

            try:
                import FlagEmbedding  # noqa: F401
            except Exception as exc:  # noqa: BLE001
                self._load_error = (
                    f"FlagEmbedding 导入失败（可能未安装或 torch 缺失）: {exc!r}"
                )
                logger.error("{} | model={}", self._load_error, self.model_name)
                raise ModelNotReadyError(self._load_error, model=self.model_name) from exc

            # 按模型架构**先决定**用哪个类，而不是"先试错再回退"
            kind = _detect_model_kind(self.model_name, settings.model_cache_dir_or_none)
            started = time.perf_counter()

            try:
                if kind == "llm":
                    from FlagEmbedding import FlagLLMReranker

                    logger.info("开始加载重排模型(FlagLLMReranker): {}", self.model_name)
                    self._model = FlagLLMReranker(self.model_name, use_fp16=False)
                else:
                    from FlagEmbedding import FlagReranker

                    logger.info("开始加载重排模型(FlagReranker): {}", self.model_name)
                    self._model = FlagReranker(self.model_name, use_fp16=False)
                self._kind = kind
            except Exception as exc:  # noqa: BLE001
                self._load_error = f"重排模型加载失败（模型文件可能未下载）: {exc!r}"
                logger.error("{} | model={}", self._load_error, self.model_name)
                raise ModelNotReadyError(self._load_error, model=self.model_name) from exc

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
