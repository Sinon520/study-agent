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
 * 复盘报告表
 */
@Data
@TableName("review_report")
@Schema(description = "复盘报告")
public class ReviewReport implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "类型：周报/月报")
    private String reportType;

    @Schema(description = "统计周期开始")
    private LocalDate periodStart;

    @Schema(description = "统计周期结束")
    private LocalDate periodEnd;

    @Schema(description = "markdown 正文")
    private String content;

    /** MySQL json 列，JDBC 以字符串读取 */
    @Schema(description = "统计数据 JSON：完成率/时长/错题趋势")
    private String statsJson;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
