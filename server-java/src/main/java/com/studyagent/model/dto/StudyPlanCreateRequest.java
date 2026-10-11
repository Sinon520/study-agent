package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 创建/编辑学习计划请求。
 *
 * <h3>创建时任务由用户自己输入（2026-10-11 调整）</h3>
 * <p>最初版本在这里收"科目列表"，由后端按天数**均摊生成模板任务**
 * （如"完成 N 道专题练习"）。实际用起来这类套模板的任务和真实备考对不上，
 * 遂改为：创建时必须随请求提交**任务清单**（{@link #tasks}），逐条校验后落库；
 * 科目不再作为计划级字段参与生成 —— 每条任务自带 subject。
 * 想要"智能定制"的任务走「AI 生成计划」（LangGraph 规划 Agent），是另一条链路。</p>
 *
 * <p>注意：本 DTO 同时用于**编辑计划**（PUT），而编辑不涉及任务 ——
 * 所以 {@code tasks} 不在 DTO 上做"必填"（那会误伤编辑），
 * "创建时至少一条"由服务层校验（见 {@code StudyPlanServiceImpl.createPlan}）。</p>
 */
@Data
@Schema(description = "创建/编辑学习计划请求")
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
    @Schema(description = "每日可投入时长（分钟），作为计划的目标档位展示", example = "120")
    private Integer dailyMinutes;

    /** 任务清单：创建计划的核心内容（服务层校验至少一条）；每条任务的日期须落在计划起止范围内 */
    @Size(max = 100, message = "单次最多创建 100 条任务，更多任务可创建后分批补充")
    @Valid
    @Schema(description = "任务清单（创建时必填，至少一条）")
    private List<TaskItem> tasks;

    @Schema(description = "备注（可选）")
    private String remark;

    /**
     * 任务清单里的一条任务。
     */
    @Data
    @Schema(description = "自定义任务")
    public static class TaskItem {

        @Schema(description = "任务日期，不传则取计划开始日期", example = "2026-10-11")
        private LocalDate taskDate;

        @NotBlank(message = "任务内容不能为空")
        @Size(max = 128, message = "任务内容最长 128 个字符")
        @Schema(description = "任务内容", example = "背 50 个核心词 + 精读 1 篇真题阅读")
        private String title;

        @Size(max = 32, message = "科目名最长 32 个字符")
        @Schema(description = "科目（可选）", example = "英语")
        private String subject;

        @Min(value = 1, message = "预计时长至少 1 分钟")
        @Max(value = 1440, message = "预计时长不能超过 1440 分钟")
        @Schema(description = "预计时长（分钟），不传默认 60", example = "60")
        private Integer estimatedMinutes;
    }
}
