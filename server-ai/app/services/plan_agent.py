"""规划 Agent（LangGraph 三节点图）。

执行计划要求的三节点：
    节点1 信息补全 → 节点2 计划生成 → 节点3 校验调整（不合法回节点2，最多重试 2 次）

实现约定：
- **节点1（信息补全）**：检查必填信息（考试类型 / 目标日期 / 每日时长），
  缺什么就补什么。本项目由前端表单收集，所以这里做的是「校验 + 归一化 + 给出默认值」，
  而不是多轮对话追问 —— 对应执行计划降级预案第 4 条（砍掉多轮补全）。
  需要多轮时，把 `missing_fields` 返回给前端做追问即可，图结构不用改。
- **节点2（计划生成）**：用 `response_format={"type": "json_object"}` 约束 LLM 输出结构化 JSON。
  长计划（> {@link CHUNK_DAYS} 天）按 10 天**分片并发生成后合并** —— 单次全量输出
  会超时，原因见 `CHUNK_DAYS` 的注释。
- **节点3（校验调整）**：校验 JSON 合法性、日期连续性、每日时长的合理性；
  不合法就带着**具体错误原因**回到节点2重试（错误信息进 prompt，让模型自我修正）。
  **可确定性修复的问题不触发重试**（每日时长超出上限 → 削减任务时长；
  任务条数超限 → 移除多余条目），修正动作记入 warnings ——
  把"重生成整段（分钟级）"降级为"毫秒级修正"。

为什么要自己校验而不是信任模型：LLM 会产出"日期跳了一天""某天 8 小时""科目写错"
这类问题。校验器把这些问题变成可重试的反馈，比前端硬判更可靠。
"""

from __future__ import annotations

import json
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta
from typing import Any, TypedDict

from app.core.config import get_settings
from app.core.exceptions import BizException
from app.core.llm import get_llm
from app.core.logger import logger

#: 单次生成的最大天数（与 Java 侧均摊版保持一致的上限口径）
MAX_DAYS = 400
#: 校验失败后最多重试几次
MAX_RETRIES = 2
#: 每天最多多少条任务（超过说明模型在凑数）
MAX_TASKS_PER_DAY = 6
#: 分片天数：单次 LLM 调用最多生成这么多天的任务。
#:
#: 为什么要分片（实测踩坑）：最初整段计划一次生成，72 天的任务 JSON 输出量太大
#: （约 15K~30K 字符，还要叠加思考模式的思维链），qwen3.7-flash 单次生成远超
#: 60 秒的客户端超时 —— 连续 3 次尝试都在 60 秒处被掐断（日志：OpenAITimeoutError），
#: 对外表现成"AI 没有生成任何任务"。按 10 天分片后，单次输出降到 2K~4K 字符，
#: 10~30 秒即可完成；片段之间互不依赖，用线程池并发跑，
#: 72 天计划的总耗时压到 1 分钟级。
CHUNK_DAYS = 10
#: 分片并发的最大线程数（DashScope 单 key 的并发额度足够 8 路）
CHUNK_WORKERS = 8
#: 单个分片的生成尝试次数（JSON 解析失败 / 超时都值得原地再来一次，
#: 比"整个计划重生成"便宜得多：单分片约 1 分钟，整段约 2~6 分钟）
SEGMENT_ATTEMPTS = 2


# --------------------------------------------------------------------- 状态


class PlanState(TypedDict, total=False):
    """图的状态。每个节点读它、返回要更新的字段。"""

    # 输入
    exam_type: str
    target_date: str
    daily_minutes: int
    subjects: list[str]
    level: str
    title: str

    # 节点1 产出
    normalized: dict
    missing_fields: list[str]

    # 节点2 产出
    raw_plan: dict
    retries: int
    feedback: list[str]

    # 节点3 产出
    valid: bool
    errors: list[str]
    tasks: list[dict]
    warnings: list[str]


# --------------------------------------------------------------------- 节点1


def node_validate_input(state: PlanState) -> dict:
    """节点1：信息补全与归一化。

    - 必填缺失 → 记录到 `missing_fields`，由调用方决定是追问还是直接报错；
    - 日期归一化成 `yyyy-MM-dd`，并算出总天数；
    - 每日时长做上下限收敛（10 ~ 1440 分钟）；
    - 科目为空时按考试类型给一套默认科目。
    """
    missing: list[str] = []
    normalized: dict[str, Any] = {}
    warnings: list[str] = []

    exam_type = (state.get("exam_type") or "").strip()
    if not exam_type:
        missing.append("examType")
    else:
        normalized["examType"] = exam_type

    target_date_raw = (state.get("target_date") or "").strip()
    target_date = _parse_date(target_date_raw)
    if target_date is None:
        missing.append("targetDate")
    else:
        normalized["targetDate"] = target_date

    daily_minutes = state.get("daily_minutes") or 0
    if daily_minutes < 10:
        missing.append("dailyMinutes")
    else:
        normalized["dailyMinutes"] = min(int(daily_minutes), 1440)

    subjects = [s.strip() for s in (state.get("subjects") or []) if s and s.strip()]
    if not subjects:
        subjects = _default_subjects(exam_type)
        warnings.append(f"未指定科目，按考试类型「{exam_type or '通用'}」使用默认科目：{'、'.join(subjects)}")
    normalized["subjects"] = subjects

    normalized["level"] = (state.get("level") or "未说明").strip()
    normalized["title"] = (state.get("title") or "").strip()

    if target_date is not None:
        today = date.today()
        days = (target_date - today).days + 1
        if days < 1:
            missing.append("targetDate(已过期)")
        elif days > MAX_DAYS:
            raise BizException(
                code=4000,
                msg=f"目标日期距今 {days} 天，超过单次生成上限 {MAX_DAYS} 天，请拆分阶段计划",
            )
        else:
            normalized["startDate"] = today
            normalized["days"] = days

    return {"normalized": normalized, "missing_fields": missing, "warnings": warnings}


def _parse_date(value: str) -> date | None:
    """宽松解析日期：支持 2027-12-20、2027/12/20、2027年12月20日。"""
    if not value:
        return None
    text = value.strip().replace("/", "-").replace("年", "-").replace("月", "-").replace("日", "")
    match = re.match(r"(\d{4})-(\d{1,2})-(\d{1,2})", text)
    if not match:
        return None
    try:
        return date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
    except ValueError:
        return None


def _default_subjects(exam_type: str) -> list[str]:
    """按考试类型给默认科目。"""
    if "公" in exam_type:
        return ["行测", "申论"]
    if "研" in exam_type:
        return ["英语", "数学", "政治", "专业课"]
    return ["综合"]


# --------------------------------------------------------------------- 节点2


def node_generate_plan(state: PlanState) -> dict:
    """节点2：LLM 产出结构化 JSON 计划。

    长计划（超过 {@link CHUNK_DAYS} 天）按片切分、**并发**调用 LLM，再按日期合并 ——
    单次全量生成会因输出过长而超时，原因见 {@link CHUNK_DAYS} 的注释。

    `feedback` 非空时表示上一轮校验失败，把错误原因一起交给模型自我修正
    （每个分片都会带上完整的反馈列表，让对应日期所在的片段自我修复）。
    """
    normalized = state.get("normalized") or {}
    if not normalized:
        return {"raw_plan": {}, "errors": ["信息补全失败，无法生成计划"]}

    retries = int(state.get("retries") or 0)
    feedback = state.get("feedback") or []

    start: date = normalized.get("startDate") or date.today()
    days = int(normalized.get("days") or 1)
    segments = _split_segments(start, days)
    multi = len(segments) > 1

    logger.info(
        "规划 Agent 节点2：生成计划（第 {} 次尝试）| 天数={} 每日={} 分钟 科目={} | 分 {} 片{}",
        retries + 1,
        days,
        normalized.get("dailyMinutes"),
        "、".join(normalized.get("subjects") or []),
        len(segments),
        "（并发生成）" if multi else "",
    )

    results: dict[int, tuple[str, list[dict]]] = {}
    failures: list[str] = []

    def _run(index: int) -> None:
        segment = segments[index] if multi else None
        last_exc: Exception | None = None
        for attempt in range(1, SEGMENT_ATTEMPTS + 1):
            try:
                results[index] = _invoke_segment(normalized, segment, feedback)
                return
            except Exception as exc:  # noqa: BLE001 - 单个分片失败不丢弃其它分片
                last_exc = exc
                logger.warning(
                    "规划 Agent 节点2 分片 [{}] 第 {}/{} 次失败: {!r}",
                    _describe_segment(segments[index]),
                    attempt,
                    SEGMENT_ATTEMPTS,
                    exc,
                )
        failures.append(f"{_describe_segment(segments[index])}：{last_exc!r}")

    if not multi:
        _run(0)
    else:
        with ThreadPoolExecutor(max_workers=min(CHUNK_WORKERS, len(segments))) as pool:
            list(pool.map(_run, range(len(segments))))

    if not results:
        return {"raw_plan": {}, "errors": ["计划生成失败：" + "；".join(failures[:3])]}

    if failures:
        # 缺的日期会由校验节点报出来并触发重试；这里先把警告记清楚的
        logger.warning(
            "规划 Agent 节点2：{} 个分片失败，缺失日期将由校验节点报出 | {}",
            len(failures),
            "；".join(failures[:3]),
        )

    merged: list[dict] = []
    title = ""
    for index in sorted(results):
        seg_title, tasks = results[index]
        if not title and seg_title:
            title = seg_title
        merged.extend(tasks)
    merged.sort(key=lambda item: str(item.get("taskDate") or ""))
    return {"raw_plan": {"title": title, "tasks": merged}}


def _invoke_segment(
    normalized: dict, segment: tuple[date, date, int, int] | None, feedback: list[str]
) -> tuple[str, list[dict]]:
    """调用一次 LLM 生成（整段或某一个分片）并解析 JSON。

    Returns:
        `(title, tasks)` —— tasks 为模型返回的原始 dict 列表，清洗在校验节点做。
    """
    prompt = _build_generate_prompt(normalized, feedback, segment)
    # 分片后单次输出小、耗时可预期，超时放宽到 120s；同时把 HTTP 层重试降到 1 次，
    # 避免单分片在"客户端超时"上浪费 3 倍时间（整体失败由图级重试兜底）
    llm = get_llm(streaming=False, temperature=0.2, timeout=120, max_retries=1)
    # 用 response_format 约束模型只输出 JSON 对象。实测不开它时，个别分片会产出
    # "看起来像 JSON、实际解析失败"的文本（长度 3.9K~5.8K），只能靠图级重试补救，
    # 代价是整段计划重新生成。DashScope 的 OpenAI 兼容协议支持这个参数。
    message = llm.bind(response_format={"type": "json_object"}).invoke(prompt)
    text = _extract_text(message)
    raw = _parse_json(text)
    tasks = raw.get("tasks")
    if not isinstance(tasks, list) or not tasks:
        # 抛出去交给分片级重试（_run），而不是静默返回空 tasks
        raise ValueError(f"模型输出不是含 tasks 的 JSON（长度 {len(text)}）")
    return str(raw.get("title") or ""), [t for t in tasks if isinstance(t, dict)]


def _split_segments(start: date, days: int) -> list[tuple[date, date, int, int]]:
    """把 [start, start + days - 1] 切成连续、不重叠、最多 CHUNK_DAYS 天的片段。

    Returns:
        `[(片段起始日, 片段结束日, 第几天起(1-based), 第几天止), ...]`
    """
    segments: list[tuple[date, date, int, int]] = []
    offset = 0
    while offset < days:
        size = min(CHUNK_DAYS, days - offset)
        seg_start = start + timedelta(days=offset)
        seg_end = seg_start + timedelta(days=size - 1)
        segments.append((seg_start, seg_end, offset + 1, offset + size))
        offset += size
    return segments


def _describe_segment(segment: tuple[date, date, int, int]) -> str:
    """人类可读的片段描述（日志 / 错误信息用）。"""
    return f"{segment[0]} ~ {segment[1]}（第 {segment[2]}~{segment[3]} 天）"


def _build_generate_prompt(
    normalized: dict,
    feedback: list[str],
    segment: tuple[date, date, int, int] | None = None,
) -> str:
    """拼生成计划的 prompt（segment 非空时表示只生成该分片）。"""
    subjects = "、".join(normalized.get("subjects") or [])
    days = normalized.get("days")
    daily = normalized.get("dailyMinutes")
    target = normalized.get("targetDate")
    start = normalized.get("startDate")
    exam_type = normalized.get("examType")
    level = normalized.get("level")
    title = normalized.get("title") or f"{days} 天{exam_type}冲刺计划"

    # 本次要覆盖的日期范围：分片时只覆盖片段，否则覆盖全时段
    if segment is not None:
        span = f"{segment[0]} 到 {segment[1]}"
    else:
        span = f"{start} 到 {target}"

    parts = [
        "你是一位备考规划专家。请为下面这位学生生成逐日学习计划。",
        "",
        "【学生情况】",
        f"- 考试类型：{exam_type}",
        f"- 当前基础：{level}",
        f"- 考试日期：{target}",
        f"- 计划开始：{start}（今天）",
        f"- 总天数：{days} 天（含今天与考试当天）",
        f"- 每日可投入：{daily} 分钟",
        f"- 需要覆盖的科目：{subjects}",
    ]

    if segment is not None:
        parts.extend(
            [
                "",
                "【本次生成范围（重要）】",
                f"这是一份 {days} 天计划里的第 {segment[2]} ~ {segment[3]} 天部分"
                f"（{segment[0]} ~ {segment[1]}，共 {segment[3] - segment[2] + 1} 天）。",
                "计划的其它日期由别的生成任务负责，**你只生成这个范围内的日期**，"
                "不要生成范围之外的日期，也不要漏掉范围内的任何一天。",
            ]
        )

    parts.extend(
        [
            "",
            "【要求】",
            f"1. 从 {span} **每一天**都要有任务，日期必须连续、不能跳日、不能超出范围；",
            f"2. 每天所有任务的预计时长之和必须 ≤ {daily} 分钟，且不低于 {max(10, daily // 2)} 分钟；",
            f"3. 每天任务条数在 1~{MAX_TASKS_PER_DAY} 条之间；",
            "4. 科目要交替安排，同一科目不要连续太多天只做同一件事；",
            f"5. subject 字段**只能填**以下科目名之一（严格照抄，不要写「综合」「混合」这类新名字）：{subjects}；",
            "6. 越接近考试，越偏向真题模考与查漏补缺；越靠前越偏向基础与知识框架；",
            "7. 任务标题要具体可执行（例如「背 100 个考研核心词 + 精读 1 篇真题阅读」），",
            "   不要写「学习英语」这种空泛描述；",
            "8. 只输出 JSON，不要输出任何解释文字、不要用 markdown 代码块包裹。",
            "",
            "【输出 JSON 结构】",
            "{",
            '  "title": "计划标题",',
            '  "tasks": [',
            '    {"taskDate": "yyyy-MM-dd", "title": "任务标题", "content": "具体要求", '
            '"subject": "科目", "estimatedMinutes": 60}',
            "  ]",
            "}",
        ]
    )

    if feedback:
        parts.extend(
            [
                "",
                "【上一版计划被校验拒绝，原因如下，请逐条修正】",
                *(f"- {item}" for item in feedback[:10]),
            ]
        )

    if title:
        parts.append(f"\n计划标题建议：{title}")
    return "\n".join(parts)


def _extract_text(message: Any) -> str:
    """从 LangChain 消息里取正文（兼容 content 为 list 的形态）。"""
    content = getattr(message, "content", None)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and item.get("text"):
                parts.append(str(item["text"]))
        return "".join(parts)
    return "" if content is None else str(content)


def _parse_json(text: str) -> dict:
    """从模型输出里抠出 JSON。

    模型偶尔会包上 ```json 代码块或前后加解释，这里做三层兜底：
    直接解析 → 去代码块 → 取第一个 `{` 到最后一个 `}` 的子串。
    """
    if not text or not text.strip():
        return {}

    candidates: list[str] = [text.strip()]

    fenced = re.search(r"```(?:json)?\s*(.+?)\s*```", text, re.DOTALL)
    if fenced:
        candidates.append(fenced.group(1).strip())

    first = text.find("{")
    last = text.rfind("}")
    if first != -1 and last > first:
        candidates.append(text[first:last + 1])

    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            continue

    logger.warning(
        "规划 Agent 无法从模型输出中解析出 JSON，长度={} | 开头={!r} | 结尾={!r}",
        len(text),
        text[:160],
        text[-120:],
    )
    return {}


# --------------------------------------------------------------------- 节点3


def node_validate_plan(state: PlanState) -> dict:
    """节点3：校验计划，不合法则给出具体错误（供重试）。"""
    normalized = state.get("normalized") or {}
    raw_plan = state.get("raw_plan") or {}
    retries = int(state.get("retries") or 0)

    errors: list[str] = []
    warnings: list[str] = list(state.get("warnings") or [])

    if not raw_plan:
        errors.append("模型没有返回可解析的 JSON 计划")
        return {
            "valid": False,
            "errors": errors,
            "retries": retries + 1,
            "feedback": errors,
            "warnings": warnings,
        }

    raw_tasks = raw_plan.get("tasks")
    if not isinstance(raw_tasks, list) or not raw_tasks:
        errors.append("JSON 里缺少 tasks 数组或数组为空")
        return {
            "valid": False,
            "errors": errors,
            "retries": retries + 1,
            "feedback": errors,
            "warnings": warnings,
        }

    start: date = normalized.get("startDate") or date.today()
    target: date = normalized.get("targetDate") or start
    daily_minutes = int(normalized.get("dailyMinutes") or 120)
    subjects = normalized.get("subjects") or []

    # ---- 逐条清洗与规范化 ----
    cleaned: list[dict] = []
    for index, item in enumerate(raw_tasks):
        if not isinstance(item, dict):
            errors.append(f"第 {index + 1} 条任务不是对象")
            continue
        task_date = _parse_date(str(item.get("taskDate") or item.get("date") or ""))
        title = str(item.get("title") or "").strip()
        if task_date is None:
            errors.append(f"第 {index + 1} 条任务的 taskDate 无法解析：{item.get('taskDate')!r}")
            continue
        if not title:
            errors.append(f"第 {index + 1} 条任务缺少 title")
            continue
        if task_date < start or task_date > target:
            errors.append(
                f"第 {index + 1} 条任务日期 {task_date} 超出计划范围 {start} ~ {target}"
            )
            continue

        try:
            estimated = int(item.get("estimatedMinutes") or 60)
        except (TypeError, ValueError):
            estimated = 60
        estimated = max(10, min(estimated, daily_minutes))

        subject = str(item.get("subject") or "").strip()
        if subjects and subject and subject not in subjects:
            # 科目不在清单里不算致命错误，纠正成最近的一个即可
            warnings.append(f"任务日期 {task_date} 的科目「{subject}」不在计划科目内，已归入「{subjects[0]}」")
            subject = subjects[0]

        cleaned.append(
            {
                "taskDate": task_date.isoformat(),
                "title": title[:128],
                "content": str(item.get("content") or "").strip()[:1000],
                "subject": subject or (subjects[0] if subjects else "综合"),
                "estimatedMinutes": estimated,
            }
        )

    # ---- 日期连续性 ----
    expected_dates = [
        (start + timedelta(days=offset)).isoformat()
        for offset in range((target - start).days + 1)
    ]
    actual_dates = {t["taskDate"] for t in cleaned}
    missing_dates = [d for d in expected_dates if d not in actual_dates]
    if missing_dates:
        preview = "、".join(missing_dates[:5])
        suffix = f" 等 {len(missing_dates)} 天" if len(missing_dates) > 5 else ""
        errors.append(f"以下日期没有任务：{preview}{suffix}（要求每一天都有任务）")

    # ---- 每日时长 / 条数：确定性修正 ----
    #
    # 这两类问题完全可以由代码修好（削减最后一条任务的时长 / 移除多余条目），
    # 没必要带着它们回节点2 重生成整段计划 —— 实测一次整段重生成要 2 分钟以上，
    # 而这里的修正是毫秒级。修正动作记进 warnings，前端可以展示"已自动校正"。
    by_day: dict[str, list[dict]] = {}
    for task in cleaned:
        by_day.setdefault(task["taskDate"], []).append(task)

    for day in sorted(by_day):
        day_tasks = by_day[day]
        total = sum(t["estimatedMinutes"] for t in day_tasks)
        while total > daily_minutes and day_tasks:
            last = day_tasks[-1]
            cut = min(last["estimatedMinutes"] - 10, total - daily_minutes)
            if cut > 0:
                last["estimatedMinutes"] -= cut
                total -= cut
                warnings.append(
                    f"{day} 任务总时长超出每日上限 {daily_minutes} 分钟，已把最后一条任务削减 {cut} 分钟"
                )
            else:
                removed = day_tasks.pop()
                total = sum(t["estimatedMinutes"] for t in day_tasks)
                warnings.append(
                    f"{day} 任务总时长超出每日上限，已移除任务「{removed['title'][:20]}」"
                )
        if len(day_tasks) > MAX_TASKS_PER_DAY:
            extra = day_tasks[MAX_TASKS_PER_DAY:]
            del day_tasks[MAX_TASKS_PER_DAY:]
            warnings.append(f"{day} 任务条数超过 {MAX_TASKS_PER_DAY} 条，已移除 {len(extra)} 条")

    cleaned = [task for day_tasks in by_day.values() for task in day_tasks]

    # ---- 每日统计（日志用；修正后必然满足上限）----
    per_day: dict[str, int] = {}
    for task in cleaned:
        per_day[task["taskDate"]] = per_day.get(task["taskDate"], 0) + task["estimatedMinutes"]

    if errors:
        logger.warning(
            "规划 Agent 节点3：校验不通过，共 {} 条问题（第 {} 次尝试）| {}",
            len(errors),
            retries + 1,
            "；".join(errors[:3]),
        )
        return {
            "valid": False,
            "errors": errors,
            "tasks": cleaned,
            "retries": retries + 1,
            "feedback": errors,
            "warnings": warnings,
        }

    logger.info("规划 Agent 节点3：校验通过，共 {} 条任务（跨 {} 天）", len(cleaned), len(per_day))
    return {
        "valid": True,
        "errors": [],
        "tasks": cleaned,
        "retries": retries + 1,
        "feedback": [],
        "warnings": warnings,
    }


# --------------------------------------------------------------------- 图


def _route_after_validate(state: PlanState) -> str:
    """条件边：校验通过就结束，不通过且还有重试额度就回节点2。"""
    if state.get("valid"):
        return "done"
    if int(state.get("retries") or 0) > MAX_RETRIES:
        return "give_up"
    return "retry"


def build_plan_graph():
    """构建 LangGraph 三节点图。

    结构：
        START → 信息补全 → 计划生成 → 校验调整 ─(通过)→ END
                            ↑              └(不通过且未超重试)→ 计划生成
                            └──────────────┘
    """
    from langgraph.graph import END, START, StateGraph

    graph = StateGraph(PlanState)
    graph.add_node("normalize", node_validate_input)
    graph.add_node("generate", node_generate_plan)
    graph.add_node("validate", node_validate_plan)

    graph.add_edge(START, "normalize")
    graph.add_edge("normalize", "generate")
    graph.add_edge("generate", "validate")
    graph.add_conditional_edges(
        "validate",
        _route_after_validate,
        {"retry": "generate", "done": END, "give_up": END},
    )
    return graph.compile()


_graph = None


def get_plan_graph():
    """图编译一次即可复用（编译有开销，且 LangGraph 的图是线程安全的）。"""
    global _graph
    if _graph is None:
        _graph = build_plan_graph()
        logger.info("规划 Agent 图已编译：normalize → generate → validate（最多重试 {} 次）", MAX_RETRIES)
    return _graph


def generate_plan(
    exam_type: str,
    target_date: str,
    daily_minutes: int,
    subjects: list[str] | None = None,
    level: str | None = None,
    title: str | None = None,
) -> dict:
    """跑一次规划 Agent，返回最终状态。

    Returns:
        `{"title", "tasks", "warnings", "retries", "valid", "errors", "normalized"}`

    Raises:
        BizException: 信息不全（缺必填）或目标日期超过上限。
    """
    settings = get_settings()
    if not settings.llm_configured:
        raise BizException(code=5032, msg="未配置 DASHSCOPE_API_KEY，无法生成计划")

    initial: PlanState = {
        "exam_type": exam_type,
        "target_date": target_date,
        "daily_minutes": daily_minutes,
        "subjects": subjects or [],
        "level": level or "",
        "title": title or "",
        "retries": 0,
        "feedback": [],
        "warnings": [],
    }

    # 先本地跑一次节点1：缺必填就立刻报错，不必浪费一次 LLM 调用
    prescreen = node_validate_input(initial)
    missing = prescreen.get("missing_fields") or []
    if missing:
        raise BizException(
            code=4000,
            msg="信息不完整，无法生成计划，缺少：" + "、".join(missing),
        )
    initial.update(prescreen)

    final_state = get_plan_graph().invoke(
        initial, config={"recursion_limit": 25}
    )

    valid = bool(final_state.get("valid"))
    errors = final_state.get("errors") or []
    tasks = final_state.get("tasks") or []

    if not valid:
        logger.warning("规划 Agent 达到重试上限仍未通过校验: {}", "；".join(errors[:5]))

    normalized = final_state.get("normalized") or {}
    return {
        "title": (final_state.get("raw_plan") or {}).get("title")
        or normalized.get("title")
        or f"{normalized.get('examType', '')}备考计划",
        "tasks": tasks,
        "targetDate": normalized.get("targetDate").isoformat() if normalized.get("targetDate") else target_date,
        "dailyMinutes": normalized.get("dailyMinutes", daily_minutes),
        "days": normalized.get("days", 0),
        "warnings": (final_state.get("warnings") or []) + ([] if valid else [f"校验未完全通过：{'；'.join(errors[:3])}"]),
        # 语义换算：图内部的 retries 计的是"校验执行次数"（首次校验 = 1），
        # 对外返回的是"真正发生了几次重试"（首轮通过 = 0）——
        # 否则前端会把一次成功的生成显示成"Agent 自检重试 1 次"。
        "retries": max(0, int(final_state.get("retries") or 0) - 1),
        "valid": valid,
        "errors": errors,
        "normalized": normalized,
    }


__all__ = [
    "MAX_DAYS",
    "MAX_RETRIES",
    "PlanState",
    "node_validate_input",
    "node_generate_plan",
    "node_validate_plan",
    "build_plan_graph",
    "get_plan_graph",
    "generate_plan",
]
