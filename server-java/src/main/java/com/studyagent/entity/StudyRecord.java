package com.studyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习记录表
 */
@Data
@TableName("study_record")
@Schema(description = "学习记录")
public class StudyRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "关联任务ID，手动记录时为空")
    private Long planTaskId;

    @Schema(description = "学习日期（统计维度）")
    private LocalDate studyDate;

    @Schema(description = "学习时长（分钟）")
    private Integer minutes;

    @Schema(description = "科目")
    private String subject;

    @Schema(description = "备注")
    private String note;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
