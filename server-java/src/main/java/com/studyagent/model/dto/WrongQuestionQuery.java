package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;

/**
 * 错题分页查询条件。
 *
 * <p>继承 {@link PageQuery} 复用分页参数与边界收敛。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "错题查询条件")
public class WrongQuestionQuery extends PageQuery {

    @Schema(description = "科目，精确匹配", example = "数学")
    private String subject;

    @Schema(description = "掌握度：未掌握/复习中/已掌握", example = "未掌握")
    private String mastery;

    @Schema(description = "题型", example = "解答")
    private String questionType;

    @Schema(description = "难度（精确匹配 1~5）", example = "3")
    private Integer difficulty;

    @Schema(description = "标签名，按标签筛选（多个标签取交集）", example = "[\"定积分\"]")
    private List<String> tags;

    @Schema(description = "关键字，模糊匹配题干/答案/解析", example = "定积分")
    private String keyword;

    @Schema(description = "录入时间范围-开始", example = "2026-10-01")
    private LocalDate startDate;

    @Schema(description = "录入时间范围-结束", example = "2026-10-31")
    private LocalDate endDate;

    @Schema(description = "排序字段：createTime / difficulty / mastery", example = "createTime")
    private String sortBy = "createTime";

    @Schema(description = "是否升序；默认 false（最新/最难在前）", example = "false")
    private Boolean asc = false;
}
