package com.studyagent.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 微信订阅消息推送（topic: {@code wx-push}）。
 *
 * <p>消费者调用微信 {@code subscribeMessage.send}；{@code access_token}
 * 缓存到 Redis（2 小时过期前自动刷新，刷新本身也要防并发重复刷新）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WxPushMessage implements Serializable {

    private Long userId;

    /** 微信订阅消息模板 ID（小程序后台申请） */
    private String templateId;

    /** 模板数据：{@code {"thing1": {"value": "..."}, ...}} */
    private Map<String, Object> data;

    /** 产生这条消息的请求链路 ID */
    private String traceId;
}
