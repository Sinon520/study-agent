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
 * 微信订阅消息授权表（Day 25）。
 *
 * <p>WeChat 语义：一次订阅授权 = 一次发送额度（accept_count）；
 * 发送成功后扣减，扣到 0 后需要用户再次授权才能继续发。
 * 用户点"拒绝"时只更新 status，不回收已有额度。</p>
 */
@Data
@TableName("wx_subscribe")
@Schema(description = "微信订阅消息授权")
public class WxSubscribe implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "模板场景：reminder/review/report")
    private String templateKey;

    @Schema(description = "微信模板ID")
    private String templateId;

    @Schema(description = "最近一次授权结果：1同意 0拒绝/失效")
    private Integer status;

    @Schema(description = "剩余可发送次数")
    private Integer acceptCount;

    @Schema(description = "最近一次授权时间")
    private LocalDateTime authorizeTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
