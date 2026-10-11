package com.studyagent.mq.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 微信订阅消息推送（topic: {@code wx-push}）。
 *
 * <p>消费者（{@code WxPushConsumer}）负责：查 openid → 校验订阅授权与剩余次数
 * → 取 access_token（Redis 缓存）→ 调微信 {@code subscribeMessage.send}
 * → 扣减次数 + 写推送日志。</p>
 *
 * <p>{@code templateKey} 是"场景"而不是模板 ID：模板 ID 属于配置（申请后可能换），
 * 消息里只带业务语义，消费端按 {@code wx.templates.*} 解析成当前模板 ID。
 * 这样换模板不用重发历史消息，也避免占位 ID 散落在业务代码里。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WxPushMessage implements Serializable {

    private Long userId;

    /** 模板场景 key：reminder（学习提醒）/ review（错题复习）/ report（复盘报告） */
    private String templateKey;

    /**
     * 兼容字段：Day 24 的消息直接带模板 ID（彼时还没有场景配置）。
     * 新代码不再写入；消费端只认 {@code templateKey}，缺 key 的历史消息直接丢弃
     * （模板 ID 属于可换的运行时配置，不参与业务解析）。
     */
    private String templateId;

    /** 点击订阅消息跳转的小程序页面路径，如 pages/plan/plan（可为空） */
    private String page;

    /** 模板数据：{@code {"thing1": {"value": "..."}, ...}} */
    private Map<String, Object> data;

    /** 产生这条消息的请求链路 ID */
    private String traceId;
}
