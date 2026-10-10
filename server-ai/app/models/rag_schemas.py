"""RAG 与 Agent 相关的请求 / 响应模型。"""

from __future__ import annotations

from pydantic import BaseModel, Field


# --------------------------------------------------------------------- 入库


class IngestRequest(BaseModel):
    """文档入库请求（由 Java 的 /kb/upload 落盘后回调）。"""

    document_id: int = Field(..., ge=1, alias="documentId", description="kb_document.id")
    user_id: int = Field(..., ge=1, alias="userId", description="所属用户")
    file_path: str = Field(..., alias="filePath", description="服务器上的文件绝对路径")
    chunk_size: int | None = Field(default=None, alias="chunkSize", ge=100, le=2000)
    chunk_overlap: int | None = Field(default=None, alias="chunkOverlap", ge=0, le=500)

    model_config = {"populate_by_name": True, "protected_namespaces": ()}


class IngestSample(BaseModel):
    """入库结果里的样本（便于人工确认切分质量）。"""

    chunkIndex: int
    pageNo: int | None = None
    charCount: int
    preview: str


class IngestResponse(BaseModel):
    """文档入库响应。"""

    code: int = 0
    msg: str = "ok"
    documentId: int
    pageCount: int = Field(..., description="解析出的页/逻辑段数")
    chunkCount: int = Field(..., description="分块数")
    vectorCount: int = Field(..., description="写入 Milvus 的向量数（应与 chunkCount 相等）")
    chunkSize: int
    chunkOverlap: int
    embedSeconds: float = Field(..., description="向量化耗时（秒）")
    totalSeconds: float = Field(..., description="全流程耗时（秒）")
    samples: list[IngestSample] = Field(default_factory=list)
    traceId: str = ""


class KbStatsResponse(BaseModel):
    """向量库统计。"""

    code: int = 0
    msg: str = "ok"
    collection: str
    vectorCount: int
    error: str | None = None
    traceId: str = ""


# --------------------------------------------------------------------- 问答


class ChatRequest(BaseModel):
    """RAG 问答请求。"""

    user_id: int = Field(..., ge=1, alias="userId")
    question: str = Field(..., min_length=1, max_length=2000)
    session_id: int | None = Field(default=None, alias="sessionId", description="会话 ID，为空则视为新会话")
    document_ids: list[int] | None = Field(
        default=None, alias="documentIds", description="限定只在这些文档里检索"
    )

    model_config = {"populate_by_name": True, "protected_namespaces": ()}


class Citation(BaseModel):
    """引用溯源项。"""

    index: int
    documentId: int
    documentName: str
    chunkId: str
    chunkIndex: int
    pageNo: int | None = None
    score: float
    snippet: str


# --------------------------------------------------------------------- Agent


class ClassifyRequest(BaseModel):
    """错题归类请求（Java 的 RocketMQ 消费者调用）。"""

    wrong_question_id: int = Field(..., alias="wrongQuestionId")
    content: str = Field(..., min_length=1, max_length=5000)
    subject: str | None = Field(default=None, description="用户填的科目，作为参考")

    model_config = {"populate_by_name": True, "protected_namespaces": ()}


class ClassifyResponse(BaseModel):
    """错题归类响应（结构化 JSON）。"""

    code: int = 0
    msg: str = "ok"
    subject: str = Field(..., description="判定出的科目")
    tags: list[str] = Field(default_factory=list, description="知识点标签")
    difficulty: int | None = Field(default=None, ge=1, le=5)
    reason: str = Field(default="", description="判定依据（便于人工核对）")


class ReportRequest(BaseModel):
    """复盘报告生成请求。"""

    user_id: int = Field(..., alias="userId")
    period_start: str = Field(..., alias="periodStart", description="yyyy-MM-dd")
    period_end: str = Field(..., alias="periodEnd", description="yyyy-MM-dd")
    stats: dict = Field(default_factory=dict, description="Java 聚合好的统计数据")

    model_config = {"populate_by_name": True, "protected_namespaces": ()}


class ReportResponse(BaseModel):
    """复盘报告响应。"""

    code: int = 0
    msg: str = "ok"
    content: str = Field(..., description="markdown 正文")
    highlights: list[str] = Field(default_factory=list, description="数据卡片要点")
    suggestions: list[str] = Field(default_factory=list, description="行动建议")


class PlanGenerateRequest(BaseModel):
    """规划 Agent 请求。"""

    user_id: int = Field(..., alias="userId")
    title: str | None = Field(default=None, description="计划标题，为空则由 Agent 生成")
    exam_type: str = Field(..., alias="examType", description="考研 / 考公")
    target_date: str = Field(..., alias="targetDate", description="yyyy-MM-dd")
    daily_minutes: int = Field(..., alias="dailyMinutes", ge=10, le=1440)
    subjects: list[str] = Field(default_factory=list, description="要覆盖的科目")
    level: str | None = Field(default=None, description="基础水平描述，可选")
    persist: bool = Field(default=True, description="是否回调 Java 落库")

    model_config = {"populate_by_name": True, "protected_namespaces": ()}


class GeneratedTask(BaseModel):
    """Agent 生成的单条任务。"""

    task_date: str = Field(..., alias="taskDate")
    title: str
    content: str = ""
    subject: str = ""
    estimated_minutes: int = Field(default=60, alias="estimatedMinutes")

    model_config = {"populate_by_name": True, "protected_namespaces": ()}


class PlanGenerateResponse(BaseModel):
    """规划 Agent 响应。"""

    code: int = 0
    msg: str = "ok"
    title: str
    targetDate: str
    dailyMinutes: int
    totalTasks: int
    days: int
    tasks: list[GeneratedTask] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list, description="校验与重试过程记录")
    retries: int = Field(default=0, description="校验失败重试次数")
    planId: int | None = Field(default=None, description="落库后的计划 ID（persist=true 时）")
    traceId: str = ""


__all__ = [
    "IngestRequest",
    "IngestResponse",
    "IngestSample",
    "KbStatsResponse",
    "ChatRequest",
    "Citation",
    "ClassifyRequest",
    "ClassifyResponse",
    "ReportRequest",
    "ReportResponse",
    "PlanGenerateRequest",
    "PlanGenerateResponse",
    "GeneratedTask",
]
