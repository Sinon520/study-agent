"""探针 3：验证「模块级猴补丁」能否让 LangChain 保留 reasoning_content。

背景：langchain-openai 1.7.0 里
  - 流式：base.py:1651 调用**模块级函数** _convert_delta_to_message_chunk
  - 非流式：base.py:2109 调用**模块级函数** _convert_dict_to_message
两处都只映射 OpenAI 标准字段，reasoning_content 被静默丢弃。
因为调用点解析的是模块全局名，所以替换模块属性即可同时拦截两条路径。
"""
from __future__ import annotations

import sys
from collections.abc import Mapping

sys.path.insert(0, r"D:\code\wkbdy\study-agent\server-ai")
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from app.core.config import apply_runtime_env, get_settings  # noqa: E402

apply_runtime_env()
cfg = get_settings()
MODEL = cfg.dashscope_model
PROMPT = "只回复两个字：在线"
SEP = "=" * 78

import langchain_openai.chat_models.base as base  # noqa: E402

print(SEP)
print("安装猴补丁 ...")

_orig_delta = base._convert_delta_to_message_chunk


def _patched_delta(_dict, default_class):
    msg = _orig_delta(_dict, default_class)
    try:
        rc = _dict.get("reasoning_content") if isinstance(_dict, Mapping) else None
    except Exception:
        rc = None
    if rc and hasattr(msg, "additional_kwargs"):
        msg.additional_kwargs["reasoning_content"] = rc
    return msg


_orig_dict = base._convert_dict_to_message


def _patched_dict(_dict):
    msg = _orig_dict(_dict)
    try:
        rc = _dict.get("reasoning_content") if isinstance(_dict, Mapping) else None
    except Exception:
        rc = None
    if rc and hasattr(msg, "additional_kwargs"):
        msg.additional_kwargs["reasoning_content"] = rc
    return msg


base._convert_delta_to_message_chunk = _patched_delta
base._convert_dict_to_message = _patched_dict
print("补丁已安装")

from langchain_openai import ChatOpenAI  # noqa: E402

from app.core.llm import extract_content, extract_reasoning  # noqa: E402

# ---------------- 非流式 ----------------
print("-" * 78)
print("【打补丁后 · 非流式】")
llm = ChatOpenAI(
    model=MODEL, api_key=cfg.dashscope_api_key, base_url=cfg.dashscope_base_url, temperature=0.3
)
resp = llm.invoke(PROMPT)
reason = extract_reasoning(resp)
print(f"  content             = {extract_content(resp)!r}")
print(f"  extract_reasoning() 长度 = {len(reason)}")
print(f"  reasoning 前 80 字   = {reason[:80]!r}")
print(f"  additional_kwargs 键 = {sorted(resp.additional_kwargs.keys())}")

# ---------------- 流式 ----------------
print("-" * 78)
print("【打补丁后 · 流式】前 5 个 chunk")
llm_s = ChatOpenAI(
    model=MODEL,
    api_key=cfg.dashscope_api_key,
    base_url=cfg.dashscope_base_url,
    temperature=0.3,
    streaming=True,
)
reason_parts: list[str] = []
content_parts: list[str] = []
first_content_idx = first_reason_idx = None
for i, ch in enumerate(llm_s.stream(PROMPT)):
    rc = extract_reasoning(ch)
    if rc and first_reason_idx is None:
        first_reason_idx = i
    if ch.content and first_content_idx is None:
        first_content_idx = i
    if rc:
        reason_parts.append(rc)
    if ch.content:
        content_parts.append(str(ch.content))
    if i < 5:
        print(f"  chunk[{i}] content={ch.content!r}")
        print(f"           additional_kwargs={ch.additional_kwargs}")

print("-" * 78)
print(f"  首个 reasoning chunk = {first_reason_idx}")
print(f"  首个 content chunk   = {first_content_idx}")
print(f"  reasoning 累计长度   = {len(''.join(reason_parts))}")
print(f"  content 累计         = {''.join(content_parts)!r}")
print(SEP)
print("探针 3 结束")
