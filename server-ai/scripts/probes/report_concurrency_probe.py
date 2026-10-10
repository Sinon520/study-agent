"""探针：`/agent/report` 的多路并发是否真并行（FastAPI 线程池 vs 事件循环）。

背景（第 4 周 Day 24 实测）：周报定时任务一次给 6 个活跃用户发消息，
Python 侧日志显示 6 次生成**逐个完成**（间隔恰好等于单次 LLM 耗时 ~45s），
整批串行耗时 ~4.5 分钟。根因：端点写成 `async def` 但内部 `llm.invoke()` 是
阻塞调用 —— 事件循环被占死，所有请求退化为串行，且期间**整个 Python 服务
（含健康检查与问答）都被冻住**。

修复：这些长阻塞端点改为同步 `def`（`/agent/classify` `/agent/report`
`/agent/plan` `/rag/ingest`），FastAPI 自动放入工作线程池并行执行。

预期结果：3 路并发总耗时 ≈ 单个请求耗时（约 45~60s）；
若疑似串行（总耗时 ≈ 3 倍单次）则说明修复被回退。

运行前提：Python 服务已在 8000 端口运行，`.env` 里有可用的
`DASHSCOPE_API_KEY`。本脚本只读 LLM（不落库、不经过 Java），可随时重跑。
"""

from __future__ import annotations

import json
import threading
import time
import urllib.request

results: dict[str, float] = {}
started = time.time()


def fire(idx: int) -> None:
    body = json.dumps({
        "userId": 900 + idx,
        "periodStart": "2026-10-05",
        "periodEnd": "2026-10-10",
        "stats": {"totalMinutes": 120 + idx, "completionRate": 50.0, "total": 10, "done": 5},
    }).encode("utf-8")
    req = urllib.request.Request(
        "http://127.0.0.1:8000/agent/report", data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            results[f"req{idx}"] = time.time() - t0
            print(f"  req{idx}: {time.time()-t0:.1f}s code={payload.get('code')} "
                  f"正文={len(payload.get('content') or '')}字", flush=True)
    except Exception as exc:  # noqa: BLE001
        results[f"req{idx}"] = -1
        print(f"  req{idx}: 失败 {exc}", flush=True)


def main() -> None:
    threads = [threading.Thread(target=fire, args=(i,)) for i in range(3)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    wall = time.time() - started
    single = [v for v in results.values() if v > 0]
    print(f"总墙钟时间: {wall:.1f}s")
    if single:
        print(f"最快 {min(single):.1f}s / 最慢 {max(single):.1f}s / 差值 {max(single)-min(single):.1f}s")
        if wall < max(single) * 1.6:
            print("结论：并行执行 ✓（线程池修复生效）")
        else:
            print("结论：仍疑似串行 ✗")


if __name__ == "__main__":
    main()
