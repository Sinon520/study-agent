"""SSE 探针客户端：验证 /debug/stream 逐块下发、且 reasoning/content 分离。

用时间戳证明「不是攒完一次性吐出」——纯 ASGI traceId 中间件不缓冲 SSE。
"""
from __future__ import annotations

import json
import sys
import time

import httpx

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

URL = "http://127.0.0.1:8000/debug/stream"
SEP = "=" * 78

print(SEP)
print(f"SSE 探针: {URL}")
print(SEP)

t0 = time.perf_counter()
events: list[tuple[float, str, str]] = []
current_event = "message"
resp_headers: dict[str, str] = {}

with httpx.stream("GET", URL, params={"prompt": "只回复两个字：在线"}, timeout=120) as r:
    print(f"HTTP 状态        = {r.status_code}")
    print(f"content-type     = {r.headers.get('content-type')}")
    print(f"x-trace-id       = {r.headers.get('x-trace-id')}")
    print(f"cache-control    = {r.headers.get('cache-control')}")
    resp_headers = dict(r.headers)
    for line in r.iter_lines():
        if line.startswith("event:"):
            current_event = line.split(":", 1)[1].strip()
        elif line.startswith("data:"):
            data = line.split(":", 1)[1].strip()
            events.append((time.perf_counter() - t0, current_event, data))

total = time.perf_counter() - t0
reasoning_events = [e for e in events if e[1] == "reasoning"]
content_events = [e for e in events if e[1] == "content"]

print("-" * 78)
print("前 8 个事件（相对时间 / 事件名 / 数据）：")
for ts, name, data in events[:8]:
    print(f"  +{ts:6.3f}s  {name:<10} {data[:70]}")

print("-" * 78)
print("最后 3 个事件：")
for ts, name, data in events[-3:]:
    print(f"  +{ts:6.3f}s  {name:<10} {data[:70]}")

print("-" * 78)
print(f"事件总数            = {len(events)}")
print(f"reasoning 事件数    = {len(reasoning_events)}")
print(f"content 事件数      = {len(content_events)}")
if reasoning_events:
    print(f"首个 reasoning 到达 = +{reasoning_events[0][0]:.3f}s")
if content_events:
    print(f"首个 content 到达   = +{content_events[0][0]:.3f}s")
print(f"整条流总时长        = {total:.3f}s")

reasoning_text = "".join(json.loads(e[2])["text"] for e in reasoning_events if e[2] != "{}")
content_text = "".join(json.loads(e[2])["text"] for e in content_events if e[2] != "{}")
print(f"reasoning 累计      = {len(reasoning_text)} 字")
print(f"content 累计        = {content_text!r}")

# 判定是否真流式：reasoning 与 content 到达时间应明显分散
spread = (events[-1][0] - events[0][0]) if events else 0
print(f"首末事件时间跨度    = {spread:.3f}s  -> {'真流式（逐块下发）' if spread > 0.3 else '疑似一次性缓冲'}")
print(SEP)
print("SSE 探针结束")
