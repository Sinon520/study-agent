#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 4 周冒烟：RocketMQ 收发链路（Day 22）。

验证范围：
  A. 内部接口保护（/internal/** 无令牌 403、错令牌 403）
  B. MQ 环回自检：发消息 → 本进程消费者回执 → 载荷回显一致（producer→consumer 全链路）
  C. 连续多次自检稳定（消息不丢、不漏、不串）

用法（需先启动 Java 8080 与 RocketMQ NameServer+Broker）：
    python scripts/smoke_week4_mq.py
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import urllib.error
import urllib.request

# Windows 控制台默认 GBK，统一 UTF-8 + replace（详见 scripts/_console.py）
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from _console import enable_utf8_output  # noqa: E402

enable_utf8_output()

PASS = 0
FAIL = 0
FAILED: list[str] = []


def check(cond: bool, desc: str, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {desc}")
    else:
        FAIL += 1
        FAILED.append(desc)
        print(f"  [FAIL] {desc}")
    if detail:
        print(f"         {detail}")


def call(base: str, method: str, path: str, token: str | None = None,
         internal: str | None = None, timeout: int = 60):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if internal:
        headers["X-Internal-Token"] = internal
    req = urllib.request.Request(base + path, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, _json(raw), raw
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        return exc.code, _json(raw), raw
    except Exception as exc:  # noqa: BLE001
        return -1, None, f"{type(exc).__name__}: {exc}"


def _json(raw: str):
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    parser.add_argument("--internal-token", default="study-agent-internal")
    args = parser.parse_args()
    java, token = args.java, args.internal_token

    print("=" * 64)
    print(" 第 4 周冒烟：RocketMQ 收发链路（Day 22）")
    print(f" Java = {java}")
    print(f" NameServer = 127.0.0.1:9876   Broker = 127.0.0.1:10911")
    print("=" * 64)

    # ---------------------------------------------------------- 接口保护
    print()
    print("--- A. /internal/** 接口保护 ---")
    status, body, _ = call(java, "POST", "/internal/mq/selfcheck")
    check(status == 403, "无令牌访问自检接口被拒（403）", f"status={status}")

    status, body, _ = call(java, "POST", "/internal/mq/selfcheck", internal="wrong-token")
    check(status == 403, "错误令牌被拒（403）", f"status={status}")

    # ---------------------------------------------------------- 环回自检
    print()
    print("--- B. MQ 环回自检（producer → Broker → consumer）---")
    status, body, raw = call(java, "POST", "/internal/mq/selfcheck?timeoutMs=20000",
                             internal=token, timeout=60)
    data = (body or {}).get("data") or {}
    check(status == 200 and (body or {}).get("code") == 200, "自检接口可调用",
          f"msg={(body or {}).get('msg')}")
    check(bool(data.get("msgId")), "消息发送成功（拿到 msgId）",
          f"msgId={data.get('msgId')} sendStatus={data.get('sendStatus')} queueId={data.get('queueId')}")
    check(data.get("received") is True, "消费者收到消息（环回成功）",
          f"roundTripMs={data.get('roundTripMs')}")
    check(data.get("echoOk") is True, "载荷回显一致（JSON 序列化没有损坏）",
          f"echo={data.get('echo')}")

    # ---------------------------------------------------------- 连续稳定性
    print()
    print("--- C. 连续 3 次自检稳定性 ---")
    ok_count = 0
    for i in range(3):
        status, body, raw = call(java, "POST", "/internal/mq/selfcheck?timeoutMs=20000",
                                 internal=token, timeout=60)
        d = (body or {}).get("data") or {}
        if d.get("received") and d.get("echoOk"):
            ok_count += 1
    check(ok_count == 3, "3 次连续自检全部环回成功（消息不丢不串）",
          f"成功 {ok_count}/3")

    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" MQ 冒烟：全部通过 ({PASS}/{total})")
    else:
        print(f" MQ 冒烟：{PASS} 通过 / {FAIL} 失败（共 {total}）")
        for name in FAILED:
            print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
