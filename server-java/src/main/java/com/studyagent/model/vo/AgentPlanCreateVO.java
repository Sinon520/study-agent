package com.studyagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 计划落库结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 计划落库结果")
public class AgentPlanCreateVO {

    @Schema(description = "新建计划 ID")
    private Long planId;

    @Schema(description = "落库任务数")
    private Integer taskCount;
}
