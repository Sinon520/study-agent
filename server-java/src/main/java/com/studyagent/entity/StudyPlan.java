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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习计划表
 */
@Data
@TableName("study_plan")
@Schema(description = "学习计划")
public class StudyPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属用户")
    private Long userId;

    @Schema(description = "计划标题")
    private String title;

    @Schema(description = "考试类型")
    @TableField("exam_type")
    private String examType;

    @Schema(description = "目标考试日期")
    private LocalDate targetDate;

    @Schema(description = "计划开始日期")
    private LocalDate startDate;

    @Schema(description = "每日可投入时间（分钟）")
    private Integer dailyMinutes;

    @Schema(description = "状态：进行中/已完成/已放弃")
    private String status;

    @Schema(description = "来源：手动/Agent生成")
    private String source;

    @TableLogic
    @Schema(description = "逻辑删除：0未删 1已删")
    private Integer deleted;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
