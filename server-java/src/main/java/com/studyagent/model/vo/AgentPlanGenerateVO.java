package com.studyagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「AI 生成计划」结果（前端展示生成过程信息）。
 */
@Data
@Schema(description = "AI 生成计划结果")
public class AgentPlanGenerateVO {

    @Schema(description = "落库后的计划 ID；persist=false 时为空")
    private Long planId;

    @Schema(description = "计划标题")
    private String title;

    @Schema(description = "目标考试日期")
    private String targetDate;

    @Schema(description = "每日时长（分钟）")
    private Integer dailyMinutes;

    @Schema(description = "覆盖天数")
    private Integer days;

    @Schema(description = "任务总数")
    private Integer totalTasks;

    @Schema(description = "落库任务数")
    private Integer savedTasks;

    @Schema(description = "校验重试次数（Agent 自检重试，越多说明模型第一次输出质量越差）")
    private Integer retries;

    @Schema(description = "过程提示（校验未通过的原因、降级说明等）")
    private List<String> warnings = new ArrayList<>();

    @Schema(description = "是否通过全部校验")
    private Boolean valid;
}
