package com.studyagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 进度统计视图集合。
 *
 * <p>进度页要 4 张 ECharts 图 + 1 组概览卡片。如果每张图一个接口就是 5 次往返，
 * 弱网与小程序下体验很差，所以做成一个聚合接口返回。</p>
 *
 * <p>所有内部类都是 {@code public static}，业务代码可以直接
 * {@code import com.studyagent.model.vo.ProgressVO.DailyMinutes} 使用。</p>
 */
public final class ProgressVO {

    private ProgressVO() {
    }

    /** 折线图：近 N 天每日学习时长 */
    @Data
    @Schema(description = "每日学习时长")
    public static class DailyMinutes {
        @Schema(description = "日期")
        private LocalDate date;
        @Schema(description = "学习时长（分钟）")
        private Integer minutes;
        @Schema(description = "当天完成的任务数")
        private Integer taskDone;
    }

    /** 折线图：计划完成率趋势 */
    @Data
    @Schema(description = "完成率趋势点")
    public static class CompletionPoint {
        @Schema(description = "日期")
        private LocalDate date;
        @Schema(description = "当天应完成任务数")
        private Integer total;
        @Schema(description = "当天已完成任务数")
        private Integer done;
        @Schema(description = "完成率百分比 0~100")
        private Double rate;
    }

    /** 饼图：科目分布 */
    @Data
    @Schema(description = "科目分布项")
    public static class SubjectItem {
        @Schema(description = "科目名")
        private String subject;
        @Schema(description = "学习时长（分钟）")
        private Integer minutes;
        @Schema(description = "占比百分比 0~100")
        private Double percent;
    }

    /** 柱状图：错题掌握度分布 */
    @Data
    @Schema(description = "掌握度分布项")
    public static class MasteryItem {
        @Schema(description = "掌握度：未掌握/复习中/已掌握")
        private String mastery;
        @Schema(description = "错题数量")
        private Integer count;
    }

    /** 概览卡片 */
    @Data
    @Schema(description = "进度总览")
    public static class Overview {
        @Schema(description = "统计区间开始日期")
        private LocalDate periodStart;
        @Schema(description = "统计区间结束日期")
        private LocalDate periodEnd;
        @Schema(description = "区间内总学习时长（分钟）")
        private Integer totalMinutes;
        @Schema(description = "区间内日均学习时长（分钟）")
        private Integer avgMinutes;
        @Schema(description = "有学习记录的天数")
        private Integer activeDays;
        @Schema(description = "任务总数")
        private Integer taskTotal;
        @Schema(description = "完成任务数")
        private Integer taskDone;
        @Schema(description = "总体完成率百分比")
        private Double completionRate;
        @Schema(description = "错题总数")
        private Integer wrongTotal;
        @Schema(description = "已掌握错题数")
        private Integer wrongMastered;
        @Schema(description = "连续学习天数")
        private Integer streakDays;
    }

    /** 进度页完整数据 */
    @Data
    @Schema(description = "进度页数据")
    public static class ProgressDashboard {
        @Schema(description = "总览卡片")
        private Overview overview;
        @Schema(description = "每日学习时长（折线）")
        private List<DailyMinutes> dailyMinutes;
        @Schema(description = "完成率趋势（折线）")
        private List<CompletionPoint> completionTrend;
        @Schema(description = "科目分布（饼图）")
        private List<SubjectItem> subjectDistribution;
        @Schema(description = "错题掌握度分布（柱状）")
        private List<MasteryItem> masteryDistribution;
    }
}
