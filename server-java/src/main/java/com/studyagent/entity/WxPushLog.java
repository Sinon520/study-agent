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
 * 微信推送日志表（Day 25）。
 *
 * <p>每次推送尝试一条：success / failed（含微信错误码）/ skipped（终态跳过，
 * 如无 openid、未授权、用户拒收）。没有真实 AppID 期间 mock=1（模拟发送成功）。</p>
 */
@Data
@TableName("wx_push_log")
@Schema(description = "微信推送日志")
public class WxPushLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态常量 */
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SKIPPED = "skipped";

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "模板场景：reminder/review/report")
    private String templateKey;

    @Schema(description = "微信模板ID")
    private String templateId;

    @Schema(description = "点击跳转页面")
    private String page;

    @Schema(description = "success/failed/skipped")
    private String status;

    @Schema(description = "是否 mock 发送")
    private Integer mock;

    @Schema(description = "微信错误码或跳过原因")
    private String errorCode;

    @Schema(description = "错误/跳过说明")
    private String errorMsg;

    @Schema(description = "消息内容摘要")
    private String content;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
