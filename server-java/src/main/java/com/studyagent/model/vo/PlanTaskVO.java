package com.studyagent.model.vo;

import com.studyagent.entity.PlanTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计划任务视图。
 */
@Data
@Schema(description = "计划任务")
public class PlanTaskVO {

    @Schema(description = "任务 ID")
    private Long id;

    @Schema(description = "所属计划 ID")
    private Long planId;

    @Schema(description = "所属计划标题（列表页展示用，避免前端再查一次）")
    private String planTitle;

    @Schema(description = "任务日期")
    private LocalDate taskDate;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "任务详情")
    private String content;

    @Schema(description = "科目")
    private String subject;

    @Schema(description = "预计时长（分钟）")
    private Integer estimatedMinutes;

    @Schema(description = "状态：待办/完成/跳过")
    private String status;

    @Schema(description = "同天排序")
    private Integer sort;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    public static PlanTaskVO from(PlanTask task) {
        PlanTaskVO vo = new PlanTaskVO();
        vo.setId(task.getId());
        vo.setPlanId(task.getPlanId());
        vo.setTaskDate(task.getTaskDate());
        vo.setTitle(task.getTitle());
        vo.setContent(task.getContent());
        vo.setSubject(task.getSubject());
        vo.setEstimatedMinutes(task.getEstimatedMinutes());
        vo.setStatus(task.getStatus());
        vo.setSort(task.getSort());
        vo.setFinishTime(task.getFinishTime());
        return vo;
    }
}
