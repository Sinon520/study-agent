package com.studyagent.model.vo;

import com.studyagent.entity.WrongQuestion;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 错题视图（含标签）。
 */
@Data
@Schema(description = "错题")
public class WrongQuestionVO {

    @Schema(description = "错题 ID")
    private Long id;

    @Schema(description = "科目")
    private String subject;

    @Schema(description = "题型")
    private String questionType;

    @Schema(description = "题干")
    private String content;

    @Schema(description = "正确答案")
    private String answer;

    @Schema(description = "解析")
    private String analysis;

    @Schema(description = "题目图片 URL")
    private String imageUrl;

    @Schema(description = "难度 1~5")
    private Integer difficulty;

    @Schema(description = "掌握度：未掌握/复习中/已掌握")
    private String mastery;

    @Schema(description = "来源")
    private String source;

    @Schema(description = "标签名列表")
    private List<String> tags = new ArrayList<>();

    @Schema(description = "录入时间")
    private LocalDateTime createTime;

    public static WrongQuestionVO from(WrongQuestion question) {
        WrongQuestionVO vo = new WrongQuestionVO();
        vo.setId(question.getId());
        vo.setSubject(question.getSubject());
        vo.setQuestionType(question.getQuestionType());
        vo.setContent(question.getContent());
        vo.setAnswer(question.getAnswer());
        vo.setAnalysis(question.getAnalysis());
        vo.setImageUrl(question.getImageUrl());
        vo.setDifficulty(question.getDifficulty());
        vo.setMastery(question.getMastery());
        vo.setSource(question.getSource());
        vo.setCreateTime(question.getCreateTime());
        return vo;
    }
}
