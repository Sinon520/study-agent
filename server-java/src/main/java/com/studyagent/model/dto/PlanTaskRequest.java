package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 计划任务新增/修改请求。
 */
@Data
@Schema(description = "计划任务请求")
public class PlanTaskRequest {

    @NotNull(message = "所属计划不能为空")
    @Schema(description = "所属计划 ID")
    private Long planId;

    @NotNull(message = "任务日期不能为空")
    @Schema(description = "任务日期", example = "2026-10-11")
    private LocalDate taskDate;

    @NotBlank(message = "任务标题不能为空")
    @Size(max = 128, message = "任务标题最长 128 个字符")
    @Schema(description = "任务标题", example = "背 100 个考研核心词")
    private String title;

    @Size(max = 1000, message = "任务详情最长 1000 个字符")
    @Schema(description = "任务详情")
    private String content;

    @Schema(description = "科目", example = "英语")
    private String subject;

    @Schema(description = "预计时长（分钟）", example = "60")
    private Integer estimatedMinutes;

    @Schema(description = "同天排序", example = "0")
    private Integer sort;
}
