"""业务服务包：本地嵌入 / 重排模型的单例懒加载封装。

注意：此文件只做惰性导出（`__getattr__`），不会在本包被导入时加载任何模型，
避免 `import app.services` 就触发几个 GB 的模型加载。
"""

from __future__ import annotations

__all__ = ["BGEM3Embedder", "get_embedder", "BGEReranker", "get_reranker"]


def __getattr__(name: str):
    if name in ("BGEM3Embedder", "get_embedder"):
        from app.services import embedding

        return getattr(embedding, name)
    if name in ("BGEReranker", "get_reranker"):
        from app.services import reranker

        return getattr(reranker, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
