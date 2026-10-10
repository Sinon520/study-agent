"""API 请求 / 响应模型。"""

from __future__ import annotations

from pydantic import BaseModel, Field


class EmbedRequest(BaseModel):
    """嵌入请求。"""

    texts: list[str] = Field(..., min_length=1, description="待编码文本列表")
    return_sparse: bool = Field(default=False, description="是否返回稀疏向量")


class EmbedResponse(BaseModel):
    """嵌入响应。"""

    code: int = 0
    msg: str = "ok"
    dimension: int = Field(..., description="向量维度，BGE-M3 稠密向量为 1024")
    count: int = Field(..., description="向量条数")
    vectors: list[list[float]] = Field(default_factory=list, description="稠密向量")
    sample: list[float] = Field(default_factory=list, description="首条向量前 8 维，便于肉眼确认")


class RerankRequest(BaseModel):
    """重排请求。"""

    query: str = Field(..., min_length=1, description="查询语句")
    passages: list[str] = Field(..., min_length=1, description="候选段落列表")
    top_k: int = Field(default=5, ge=1, description="返回条数")


class RerankItem(BaseModel):
    """重排结果项。"""

    index: int = Field(..., description="在 passages 中的原始下标")
    score: float = Field(..., description="相关性分数")
    passage: str = Field(..., description="段落原文")


class RerankResponse(BaseModel):
    """重排响应。"""

    code: int = 0
    msg: str = "ok"
    results: list[RerankItem] = Field(default_factory=list)


class ModelsStatus(BaseModel):
    """本地模型加载状态（注意：只报告状态，不触发加载）。"""

    embedding: bool = Field(..., description="BGE-M3 是否已加载到内存")
    reranker: bool = Field(..., description="BGE-Reranker 是否已加载到内存")


class HealthResponse(BaseModel):
    """健康检查响应。"""

    status: str = "UP"
    service: str
    version: str
    traceId: str
    models: ModelsStatus
    llm_configured: bool


class LLMHealthResponse(BaseModel):
    """LLM 健康检查响应。"""

    code: int = 0
    msg: str = "ok"
    data: dict = Field(default_factory=dict)


class DBHealthResponse(BaseModel):
    """数据库健康检查响应。"""

    status: str
    error: str | None = None
    detail: dict = Field(default_factory=dict)


class StreamProbeResponse(BaseModel):
    """SSE 流式探针说明（真实响应是 text/event-stream）。"""

    note: str = "该路径返回 SSE 事件流：start / reasoning / content / done"
