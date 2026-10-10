package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Agent 生成计划的落库请求（内部接口，Python 规划 Agent 回调）。
 *
 * <p>与 {@code StudyPlanCreateRequest} 的关键区别：**任务由 Agent 直接给出**，
 * 不需要走"按天数均摊"的生成器。所以这是独立的一套 DTO，
 * 免得把"是否重新生成任务"这种分支塞进人工创建的接口里。</p>
 */
@Data
@Schema(description = "Agent 计划落库请求（内部）")
public class AgentPlanCreateRequest {

    @NotNull(message = "userId 不能为空")
    @Schema(description = "所属用户")
    private Long userId;

    @NotBlank(message = "title 不能为空")
    @Schema(description = "计划标题")
    private String title;

    @NotBlank(message = "examType 不能为空")
    @Schema(description = "考试类型：考研/考公")
    private String examType;

    @NotNull(message = "targetDate 不能为空")
    @Schema(description = "目标考试日期")
    private LocalDate targetDate;

    @Schema(description = "开始日期，不传取今天")
    private LocalDate startDate;

    @NotNull(message = "dailyMinutes 不能为空")
    @Schema(description = "每日可投入时长（分钟）")
    private Integer dailyMinutes;

    @Schema(description = "来源，固定为 Agent生成")
    private String source = "Agent生成";

    @Schema(description = "Agent 生成的逐日任务")
    private List<Item> tasks;

    @Data
    @Schema(description = "Agent 生成的任务项")
    public static class Item {

        @NotBlank(message = "taskDate 不能为空")
        @Schema(description = "任务日期")
        private LocalDate taskDate;

        @NotBlank(message = "title 不能为空")
        @Schema(description = "任务标题")
        private String title;

        @Schema(description = "任务详情")
        private String content;

        @Schema(description = "科目")
        private String subject;

        @Schema(description = "预计时长（分钟）")
        private Integer estimatedMinutes;
    }
}
