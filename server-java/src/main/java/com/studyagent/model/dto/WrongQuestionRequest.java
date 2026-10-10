package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 错题新增/修改请求。
 */
@Data
@Schema(description = "错题请求")
public class WrongQuestionRequest {

    @NotBlank(message = "科目不能为空")
    @Size(max = 32, message = "科目最长 32 个字符")
    @Schema(description = "科目", example = "数学")
    private String subject;

    @Schema(description = "题型：单选/多选/填空/解答/论述", example = "解答")
    private String questionType;

    @NotBlank(message = "题干不能为空")
    @Size(max = 5000, message = "题干最长 5000 个字符")
    @Schema(description = "题干")
    private String content;

    @Size(max = 5000, message = "答案最长 5000 个字符")
    @Schema(description = "正确答案")
    private String answer;

    @Size(max = 8000, message = "解析最长 8000 个字符")
    @Schema(description = "解析")
    private String analysis;

    @Size(max = 512, message = "图片地址最长 512 个字符")
    @Schema(description = "题目图片 URL")
    private String imageUrl;

    @Min(value = 1, message = "难度最小为 1")
    @Max(value = 5, message = "难度最大为 5")
    @Schema(description = "难度 1~5", example = "3")
    private Integer difficulty;

    @Schema(description = "掌握度：未掌握/复习中/已掌握", example = "未掌握")
    private String mastery;

    @Schema(description = "来源：真题/模拟题/教材", example = "真题")
    private String source;

    @Schema(description = "标签名列表；传了就全量替换该错题的标签", example = "[\"定积分\",\"换元法\"]")
    private List<String> tags;
}
