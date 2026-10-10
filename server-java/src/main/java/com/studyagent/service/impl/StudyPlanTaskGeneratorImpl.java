package com.studyagent.service.impl;

import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.PlanTask;
import com.studyagent.entity.StudyPlan;
import com.studyagent.service.PlanTaskGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 均摊版任务生成器（第 2 周实现）。
 *
 * <h3>算法</h3>
 * <ol>
 *   <li>日期范围 = [startDate（默认今天）, targetDate]，按自然日逐天生成；</li>
 *   <li>每天的科目循环取 {@code subjects}，保证各科目交替出现而不是"前 30 天只背单词"；</li>
 *   <li>每日时长按科目数均分，余数补给第一个科目（避免出现 0 分钟任务）；</li>
 *   <li>任务标题按科目套模板，让生成的计划一眼能看懂。</li>
 * </ol>
 *
 * <h3>为什么要有上限</h3>
 * 目标日期如果填了"两年后"，会生成 700+ 条任务，插入慢、页面也刷不动。
 * 这里限制最多 {@link #MAX_DAYS} 天，超出直接拒绝并给出明确提示，
 * 让用户自己把目标日期填对，而不是悄悄截断生成半截计划。
 */
@Slf4j
@Service
public class StudyPlanTaskGeneratorImpl implements PlanTaskGenerator {

    /** 单次生成的最大天数，防止误填远期日期造出几千条任务 */
    public static final int MAX_DAYS = 400;

    /** 各科目默认任务模板，命中就用模板，未命中用通用模板 */
    private static final java.util.Map<String, String> SUBJECT_TEMPLATES = java.util.Map.of(
            "英语", "英语：背 %d 个核心词 + 精读 1 篇真题阅读",
            "数学", "数学：完成 %d 道专题练习 + 整理错题",
            "政治", "政治：学习 1 个章节 + 做 %d 道选择题",
            "专业课", "专业课：精读 1 节教材 + 整理知识框架",
            "行测", "行测：专项刷题 %d 道 + 限时训练",
            "申论", "申论：精读 1 篇范文 + 手写 1 道小题"
    );

    @Override
    public List<PlanTask> generateBySpread(StudyPlan plan, List<String> subjects) {
        if (plan == null || plan.getId() == null) {
            throw new BizException(Result.CODE_FAIL, "生成任务失败：计划尚未落库");
        }
        if (plan.getTargetDate() == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "生成任务失败：计划缺少目标日期");
        }

        LocalDate start = plan.getStartDate() != null ? plan.getStartDate() : LocalDate.now();
        LocalDate end = plan.getTargetDate();

        if (end.isBefore(start)) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "目标考试日期（" + end + "）不能早于计划开始日期（" + start + "）");
        }

        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "计划跨度 " + days + " 天，超过单次生成上限 " + MAX_DAYS + " 天。"
                            + "请确认目标考试日期是否正确，或拆成多个阶段计划。");
        }

        List<String> subjectList = (subjects == null || subjects.isEmpty())
                ? List.of("综合")
                : subjects;
        int dailyMinutes = plan.getDailyMinutes() != null && plan.getDailyMinutes() > 0
                ? plan.getDailyMinutes()
                : 120;

        // 每个科目的时长：向下取整，余数给第一个科目
        int perSubject = Math.max(dailyMinutes / subjectList.size(), 10);
        int remainder = Math.max(dailyMinutes - perSubject * subjectList.size(), 0);

        List<PlanTask> tasks = new ArrayList<>((int) days * subjectList.size());
        for (int dayOffset = 0; dayOffset < days; dayOffset++) {
            LocalDate date = start.plusDays(dayOffset);
            for (int i = 0; i < subjectList.size(); i++) {
                String subject = subjectList.get(i);
                int minutes = perSubject + (i == 0 ? remainder : 0);

                PlanTask task = new PlanTask();
                task.setPlanId(plan.getId());
                task.setUserId(plan.getUserId());
                task.setTaskDate(date);
                task.setSubject(subject);
                task.setEstimatedMinutes(minutes);
                task.setStatus("待办");
                task.setSort(i);
                task.setTitle(buildTitle(subject, minutes, dayOffset));
                task.setContent(buildContent(plan, subject, date));
                tasks.add(task);
            }
        }

        log.info("计划任务均摊生成完成 planId={} 天数={} 科目数={} 任务数={}",
                plan.getId(), days, subjectList.size(), tasks.size());
        return tasks;
    }

    private String buildTitle(String subject, int minutes, int dayOffset) {
        String template = SUBJECT_TEMPLATES.get(subject);
        // 天数越多，单词/题量的"日强度"越低；这里给一个随天数递减的目标值，至少 20
        int amount = Math.max(20, 100 - dayOffset / 7 * 5);
        if (template != null) {
            return String.format(template, amount);
        }
        return subject + "：完成今日计划任务（" + minutes + " 分钟）";
    }

    private String buildContent(StudyPlan plan, String subject, LocalDate date) {
        return "所属计划：" + plan.getTitle()
                + "；日期：" + date
                + "；本任务预计 " + plan.getDailyMinutes() + " 分钟档位内的 " + subject + " 部分。"
                + "完成后请回到计划页勾选，系统会自动累计学习时长。";
    }
}
