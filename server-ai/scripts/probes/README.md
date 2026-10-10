# scripts/probes —— 结构性探针脚本

这些脚本不是服务运行时依赖，而是**用于摸清第三方库/服务真实行为**的一次性探针。
保留它们的原因：第 3 周要做 SSE 流式输出，而「思考模型的思维链到底走哪条路」这个
结论必须可复现；换机器、升版本后重跑这几个脚本就能立刻验证结论是否仍然成立。

运行前提：在 `server-ai/` 目录下，用 venv 的 python；`.env` 里需有可用的
`DASHSCOPE_API_KEY`。

```powershell
cd D:\code\wkbdy\study-agent\server-ai
.\.venv\Scripts\python.exe scripts\probes\llm_probe.py
```

| 脚本 | 作用 | 结论要点 |
| --- | --- | --- |
| `llm_probe.py` | 对比「原生 openai SDK」与「LangChain ChatOpenAI」在非流式/流式下能否拿到 `reasoning_content`，并试 `extra_body={"enable_thinking": True}` | 原生 SDK 拿得到；**LangChain 全部丢弃**；`enable_thinking` 无影响（思考默认开启） |
| `llm_probe2.py` | 完整流式对比 + TTFT：统计 chunk 数、首个 reasoning / content chunk 下标、累计长度 | 原生：77 chunk，第 1 个出 reasoning、第 74 个出 content；LangChain：`additional_kwargs` 全程为空，仅 usage 里残留 `reasoning_tokens` |
| `llm_probe3.py` | 验证「模块级猴补丁」能否让 LangChain 保留 `reasoning_content` | 可以：补丁后非流式/流式的 `additional_kwargs` 均有值（即 `core/llm.py` 里 `enable_reasoning_passthrough()` 的原型） |
| `sse_probe.py` | 作为客户端消费 `/debug/stream`，用时间戳证明 SSE 是**逐块**下发而非一次性缓冲 | 90 个事件 / 3.70s，首个 reasoning +0.42s、首个 content +3.66s，跨度 3.60s → 纯 ASGI traceId 中间件不缓冲 SSE |

> 注：`sse_probe.py` 需要服务已在 8000 端口运行。
