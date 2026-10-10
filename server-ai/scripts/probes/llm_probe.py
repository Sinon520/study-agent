"""临时探针：判定 reasoning_content 是「服务端没返回」还是「LangChain 丢弃了」。

输出刻意做得很紧凑，便于直接贴进报告。
"""
from __future__ import annotations

import sys

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

print(SEP)
print(f"model = {MODEL} | base_url = {cfg.dashscope_base_url}")
print(SEP)

# ---------------------------------------------------------------- 原生 SDK
from openai import OpenAI  # noqa: E402

client = OpenAI(api_key=cfg.dashscope_api_key, base_url=cfg.dashscope_base_url)


def raw_nonstream(extra_body):
    tag = f"A) 原生SDK 非流式 extra_body={extra_body}"
    print(tag)
    try:
        r = client.chat.completions.create(
            model=MODEL,
            messages=[{"role": "user", "content": PROMPT}],
            extra_body=extra_body,
        )
        msg = r.choices[0].message
        dumped = msg.model_dump()
        print(f"   message 全部字段 = {sorted(dumped.keys())}")
        print(f"   content            = {msg.content!r}")
        print(f"   reasoning_content  = {getattr(msg, 'reasoning_content', None)!r}")
        print(f"   finish_reason      = {r.choices[0].finish_reason}")
    except Exception as exc:  # noqa: BLE001
        print(f"   [FAIL] {type(exc).__name__}: {exc}")
    print("-" * 78)


def raw_stream(extra_body):
    tag = f"B) 原生SDK 流式 extra_body={extra_body}"
    print(tag)
    try:
        stream = client.chat.completions.create(
            model=MODEL,
            messages=[{"role": "user", "content": PROMPT}],
            stream=True,
            extra_body=extra_body,
        )
        for i, chunk in enumerate(stream):
            if i >= 5:
                break
            delta = chunk.choices[0].delta if chunk.choices else None
            if delta is None:
                print(f"   chunk[{i}] 无 delta")
                continue
            keys = sorted(delta.model_dump(exclude_none=True).keys())
            print(f"   chunk[{i}] delta字段={keys}")
            print(f"            content           = {delta.content!r}")
            print(f"            reasoning_content = {getattr(delta, 'reasoning_content', None)!r}")
    except Exception as exc:  # noqa: BLE001
        print(f"   [FAIL] {type(exc).__name__}: {exc}")
    print("-" * 78)


raw_nonstream(None)
raw_nonstream({"enable_thinking": True})
raw_stream(None)
raw_stream({"enable_thinking": True})

# ---------------------------------------------------------------- LangChain
from langchain_openai import ChatOpenAI  # noqa: E402

from app.core.llm import extract_content, extract_reasoning  # noqa: E402


def lc_nonstream(label, **kw):
    print(f"C) LangChain 非流式 {label}")
    try:
        llm = ChatOpenAI(
            model=MODEL,
            api_key=cfg.dashscope_api_key,
            base_url=cfg.dashscope_base_url,
            temperature=0.3,
            **kw,
        )
        resp = llm.invoke(PROMPT)
        print(f"   content                = {extract_content(resp)!r}")
        print(f"   additional_kwargs      = {resp.additional_kwargs}")
        print(f"   response_metadata keys = {sorted(resp.response_metadata.keys())}")
        print(f"   extract_reasoning()    = {extract_reasoning(resp)!r}")
    except Exception as exc:  # noqa: BLE001
        print(f"   [FAIL] {type(exc).__name__}: {exc}")
    print("-" * 78)


def lc_stream(label, **kw):
    print(f"D) LangChain 流式 {label} —— 前 5 个 chunk")
    try:
        llm = ChatOpenAI(
            model=MODEL,
            api_key=cfg.dashscope_api_key,
            base_url=cfg.dashscope_base_url,
            temperature=0.3,
            streaming=True,
            **kw,
        )
        for i, ch in enumerate(llm.stream(PROMPT)):
            if i >= 5:
                break
            print(f"   chunk[{i}] type={type(ch).__name__} content={ch.content!r}")
            print(f"            additional_kwargs   = {ch.additional_kwargs}")
            print(f"            response_metadata   = {ch.response_metadata}")
            print(f"            extract_reasoning() = {extract_reasoning(ch)!r}")
    except Exception as exc:  # noqa: BLE001
        print(f"   [FAIL] {type(exc).__name__}: {exc}")
    print("-" * 78)


lc_nonstream("(默认)")
lc_nonstream("(extra_body enable_thinking=True)", extra_body={"enable_thinking": True})
lc_stream("(默认)")
lc_stream("(extra_body enable_thinking=True)", extra_body={"enable_thinking": True})

print(SEP)
print("探针结束")
