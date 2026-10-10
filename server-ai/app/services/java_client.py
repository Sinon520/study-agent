"""与 Java 侧互调的客户端。

设计原则：**Java 是唯一写入口**。Python 需要写库时（回写切片、更新文档状态、
写入问答消息、落计划任务）一律回调 Java 的内部接口，而不是直连 MySQL。
代价是多一次内网 HTTP，换来的是：
- 权限校验、事务、审计、traceId 全部只在一处（Java）实现；
- Python 侧不需要持有数据库写入权限，安全边界清晰。
"""

from __future__ import annotations

from typing import Any

import httpx

from app.core.config import get_settings
from app.core.exceptions import BizException
from app.core.logger import logger
from app.core.trace import get_trace_id


class JavaClient:
    """Java 内部接口客户端（同步；RAG 链路本身就是同步/线程池里跑）。"""

    def __init__(self) -> None:
        settings = get_settings()
        self.base_url = settings.java_base_url.rstrip("/")
        self.internal_token = settings.internal_token
        self.timeout = 30.0

    def _headers(self) -> dict[str, str]:
        """带上内网令牌与 traceId —— 后者让两端日志能串起来。"""
        return {
            "Content-Type": "application/json; charset=utf-8",
            "X-Internal-Token": self.internal_token,
            "X-Trace-Id": get_trace_id(),
        }

    def _request(self, method: str, path: str, payload: Any = None) -> Any:
        url = f"{self.base_url}{path}"
        try:
            with httpx.Client(timeout=self.timeout) as client:
                response = client.request(
                    method, url, json=payload, headers=self._headers()
                )
        except Exception as exc:  # noqa: BLE001 - 网络问题统一转业务异常
            logger.error("回调 Java 失败: {} {} | {!r}", method, url, exc)
            raise BizException(
                code=5003, msg=f"回调 Java 内部接口失败（{path}）：{exc!r}"
            ) from exc

        if response.status_code >= 500:
            logger.error(
                "Java 内部接口返回 {}: {} {} | body={}",
                response.status_code,
                method,
                url,
                response.text[:500],
            )
            raise BizException(
                code=5003,
                msg=f"Java 内部接口异常（{path}）: HTTP {response.status_code}",
            )

        try:
            body = response.json()
        except Exception:  # noqa: BLE001
            return response.text

        # Java 侧统一返回体：{code, msg, data}
        if isinstance(body, dict) and "code" in body:
            if body.get("code") != 200:
                logger.warning(
                    "Java 内部接口业务失败: {} | code={} msg={}",
                    path,
                    body.get("code"),
                    body.get("msg"),
                )
                raise BizException(
                    code=int(body.get("code") or 5003),
                    msg=str(body.get("msg") or "Java 内部接口业务失败"),
                )
            return body.get("data")
        return body

    # ------------------------------------------------------------- 知识库

    def save_chunks(self, document_id: int, chunks: list[dict]) -> int:
        """批量回写切片元数据到 Java（落 MySQL kb_chunk）。

        Args:
            document_id: 文档 ID。
            chunks: 每项 `{chunkIndex, content, pageNo, vectorId}`。

        Returns:
            写入条数。
        """
        data = self._request(
            "POST",
            "/internal/kb/chunks",
            {"documentId": document_id, "chunks": chunks},
        )
        return int(data or 0)

    def update_document_status(
        self,
        document_id: int,
        parse_status: str,
        chunk_count: int | None = None,
        error_msg: str | None = None,
    ) -> None:
        """更新文档解析状态（待解析/解析中/完成/失败）。"""
        self._request(
            "PUT",
            f"/internal/kb/document/{document_id}/status",
            {
                "parseStatus": parse_status,
                "chunkCount": chunk_count,
                "errorMsg": error_msg,
            },
        )

    # ------------------------------------------------------------- 问答落库

    def save_chat_messages(self, payload: dict) -> Any:
        """把一轮问答（user + assistant 两条）落库。

        payload: `{userId, sessionId, question, answer, reasoning, citations, tokenCount}`
        """
        return self._request("POST", "/internal/chat/messages", payload)

    def get_chat_history(self, session_id: int, limit: int = 6) -> list[dict]:
        """取会话历史（最近 N 轮），供多轮问答拼 prompt。"""
        data = self._request(
            "GET", f"/internal/chat/history?sessionId={session_id}&limit={limit}"
        )
        return list(data or [])

    # ------------------------------------------------------------- 计划

    def create_plan_with_tasks(self, payload: dict) -> Any:
        """把 Agent 生成的计划与任务批量落库（source=Agent生成）。"""
        return self._request("POST", "/plan/agent-create", payload)


_client: JavaClient | None = None


def get_java_client() -> JavaClient:
    """获取 Java 客户端单例。"""
    global _client
    if _client is None:
        _client = JavaClient()
    return _client


__all__ = ["JavaClient", "get_java_client"]
