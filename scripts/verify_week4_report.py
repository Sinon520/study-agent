#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 4 周验收：复盘报告生成链路（Day 24）。

验证范围：
  A. 鉴权：未登录访问报告接口 → 401
  B. 端到端：手动触发（默认周期 本周一~今天）→ MQ report-generate → 统计聚合
     → Python /agent/report → 落 review_report → 轮询到报告出现
     - 详情字段完整：markdown 正文 / 数据要点 / 行动建议 / 统计 JSON
     - 统计口径与进度接口一致（同一周期 /progress/dashboard 对比数字）
  C. 站内通知：type=报告 的通知已写入且 bizId 指向报告 ID
  D. 幂等：同周期再次触发 → 报告仍只有一条（ID 不变、内容被重新生成）
  E. 参数校验：周期倒挂 / 跨度超限 / 未来日期 / 只传一半 → 400
  F. 横向越权：另一个账号读 demo 的报告详情 → 403

为什么周期用"本周一~今天"：这是前端「立即生成」与定时任务共用的默认口径，
测的就是用户实际会走的那条路径。

用法（需先启动 Java 8080、Python 8000、RocketMQ、MySQL、Redis）：
    python scripts/verify_week4_report.py
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
from datetime import date, timedelta

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
         query: dict | None = None, timeout: int = 60):
    url = base + path
    if query:
        url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
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


def wait_report(base: str, jwt: str, start: str, end: str,
                expect_time_diff: str | None = None, timeout: int = 180):
    """轮询报告列表，等待目标周期的报告出现（或 createTime 变化 = 重新生成完成）。

    :param expect_time_diff: 传入旧的 createTime 时，等待该周期报告的 createTime 与原值不同。
    :return: (record or None, elapsed_seconds)
    """
    deadline = time.time() + timeout
    started = time.time()
    while time.time() < deadline:
        status, body, _ = call(base, "GET", "/report/page",
                               token=jwt, query={"pageNum": 1, "pageSize": 50})
        records = ((body or {}).get("data") or {}).get("records") or []
        for record in records:
            if record.get("periodStart") == start and record.get("periodEnd") == end:
                if expect_time_diff is None or record.get("createTime") != expect_time_diff:
                    return record, time.time() - started
        time.sleep(5)
    return None, time.time() - started


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    java = args.java

    today = date.today()
    monday = today - timedelta(days=today.weekday())
    start_s, end_s = monday.isoformat(), today.isoformat()

    print("=" * 64)
    print(" 第 4 周验收：复盘报告生成链路（Day 24）")
    print(f" Java = {java}")
    print(f" 统计周期 = {start_s} ~ {end_s}（本周一 ~ 今天，与定时任务同口径）")
    print("=" * 64)

    # ---------------------------------------------------------- A. 鉴权
    print()
    print("--- A. 鉴权保护 ---")
    status, body, _ = call(java, "GET", "/report/page")
    code = (body or {}).get("code")
    check(status == 401 or code == 401, "未登录访问报告列表被拒（401）",
          f"status={status} code={code}")

    # ---------------------------------------------------------- 登录
    status, body, _ = call(java, "POST", "/auth/login", {"username": "demo", "password": "123456"})
    jwt = ((body or {}).get("data") or {}).get("accessToken")
    check(bool(jwt), "demo 登录成功")
    if not jwt:
        return summary()

    # ---------------------------------------------------------- B. 端到端生成
    print()
    print("--- B. 手动触发 → MQ → 生成 → 落库（默认周期）---")

    # 先看该周期是否已有报告（重复跑脚本时旧报告会立刻命中轮询，
    # 必须记录旧的 createTime，等"重新生成"完成才算真跑通）
    status, body, _ = call(java, "GET", "/report/page", token=jwt,
                           query={"pageNum": 1, "pageSize": 50})
    records = ((body or {}).get("data") or {}).get("records") or []
    existing = next((r for r in records
                     if r.get("periodStart") == start_s and r.get("periodEnd") == end_s), None)
    old_time = existing.get("createTime") if existing else None

    status, body, _ = call(java, "POST", "/report/generate", {}, token=jwt)
    data = (body or {}).get("data") or {}
    check((body or {}).get("code") == 200, "触发接口返回成功（任务已投递 MQ）",
          f"msg={(body or {}).get('msg')} period={data.get('periodStart')}~{data.get('periodEnd')}")
    check(data.get("periodStart") == start_s and data.get("periodEnd") == end_s,
          "不传参数时默认周期 = 本周一 ~ 今天", f"期望 {start_s}~{end_s}，返回 {data.get('periodStart')}~{data.get('periodEnd')}")

    record, elapsed = wait_report(java, jwt, start_s, end_s, expect_time_diff=old_time)
    check(record is not None, "轮询到复盘报告（生成全链路跑通）",
          f"耗时 {elapsed:.0f}s reportId={(record or {}).get('id')} "
          f"旧createTime={old_time} 新createTime={(record or {}).get('createTime')}")
    if not record:
        return summary()

    report_id = record["id"]
    check("content" not in record or record.get("content") in (None, ""),
          "列表接口不返回正文大字段（轻量列表）")

    status, body, _ = call(java, "GET", f"/report/{report_id}", token=jwt)
    detail = (body or {}).get("data") or {}
    content = detail.get("content") or ""
    check((body or {}).get("code") == 200 and len(content) >= 100,
          "详情：markdown 正文非空", f"正文 {len(content)} 字")
    check("##" in content or "**" in content, "正文含 markdown 结构标记",
          f"片段：{content[:60]!r}…")
    check(len(detail.get("highlights") or []) >= 3, "数据要点 ≥ 3 条",
          f"highlights={detail.get('highlights')}")
    check(len(detail.get("suggestions") or []) >= 3, "行动建议 ≥ 3 条",
          f"suggestions={len(detail.get('suggestions') or [])} 条")
    check(detail.get("reportType") == "周报", "报告类型 = 周报")
    check(detail.get("periodStart") == start_s and detail.get("periodEnd") == end_s,
          "详情周期与触发周期一致")

    # 统计口径一致性：与进度接口（同一周期）对比
    stats = detail.get("stats") or {}
    status, body, _ = call(java, "GET", "/progress/dashboard", token=jwt,
                           query={"startDate": start_s, "endDate": end_s})
    overview = ((body or {}).get("data") or {}).get("overview") or {}
    check(stats.get("totalMinutes") == overview.get("totalMinutes"),
          "报告统计与进度页一致：总学习时长",
          f"报告={stats.get('totalMinutes')} 进度页={overview.get('totalMinutes')}")
    check(stats.get("completionRate") == overview.get("completionRate"),
          "报告统计与进度页一致：任务完成率",
          f"报告={stats.get('completionRate')} 进度页={overview.get('completionRate')}")
    check(stats.get("taskDone") == overview.get("taskDone") and stats.get("taskTotal") == overview.get("taskTotal"),
          "报告统计与进度页一致：任务完成数")
    check(isinstance(stats.get("wrongNew"), (int, float)) and
          isinstance(stats.get("subjectDistribution"), list),
          "统计 JSON 含错题新增与科目分布", f"stats keys={sorted(stats.keys())}")

    # ---------------------------------------------------------- C. 站内通知
    print()
    print("--- C. 站内通知联动 ---")
    status, body, _ = call(java, "GET", "/notice/page", token=jwt,
                           query={"pageNum": 1, "pageSize": 30, "type": "报告"})
    notices = ((body or {}).get("data") or {}).get("records") or []
    hit = next((n for n in notices if n.get("bizId") == report_id), None)
    check(hit is not None, "报告通知已写入 notice_message（type=报告，bizId=报告ID）",
          f"title={(hit or {}).get('title')}")
    check("复盘" in ((hit or {}).get("title") or ""), "通知标题可读",
          f"content={(hit or {}).get('content')}")

    # ---------------------------------------------------------- D. 幂等
    print()
    print("--- D. 同周期重复触发幂等 ---")
    old_create_time = record.get("createTime")
    status, body, _ = call(java, "POST", "/report/generate",
                           {"periodStart": start_s, "periodEnd": end_s}, token=jwt)
    check((body or {}).get("code") == 200, "同周期二次触发成功")
    record2, elapsed2 = wait_report(java, jwt, start_s, end_s,
                                    expect_time_diff=old_create_time)
    check(record2 is not None, "同周期报告被重新生成（createTime 已更新）",
          f"耗时 {elapsed2:.0f}s 旧={old_create_time} 新={(record2 or {}).get('createTime')}")
    check((record2 or {}).get("id") == report_id,
          "报告 ID 不变（唯一键 upsert，没有产生重复行）",
          f"id={report_id}")

    # 通知不重复：同一报告只在首次生成时写入一条
    status, body, _ = call(java, "GET", "/notice/page", token=jwt,
                           query={"pageNum": 1, "pageSize": 50, "type": "报告"})
    notices = ((body or {}).get("data") or {}).get("records") or []
    same_biz = [n for n in notices if n.get("bizId") == report_id]
    check(len(same_biz) == 1, "同一报告的通知只有一条（重投不重复通知）",
          f"count={len(same_biz)}")

    # ---------------------------------------------------------- E. 参数校验
    print()
    print("--- E. 参数校验（同步拒绝，不投递 MQ）---")
    cases = [
        ({"periodStart": (today - timedelta(days=1)).isoformat(), "periodEnd": (today - timedelta(days=5)).isoformat()},
         "周期倒挂"),
        ({"periodStart": (today - timedelta(days=40)).isoformat(), "periodEnd": today.isoformat()},
         "跨度超限（>31 天）"),
        ({"periodStart": today.isoformat(), "periodEnd": (today + timedelta(days=1)).isoformat()},
         "结束日期在未来"),
        ({"periodStart": (today - timedelta(days=3)).isoformat()},
         "只传 periodStart"),
    ]
    for payload, name in cases:
        status, body, _ = call(java, "POST", "/report/generate", payload, token=jwt)
        check((body or {}).get("code") == 400, f"{name} → 400",
              f"msg={(body or {}).get('msg')}")

    # ---------------------------------------------------------- F. 横向越权
    print()
    print("--- F. 横向越权防护 ---")
    other_user = rand("report_u_")
    call(java, "POST", "/auth/register",
         {"username": other_user, "password": "verify123456", "nickname": other_user})
    status, body, _ = call(java, "POST", "/auth/login",
                           {"username": other_user, "password": "verify123456"})
    other_jwt = ((body or {}).get("data") or {}).get("accessToken")
    check(bool(other_jwt), "第二个账号注册并登录成功", f"username={other_user}")
    if other_jwt:
        status, body, _ = call(java, "GET", f"/report/{report_id}", token=other_jwt)
        check((body or {}).get("code") == 403, "B 账号读 demo 的报告详情 → 403",
              f"msg={(body or {}).get('msg')}")
        status, body, _ = call(java, "GET", "/report/page", token=other_jwt)
        records = ((body or {}).get("data") or {}).get("records") or []
        check(len(records) == 0, "B 账号的报告列表为空（数据隔离）", f"count={len(records)}")

    return summary()


def summary() -> int:
    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" 报告链路验收：全部通过 ({PASS}/{total})")
    else:
        print(f" 报告链路验收：{PASS} 通过 / {FAIL} 失败（共 {total}）")
        for name in FAILED:
            print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
