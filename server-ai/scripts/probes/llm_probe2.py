"""探针 2：量化 LangChain 是否彻底丢弃 reasoning_content，并测量 TTFT。

结论用于第 3 周 SSE 设计选型：
- 若 LangChain 流式完全拿不到 reasoning，就必须用原生 openai SDK 或打补丁。
"""
from __future__ import annotations

import sys
import time

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
CAP = 600

print(SEP)
print("探针 2：完整流式对比 + TTFT")
print(SEP)

# ------------------------------------------------ 原生 SDK 完整流式
from openai import OpenAI  # noqa: E402

client = OpenAI(api_key=cfg.dashscope_api_key, base_url=cfg.dashscope_base_url)

t0 = time.perf_counter()
stream = client.chat.completions.create(
    model=MODEL, messages=[{"role": "user", "content": PROMPT}], stream=True
)
n = 0
first_reason_idx = first_content_idx = None
reason_parts: list[str] = []
content_parts: list[str] = []
for i, chunk in enumerate(stream):
    n += 1
    if n > CAP:
        break
    d = chunk.choices[0].delta if chunk.choices else None
    if d is None:
        continue
    rc = getattr(d, "reasoning_content", None)
    if rc and first_reason_idx is None:
        first_reason_idx = i
    if d.content and first_content_idx is None:
        first_content_idx = i
    if rc:
        reason_parts.append(rc)
    if d.content:
        content_parts.append(d.content)
raw_elapsed = time.perf_counter() - t0

print("【原生 openai SDK · 流式】")
print(f"  chunk 总数            = {n}{' (达到上限)' if n > CAP else ''}")
print(f"  首个 reasoning chunk  = {first_reason_idx}")
print(f"  首个 content chunk    = {first_content_idx}")
print(f"  reasoning 累计长度    = {len(''.join(reason_parts))}")
print(f"  content 累计          = {''.join(content_parts)!r}")
print(f"  总耗时                = {raw_elapsed:.2f}s")
print("-" * 78)

# ------------------------------------------------ LangChain 完整流式
from app.core.llm import extract_content, extract_reasoning  # noqa: E402
from langchain_openai import ChatOpenAI  # noqa: E402

llm = ChatOpenAI(
    model=MODEL,
    api_key=cfg.dashscope_api_key,
    base_url=cfg.dashscope_base_url,
    temperature=0.3,
    streaming=True,
)

t0 = time.perf_counter()
n = 0
first_content_idx = None
additional_seen: dict[str, object] = {}
meta_seen: dict[str, object] = {}
parts: list[str] = []
for i, ch in enumerate(llm.stream(PROMPT)):
    n += 1
    if n > CAP:
        break
    if ch.additional_kwargs:
        for k, v in ch.additional_kwargs.items():
            additional_seen.setdefault(k, v)
    if ch.response_metadata:
        for k, v in ch.response_metadata.items():
            meta_seen.setdefault(k, v)
    if ch.content and first_content_idx is None:
        first_content_idx = i
    if ch.content:
        parts.append(str(ch.content))
lc_elapsed = time.perf_counter() - t0

print("【LangChain ChatOpenAI · 流式】")
print(f"  chunk 总数               = {n}{' (达到上限)' if n > CAP else ''}")
print(f"  首个非空 content chunk   = {first_content_idx}")
print(f"  content 累计             = {''.join(parts)!r}")
print(f"  出现过的 additional_kwargs 键 = {sorted(additional_seen.keys()) or '（全程为空）'}")
print(f"  出现过的 response_metadata 键 = {sorted(meta_seen.keys())}")
print(f"  总耗时                   = {lc_elapsed:.2f}s")
print("-" * 78)

# ------------------------------------------------ LangChain 非流式 AIMessage 全字段
print("【LangChain ChatOpenAI · 非流式】AIMessage 是否任何位置含 reasoning")
llm2 = ChatOpenAI(
    model=MODEL,
    api_key=cfg.dashscope_api_key,
    base_url=cfg.dashscope_base_url,
    temperature=0.3,
)
resp = llm2.invoke(PROMPT)
dumped = resp.model_dump()
print(f"  model_dump() 顶层键      = {sorted(dumped.keys())}")
print(f"  content                  = {extract_content(resp)!r}")
print(f"  additional_kwargs        = {resp.additional_kwargs}")
print(f"  response_metadata        = {resp.response_metadata}")
print(f"  任一字段含 reasoning?    = {'reasoning_content' in str(dumped)}")
print(SEP)
print("探针 2 结束")
