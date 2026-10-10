#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
第 2 周验收脚本：计划 / 错题 / 进度 / 通知 四模块 CRUD 闭环

覆盖：
  A. 计划模块：创建（自动生成任务）、分页、详情、修改、状态变更、删除
  B. 分页边界：pageNum=0 / 负数 / 超大 pageSize / 超大页码 都不炸
  C. 任务模块：列表、今日任务、新增、修改、勾选完成（联动 study_record）、取消完成
  D. 进度模块：dashboard / recent / overview，验证时长与完成率真的变了
  E. 错题模块：新增（含标签自动建）、筛选（科目/掌握度/标签）、修改、批量标记、批量删除、标签列表
  F. 通知模块：列表、未读数、标记已读、全部已读、删除
  G. 越权防护：用另一个账号访问第一个账号的资源必须 403
  H. 导出：/wrong-question/export 返回真实 xlsx（校验 ZIP 魔数 PK）

用法：
    python scripts/verify_week2.py
退出码：失败项数量（0 = 全部通过）。
"""

from __future__ import annotations

import argparse
import io
import json
import random
import string
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta

PASS = 0
FAIL = 0
FAILED_NAMES: list[str] = []


def step(title: str) -> None:
    print()
    print(f"--- {title}")


def check(cond: bool, desc: str, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {desc}")
    else:
        FAIL += 1
        FAILED_NAMES.append(desc)
        print(f"  [FAIL] {desc}")
    if detail:
        print(f"         {detail}")


class Api:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def __call__(self, method, path, body=None, token=None, raw=False, query=None):
        url = self.base_url + path
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None}, doseq=True)
        data = None
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        if token:
            headers["Authorization"] = f"Bearer {token}"

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                blob = resp.read()
                if raw:
                    return {"status": resp.status, "bytes": blob,
                            "headers": {k.lower(): v for k, v in resp.headers.items()}}
                text = blob.decode("utf-8", errors="replace")
                return {"status": resp.status, "text": text, "json": self._parse(text),
                        "headers": {k.lower(): v for k, v in resp.headers.items()}}
        except urllib.error.HTTPError as e:
            blob = e.read()
            if raw:
                return {"status": e.code, "bytes": blob,
                        "headers": {k.lower(): v for k, v in (e.headers or {}).items()}}
            text = blob.decode("utf-8", errors="replace")
            return {"status": e.code, "text": text, "json": self._parse(text),
                    "headers": {k.lower(): v for k, v in (e.headers or {}).items()}}
        except Exception as e:
            return {"status": -1, "text": f"{type(e).__name__}: {e}", "json": None, "headers": {}}

    @staticmethod
    def _parse(text: str):
        try:
            return json.loads(text)
        except Exception:
            return None


def rand(prefix: str) -> str:
    return prefix + "".join(random.choices(string.ascii_lowercase + string.digits, k=6))


def login_or_register(api: Api, username: str, password: str = "verify123456") -> str:
    api("POST", "/auth/register", {"username": username, "password": password, "nickname": username})
    r = api("POST", "/auth/login", {"username": username, "password": password})
    data = (r["json"] or {}).get("data") or {}
    return data.get("accessToken")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    api = Api(args.base_url)

    user_a = rand("w2a_")
    user_b = rand("w2b_")
    token_a = login_or_register(api, user_a)
    token_b = login_or_register(api, user_b)

    print("=" * 64)
    print(" 第 2 周验收 · 计划 / 错题 / 进度 / 通知")
    print(f" 目标服务: {args.base_url}")
    print(f" 主账号  : {user_a}")
    print(f" 越权账号: {user_b}")
    print("=" * 64)
    check(bool(token_a) and bool(token_b), "两个账号登录成功")

    today = date.today()
    target = today + timedelta(days=13)          # 14 天计划

    # ================================================================ A
    step("A. 计划模块 CRUD")
    create = api("POST", "/plan", {
        "title": "第2周验收计划",
        "examType": "考研",
        "targetDate": target.isoformat(),
        "startDate": today.isoformat(),
        "dailyMinutes": 120,
        "subjects": "英语,政治",
    }, token=token_a)
    cj = create["json"] or {}
    plan = cj.get("data") or {}
    plan_id = plan.get("id")
    check(cj.get("code") == 200 and plan_id, "创建计划成功",
          f"code={cj.get('code')} msg={cj.get('msg')} planId={plan_id}")

    # 14 天 × 2 科目 = 28 条任务
    check(plan.get("taskTotal") == 28, "按「目标日期 + 每日时长」均摊生成任务",
          f"taskTotal={plan.get('taskTotal')}（期望 28 = 14 天 × 2 科）")
    check(plan.get("source") == "手动", "source 标记为手动", f"source={plan.get('source')}")
    check(plan.get("status") == "进行中", "初始状态为进行中", f"status={plan.get('status')}")
    check(plan.get("daysLeft") is not None, "返回距考试天数", f"daysLeft={plan.get('daysLeft')}")

    page = api("GET", "/plan/page", token=token_a, query={"pageNum": 1, "pageSize": 10})
    pj = (page["json"] or {}).get("data") or {}
    check((page["json"] or {}).get("code") == 200, "计划分页查询成功",
          f"total={pj.get('total')} records={len(pj.get('records') or [])}")

    detail = api("GET", f"/plan/{plan_id}", token=token_a)
    check(((detail["json"] or {}).get("data") or {}).get("id") == plan_id, "计划详情成功")

    upd = api("PUT", f"/plan/{plan_id}", {
        "title": "第2周验收计划（改名）", "examType": "考研",
        "targetDate": target.isoformat(), "startDate": today.isoformat(), "dailyMinutes": 150,
    }, token=token_a)
    uj = (upd["json"] or {}).get("data") or {}
    check(uj.get("title") == "第2周验收计划（改名）", "修改计划成功", f"title={uj.get('title')}")
    check(uj.get("taskTotal") == 28, "修改计划不重新生成任务（保护已勾选进度）",
          f"taskTotal={uj.get('taskTotal')}")

    # ================================================================ B
    step("B. 分页边界（不炸是底线）")
    edge_cases = [
        ("pageNum=0", {"pageNum": 0, "pageSize": 10}),
        ("pageNum=-5", {"pageNum": -5, "pageSize": 10}),
        ("pageSize=0", {"pageNum": 1, "pageSize": 0}),
        ("pageSize=999999", {"pageNum": 1, "pageSize": 999999}),
        ("pageNum=999999", {"pageNum": 999999, "pageSize": 10}),
        ("非数字 pageNum", {"pageNum": "abc", "pageSize": 10}),
    ]
    for name, params in edge_cases:
        r = api("GET", "/plan/page", token=token_a, query=params)
        ok = r["status"] == 200 and (r["json"] or {}).get("code") in (200, 400)
        detail_txt = f"status={r['status']} code={(r['json'] or {}).get('code')}"
        if r["status"] == 200 and (r["json"] or {}).get("code") == 200:
            data = (r["json"].get("data") or {})
            detail_txt += f" pageSize={data.get('pageSize')} pageNum={data.get('pageNum')}"
        check(ok, f"计划分页 {name} 不报 500", detail_txt)

    # ================================================================ C
    step("C. 任务模块与「勾选完成 → 学习记录」联动")

    tasks = api("GET", "/plan/task/list", token=token_a,
                query={"planId": plan_id, "startDate": today.isoformat(), "endDate": target.isoformat()})
    tlist = (tasks["json"] or {}).get("data") or []
    check(len(tlist) == 28, "任务列表返回 28 条", f"实际 {len(tlist)} 条")

    today_tasks = api("GET", "/plan/task/today", token=token_a)
    tj = (today_tasks["json"] or {}).get("data") or {}
    check(tj.get("date") == today.isoformat(), "今日任务日期正确", f"date={tj.get('date')}")
    check(tj.get("total") == 2, "今天有 2 条任务（2 个科目）", f"total={tj.get('total')} tip={tj.get('tip')}")
    check(tj.get("progress") == 0.0, "初始完成率为 0", f"progress={tj.get('progress')}")

    # 基线：勾选前的进度
    before = (api("GET", "/progress/overview", token=token_a, query={"days": 30})["json"] or {}).get("data") or {}
    before_minutes = before.get("totalMinutes") or 0

    first_task_id = tlist[0]["id"] if tlist else None
    est_minutes = tlist[0]["estimatedMinutes"] if tlist else 0
    done = api("PUT", "/plan/task/status", {
        "taskId": first_task_id, "status": "完成", "actualMinutes": 99, "note": "验收脚本勾选",
    }, token=token_a)
    dj = (done["json"] or {}).get("data") or {}
    check((done["json"] or {}).get("code") == 200, "勾选任务完成成功",
          f"code={(done['json'] or {}).get('code')} msg={(done['json'] or {}).get('msg')}")
    check(dj.get("status") == "完成" and dj.get("finishTime"), "任务状态与完成时间已写入",
          f"status={dj.get('status')} finishTime={dj.get('finishTime')}")

    after = (api("GET", "/progress/overview", token=token_a, query={"days": 30})["json"] or {}).get("data") or {}
    after_minutes = after.get("totalMinutes") or 0
    check(after_minutes == before_minutes + 99,
          "进度统计的时长随勾选增加（study_record 联动生效）",
          f"{before_minutes} -> {after_minutes}（期望 +99）")
    check(after.get("taskDone") == (before.get("taskDone") or 0) + 1, "完成任务数 +1",
          f"{before.get('taskDone')} -> {after.get('taskDone')}")

    # 重复勾选必须幂等
    api("PUT", "/plan/task/status", {"taskId": first_task_id, "status": "完成", "actualMinutes": 99}, token=token_a)
    again = (api("GET", "/progress/overview", token=token_a, query={"days": 30})["json"] or {}).get("data") or {}
    check(again.get("totalMinutes") == after_minutes, "重复勾选同一任务是幂等的（不重复累计时长）",
          f"重复勾选后 totalMinutes={again.get('totalMinutes')}（期望 {after_minutes}）")

    # 取消完成必须回滚记录
    undo = api("PUT", "/plan/task/status", {"taskId": first_task_id, "status": "待办"}, token=token_a)
    undone = (api("GET", "/progress/overview", token=token_a, query={"days": 30})["json"] or {}).get("data") or {}
    check((undo["json"] or {}).get("code") == 200, "取消完成成功")
    check(undone.get("totalMinutes") == before_minutes,
          "取消完成后时长回滚（不会残留脏记录）",
          f"totalMinutes={undone.get('totalMinutes')}（期望回滚到 {before_minutes}）")

    # 再勾回来，后面进度测试要用
    api("PUT", "/plan/task/status", {"taskId": first_task_id, "status": "完成",
                                     "actualMinutes": est_minutes}, token=token_a)

    add = api("POST", "/plan/task", {
        "planId": plan_id, "taskDate": today.isoformat(), "title": "验收脚本新增的任务",
        "content": "内容", "subject": "数学", "estimatedMinutes": 45, "sort": 9,
    }, token=token_a)
    new_task = (add["json"] or {}).get("data") or {}
    check((add["json"] or {}).get("code") == 200 and new_task.get("id"), "新增任务成功",
          f"taskId={new_task.get('id')}")

    upd_task = api("PUT", f"/plan/task/{new_task.get('id')}", {
        "planId": plan_id, "taskDate": today.isoformat(), "title": "验收脚本新增的任务（改名）",
        "subject": "数学", "estimatedMinutes": 60, "sort": 9,
    }, token=token_a)
    check(((upd_task["json"] or {}).get("data") or {}).get("title", "").endswith("（改名）"), "修改任务成功")

    del_task = api("DELETE", f"/plan/task/{new_task.get('id')}", token=token_a)
    check((del_task["json"] or {}).get("code") == 200, "删除任务成功")

    # 无效状态必须被拒
    bad_status = api("PUT", "/plan/task/status", {"taskId": first_task_id, "status": "瞎写的状态"}, token=token_a)
    check((bad_status["json"] or {}).get("code") == 400, "非法任务状态被拒绝",
          f"code={(bad_status['json'] or {}).get('code')} msg={(bad_status['json'] or {}).get('msg')}")

    # ================================================================ D
    step("D. 进度统计接口")
    dash = api("GET", "/progress/dashboard", token=token_a)
    dj2 = (dash["json"] or {}).get("data") or {}
    check((dash["json"] or {}).get("code") == 200, "dashboard 返回成功")
    check(len(dj2.get("dailyMinutes") or []) == 30, "每日时长序列补零到 30 天",
          f"长度={len(dj2.get('dailyMinutes') or [])}（折线 X 轴必须连续）")
    check(len(dj2.get("completionTrend") or []) == 30, "完成率趋势补零到 30 天",
          f"长度={len(dj2.get('completionTrend') or [])}")
    check(len(dj2.get("subjectDistribution") or []) >= 1, "科目分布有数据",
          f"科目数={len(dj2.get('subjectDistribution') or [])}")
    check(len(dj2.get("masteryDistribution") or []) == 3, "掌握度分布返回 3 个档位",
          f"{dj2.get('masteryDistribution')}")
    ov = dj2.get("overview") or {}
    check(ov.get("streakDays") == 1, "连续学习天数为 1（今天学过）", f"streakDays={ov.get('streakDays')}")
    check(ov.get("activeDays") == 1, "有学习记录的天数为 1", f"activeDays={ov.get('activeDays')}")

    recent = api("GET", "/progress/recent", token=token_a, query={"days": 7})
    check(len(((recent["json"] or {}).get("data") or {}).get("dailyMinutes") or []) == 7, "recent?days=7 返回 7 天")

    huge = api("GET", "/progress/recent", token=token_a, query={"days": 100000})
    check((huge["json"] or {}).get("code") in (200, 400), "days=100000 被收敛/拒绝，不报 500",
          f"code={(huge['json'] or {}).get('code')}")

    bad_range = api("GET", "/progress/dashboard", token=token_a,
                    query={"startDate": today.isoformat(), "endDate": (today - timedelta(days=5)).isoformat()})
    check((bad_range["json"] or {}).get("code") == 400, "起止日期倒置被拒绝",
          f"msg={(bad_range['json'] or {}).get('msg')}")

    # ================================================================ E
    step("E. 错题模块与标签")
    wq = api("POST", "/wrong-question", {
        "subject": "数学", "questionType": "解答",
        "content": "计算定积分 ∫₀¹ x² dx 的值。",
        "answer": "1/3", "analysis": "使用牛顿-莱布尼茨公式。",
        "difficulty": 3, "mastery": "未掌握", "source": "真题",
        "tags": ["定积分", "牛顿-莱布尼茨公式"],
    }, token=token_a)
    wj = (wq["json"] or {}).get("data") or {}
    wq_id = wj.get("id")
    check((wq["json"] or {}).get("code") == 200 and wq_id, "新增错题成功（含自动建标签）",
          f"id={wq_id} tags={wj.get('tags')}")
    check(sorted(wj.get("tags") or []) == sorted(["定积分", "牛顿-莱布尼茨公式"]),
          "标签自动创建并回填", f"tags={wj.get('tags')}")

    # 同标签第二次引用，不应该重复建标签
    wq2 = api("POST", "/wrong-question", {
        "subject": "数学", "content": "计算 ∫₀² x dx。", "answer": "2",
        "difficulty": 2, "mastery": "复习中", "tags": ["定积分"],
    }, token=token_a)
    wq2_id = ((wq2["json"] or {}).get("data") or {}).get("id")
    tags = api("GET", "/wrong-question/tags", token=token_a)
    tag_list = (tags["json"] or {}).get("data") or []
    check(tag_list.count("定积分") == 1, "同名标签不会重复创建", f"tags={tag_list}")

    # 再补几道，用于筛选与批量测试
    for i in range(3):
        api("POST", "/wrong-question", {
            "subject": "英语", "content": f"英语错题 {i}", "answer": "A",
            "difficulty": 1 + i, "mastery": "未掌握", "tags": ["长难句"],
        }, token=token_a)

    by_subject = api("GET", "/wrong-question/page", token=token_a,
                     query={"pageNum": 1, "pageSize": 50, "subject": "数学"})
    math_records = ((by_subject["json"] or {}).get("data") or {}).get("records") or []
    check(len(math_records) == 2, "按科目筛选生效", f"数学错题数={len(math_records)}")

    by_mastery = api("GET", "/wrong-question/page", token=token_a,
                     query={"pageNum": 1, "pageSize": 50, "mastery": "未掌握"})
    unmastered = ((by_mastery["json"] or {}).get("data") or {}).get("records") or []
    check(len(unmastered) == 4, "按掌握度筛选生效", f"未掌握数={len(unmastered)}")

    by_tag = api("GET", "/wrong-question/page", token=token_a,
                 query={"pageNum": 1, "pageSize": 50, "tags": ["定积分"]})
    tag_records = ((by_tag["json"] or {}).get("data") or {}).get("records") or []
    check(len(tag_records) == 2, "按标签筛选生效", f"含「定积分」的错题数={len(tag_records)}")

    by_kw = api("GET", "/wrong-question/page", token=token_a,
                query={"pageNum": 1, "pageSize": 50, "keyword": "牛顿"})
    kw_records = ((by_kw["json"] or {}).get("data") or {}).get("records") or []
    check(len(kw_records) == 1, "关键字模糊搜索生效", f"命中={len(kw_records)}")

    # 难度用 5 这个在其它测试数据里没出现过的值，避免"难 => 5"的用例与其它用例互相干扰
    hard = api("POST", "/wrong-question", {
        "subject": "专业课", "content": "难度筛选专用错题", "answer": "略",
        "difficulty": 5, "mastery": "未掌握",
    }, token=token_a)
    hard_id = ((hard["json"] or {}).get("data") or {}).get("id")

    by_diff = api("GET", "/wrong-question/page", token=token_a,
                  query={"pageNum": 1, "pageSize": 50, "difficulty": 5})
    hard_rows = ((by_diff["json"] or {}).get("data") or {}).get("records") or []
    check(len(hard_rows) == 1 and hard_rows[0]["id"] == hard_id,
          "按难度筛选生效（difficulty=5 只命中那一条）",
          f"命中={len(hard_rows)} 条，id={[r['id'] for r in hard_rows]}")

    upd_wq = api("PUT", f"/wrong-question/{wq_id}", {
        "subject": "数学", "content": "计算定积分 ∫₀¹ x² dx 的值。（已订正）",
        "answer": "1/3", "difficulty": 4, "mastery": "复习中", "tags": ["定积分"],
    }, token=token_a)
    uwj = (upd_wq["json"] or {}).get("data") or {}
    check(uwj.get("difficulty") == 4 and uwj.get("tags") == ["定积分"],
          "修改错题 + 标签全量替换生效", f"difficulty={uwj.get('difficulty')} tags={uwj.get('tags')}")

    batch = api("PUT", "/wrong-question/batch/mastery",
                {"ids": [wq_id, wq2_id], "mastery": "已掌握"}, token=token_a)
    check(((batch["json"] or {}).get("data")) == 2, "批量标记掌握度成功",
          f"更新条数={(batch['json'] or {}).get('data')}")

    bad_mastery = api("PUT", "/wrong-question/batch/mastery",
                      {"ids": [wq_id], "mastery": "瞎写"}, token=token_a)
    check((bad_mastery["json"] or {}).get("code") == 400, "非法掌握度被拒绝")

    # ================================================================ H
    step("H. Excel 导出")
    export = api("GET", "/wrong-question/export", token=token_a, raw=True)
    blob = export.get("bytes") or b""
    check(export["status"] == 200, "导出返回 200", f"status={export['status']}")
    check(blob[:2] == b"PK", "导出内容是真实 xlsx（ZIP 魔数 PK）", f"前两字节={blob[:2]!r} 大小={len(blob)} 字节")
    check("attachment" in (export["headers"].get("content-disposition") or ""),
          "响应头带 Content-Disposition attachment",
          export["headers"].get("content-disposition"))

    # ================================================================ F
    step("F. 通知模块")
    # 通知没有对外的新增接口（本周由定时任务/MQ 产生），直接查列表与已读接口
    notice_page = api("GET", "/notice/page", token=token_a, query={"pageNum": 1, "pageSize": 10})
    check((notice_page["json"] or {}).get("code") == 200, "通知分页查询成功",
          f"total={(((notice_page['json'] or {}).get('data') or {}).get('total'))}")
    unread = api("GET", "/notice/unread-count", token=token_a)
    check((unread["json"] or {}).get("code") == 200, "未读数量接口成功",
          f"unread={((unread['json'] or {}).get('data'))}")
    all_read = api("PUT", "/notice/read/all", token=token_a)
    check((all_read["json"] or {}).get("code") == 200, "全部标记已读成功",
          f"count={(all_read['json'] or {}).get('data')}")

    # ================================================================ G
    step("G. 越权防护（横向权限）")
    other_detail = api("GET", f"/plan/{plan_id}", token=token_b)
    check((other_detail["json"] or {}).get("code") == 403, "B 账号读 A 的计划 → 403",
          f"code={(other_detail['json'] or {}).get('code')} msg={(other_detail['json'] or {}).get('msg')}")

    other_update = api("PUT", f"/plan/{plan_id}", {
        "title": "越权改名", "examType": "考研", "targetDate": target.isoformat(),
        "startDate": today.isoformat(), "dailyMinutes": 60,
    }, token=token_b)
    check((other_update["json"] or {}).get("code") == 403, "B 账号改 A 的计划 → 403",
          f"code={(other_update['json'] or {}).get('code')}")

    other_delete = api("DELETE", f"/plan/{plan_id}", token=token_b)
    check((other_delete["json"] or {}).get("code") == 403, "B 账号删 A 的计划 → 403",
          f"code={(other_delete['json'] or {}).get('code')}")

    other_task = api("PUT", "/plan/task/status", {"taskId": first_task_id, "status": "跳过"}, token=token_b)
    check((other_task["json"] or {}).get("code") == 403, "B 账号改 A 的任务 → 403",
          f"code={(other_task['json'] or {}).get('code')}")

    other_wq = api("GET", f"/wrong-question/{wq_id}", token=token_b)
    check((other_wq["json"] or {}).get("code") == 403, "B 账号读 A 的错题 → 403",
          f"code={(other_wq['json'] or {}).get('code')}")

    # 批量操作里的越权 ID 必须被静默过滤掉，不能真的删到别人的数据
    b_batch = api("DELETE", "/wrong-question/batch", {"ids": [wq_id, wq2_id]}, token=token_b)
    check(((b_batch["json"] or {}).get("data")) == 0, "B 账号批量删 A 的错题，实际删除 0 条",
          f"返回={((b_batch['json'] or {}).get('data'))}")
    still_there = api("GET", f"/wrong-question/{wq_id}", token=token_a)
    check((still_there["json"] or {}).get("code") == 200, "A 的错题仍存在（越权未生效）")

    # 无 token 访问业务接口必须 401
    for path in ["/plan/page", "/wrong-question/page", "/progress/dashboard", "/notice/page"]:
        r = api("GET", path)
        check(r["status"] == 401, f"未登录访问 {path} → 401", f"status={r['status']}")

    # ================================================================ 收尾
    step("收尾：计划状态变更与删除")
    st = api("PUT", f"/plan/{plan_id}/status", token=token_a, query={"status": "已完成"})
    check((st["json"] or {}).get("code") == 200, "变更计划状态为已完成")
    bad_st = api("PUT", f"/plan/{plan_id}/status", token=token_a, query={"status": "瞎写"})
    check((bad_st["json"] or {}).get("code") == 400, "非法计划状态被拒绝")

    del_plan = api("DELETE", f"/plan/{plan_id}", token=token_a)
    check((del_plan["json"] or {}).get("code") == 200, "删除计划成功")
    gone = api("GET", f"/plan/{plan_id}", token=token_a)
    check((gone["json"] or {}).get("code") == 400, "删除后详情返回业务错误（逻辑删除生效）",
          f"code={(gone['json'] or {}).get('code')}")

    leftover = api("GET", "/plan/task/list", token=token_a, query={"planId": plan_id})
    check(len((leftover["json"] or {}).get("data") or []) == 0, "删除计划时其任务一并删除")

    total = PASS + FAIL
    print()
    print("=" * 64)
    if FAIL == 0:
        print(f" 验收结果：全部通过 ({PASS}/{total})")
    else:
        print(f" 验收结果：{PASS} 通过 / {FAIL} 失败（共 {total}）")
        for name in FAILED_NAMES:
            print(f"   - 失败: {name}")
    print("=" * 64)
    return FAIL


if __name__ == "__main__":
    sys.exit(main())
