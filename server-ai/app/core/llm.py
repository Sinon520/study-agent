"""ChatOpenAI 客户端工厂（阿里云百炼 / DashScope OpenAI 兼容协议）。

关于 `qwen3.7-flash`：
它是**思考模型（reasoning model）**，服务端把「思维链」与「正式回答」分开返回：
- `content`            → 正式回答；
- `reasoning_content`  → 思维链（思考过程）。

⚠️ 实测结论（非常重要，第 3 周 SSE 设计依据）
---------------------------------------------------------------
环境：langchain-openai 1.7.0 + openai 3.26.1 + qwen3.7-flash

1. **服务端确实返回了 `reasoning_content`**：
   - 非流式：`choices[0].message.reasoning_content` 有值（英文思维链，约 700~850 字符）；
   - 流式：`choices[0].delta.reasoning_content` 先逐块下发，`delta.content` 最后才出现
     （实测 77 个 chunk：第 1 个 chunk 开始出 reasoning，第 74 个 chunk 才出 content）。
   - 思考默认开启，`extra_body={"enable_thinking": True}` 无变化。

2. **`langchain_openai.ChatOpenAI` 会静默丢弃 `reasoning_content`**：
   - 非流式 `AIMessage.additional_kwargs` 只有 `{'refusal': None}`；
   - 流式 `AIMessageChunk.additional_kwargs` **全程为空**，只能看到延后出现的 `content`；
   - 唯一残留的痕迹是 usage 里的 token 统计：
     `response_metadata["token_usage"]["completion_tokens_details"]["reasoning_tokens"]`
     （实测该值为 190，说明思考确实发生了，只是文本被丢了）。

3. 根因：langchain-openai 的
   `_convert_delta_to_message_chunk`（流式）与 `_convert_dict_to_message`（非流式）
   只映射 OpenAI 标准字段，未映射 `reasoning_content`。

因此本模块提供两条可用路径：
- `enable_reasoning_passthrough()`：给上述两个**模块级函数**打补丁，
  把 `reasoning_content` 注入 `additional_kwargs`，之后 `extract_reasoning()` 才有效；
  （调用点在模块内是全局名解析，所以替换模块属性即可同时拦截流式与非流式两条路径）
- `get_openai_client()`：直接拿**原生 openai SDK** 客户端，
  其 `delta.reasoning_content` / `delta.content` 天然分离，是做 SSE 的最稳选择。
"""

from __future__ import annotations

from collections.abc import Mapping
from functools import lru_cache
from typing import Any

from langchain_openai import ChatOpenAI

from app.core.config import get_settings
from app.core.logger import logger

#: 补丁是否已安装（幂等标记）
_REASONING_PASSTHROUGH_APPLIED = False


@lru_cache(maxsize=16)
def get_llm(streaming: bool = False, temperature: float = 0.3,
            timeout: float = 60, max_retries: int = 2) -> ChatOpenAI:
    """获取 ChatOpenAI 客户端（带缓存，按参数维度缓存）。

    Args:
        streaming: 是否开启流式输出。
        temperature: 采样温度，默认 0.3。
        timeout: 单次 HTTP 请求超时（秒）。默认 60；长输出场景（如规划 Agent
            的任务 JSON）要显式放宽，否则会被客户端超时掐断。
        max_retries: 传输层重试次数。注意总耗时上限 ≈ timeout × (1 + max_retries)，
            长超时场景建议调小。

    Returns:
        配置好的 `ChatOpenAI` 实例。

    Raises:
        ValueError: 未配置 `DASHSCOPE_API_KEY` 时抛出。
    """
    settings = get_settings()
    if not settings.llm_configured:
        raise ValueError(
            "未配置 DASHSCOPE_API_KEY，请在 .env 或环境变量中设置后再调用大模型"
        )

    logger.debug(
        "创建 ChatOpenAI 客户端: model={} streaming={} temperature={} timeout={} max_retries={}",
        settings.dashscope_model,
        streaming,
        temperature,
        timeout,
        max_retries,
    )
    return ChatOpenAI(
        model=settings.dashscope_model,
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        temperature=temperature,
        streaming=streaming,
        timeout=timeout,
        max_retries=max_retries,
    )


@lru_cache(maxsize=1)
def get_openai_client():
    """获取**原生** openai SDK 同步客户端（推荐用于 SSE 流式输出）。

    原生 SDK 不做字段裁剪，`delta.reasoning_content` 与 `delta.content` 分离可得，
    是做「思考中… / 正式回答」分段流式的首选。
    """
    from openai import OpenAI

    settings = get_settings()
    if not settings.llm_configured:
        raise ValueError("未配置 DASHSCOPE_API_KEY，无法创建 openai 客户端")
    return OpenAI(
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        timeout=60,
    )


@lru_cache(maxsize=1)
def get_async_openai_client():
    """获取**原生** openai SDK 异步客户端（FastAPI SSE 路由里应该用这个）。"""
    from openai import AsyncOpenAI

    settings = get_settings()
    if not settings.llm_configured:
        raise ValueError("未配置 DASHSCOPE_API_KEY，无法创建 AsyncOpenAI 客户端")
    return AsyncOpenAI(
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        timeout=60,
    )


def enable_reasoning_passthrough() -> bool:
    """打补丁让 LangChain 保留 `reasoning_content`（幂等、失败不影响主流程）。

    做法：替换 `langchain_openai.chat_models.base` 模块里的两个模块级转换函数，
    把 `reasoning_content` 塞进 `additional_kwargs`。之后 `extract_reasoning()`
    对 `AIMessage` / `AIMessageChunk` 都能取到思维链。

    Returns:
        bool: 补丁是否处于已生效状态。失败时返回 False 并打 warning，
        业务可退化为「只用原生 openai SDK」的方案，不影响服务启动。
    """
    global _REASONING_PASSTHROUGH_APPLIED
    if _REASONING_PASSTHROUGH_APPLIED:
        return True

    try:
        import langchain_openai.chat_models.base as base

        orig_delta = getattr(base, "_convert_delta_to_message_chunk", None)
        orig_dict = getattr(base, "_convert_dict_to_message", None)
        if orig_delta is None or orig_dict is None:
            logger.warning(
                "reasoning 透传补丁跳过：langchain_openai 内部函数名变化（流式={} 非流式={}）",
                orig_delta is not None,
                orig_dict is not None,
            )
            return False

        def _patched_delta(_dict, default_class):
            msg = orig_delta(_dict, default_class)
            _attach_reasoning(msg, _dict)
            return msg

        def _patched_dict(_dict):
            msg = orig_dict(_dict)
            _attach_reasoning(msg, _dict)
            return msg

        base._convert_delta_to_message_chunk = _patched_delta
        base._convert_dict_to_message = _patched_dict
        _REASONING_PASSTHROUGH_APPLIED = True
        logger.info(
            "已启用 reasoning_content 透传补丁：LangChain 的 additional_kwargs 将包含思维链"
        )
        return True
    except Exception as exc:  # noqa: BLE001 - 补丁失败不能影响服务启动
        logger.warning("reasoning 透传补丁安装失败: {!r}（可改用原生 openai SDK 方案）", exc)
        return False


def _attach_reasoning(msg: Any, source: Any) -> None:
    """把源 dict 里的 reasoning_content 附加到消息的 additional_kwargs。"""
    try:
        if not isinstance(source, Mapping):
            return
        reasoning = source.get("reasoning_content")
        if reasoning and hasattr(msg, "additional_kwargs"):
            msg.additional_kwargs["reasoning_content"] = reasoning
    except Exception:  # pragma: no cover - 防御性
        pass


def extract_reasoning(chunk: Any) -> str:
    """从 LLM 返回块 / 消息中提取思维链 `reasoning_content`。

    兼容 `AIMessage` / `AIMessageChunk` / 原生 SDK delta / dict 四种形态：
    1. `additional_kwargs["reasoning_content"]`
       （需先调用 `enable_reasoning_passthrough()`，否则 LangChain 已将其丢弃）；
    2. `response_metadata["reasoning_content"]`；
    3. 对象属性 `chunk.reasoning_content`（原生 openai SDK 的 delta/message）；
    4. dict 形式的 `reasoning_content` 键。

    Args:
        chunk: LangChain 消息对象、原生 SDK 对象或 dict。

    Returns:
        思维链文本；不存在时返回空字符串。
    """
    if chunk is None:
        return ""

    additional = getattr(chunk, "additional_kwargs", None)
    if isinstance(additional, dict):
        value = additional.get("reasoning_content")
        if value:
            return str(value)

    metadata = getattr(chunk, "response_metadata", None)
    if isinstance(metadata, dict):
        value = metadata.get("reasoning_content")
        if value:
            return str(value)

    # 原生 openai SDK：delta.reasoning_content / message.reasoning_content
    value = getattr(chunk, "reasoning_content", None)
    if value:
        return str(value)

    if isinstance(chunk, dict):
        value = chunk.get("reasoning_content")
        if value:
            return str(value)
        additional = chunk.get("additional_kwargs")
        if isinstance(additional, dict) and additional.get("reasoning_content"):
            return str(additional["reasoning_content"])

    return ""


def extract_content(chunk: Any) -> str:
    """从消息块中提取正式回答文本（content 可能是 list 形态）。"""
    if chunk is None:
        return ""
    content = getattr(chunk, "content", None)
    if content is None and isinstance(chunk, dict):
        content = chunk.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and item.get("text"):
                parts.append(str(item["text"]))
        return "".join(parts)
    return "" if content is None else str(content)


def extract_reasoning_tokens(message: Any) -> int | None:
    """从 usage 元数据里取 `reasoning_tokens`（思考被丢弃时唯一的残留痕迹）。"""
    try:
        metadata = getattr(message, "response_metadata", None) or {}
        usage = metadata.get("token_usage") or {}
        details = usage.get("completion_tokens_details") or {}
        value = details.get("reasoning_tokens")
        return int(value) if value is not None else None
    except Exception:  # pragma: no cover - 防御性
        return None


def check_llm() -> dict[str, Any]:
    """连通性自检：发一句「只回复两个字：在线」，检查 content / reasoning_content。

    Returns:
        dict: 含 `ok`、`content`、`reasoning_content`、
        `has_content`、`has_reasoning`、`reasoning_length` 等字段。
    """
    settings = get_settings()
    if not settings.llm_configured:
        return {
            "ok": False,
            "configured": False,
            "model": settings.dashscope_model,
            "error": "未配置 DASHSCOPE_API_KEY",
        }

    prompt = "只回复两个字：在线"
    try:
        llm = get_llm(streaming=False, temperature=0.3)
        resp = llm.invoke(prompt)
        content = extract_content(resp)
        reasoning = extract_reasoning(resp)
        logger.info(
            "LLM 自检成功: content={!r} reasoning_length={}",
            content,
            len(reasoning),
        )
        return {
            "ok": True,
            "configured": True,
            "model": settings.dashscope_model,
            "content": content,
            "reasoning_content": reasoning,
            "has_content": bool(content),
            "has_reasoning_content": bool(reasoning),
            "reasoning_length": len(reasoning),
            "reasoning_tokens": extract_reasoning_tokens(resp),
            "reasoning_passthrough": _REASONING_PASSTHROUGH_APPLIED,
        }
    except Exception as exc:  # noqa: BLE001 - 自检接口需要把失败原因原样返回
        logger.error("LLM 自检失败: {!r}", exc)
        return {
            "ok": False,
            "configured": True,
            "model": settings.dashscope_model,
            "error": f"{type(exc).__name__}: {exc}",
        }


__all__ = [
    "get_llm",
    "get_openai_client",
    "get_async_openai_client",
    "enable_reasoning_passthrough",
    "extract_reasoning",
    "extract_content",
    "extract_reasoning_tokens",
    "check_llm",
]
