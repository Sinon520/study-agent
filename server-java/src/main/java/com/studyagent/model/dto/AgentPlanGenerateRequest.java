package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 「AI 生成计划」入口请求（前端 → Java）。
 *
 * <p>没有 userId 字段：身份取自 JWT，前端不能指定给谁生成。</p>
 */
@Data
@Schema(description = "AI 生成计划请求")
public class AgentPlanGenerateRequest {

    @Schema(description = "计划标题，不传由 Agent 生成")
    private String title;

    @NotBlank(message = "考试类型不能为空")
    @Schema(description = "考试类型：考研/考公", example = "考研")
    private String examType;

    @NotNull(message = "目标考试日期不能为空")
    @Schema(description = "目标考试日期", example = "2026-12-20")
    private LocalDate targetDate;

    @NotNull(message = "每日可投入时长不能为空")
    @Min(value = 10, message = "每日时长至少 10 分钟")
    @Max(value = 1440, message = "每日时长不能超过 1440 分钟")
    @Schema(description = "每日可投入时长（分钟）", example = "120")
    private Integer dailyMinutes;

    @Schema(description = "要覆盖的科目，逗号分隔", example = "英语,政治")
    private String subjects;

    @Schema(description = "基础水平描述（可选），会影响任务难度安排", example = "英语基础一般，政治零基础")
    private String level;

    @Schema(description = "是否落库；前端一般传 true（或留空）", example = "true")
    private Boolean persist = Boolean.TRUE;
}
