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
 * 错题标签表（用户私有）
 */
@Data
@TableName("wrong_question_tag")
@Schema(description = "错题标签")
public class WrongQuestionTag implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属用户（标签是私有的）")
    private Long userId;

    @Schema(description = "标签名称")
    private String name;

    @Schema(description = "标签分类")
    private String category;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
