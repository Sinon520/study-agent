package com.studyagent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.entity.WrongQuestion;
import com.studyagent.model.vo.ProgressVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 复盘报告统计聚合器（Day 24）。
 *
 * <p>为什么不让 Python 去查数据：Java 持有库连接与用户上下文，
 * 统计口径（时长怎么算、完成率怎么算）与进度页完全一致 —— 直接复用
 * {@link ProgressService#dashboard}，报告里的数字和用户平时在进度页看到的一致，
 * 不会出现"两个页面两个数"的信任问题。Python 只做"数字 → 文字"。</p>
 *
 * <p>输出 key 采用小驼峰，与 Python {@code /agent/report} 的
 * {@code _format_stats} 约定对齐（改动任何一侧都要同步另一侧）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAggregator {

    private final ProgressService progressService;
    private final WrongQuestionService wrongQuestionService;

    /**
     * 聚合指定用户、指定周期的学习统计数据。
     *
     * @return 可直接序列化为 JSON 的 LinkedHashMap（保持字段顺序，便于人工核对）
     */
    public Map<String, Object> aggregate(Long userId, LocalDate start, LocalDate end) {
        ProgressVO.ProgressDashboard dashboard = progressService.dashboard(userId, start, end);
        ProgressVO.Overview overview = dashboard.getOverview();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalMinutes", overview.getTotalMinutes());
        stats.put("avgMinutes", overview.getAvgMinutes());
        stats.put("activeDays", overview.getActiveDays());
        stats.put("taskTotal", overview.getTaskTotal());
        stats.put("taskDone", overview.getTaskDone());
        stats.put("completionRate", overview.getCompletionRate());
        stats.put("wrongTotal", overview.getWrongTotal());
        stats.put("wrongNew", countNewWrong(userId, start, end));
        stats.put("wrongMastered", overview.getWrongMastered());
        stats.put("subjectDistribution", dashboard.getSubjectDistribution());
        stats.put("dailyMinutes", dashboard.getDailyMinutes());
        stats.put("masteryDistribution", dashboard.getMasteryDistribution());

        log.debug("周报统计聚合完成 userId={} 周期={}~{} stats={}", userId, start, end, stats.keySet());
        return stats;
    }

    /** 周期内新增错题数（wrong_question.create_time 是 datetime，按 [start 00:00, end+1 00:00) 半开区间） */
    private long countNewWrong(Long userId, LocalDate start, LocalDate end) {
        return wrongQuestionService.count(Wrappers.<WrongQuestion>lambdaQuery()
                .eq(WrongQuestion::getUserId, userId)
                .ge(WrongQuestion::getCreateTime, start.atStartOfDay())
                .lt(WrongQuestion::getCreateTime, end.plusDays(1).atStartOfDay()));
    }
}
