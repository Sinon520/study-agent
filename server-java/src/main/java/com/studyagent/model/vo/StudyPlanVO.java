package com.studyagent.model.vo;

import com.studyagent.entity.StudyPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习计划视图（含任务完成统计）。
 *
 * <p>列表页需要"这个计划进行到哪了"，所以把统计字段直接挂在 VO 上，
 * 前端不用再为每个计划单独发一次统计请求（N+1 请求问题）。</p>
 */
@Data
@Schema(description = "学习计划")
public class StudyPlanVO {

    @Schema(description = "计划 ID")
    private Long id;

    @Schema(description = "计划标题")
    private String title;

    @Schema(description = "考试类型")
    private String examType;

    @Schema(description = "目标考试日期")
    private LocalDate targetDate;

    @Schema(description = "计划开始日期")
    private LocalDate startDate;

    @Schema(description = "每日可投入时长（分钟）")
    private Integer dailyMinutes;

    @Schema(description = "状态：进行中/已完成/已放弃")
    private String status;

    @Schema(description = "来源：手动/Agent生成")
    private String source;

    @Schema(description = "距离目标考试还有多少天")
    private Long daysLeft;

    @Schema(description = "任务总数")
    private Integer taskTotal;

    @Schema(description = "已完成任务数")
    private Integer taskDone;

    @Schema(description = "已跳过任务数")
    private Integer taskSkipped;

    @Schema(description = "完成率百分比（0~100，保留 1 位小数）")
    private Double progress;

    @Schema(description = "计划总预计时长（分钟）")
    private Integer totalMinutes;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /** 基础字段映射；统计字段由 Service 层补齐 */
    public static StudyPlanVO from(StudyPlan plan) {
        StudyPlanVO vo = new StudyPlanVO();
        vo.setId(plan.getId());
        vo.setTitle(plan.getTitle());
        vo.setExamType(plan.getExamType());
        vo.setTargetDate(plan.getTargetDate());
        vo.setStartDate(plan.getStartDate());
        vo.setDailyMinutes(plan.getDailyMinutes());
        vo.setStatus(plan.getStatus());
        vo.setSource(plan.getSource());
        vo.setCreateTime(plan.getCreateTime());
        if (plan.getTargetDate() != null) {
            vo.setDaysLeft(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), plan.getTargetDate()));
        }
        return vo;
    }
}
