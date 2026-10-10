"""应用配置。

使用 pydantic-settings 从环境变量 / `.env` 文件读取配置。
严禁在代码中硬编码任何 API Key：`DASHSCOPE_API_KEY` 只从环境变量读取，
未配置时为空字符串，由业务侧决定如何降级。
"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

#: server-ai 工程根目录（app/core/config.py -> parents[0]=core, [1]=app, [2]=server-ai）
BASE_DIR: Path = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    """全局配置对象。

    字段名小写，环境变量名大写（pydantic-settings 默认大小写不敏感匹配）；
    少数字段通过 `alias` 显式绑定环境变量名。
    """

    # env_file 用**相对文件自身的绝对路径**，保证无论从哪个工作目录启动都能读到 .env；
    # 同时保留 CWD 下的 ".env" 作为兜底（后面的文件优先级更高）。
    model_config = SettingsConfigDict(
        env_file=(BASE_DIR / ".env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        populate_by_name=True,
    )

    # ---------------- 应用基础 ----------------
    app_name: str = "study-agent-ai"
    app_version: str = "1.0.0"
    host: str = "0.0.0.0"
    port: int = 8000
    debug: bool = False

    # ---------------- 阿里云百炼（DashScope，OpenAI 兼容） ----------------
    dashscope_api_key: str = Field(default="", alias="DASHSCOPE_API_KEY")
    dashscope_base_url: str = Field(
        default="https://dashscope.aliyuncs.com/compatible-mode/v1",
        alias="DASHSCOPE_BASE_URL",
    )
    dashscope_model: str = Field(default="qwen3.7-flash", alias="DASHSCOPE_MODEL")
    # qwen3.7-flash 是思考模型；LangChain 默认会丢弃 reasoning_content，
    # 开启后启动时自动打补丁，使 additional_kwargs 里能拿到思维链（详见 core/llm.py）
    preserve_reasoning: bool = Field(default=True, alias="PRESERVE_REASONING")

    # ---------------- 本地开源模型 ----------------
    embedding_model: str = Field(default="BAAI/bge-m3", alias="EMBEDDING_MODEL")
    reranker_model: str = Field(
        default="BAAI/bge-reranker-v2-m3", alias="RERANKER_MODEL"
    )
    # 为空表示使用 HuggingFace 默认缓存目录（C:\Users\<user>\.cache\huggingface）
    model_cache_dir: str = Field(default="", alias="MODEL_CACHE_DIR")
    hf_endpoint: str = Field(default="https://hf-mirror.com", alias="HF_ENDPOINT")
    # 双保险开关：置 true 会设置 HF_HUB_OFFLINE=1（完全不联网）。
    # 默认 **false**，原因见 app/services/model_cache.py：
    # bge-m3 的 imgs/.DS_Store 在镜像上恒 403，snapshot 永远不「完整」，
    # 离线模式下会抛 IncompleteSnapshotError，即使权重已下好也加载不了。
    hf_hub_offline: bool = Field(default=False, alias="HF_HUB_OFFLINE")
    # True：模型只从本地缓存加载，缺失时**立刻**返回 503，绝不在请求里联网下载几 GB
    # False：允许 huggingface_hub 在请求内按需下载（开发期方便，但会长时间阻塞请求）
    model_local_files_only: bool = Field(default=True, alias="MODEL_LOCAL_FILES_ONLY")

    # ---------------- MySQL ----------------
    mysql_host: str = Field(default="127.0.0.1", alias="MYSQL_HOST")
    mysql_port: int = Field(default=3306, alias="MYSQL_PORT")
    mysql_user: str = Field(default="root", alias="MYSQL_USER")
    mysql_password: str = Field(default="", alias="MYSQL_PASSWORD")
    mysql_database: str = Field(default="study_agent", alias="MYSQL_DATABASE")

    # ---------------- Redis ----------------
    redis_host: str = Field(default="127.0.0.1", alias="REDIS_HOST")
    redis_port: int = Field(default=6379, alias="REDIS_PORT")

    # ---------------- Milvus（Lite 本地文件模式，免 Docker） ----------------
    milvus_uri: str = Field(default="./data/milvus_study.db", alias="MILVUS_URI")
    milvus_collection: str = Field(default="kb_chunk", alias="MILVUS_COLLECTION")

    # ---------------- 与 Java 侧（server-java）互调 ----------------
    java_base_url: str = Field(default="http://127.0.0.1:8080", alias="JAVA_BASE_URL")
    internal_token: str = Field(
        default="study-agent-internal", alias="INTERNAL_TOKEN"
    )

    # ---------------- RAG 检索参数（第 3 周） ----------------
    #: 分块目标字符数，与执行计划一致
    chunk_size: int = Field(default=500, alias="CHUNK_SIZE")
    #: 相邻分块重叠字符数
    chunk_overlap: int = Field(default=50, alias="CHUNK_OVERLAP")
    #: 向量召回条数（先粗召回，再重排）
    retrieve_top_k: int = Field(default=20, alias="RETRIEVE_TOP_K")
    #: 重排后送入 prompt 的条数
    rerank_top_k: int = Field(default=5, alias="RERANK_TOP_K")
    #: 多轮问答带上的历史轮数
    history_rounds: int = Field(default=6, alias="HISTORY_ROUNDS")
    #: 低于该相似度的召回结果直接丢弃（0 表示不过滤）
    min_score: float = Field(default=0.0, alias="MIN_SCORE")

    #: 文件上传落盘目录（Java 上传接口把文件写到这里，或 Python 自己接收上传）
    upload_dir: str = Field(default="./uploads", alias="UPLOAD_DIR")

    # ---------------- 对外只读辅助属性 ----------------
    @property
    def llm_configured(self) -> bool:
        """是否已配置百炼 API Key。"""
        return bool(self.dashscope_api_key and self.dashscope_api_key.strip())

    @property
    def masked_api_key(self) -> str:
        """打码后的 API Key，仅用于启动日志展示。"""
        key = (self.dashscope_api_key or "").strip()
        if not key:
            return "(未配置)"
        return f"{key[:8]}****"

    @property
    def model_cache_dir_or_none(self) -> str | None:
        """返回模型缓存目录；为空时返回 None，表示走 HF 默认缓存。"""
        value = (self.model_cache_dir or "").strip()
        return value or None

    def summary(self) -> dict[str, object]:
        """启动时打印的配置摘要（已脱敏）。"""
        return {
            "app_name": self.app_name,
            "version": self.app_version,
            "host": self.host,
            "port": self.port,
            "dashscope_model": self.dashscope_model,
            "dashscope_base_url": self.dashscope_base_url,
            "dashscope_api_key": self.masked_api_key,
            "preserve_reasoning": self.preserve_reasoning,
            "embedding_model": self.embedding_model,
            "reranker_model": self.reranker_model,
            "model_cache_dir": self.model_cache_dir_or_none or "(HF 默认缓存)",
            "model_local_files_only": self.model_local_files_only,
            "mysql": f"{self.mysql_host}:{self.mysql_port}/{self.mysql_database}",
            "redis": f"{self.redis_host}:{self.redis_port}",
            "milvus_uri": self.milvus_uri,
            "milvus_collection": self.milvus_collection,
            "java_base_url": self.java_base_url,
        }


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局唯一配置实例（带缓存）。"""
    return Settings()


def apply_runtime_env() -> None:
    """把配置中的镜像/缓存设置写进 `os.environ`。

    为什么必须这么做：`huggingface_hub` 在 **import 时**就把 `HF_ENDPOINT`
    固化到常量里（`huggingface_hub.constants.ENDPOINT`），之后再改环境变量无效。
    因此必须在导入 `huggingface_hub` / `transformers` / `FlagEmbedding` **之前**
    调用本函数。这里用 `setdefault`，不覆盖用户已显式设置的值。

    调用点：
    1. 本模块 import 时（最早时机，早于任何模型库导入）；
    2. `app.services.embedding` / `app.services.reranker` 的 `_ensure_loaded()` 中（双保险）。
    """
    settings = get_settings()
    if settings.hf_endpoint:
        os.environ.setdefault("HF_ENDPOINT", settings.hf_endpoint)
    # 关键：hf-xet 会绕过镜像直连 cas-server.xethub.hf.co，未登录时 401，
    # 报 "File reconstruction error: CAS Client Error 401"，必须禁用走经典 HTTP
    os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
    # 镜像站下载较慢，默认 10s 超时容易断流
    os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "60")
    # Windows 无开发者模式时必有符号链接告警，噪音太大
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
    if settings.model_cache_dir_or_none:
        os.environ.setdefault("HF_HOME", settings.model_cache_dir_or_none)
    _normalize_milvus_uri()


def _normalize_milvus_uri() -> None:
    """给 `MILVUS_URI` 环境变量补一个**合法 URI 形态**，避免 pymilvus 在 import 期崩溃。

    ⚠️ 这是一个很隐蔽的坑，值得完整记录：

    `pymilvus` 在 **import 时**就会执行 `Connections()`，它读取环境变量
    `MILVUS_URI` 并调用 `__parse_address_from_uri()`
    （见 `pymilvus/orm/connections.py`）。那个函数只接受
    `http[s]://host[:port]` 形态，遇到裸文件路径会直接抛：

        ConnectionConfigException: Illegal uri: [./data/milvus_study.db],
        expected form 'http[s]://[user:password@]example.com[:12345]'

    而 pydantic-settings 加载 `.env` 时会把 `MILVUS_URI=./data/milvus_study.db`
    写进 `os.environ` —— 于是只要 `.env` 里配了 Milvus 本地文件路径，
    **import pymilvus 就会崩**，连 `MilvusClient(uri=...)` 都还没走到。

    关键点：`MilvusClient` 自己的 Milvus-Lite 支持（`uri.endswith(".db")` 就启用本地实例，
    见 `pymilvus/client/connection_manager.py`）是**后置**的，
    它只在构造客户端时才生效，救不了 import 期的那次解析。

    解决：应用代码**始终显式传 `uri=`** 给 `MilvusClient`（真实路径），
    而环境变量只用来满足 import 期的语法校验，所以把它设成一个合法的占位 URI。
    这样「配置仍读 `.env` 的真实路径」与「pymilvus 能正常 import」两件事不冲突。
    """
    uri = (get_settings().milvus_uri or "").strip()
    if uri.startswith(("http://", "https://", "tcp://", "unix:")):
        # 用户配的是真正的服务端地址，保持原样即可
        return
    # 本地文件模式：真正的路径由 milvus_store.get_client() 显式传给 MilvusClient，
    # 这里的占位值只是为了让 pymilvus 的 import 期解析通过
    os.environ["MILVUS_URI"] = "http://127.0.0.1:19530"


# 模块导入即生效：确保 HF_ENDPOINT 在任何模型库 import 之前已进入环境变量
apply_runtime_env()
