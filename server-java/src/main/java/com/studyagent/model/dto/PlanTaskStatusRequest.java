package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 计划任务状态变更请求。
 */
@Data
@Schema(description = "任务状态变更请求")
public class PlanTaskStatusRequest {

    @NotNull(message = "任务 ID 不能为空")
    @Schema(description = "任务 ID")
    private Long taskId;

    @NotBlank(message = "状态不能为空")
    @Schema(description = "目标状态：待办/完成/跳过", example = "完成")
    private String status;

    @Schema(description = "完成时记录的实际学习时长（分钟）；不传则取任务的预计时长", example = "75")
    private Integer actualMinutes;

    @Schema(description = "学习备注")
    private String note;
}
