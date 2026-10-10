package com.studyagent.service;

import com.studyagent.entity.PlanTask;
import com.studyagent.entity.StudyPlan;

import java.util.List;

/**
 * 计划任务生成器。
 *
 * <p>单独抽出来是因为这里会出现**两个实现**：</p>
 * <ul>
 *   <li>本周的"均摊版" {@link #generateBySpread}：按天数把每日时长均摊，规则简单可预期；</li>
 *   <li>第 3 周的"Agent 版"：调 Python 的 LangGraph 规划 Agent，用 LLM 生成结构化任务。</li>
 * </ul>
 * <p>接口一致，所以 {@code StudyPlanService} 只需要换注入的实现，
 * 上层的"创建计划 → 落库"流程完全不用改。</p>
 */
public interface PlanTaskGenerator {

    /**
     * 按天数均摊生成任务。
     *
     * @param plan     已确定 id 与日期的计划
     * @param subjects 科目列表，为空时按"综合"处理
     * @return 待入库的任务列表（未设置 id，userId 已填好）
     */
    List<PlanTask> generateBySpread(StudyPlan plan, List<String> subjects);
}
