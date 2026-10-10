"""Milvus 向量库封装（Milvus Lite 本地文件模式）。

为什么用 Milvus Lite：`pip install pymilvus` 就自带，落盘成一个本地文件，
不需要 Docker、不需要起服务端进程；个人项目与演示机友好。
`pymilvus.MilvusClient(uri="./data/milvus_study.db")` 直接就是 Lite 模式。

Collection 设计（对应 MySQL 的 `kb_chunk` 表）：

| 字段          | 类型         | 说明                                   |
|---------------|--------------|----------------------------------------|
| id            | VARCHAR(64)  | 主键，等于 MySQL `kb_chunk.vector_id`  |
| user_id       | INT64        | **检索时必须按用户过滤**，防止串资料   |
| document_id   | INT64        | 所属文档                               |
| chunk_index   | INT64        | 分块序号（引用排序用）                 |
| page_no       | INT64        | 来源页码（引用溯源用）                 |
| content       | VARCHAR      | 原文片段（命中后回显，省一次 MySQL 往返）|
| embedding     | FLOAT_VECTOR | 1024 维（BGE-M3 稠密向量）             |

索引：HNSW + COSINE。选 COSINE 是因为 BGE 系列向量的相似度按余弦计算；
HNSW 在小数据量下比 IVF 更省心（不需要训练）。
"""

from __future__ import annotations

import threading
import uuid
from pathlib import Path
from typing import Any

from app.core.config import get_settings
from app.core.exceptions import BizException
from app.core.logger import logger

#: BGE-M3 稠密向量维度
VECTOR_DIM = 1024

_client: Any = None
_client_lock = threading.Lock()

#: collection 是否已确认存在（避免每次请求都查一次）
_collection_ready = False


def get_client() -> Any:
    """获取 MilvusClient 单例。

    Lite 模式第一次调用会创建本地文件；`uri` 里的目录不存在会报错，
    所以这里顺手把父目录建出来。
    """
    global _client
    if _client is not None:
        return _client

    with _client_lock:
        if _client is not None:
            return _client

        try:
            from pymilvus import MilvusClient
        except Exception as exc:  # noqa: BLE001
            raise BizException(
                code=5002, msg=f"pymilvus 不可用，无法使用向量库：{exc!r}"
            ) from exc

        settings = get_settings()
        uri = settings.milvus_uri

        # 本地文件模式：把相对路径规范化成**绝对路径**再交给 Milvus Lite。
        # 踩坑：Milvus Lite 对 `./data/milvus_study.db` 这种相对路径会直接抛
        # ConnectionConfigException（它按 URI 解析，不认当前工作目录），
        # 所以这里用 BASE_DIR 拼成绝对路径，并从任何工作目录启动都一致。
        if not uri.startswith(("http://", "https://", "tcp://")):
            from app.core.config import BASE_DIR

            path = Path(uri)
            if not path.is_absolute():
                path = (BASE_DIR / path).resolve()
            path.parent.mkdir(parents=True, exist_ok=True)
            uri = str(path)
            logger.info("Milvus 本地文件模式，数据文件: {}", uri)

        logger.info("初始化 Milvus 客户端: uri={}", uri)
        try:
            _client = MilvusClient(uri=uri)
        except Exception as exc:  # noqa: BLE001
            raise BizException(code=5002, msg=f"Milvus 初始化失败：{exc!r}") from exc
        logger.info("Milvus 客户端就绪: uri={}", uri)
        return _client


def ensure_collection() -> str:
    """确保 collection 存在（不存在则按 schema 建好并建索引）。"""
    global _collection_ready
    if _collection_ready:
        return get_settings().milvus_collection

    client = get_client()
    name = get_settings().milvus_collection

    try:
        if client.has_collection(name):
            _collection_ready = True
            return name

        from pymilvus import DataType

        schema = client.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field("id", DataType.VARCHAR, is_primary=True, max_length=64)
        schema.add_field("user_id", DataType.INT64)
        schema.add_field("document_id", DataType.INT64)
        schema.add_field("chunk_index", DataType.INT64)
        schema.add_field("page_no", DataType.INT64)
        schema.add_field("content", DataType.VARCHAR, max_length=8192)
        schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=VECTOR_DIM)

        index_params = client.prepare_index_params()
        index_params.add_index(
            field_name="embedding",
            index_type="HNSW",
            metric_type="COSINE",
            params={"M": 16, "efConstruction": 200},
        )

        client.create_collection(collection_name=name, schema=schema, index_params=index_params)
        logger.info("Milvus collection 已创建: {}（HNSW + COSINE, dim={}）", name, VECTOR_DIM)
        _collection_ready = True
        return name
    except Exception as exc:  # noqa: BLE001
        raise BizException(code=5002, msg=f"Milvus collection 初始化失败：{exc!r}") from exc


def insert_chunks(
    user_id: int,
    document_id: int,
    chunks: list[Any],
    vectors: list[list[float]],
) -> list[str]:
    """把分块与向量写入 Milvus。

    Args:
        user_id: 所属用户（检索时按此过滤，是数据隔离的关键字段）。
        document_id: 所属文档。
        chunks: `document.Chunk` 列表。
        vectors: 与 chunks 一一对应的 1024 维向量。

    Returns:
        写入的向量主键列表（与 chunks 顺序一致），调用方要把它回写到
        MySQL 的 `kb_chunk.vector_id` 以建立双向定位。

    Raises:
        BizException: 数量不一致或写入失败。
    """
    if len(chunks) != len(vectors):
        raise BizException(
            code=4000,
            msg=f"分块数({len(chunks)})与向量数({len(vectors)})不一致，拒绝写入",
        )
    if not chunks:
        return []

    for vector in vectors:
        if len(vector) != VECTOR_DIM:
            raise BizException(
                code=4000,
                msg=f"向量维度不是 {VECTOR_DIM}（实际 {len(vector)}），请检查嵌入模型",
            )

    name = ensure_collection()
    client = get_client()

    ids: list[str] = []
    rows: list[dict] = []
    for chunk, vector in zip(chunks, vectors):
        vector_id = uuid.uuid4().hex
        ids.append(vector_id)
        rows.append(
            {
                "id": vector_id,
                "user_id": int(user_id),
                "document_id": int(document_id),
                "chunk_index": int(chunk.index),
                "page_no": int(chunk.page_no) if chunk.page_no else 0,
                "content": chunk.content[:8000],  # 与 schema 的 max_length 对齐
                "embedding": vector,
            }
        )

    try:
        client.insert(collection_name=name, data=rows)
        logger.info(
            "写入 Milvus 成功: collection={} document_id={} 条数={}",
            name,
            document_id,
            len(rows),
        )
    except Exception as exc:  # noqa: BLE001
        raise BizException(code=5002, msg=f"写入 Milvus 失败：{exc!r}") from exc

    return ids


def search(
    user_id: int,
    query_vector: list[float],
    top_k: int = 20,
    document_ids: list[int] | None = None,
) -> list[dict]:
    """向量检索。

    Args:
        user_id: **必填**，按用户过滤，避免检索到别人的资料。
        query_vector: 查询向量。
        top_k: 召回条数。
        document_ids: 可选，限定只在某些文档内检索。

    Returns:
        `list[dict]`，每项含 `id / document_id / chunk_index / page_no / content / score`，
        按相似度降序。

    Raises:
        BizException: 检索失败。
    """
    if len(query_vector) != VECTOR_DIM:
        raise BizException(
            code=4000, msg=f"查询向量维度不是 {VECTOR_DIM}（实际 {len(query_vector)}）"
        )

    name = ensure_collection()
    client = get_client()

    # 过滤表达式：user_id 必须匹配；这是多用户共享一个 collection 时的隔离手段。
    # 注意 Milvus 的字段名与 schema 一致，expression 里写的是 schema 字段名。
    expr = f"user_id == {int(user_id)}"
    if document_ids:
        ids_literal = ", ".join(str(int(i)) for i in document_ids)
        expr = f"{expr} and document_id in [{ids_literal}]"

    try:
        results = client.search(
            collection_name=name,
            data=[query_vector],
            limit=max(1, int(top_k)),
            filter=expr,
            output_fields=["document_id", "chunk_index", "page_no", "content"],
            search_params={"metric_type": "COSINE", "params": {"ef": 128}},
        )
    except Exception as exc:  # noqa: BLE001
        raise BizException(code=5002, msg=f"Milvus 检索失败：{exc!r}") from exc

    hits: list[dict] = []
    for hit in (results[0] if results else []):
        entity = hit.get("entity") or {}
        hits.append(
            {
                "id": hit.get("id"),
                "document_id": entity.get("document_id"),
                "chunk_index": entity.get("chunk_index"),
                "page_no": entity.get("page_no"),
                "content": entity.get("content") or "",
                "score": float(hit.get("distance") or 0.0),
            }
        )
    logger.debug("Milvus 检索: user_id={} top_k={} 命中={}", user_id, top_k, len(hits))
    return hits


def delete_document(document_id: int) -> int:
    """删除某文档在向量库里的全部切片（文档被删时调用）。"""
    name = ensure_collection()
    client = get_client()
    try:
        client.delete(collection_name=name, filter=f"document_id == {int(document_id)}")
        logger.info("已删除 Milvus 中 document_id={} 的向量", document_id)
        return 1
    except Exception as exc:  # noqa: BLE001
        logger.warning("删除 Milvus 向量失败 document_id={}: {!r}", document_id, exc)
        return 0


def collection_stats() -> dict:
    """collection 统计（验收时用：向量数应与 MySQL 的 kb_chunk 行数一致）。"""
    name = ensure_collection()
    client = get_client()
    try:
        stats = client.get_collection_stats(collection_name=name)
        return {"collection": name, "row_count": int(stats.get("row_count", 0))}
    except Exception as exc:  # noqa: BLE001
        logger.warning("获取 Milvus 统计失败: {!r}", exc)
        return {"collection": name, "row_count": -1, "error": str(exc)}


__all__ = [
    "VECTOR_DIM",
    "get_client",
    "ensure_collection",
    "insert_chunks",
    "search",
    "delete_document",
    "collection_stats",
]
