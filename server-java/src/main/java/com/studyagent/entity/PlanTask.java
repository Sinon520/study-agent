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
 * 计划任务表
 */
@Data
@TableName("plan_task")
@Schema(description = "计划任务")
public class PlanTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属计划")
    private Long planId;

    @Schema(description = "所属用户ID")
    private Long userId;

    @Schema(description = "任务日期")
    private LocalDate taskDate;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "任务描述/要求")
    private String content;

    @Schema(description = "科目")
    private String subject;

    @Schema(description = "预计时间（分钟）")
    private Integer estimatedMinutes;

    @Schema(description = "状态：待办/完成/跳过")
    private String status;

    /** sort 是 MySQL 关键字，加反引号避免语法错误 */
    @TableField("`sort`")
    @Schema(description = "同天排序")
    private Integer sort;

    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer deleted;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
