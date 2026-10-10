#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""第 3 周冒烟测试：内部接口安全 / Agent 落库 / 问答链路错误路径。

用法（需先启动 Java 8080 与 Python 8000）：
    python scripts/smoke_week3.py
"""

from __future__ import annotations

import argparse
import pathlib
import datetime as dt
import json
import sys
import urllib.error
import urllib.request

# Windows 控制台默认 GBK：接口返回的原文里常带数学符号（∫₀¹ 的下标 ₀ 等），
# print 会抛 UnicodeEncodeError 并**直接中断整个验收脚本**，表现成"跑到一半突然退出"。
# 这里在任何输出之前把 stdout/stderr 切成 UTF-8 + errors=replace。
# 详见 docs/踩坑记录.md 与 scripts/_console.py。
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


def call(base: str, method: str, path: str, body=None, token=None, internal=None, timeout=120):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if internal:
        headers["X-Internal-Token"] = internal
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
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
    parser.add_argument("--python", default="http://127.0.0.1:8000")
    parser.add_argument("--internal-token", default="study-agent-internal")
    args = parser.parse_args()

    java, py, token = args.java, args.python, args.internal_token

    print("=" * 64)
    print(" 第 3 周冒烟测试")
    print(f" Java   = {java}")
    print(f" Python = {py}")
    print("=" * 64)

    # ---------------------------------------------------------- 内部接口安全
    print()
    print("--- A. 内部接口的令牌与来源保护 ---")
    status, body, _ = call(java, "POST", "/internal/kb/document-names", {"documentIds": [1]})
    check(status == 403, "无令牌访问 /internal/** 被拒（403）", f"status={status} msg={(body or {}).get('msg')}")

    status, body, _ = call(java, "POST", "/internal/kb/document-names", {"documentIds": [1]}, internal="wrong")
    check(status == 403, "错误令牌被拒（403）", f"status={status}")

    status, body, _ = call(java, "POST", "/internal/kb/document-names", {"documentIds": [1]}, internal=token)
    check(status == 200 and (body or {}).get("code") == 200, "正确令牌放行（200）", f"status={status}")

    status, body, _ = call(java, "POST", "/plan/agent-create",
                           {"userId": 1, "title": "x", "examType": "考研",
                            "targetDate": "2026-12-20", "dailyMinutes": 120, "tasks": []})
    check(status == 403, "/plan/agent-create 无令牌被拒（403）", f"status={status}")

    # ---------------------------------------------------------- 业务接口仍需 JWT
    print()
    print("--- B. 业务接口仍需要 JWT ---")
    for path in ["/plan/page", "/kb/page", "/chat/session/page"]:
        status, _, _ = call(java, "GET", path)
        check(status == 401, f"未登录访问 {path} → 401", f"status={status}")

    # ---------------------------------------------------------- Agent 落库
    print()
    print("--- C. 规划 Agent 落库（/plan/agent-create）---")
    status, body, _ = call(java, "POST", "/auth/login", {"username": "demo", "password": "123456"})
    jwt = ((body or {}).get("data") or {}).get("accessToken")
    check(bool(jwt), "demo 账号登录成功")

    today = dt.date.today()
    tasks = [
        {"taskDate": (today + dt.timedelta(days=i)).isoformat(),
         "title": f"Agent 冒烟任务 {i + 1}", "content": "内容", "subject": "英语",
         "estimatedMinutes": 60}
        for i in range(3)
    ]
    status, body, _ = call(java, "POST", "/plan/agent-create", {
        "userId": 1, "title": "Agent 冒烟测试计划", "examType": "考研",
        "targetDate": (today + dt.timedelta(days=20)).isoformat(),
        "dailyMinutes": 120, "source": "Agent生成", "tasks": tasks,
    }, internal=token)
    data = (body or {}).get("data") or {}
    check(status == 200 and data.get("planId"), "Agent 计划落库成功",
          f"planId={data.get('planId')} taskCount={data.get('taskCount')}")
    check(data.get("taskCount") == 3, "落库任务数与请求一致", f"taskCount={data.get('taskCount')}")

    if data.get("planId"):
        status, body, _ = call(java, "GET", f"/plan/{data['planId']}", token=jwt)
        plan = (body or {}).get("data") or {}
        check(plan.get("source") == "Agent生成", "计划 source 标记为「Agent生成」", f"source={plan.get('source')}")
        check(plan.get("taskTotal") == 3, "计划详情统计到 3 条任务", f"taskTotal={plan.get('taskTotal')}")

    # ---------------------------------------------------------- Python 侧
    print()
    print("--- D. Python AI 服务 ---")
    status, body, _ = call(py, "GET", "/health")
    check(status == 200, "AI 服务 /health 200", f"status={status}")
    check((body or {}).get("llm_configured") is True, "DASHSCOPE_API_KEY 已配置")

    status, body, _ = call(py, "GET", "/rag/stats")
    check(status == 200 and (body or {}).get("code") == 0, "Milvus collection 就绪",
          f"collection={(body or {}).get('collection')} vectorCount={(body or {}).get('vectorCount')}")

    status, body, _ = call(py, "POST", "/agent/classify",
                           {"wrongQuestionId": 1, "content": "计算定积分 ∫₀¹ x² dx", "subject": "数学"})
    check(status == 200 and (body or {}).get("code") == 0, "错题归类接口可用（真实调 LLM）",
          f"subject={(body or {}).get('subject')} tags={(body or {}).get('tags')}")

    # ---------------------------------------------------------- 问答链路
    print()
    print("--- E. 问答链路（模型未就绪时的降级）---")
    if jwt:
        status, body, raw = call(java, "POST", "/chat/ask",
                                 {"question": "定积分的几何意义是什么？"}, token=jwt)
        if status == 200:
            check(True, "问答接口返回流（模型已就绪）", raw[:200])
        else:
            msg = (body or {}).get("msg") if isinstance(body, dict) else raw
            check(status == 200 or status == 500, "问答接口未就绪时返回可控错误", f"status={status}")
            check("模型" in str(msg) or "AI 服务" in str(msg),
                  "错误提示指向模型/服务状态而非「服务器内部错误」", f"msg={str(msg)[:140]}")

    # ---------------------------------------------------------- AI 生成计划
    print()
    print("--- F. AI 生成计划（/plan/agent-generate，用户入口）---")
    status, _, _ = call(java, "POST", "/plan/agent-generate",
                        {"examType": "考研", "targetDate": "2026-12-20", "dailyMinutes": 120})
    check(status == 401, "未登录访问 /plan/agent-generate → 401", f"status={status}")

    if jwt:
        # Agent 生成是"真 LLM 干活"：20 天的计划要跑 1~3 次大模型调用，
        # 首次还会触发本地 BGE-M3 加载，实测数分钟级。所以这里超时给足 10 分钟。
        status, body, raw = call(java, "POST", "/plan/agent-generate",
                                 {"examType": "考研", "targetDate": "2026-12-20",
                                  "dailyMinutes": 120, "subjects": "英语,政治"},
                                 token=jwt, timeout=600)
        if status == 200 and (body or {}).get("code") == 200:
            data = (body or {}).get("data") or {}
            check(True, "Agent 生成计划成功并落库",
                  f"planId={data.get('planId')} 任务数={data.get('savedTasks')} "
                  f"天数={data.get('days')} 重试={data.get('retries')}")
            check(data.get("planId") is not None, "返回落库后的 planId")
            # 落库后应能在计划列表里查到，且 source 是 Agent生成
            status, body, _ = call(java, "GET", f"/plan/{data.get('planId')}", token=jwt)
            plan = (body or {}).get("data") or {}
            check(plan.get("source") == "Agent生成", "计划 source 标记为「Agent生成」",
                  f"source={plan.get('source')}")
        elif status == -1:
            check(False, "Agent 生成在 10 分钟内返回（未超时）", f"raw={str(raw)[:160]}")
        else:
            msg = (body or {}).get("msg") if isinstance(body, dict) else raw
            check("模型" in str(msg) or "AI 服务" in str(msg) or "大模型" in str(msg),
                  "生成失败时提示指向模型/服务状态（不是裸的服务器内部错误）",
                  f"status={status} msg={str(msg)[:160]}")

    # ---------------------------------------------------------- 汇总
    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" 冒烟结果：全部通过 ({PASS}/{total})")
    else:
        print(f" 冒烟结果：{PASS} 通过 / {FAIL} 失败（共 {total}）")
        for name in FAILED:
            print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
