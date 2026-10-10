package com.studyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 错题表
 */
@Data
@TableName("wrong_question")
@Schema(description = "错题")
public class WrongQuestion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属用户")
    private Long userId;

    @Schema(description = "科目")
    private String subject;

    @Schema(description = "题型：单选/多选/填空/解答/其他")
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

    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer deleted;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
