package com.studyagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.PlanTask;
import com.studyagent.entity.WrongQuestion;
import com.studyagent.mapper.StudyRecordMapper;
import com.studyagent.model.vo.ProgressVO;
import com.studyagent.service.PlanTaskService;
import com.studyagent.service.ProgressService;
import com.studyagent.service.WrongQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进度统计服务实现。
 *
 * <h3>统计口径（很重要，面试会问）</h3>
 * <ul>
 *   <li><b>学习时长</b>来自 {@code study_record}（由"勾选任务完成"产生），
 *       按 {@code study_date} 聚合，所以补勾昨天的任务会算在昨天；</li>
 *   <li><b>完成率</b>的分母是"当天任务总数 − 跳过数"，被跳过的任务不该拉低完成率；</li>
 *   <li><b>连续学习天数</b>从今天（或昨天）往前数，允许"今天还没学"不算断，
 *       否则用户早上打开 App 就会看到 streak 归零，体验很差；</li>
 *   <li>日期序列要<b>补零</b>：没学习的日期必须在折线上显示为 0，
 *       否则折线会把两个不相邻的日期连起来，视觉上完全失真。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressServiceImpl implements ProgressService {

    private static final int MAX_DAYS = 365;
    private static final int DEFAULT_DAYS = 30;

    private final StudyRecordMapper studyRecordMapper;
    private final PlanTaskService planTaskService;
    private final WrongQuestionService wrongQuestionService;

    @Override
    public ProgressVO.ProgressDashboard recent(Long userId, int days) {
        int span = days < 1 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(span - 1L);
        return dashboard(userId, start, end);
    }

    @Override
    public ProgressVO.ProgressDashboard dashboard(Long userId, LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "统计起止日期不能为空");
        }
        if (end.isBefore(start)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "统计结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            throw new BizException(Result.CODE_BAD_REQUEST, "统计区间最长为 " + MAX_DAYS + " 天");
        }

        ProgressVO.ProgressDashboard dashboard = new ProgressVO.ProgressDashboard();
        dashboard.setDailyMinutes(buildDailyMinutes(userId, start, end));
        dashboard.setCompletionTrend(buildCompletionTrend(userId, start, end));
        dashboard.setSubjectDistribution(buildSubjectDistribution(userId, start, end));
        dashboard.setMasteryDistribution(buildMasteryDistribution(userId));
        dashboard.setOverview(buildOverview(userId, start, end, dashboard));
        return dashboard;
    }

    // ------------------------------------------------------------ 每日时长

    private List<ProgressVO.DailyMinutes> buildDailyMinutes(Long userId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> rows = studyRecordMapper.sumMinutesByDate(userId, start, end);

        // 先落成 Map，再按日期序列铺开（补零），保证折线 X 轴连续
        Map<LocalDate, ProgressVO.DailyMinutes> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = toLocalDate(row.get("studyDate"));
            if (date == null) {
                continue;
            }
            ProgressVO.DailyMinutes item = new ProgressVO.DailyMinutes();
            item.setDate(date);
            item.setMinutes(toInt(row.get("minutes")));
            item.setTaskDone(0);
            byDate.put(date, item);
        }

        // 用任务完成数补上"当天完成了几个任务"（study_record 的行数不等于任务数，一个任务一条）
        for (Map.Entry<LocalDate, int[]> entry : countTasksByDate(userId, start, end).entrySet()) {
            ProgressVO.DailyMinutes item = byDate.get(entry.getKey());
            if (item != null) {
                item.setTaskDone(entry.getValue()[1]);
            }
        }

        List<ProgressVO.DailyMinutes> result = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ProgressVO.DailyMinutes item = byDate.get(date);
            if (item == null) {
                item = new ProgressVO.DailyMinutes();
                item.setDate(date);
                item.setMinutes(0);
                item.setTaskDone(0);
            }
            result.add(item);
        }
        return result;
    }

    // ------------------------------------------------------------ 完成率

    private List<ProgressVO.CompletionPoint> buildCompletionTrend(Long userId, LocalDate start, LocalDate end) {
        Map<LocalDate, int[]> tasksByDate = countTasksByDate(userId, start, end);

        List<ProgressVO.CompletionPoint> points = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            int[] counts = tasksByDate.getOrDefault(date, new int[]{0, 0, 0});
            int total = counts[0];
            int done = counts[1];
            int skipped = counts[2];

            ProgressVO.CompletionPoint point = new ProgressVO.CompletionPoint();
            point.setDate(date);
            point.setTotal(total);
            point.setDone(done);
            // 分母扣掉跳过；当天没任务时完成率按 0（而不是 100%）
            int denominator = total - skipped;
            point.setRate(denominator <= 0 ? 0.0 : Math.round(done * 1000.0 / denominator) / 10.0);
            points.add(point);
        }
        return points;
    }

    /**
     * 一次查询把区间内所有任务取回来，在内存里按日期统计。
     *
     * @return date -> [总任务数, 完成数, 跳过数]
     */
    private Map<LocalDate, int[]> countTasksByDate(Long userId, LocalDate start, LocalDate end) {
        List<PlanTask> tasks = planTaskService.list(Wrappers.<PlanTask>lambdaQuery()
                .eq(PlanTask::getUserId, userId)
                .between(PlanTask::getTaskDate, start, end)
                .select(PlanTask::getTaskDate, PlanTask::getStatus));

        Map<LocalDate, int[]> map = new LinkedHashMap<>();
        for (PlanTask task : tasks) {
            int[] counts = map.computeIfAbsent(task.getTaskDate(), k -> new int[3]);
            counts[0]++;
            if ("完成".equals(task.getStatus())) {
                counts[1]++;
            } else if ("跳过".equals(task.getStatus())) {
                counts[2]++;
            }
        }
        return map;
    }

    // ------------------------------------------------------------ 科目分布

    private List<ProgressVO.SubjectItem> buildSubjectDistribution(Long userId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> rows = studyRecordMapper.sumMinutesBySubject(userId, start, end);

        int total = 0;
        for (Map<String, Object> row : rows) {
            total += toInt(row.get("minutes"));
        }

        List<ProgressVO.SubjectItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int minutes = toInt(row.get("minutes"));
            ProgressVO.SubjectItem item = new ProgressVO.SubjectItem();
            item.setSubject(String.valueOf(row.get("subject")));
            item.setMinutes(minutes);
            item.setPercent(total <= 0 ? 0.0 : Math.round(minutes * 1000.0 / total) / 10.0);
            items.add(item);
        }
        return items;
    }

    // ------------------------------------------------------------ 掌握度分布

    private List<ProgressVO.MasteryItem> buildMasteryDistribution(Long userId) {
        List<String> all = List.of("未掌握", "复习中", "已掌握");
        List<ProgressVO.MasteryItem> items = new ArrayList<>();
        for (String mastery : all) {
            long count = wrongQuestionService.count(Wrappers.<WrongQuestion>lambdaQuery()
                    .eq(WrongQuestion::getUserId, userId)
                    .eq(WrongQuestion::getMastery, mastery));
            ProgressVO.MasteryItem item = new ProgressVO.MasteryItem();
            item.setMastery(mastery);
            item.setCount((int) count);
            items.add(item);
        }
        return items;
    }

    // ------------------------------------------------------------ 概览

    private ProgressVO.Overview buildOverview(Long userId, LocalDate start, LocalDate end,
                                              ProgressVO.ProgressDashboard dashboard) {
        ProgressVO.Overview overview = new ProgressVO.Overview();
        overview.setPeriodStart(start);
        overview.setPeriodEnd(end);

        Integer totalMinutes = studyRecordMapper.sumMinutes(userId, start, end);
        int minutes = totalMinutes == null ? 0 : totalMinutes;
        overview.setTotalMinutes(minutes);

        long spanDays = ChronoUnit.DAYS.between(start, end) + 1;
        overview.setAvgMinutes(spanDays <= 0 ? 0 : (int) (minutes / spanDays));

        int activeDays = 0;
        int taskTotal = 0;
        int taskDone = 0;
        for (ProgressVO.DailyMinutes item : dashboard.getDailyMinutes()) {
            if (item.getMinutes() != null && item.getMinutes() > 0) {
                activeDays++;
            }
        }
        for (ProgressVO.CompletionPoint point : dashboard.getCompletionTrend()) {
            taskTotal += point.getTotal() == null ? 0 : point.getTotal();
            taskDone += point.getDone() == null ? 0 : point.getDone();
        }
        overview.setActiveDays(activeDays);
        overview.setTaskTotal(taskTotal);
        overview.setTaskDone(taskDone);
        overview.setCompletionRate(taskTotal <= 0 ? 0.0 : Math.round(taskDone * 1000.0 / taskTotal) / 10.0);

        long wrongTotal = wrongQuestionService.count(Wrappers.<WrongQuestion>lambdaQuery()
                .eq(WrongQuestion::getUserId, userId));
        long wrongMastered = wrongQuestionService.count(Wrappers.<WrongQuestion>lambdaQuery()
                .eq(WrongQuestion::getUserId, userId)
                .eq(WrongQuestion::getMastery, "已掌握"));
        overview.setWrongTotal((int) wrongTotal);
        overview.setWrongMastered((int) wrongMastered);

        overview.setStreakDays(calcStreakDays(userId));
        return overview;
    }

    /**
     * 连续学习天数。
     *
     * <p>规则：从今天往前数；如果今天还没学习，则从昨天开始算
     * （避免用户早上一打开 App 就看到 streak 断掉）。</p>
     */
    private int calcStreakDays(Long userId) {
        List<LocalDate> dates = studyRecordMapper.listStudyDates(userId);
        if (dates == null || dates.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate first = dates.get(0);

        LocalDate cursor;
        if (first.equals(today)) {
            cursor = today;
        } else if (first.equals(today.minusDays(1))) {
            cursor = today.minusDays(1);
        } else {
            return 0;
        }

        int streak = 0;
        for (LocalDate date : dates) {
            if (date == null) {
                continue;
            }
            if (date.equals(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
            } else if (date.isBefore(cursor)) {
                // dates 已倒序去重，遇到缺口即结束
                break;
            }
        }
        return streak;
    }

    // ------------------------------------------------------------ 工具

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
