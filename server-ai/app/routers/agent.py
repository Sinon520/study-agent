"""Agent 路由：错题归类、复盘报告、规划 Agent。

这三个能力都面向 Java 侧调用（内网），不对外暴露给浏览器，
所以鉴权由 Java 统一承担（见 docs/架构.md 的"Java 是唯一写入口"）。
"""

from __future__ import annotations

import json
import re

from fastapi import APIRouter

from app.core.config import get_settings
from app.core.exceptions import BizException
from app.core.llm import get_llm
from app.core.logger import logger
from app.core.trace import get_trace_id
from app.models.rag_schemas import (
    ClassifyRequest,
    ClassifyResponse,
    GeneratedTask,
    PlanGenerateRequest,
    PlanGenerateResponse,
    ReportRequest,
    ReportResponse,
)
from app.services.java_client import get_java_client
from app.services.plan_agent import generate_plan

router = APIRouter(prefix="/agent", tags=["agent"])


# --------------------------------------------------------------------- 通用


def _invoke_json(prompt: str, temperature: float = 0.2) -> dict:
    """调 LLM 并解析结构化 JSON（三层兜底，容忍代码块包裹）。"""
    settings = get_settings()
    if not settings.llm_configured:
        raise BizException(code=5032, msg="未配置 DASHSCOPE_API_KEY，无法调用大模型")

    try:
        llm = get_llm(streaming=False, temperature=temperature)
        message = llm.invoke(
            prompt,
            response_format={"type": "json_object"},
        )
    except BizException:
        raise
    except Exception as exc:  # noqa: BLE001
        logger.error("LLM JSON 调用失败: {!r}", exc)
        raise BizException(code=5001, msg=f"大模型调用失败：{exc!r}") from exc

    text = _extract_text(message)
    parsed = _parse_json(text)
    if not parsed:
        raise BizException(code=5001, msg="大模型没有返回可解析的 JSON")
    return parsed


def _extract_text(message) -> str:
    content = getattr(message, "content", None)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and item.get("text"):
                parts.append(str(item["text"]))
        return "".join(parts)
    return "" if content is None else str(content)


def _parse_json(text: str) -> dict:
    if not text or not text.strip():
        return {}
    candidates = [text.strip()]
    fenced = re.search(r"```(?:json)?\s*(.+?)\s*```", text, re.DOTALL)
    if fenced:
        candidates.append(fenced.group(1).strip())
    first, last = text.find("{"), text.rfind("}")
    if first != -1 and last > first:
        candidates.append(text[first:last + 1])
    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            continue
    return {}


# --------------------------------------------------------------------- 错题归类


CLASSIFY_PROMPT = """你是备考错题归类助手。请根据题目内容判断它属于哪个科目、涉及哪些知识点。

【题目内容】
{content}

【用户填写的科目（仅供参考，可能为空或不准）】
{subject}

【要求】
1. `subject` 从这些值里选一个：英语、数学、政治、专业课、行测、申论、其他；
2. `tags` 给 1~4 个**具体知识点**标签（如「定积分」「换元积分法」「长难句」「马原-唯物论」），
   不要给「数学」这种和科目重复的泛标签；
3. `difficulty` 估计难度：1 很易、3 中等、5 很难；
4. `reason` 用一句话说明判断依据；
5. 只输出 JSON，不要任何解释文字。

【输出 JSON】
{{"subject": "数学", "tags": ["定积分", "牛顿-莱布尼茨公式"], "difficulty": 3, "reason": "题目要求计算定积分"}}"""


@router.post("/classify", response_model=ClassifyResponse, summary="错题归类（AI 打标签）")
def classify(request: ClassifyRequest) -> ClassifyResponse:
    """给一道错题判定科目与知识点标签。

    由 Java 的 RocketMQ 消费者在收到 `wrong-classify` 消息后调用。
    返回的标签由 Java 侧写入 `wrong_question_tag` / `wrong_question_tag_rel`，
    唯一键 `uk_question_tag` 保证重复消费天然幂等。

    ⚠️ 端点是同步 `def`（不是 `async def`）：内部 `llm.invoke()` 是阻塞调用，
    在 async 端点里会占死事件循环。FastAPI 对 `def` 端点自动用线程池执行，
    多个归类请求才能并行（第 4 周实测：async def 下 6 个报告请求被串行成 5 分钟）。
    """
    prompt = CLASSIFY_PROMPT.format(
        content=request.content[:3000],
        subject=request.subject or "（未填写）",
    )
    parsed = _invoke_json(prompt)

    subject = str(parsed.get("subject") or request.subject or "其他").strip()
    raw_tags = parsed.get("tags")
    tags: list[str] = []
    if isinstance(raw_tags, list):
        for item in raw_tags:
            text = str(item).strip()
            # 过滤掉与科目重复的泛标签，以及过长/为空的脏数据
            if text and text != subject and 1 < len(text) <= 32 and text not in tags:
                tags.append(text)
    tags = tags[:4]

    difficulty = parsed.get("difficulty")
    try:
        difficulty = int(difficulty) if difficulty is not None else None
    except (TypeError, ValueError):
        difficulty = None
    if difficulty is not None:
        difficulty = max(1, min(5, difficulty))

    logger.info(
        "错题归类完成: wrongQuestionId={} -> subject={} tags={} difficulty={}",
        request.wrong_question_id,
        subject,
        tags,
        difficulty,
    )
    return ClassifyResponse(
        code=0,
        msg="ok",
        subject=subject,
        tags=tags,
        difficulty=difficulty,
        reason=str(parsed.get("reason") or "")[:200],
    )


# --------------------------------------------------------------------- 复盘报告


REPORT_PROMPT = """你是备考学习教练。请根据学生的本周学习数据，写一份有针对性、可执行的复盘报告。

【统计周期】
{period_start} ~ {period_end}

【本周数据】
{stats_text}

【要求】
1. `content` 是 markdown 正文，包含四段：**本周概览**、**做得好的地方**、**需要改进的地方**、**下周行动建议**；
2. 一定要**引用具体数字**（如"本周学习 320 分钟，日均 45 分钟"），不要写空话；
3. 如果有科目明显偏少（占比低于 15%）或错题积压（未掌握占比高），必须点出来；
4. `highlights` 给 3~5 条数据卡片要点（每条不超过 20 字，用于前端展示）；
5. `suggestions` 给 3~5 条下周行动建议（具体到"做什么、做多少"）；
6. 语气务实、鼓励但不说教；不要编造数据里没有的信息。

【输出 JSON】
{{"content": "markdown 正文", "highlights": ["要点1"], "suggestions": ["建议1"]}}"""


@router.post("/report", response_model=ReportResponse, summary="生成复盘报告")
def report(request: ReportRequest) -> ReportResponse:
    """根据 Java 聚合好的统计数据生成 markdown 复盘报告。

    数据聚合放在 Java（那里能直接读 study_record / plan_task / wrong_question），
    Python 只负责"把数字变成有洞察的文字"。

    同步 `def` 端点的原因同 `/agent/classify`：LLM 调用阻塞，必须交给线程池，
    否则周报批量生成时（周日 20:00 一次 6 个用户）会被事件循环串行化。
    """
    stats_text = _format_stats(request.stats)
    prompt = REPORT_PROMPT.format(
        period_start=request.period_start,
        period_end=request.period_end,
        stats_text=stats_text,
    )
    parsed = _invoke_json(prompt, temperature=0.4)

    content = str(parsed.get("content") or "").strip()
    if not content:
        raise BizException(code=5001, msg="大模型没有返回报告正文")

    highlights = _string_list(parsed.get("highlights"), limit=6)
    suggestions = _string_list(parsed.get("suggestions"), limit=6)

    logger.info(
        "复盘报告生成完成: userId={} 周期={} ~ {} | 正文 {} 字 | 要点 {} 条 | 建议 {} 条",
        request.user_id,
        request.period_start,
        request.period_end,
        len(content),
        len(highlights),
        len(suggestions),
    )
    return ReportResponse(
        code=0, msg="ok", content=content, highlights=highlights, suggestions=suggestions
    )


def _format_stats(stats: dict) -> str:
    """把统计 dict 打平成易读文本，避免把 JSON 原样丢给模型（模型会读错结构）。"""
    if not stats:
        return "（本周没有学习数据）"

    lines: list[str] = []

    def add(label: str, value) -> None:
        if value is None or value == "" or value == []:
            return
        lines.append(f"- {label}：{value}")

    add("总学习时长（分钟）", stats.get("totalMinutes"))
    add("日均学习时长（分钟）", stats.get("avgMinutes"))
    add("有学习记录的天数", stats.get("activeDays"))
    add("任务总数", stats.get("taskTotal"))
    add("完成任务数", stats.get("taskDone"))
    add("完成率（%）", stats.get("completionRate"))
    add("错题总数", stats.get("wrongTotal"))
    add("本周新增错题", stats.get("wrongNew"))
    add("已掌握错题", stats.get("wrongMastered"))

    subjects = stats.get("subjectDistribution") or stats.get("subjects")
    if isinstance(subjects, list) and subjects:
        pieces = []
        for item in subjects[:8]:
            if isinstance(item, dict):
                pieces.append(f"{item.get('subject')} {item.get('minutes')} 分钟({item.get('percent')}%)")
        if pieces:
            lines.append("- 科目分布：" + "；".join(pieces))

    daily = stats.get("dailyMinutes") or []
    if isinstance(daily, list) and daily:
        pieces = [
            f"{item.get('date')} {item.get('minutes')}分钟"
            for item in daily[:10]
            if isinstance(item, dict)
        ]
        if pieces:
            lines.append("- 每日时长：" + "；".join(pieces))

    mastery = stats.get("masteryDistribution") or []
    if isinstance(mastery, list) and mastery:
        pieces = [
            f"{item.get('mastery')} {item.get('count')} 题"
            for item in mastery
            if isinstance(item, dict)
        ]
        if pieces:
            lines.append("- 错题掌握度：" + "；".join(pieces))

    return "\n".join(lines) if lines else "（本周没有学习数据）"


def _string_list(value, limit: int) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for item in value:
        text = str(item).strip()
        if text:
            result.append(text)
        if len(result) >= limit:
            break
    return result


# --------------------------------------------------------------------- 规划 Agent


@router.post(
    "/plan",
    response_model=PlanGenerateResponse,
    summary="规划 Agent 生成学习计划（LangGraph 三节点）",
)
def plan(request: PlanGenerateRequest) -> PlanGenerateResponse:
    """用 LangGraph 编排的 Agent 生成逐日学习计划，并（可选）回调 Java 落库。

    图结构：信息补全 → 计划生成 → 校验调整（不合法回上一步，最多重试 2 次）。
    详见 `app/services/plan_agent.py` 的模块注释。

    同步 `def` 端点：规划是分钟级阻塞任务，跑在线程池里，
    生成期间问答/健康检查等其他请求不受影响。
    """
    result = generate_plan(
        exam_type=request.exam_type,
        target_date=request.target_date,
        daily_minutes=request.daily_minutes,
        subjects=request.subjects,
        level=request.level,
        title=request.title,
    )

    tasks = [
        GeneratedTask(
            taskDate=task["taskDate"],
            title=task["title"],
            content=task.get("content", ""),
            subject=task.get("subject", ""),
            estimatedMinutes=task.get("estimatedMinutes", 60),
        )
        for task in result["tasks"]
    ]

    plan_id: int | None = None
    warnings = list(result["warnings"])

    if request.persist:
        if not tasks:
            warnings.append("没有生成任何任务，已跳过落库")
        else:
            try:
                data = get_java_client().create_plan_with_tasks(
                    {
                        "userId": request.user_id,
                        "title": result["title"],
                        "examType": request.exam_type,
                        "targetDate": result["targetDate"],
                        "dailyMinutes": result["dailyMinutes"],
                        "source": "Agent生成",
                        "tasks": [
                            {
                                "taskDate": t.taskDate,
                                "title": t.title,
                                "content": t.content,
                                "subject": t.subject,
                                "estimatedMinutes": t.estimatedMinutes,
                            }
                            for t in tasks
                        ],
                    }
                )
                plan_id = int((data or {}).get("planId") or 0) or None
                logger.info(
                    "Agent 计划已落库: userId={} planId={} 任务数={}",
                    request.user_id,
                    plan_id,
                    len(tasks),
                )
            except Exception as exc:  # noqa: BLE001
                # 落库失败不丢弃生成结果：把任务原样返回给前端，同时明确告知未入库
                logger.error("Agent 计划落库失败: {!r}", exc)
                warnings.append(f"计划已生成但落库失败：{exc}")

    return PlanGenerateResponse(
        code=0,
        msg="ok" if result["valid"] else "计划已生成，但校验未完全通过（见 warnings）",
        title=result["title"],
        targetDate=result["targetDate"],
        dailyMinutes=result["dailyMinutes"],
        totalTasks=len(tasks),
        days=result["days"],
        tasks=tasks,
        warnings=warnings,
        retries=result["retries"],
        planId=plan_id,
        traceId=get_trace_id(),
    )


__all__ = ["router"]
