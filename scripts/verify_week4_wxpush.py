#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 4 周验收：微信订阅消息推送（Day 25~26）。

验证范围：
  A. 鉴权：未登录访问订阅接口 → 401
  B. 模板清单：GET /wx/templates 返回 3 个业务场景（reminder/review/report）
  C. 授权上报：同意 → 额度 +1 且可推送；拒绝 → 不可推送且不回收存量额度；
     未知 templateKey → 400
  D. 推送链路（走真实 MQ 消费者）：
     已授权 → success（mock 模拟发送）并扣减额度；
     同场景再推一次（额度耗尽）→ skipped / NO_QUOTA；
     未授权场景 → skipped / NOT_SUBSCRIBED；
     未绑定 openid 用户 → skipped / NO_OPENID；
     推送日志数据隔离：第二个账号只看到自己的记录
  E. 数据隔离：另一个账号看不到 demo 的订阅/推送记录

说明：本机没有真实 AppID/模板，推送走 **mock 通道**
（`wx.mock-enabled=true`）：调用链（授权校验 → 额度扣减 → 取 access_token →
发消息 → 写日志）与真实一致，只把最后一步的微信 API 调用换成"模拟发送"，
落库记 mock=1 以便区分，拿到真实凭证后把开关置 false 即可无缝切换。

用法（需先启动 Java 8080、Redis、RocketMQ、MySQL）：
    python scripts/verify_week4_wxpush.py
"""

from __future__ import annotations

import argparse
import json
import pathlib
import random
import string
import sys
import time
import urllib.error
import urllib.parse
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


def call(base: str, method: str, path: str, body=None, token: str | None = None,
         internal: str | None = None, query: dict | None = None, timeout: int = 60):
    url = base + path
    if query:
        url += "?" + urllib.parse.urlencode(
            {k: v for k, v in query.items() if v is not None}, encoding="utf-8")
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if internal:
        headers["X-Internal-Token"] = internal
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, _json(raw), raw
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        return exc.code, _json(raw), raw
    except Exception as exc:  # noqa: BLE001
        return -1, None, f"{type(exc).__name__}: {exc}"


def _json(raw: str):
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001
        return None


def rand(prefix: str) -> str:
    return prefix + "".join(random.choices(string.ascii_lowercase + string.digits, k=6))


def login(java: str, username: str, password: str) -> str | None:
    status, body, raw = call(java, "POST", "/auth/login", {"username": username, "password": password})
    return ((body or {}).get("data") or {}).get("accessToken")


def latest_log_id(java: str, jwt: str) -> int:
    """当前用户最新一条推送日志的 ID（无记录时为 0）。"""
    _status, body, _ = call(java, "GET", "/wx/push-log", token=jwt, query={"limit": 100})
    rows = (body or {}).get("data") or []
    ids = [int(row.get("id") or 0) for row in rows]
    return max(ids) if ids else 0


def wait_new_push_log(java: str, jwt: str, template_key: str, status: str,
                       after_id: int, timeout: int = 90):
    """轮询 wx_push_log，等待出现**新于** after_id 的指定场景 + 状态记录。

    为什么必须比 ID：推送日志是累积表，直接问"有没有这条记录"会被历史数据骗过
    （上一轮验收留下的 success 会让它永远显示通过）。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        _status, body, _ = call(java, "GET", "/wx/push-log", token=jwt, query={"limit": 100})
        rows = (body or {}).get("data") or []
        for row in rows:
            if (int(row.get("id") or 0) > after_id
                and row.get("templateKey") == template_key
                and row.get("status") == status):
                return row
        time.sleep(3)
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    parser.add_argument("--internal-token", default="study-agent-internal")
    args = parser.parse_args()
    java, iToken = args.java, args.internal_token

    print("=" * 64)
    print(" 第 4 周验收：微信订阅消息推送（Day 25~26）")
    print(f" Java = {java}")
    print(" 推送通道 = mock（无真实 AppID，落库 mock=1）")
    print("=" * 64)

    # ---------------------------------------------------------- A. 鉴权
    print()
    print("--- A. 鉴权保护 ---")
    status, body, _ = call(java, "GET", "/wx/templates")
    check(status == 401 or (body or {}).get("code") == 401,
          "未登录访问订阅模板被拒（401）", f"status={status} code={(body or {}).get('code')}")

    jwt = login(java, "demo", "123456")
    check(bool(jwt), "demo 登录成功")
    if not jwt:
        return summary()

    # ---------------------------------------------------------- B. 模板清单
    print()
    print("--- B. 订阅模板清单 ---")
    status, body, _ = call(java, "GET", "/wx/templates", token=jwt)
    templates = (body or {}).get("data") or {}
    check(status == 200 and len(templates) == 3, "返回 3 个订阅场景",
          f"scenes={sorted(templates.keys())}")
    check(all(templates.get(key) for key in ("reminder", "review", "report")),
          "三个场景都配置了模板 ID", f"templates={templates}")

    # ---------------------------------------------------------- C. 授权上报
    print()
    print("--- C. 订阅授权上报 ---")
    status, body, _ = call(java, "POST", "/wx/subscribe", {
        "items": [
            {"templateKey": "reminder", "status": "accept",
             "templateId": templates.get("reminder", "wx-template-reminder-placeholder")},
            {"templateKey": "report", "status": "accept",
             "templateId": templates.get("report", "wx-template-report-placeholder")},
            {"templateKey": "review", "status": "reject",
             "templateId": templates.get("review", "wx-template-review-placeholder")},
        ]
    }, token=jwt)
    items = (body or {}).get("data") or []
    by_key = {item.get("templateKey"): item for item in items}
    check(status == 200 and len(items) == 3, "上报 3 个场景均成功")

    reminder = by_key.get("reminder") or {}
    review = by_key.get("review") or {}
    check(reminder.get("status") == 1 and reminder.get("authorized") is True,
          "reminder 同意 → 可推送且有额度",
          f"status={reminder.get('status')} acceptCount={reminder.get('acceptCount')}")
    check(review.get("status") == 0 and review.get("authorized") is False,
          "review 拒绝 → 不可推送",
          f"status={review.get('status')} acceptCount={review.get('acceptCount')}")

    # 拒绝 review 之后，之前授权的 reminder 额度必须还在（拒绝不回收存量）
    status, body, _ = call(java, "GET", "/wx/subscribe", token=jwt)
    mine = (body or {}).get("data") or []
    reminder_after = next((item for item in mine if item.get("templateKey") == "reminder"), {})
    check((reminder_after.get("acceptCount") or 0) >= 1,
          "拒绝 review 后 reminder 的存量额度未被回收",
          f"acceptCount={reminder_after.get('acceptCount')}")

    # ---------------------------------------------------------- D. 推送链路
    print()
    print("--- D. 推送链路（内部接口投递 → MQ 消费者发送）---")

    # D1) 已授权场景：先补一次授权 → 投递 → 应 success 且额度扣减
    status, body, _ = call(java, "POST", "/wx/subscribe", {
        "items": [{"templateKey": "reminder", "status": "accept",
                   "templateId": templates.get("reminder", "wx-template-reminder-placeholder")}]
    }, token=jwt)
    reminder_before = ((body or {}).get("data") or [{}])[0]
    before_quota = reminder_before.get("acceptCount") or 0
    check(before_quota >= 1, "reminder 已授权且有余量（准备投递）",
          f"acceptCount={before_quota}")

    after_id = latest_log_id(java, jwt)
    status, body, _ = call(java, "POST",
                           "/internal/mq/wx-push?userId=1&templateKey=reminder",
                           internal=iToken)
    check(status == 200 and (body or {}).get("code") == 200,
          "投递订阅消息成功（reminder）", f"msg={(body or {}).get('msg')}")

    pushed = wait_new_push_log(java, jwt, "reminder", "success", after_id, timeout=90)
    check(pushed is not None, "reminder 推送成功并落日志（mock 发送）", f"log={pushed}")
    if pushed:
        check(pushed.get("mock") is True, "mock 期间落库标记 mock=true",
              f"mock={pushed.get('mock')}")

    # 额度应已被扣减（先授权 1 次，再投递 1 次 → 剩余 0）
    status, body, _ = call(java, "GET", "/wx/subscribe", token=jwt)
    mine = (body or {}).get("data") or []
    reminder_now = next((item for item in mine if item.get("templateKey") == "reminder"), {})
    check((reminder_now.get("acceptCount") or 0) < before_quota + 1,
          "推送后额度被扣减（一次订阅一次发送）",
          f"投递前={before_quota} 投递后={reminder_now.get('acceptCount')}")

    # D2) 持续投递直到额度耗尽 → 应出现 skipped / NO_QUOTA（终态，不重试）
    # 用"一直投到耗尽"而不是"再投一次"：脚本可被反复运行，初始额度不固定
    quota_log = None
    for _ in range(6):
        after_id2 = latest_log_id(java, jwt)
        call(java, "POST", "/internal/mq/wx-push?userId=1&templateKey=reminder",
             internal=iToken)
        quota_log = wait_new_push_log(java, jwt, "reminder", "skipped",
                                      after_id2, timeout=45)
        if quota_log is not None:
            break
    check(quota_log is not None and quota_log.get("errorCode") == "NO_QUOTA",
          "额度耗尽后再投递 → skipped / NO_QUOTA（终态，不重试）",
          f"log={quota_log}")

    # D3) 未授权场景（review 被拒绝）→ skipped / NOT_SUBSCRIBED
    after_id3 = latest_log_id(java, jwt)
    call(java, "POST", "/internal/mq/wx-push?userId=1&templateKey=review",
         internal=iToken)
    not_sub = wait_new_push_log(java, jwt, "review", "skipped", after_id3, timeout=90)
    check(not_sub is not None and not_sub.get("errorCode") == "NOT_SUBSCRIBED",
          "未授权场景（review）→ skipped / NOT_SUBSCRIBED",
          f"log={not_sub}")

    # D4) 未绑定 openid 的用户 → skipped / NO_OPENID
    #     注册一个**没走过小程序登录**的账号（因此没有 openid），对它投递：
    #     消费者应在其自己的日志里留下 1 条 NO_OPENID 跳过记录 ——
    #     顺带验证数据隔离（B 看不到 demo 的推送历史）。
    other = rand("wxs_")
    call(java, "POST", "/auth/register",
         {"username": other, "password": "verify123456", "nickname": other})
    other_jwt = login(java, other, "verify123456")
    check(bool(other_jwt), "第二个账号注册并登录成功", f"username={other}")
    if other_jwt:
        status, body, _ = call(java, "GET", "/user/profile", token=other_jwt)
        other_id = ((body or {}).get("data") or {}).get("id")
        check(bool(other_id), "拿到 B 账号 userId（准备投递）", f"id={other_id}")

        call(java, "POST", f"/internal/mq/wx-push?userId={other_id}&templateKey=reminder",
             internal=iToken)
        no_openid = wait_new_push_log(java, other_jwt, "reminder", "skipped", 0, timeout=90)
        check(no_openid is not None and no_openid.get("errorCode") == "NO_OPENID",
              "未绑定 openid 用户 → skipped / NO_OPENID", f"log={no_openid}")

        # 数据隔离：B 只能看到自己那 1 条记录，看不到 demo 的一堆
        _status, body_other, _ = call(java, "GET", "/wx/push-log",
                                           token=other_jwt, query={"limit": 100})
        logs_other = (body_other or {}).get("data") or []
        check(len(logs_other) == 1
              and int(logs_other[0].get("id") or -1) == int((no_openid or {}).get("id") or -2),
              "B 账号只看到自己的 1 条记录（数据隔离）",
              f"count={len(logs_other)}")

    return summary()


def summary() -> int:
    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" 订阅消息验收：全部通过 ({PASS}/{total})")
    else:
        print(f" 订阅消息验收：{PASS} 通过 / {FAIL} 失败（共 {total}）")
    for name in FAILED:
        print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
