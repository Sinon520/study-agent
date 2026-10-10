package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 创建学习计划请求。
 */
@Data
@Schema(description = "创建学习计划请求")
public class StudyPlanCreateRequest {

    @NotBlank(message = "计划标题不能为空")
    @Size(max = 128, message = "计划标题最长 128 个字符")
    @Schema(description = "计划标题", example = "3 个月考研英语二冲刺")
    private String title;

    @NotBlank(message = "考试类型不能为空")
    @Schema(description = "考试类型：考研/考公", example = "考研")
    private String examType;

    @NotNull(message = "目标考试日期不能为空")
    @Schema(description = "目标考试日期", example = "2027-12-20")
    private LocalDate targetDate;

    @Schema(description = "计划开始日期，不传则默认今天", example = "2026-10-11")
    private LocalDate startDate;

    @NotNull(message = "每日可投入时长不能为空")
    @Min(value = 10, message = "每日时长至少 10 分钟")
    @Max(value = 1440, message = "每日时长不能超过 1440 分钟")
    @Schema(description = "每日可投入时长（分钟）", example = "120")
    private Integer dailyMinutes;

    @Schema(description = "计划包含的科目，逗号分隔；用于按科目拆分每日任务", example = "英语,政治")
    private String subjects;

    @Schema(description = "计划总任务量提示（可选，人工创建的备注）")
    private String remark;
}
