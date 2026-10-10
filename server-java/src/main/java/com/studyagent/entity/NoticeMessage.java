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
 * 站内通知表
 */
@Data
@TableName("notice_message")
@Schema(description = "站内通知")
public class NoticeMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "接收用户")
    private Long userId;

    @Schema(description = "类型：提醒/复习/报告")
    private String type;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "渠道：PC/小程序")
    private String channel;

    @Schema(description = "已读：0未读 1已读")
    private Integer readFlag;

    @Schema(description = "业务ID，如报告ID，用于跳转")
    private Long bizId;

    @Schema(description = "发送时间")
    private LocalDateTime sendTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
