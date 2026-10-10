package com.studyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 错题-标签关联表
 */
@Data
@TableName("wrong_question_tag_rel")
@Schema(description = "错题标签关联")
public class WrongQuestionTagRel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "错题ID")
    private Long wrongQuestionId;

    @Schema(description = "标签ID")
    private Long tagId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
