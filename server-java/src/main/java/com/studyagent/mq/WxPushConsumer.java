package com.studyagent.mq;

import com.studyagent.common.util.TraceIdConstants;
import com.studyagent.mq.message.WxPushMessage;
import com.studyagent.service.PushResult;
import com.studyagent.service.WxPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 微信订阅消息推送消费者（Day 25）：收到 {@code wx-push} 消息 →
 * 查用户 openid → 校验订阅授权与剩余额度 → 取 access_token（Redis 缓存）→
 * 调微信 {@code subscribeMessage.send} → 写 {@code wx_push_log}。
 *
 * <h3>失败语义（关键）</h3>
 * <ul>
 *   <li><b>可重试</b>：网络异常、微信 5xx、非终态错误码 → 抛异常 → MQ 按重试策略重投；</li>
 *   <li><b>终态放行</b>：无 openid / 未授权（含用户拒收 43101）/ 额度不足 →
 *       直接消费成功并记 {@code skipped}，重试没有意义，只会浪费重试配额、刷日志。</li>
 * </ul>
 *
 * <p>为什么"未授权"要放行而不是报错：定时任务给一批用户发消息时，
 * 必然有相当比例的用户没授权；如果每个都抛异常，整批就永远消费不完，
 * 死信队列里塞满"用户不愿意收"的噪音，真正需要重试的故障反而被淹没。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.WX_PUSH,
        consumerGroup = MqTopics.GROUP_WX_PUSH
)
public class WxPushConsumer implements RocketMQListener<WxPushMessage> {

    private final WxPushService wxPushService;

    @Override
    public void onMessage(WxPushMessage message) {
        String previous = MDC.get(TraceIdConstants.MDC_KEY);
        MDC.put(TraceIdConstants.MDC_KEY,
                message.getTraceId() == null ? "mq-no-trace" : message.getTraceId());
        try {
            if (message.getUserId() == null) {
                log.warn("推送消息缺少 userId，直接丢弃: {}", message);
                return;
            }
            if (!org.springframework.util.StringUtils.hasText(message.getTemplateKey())) {
                log.warn("推送消息缺少 templateKey，直接丢弃: {}", message);
                return;
            }

            long started = System.currentTimeMillis();
            PushResult result = wxPushService.push(
                    message.getUserId(),
                    message.getTemplateKey(),
                    message.getPage(),
                    message.getData());

            log.info("微信订阅消息处理完成 userId={} templateKey={} sent={} status={} detail={} 耗时={}ms",
                    message.getUserId(), message.getTemplateKey(), result.sent(),
                    result.status(), result.detail(), System.currentTimeMillis() - started);
        } finally {
            // 消费线程会被复用，恢复现场避免污染下一条消息的日志
            if (previous == null) {
                MDC.remove(TraceIdConstants.MDC_KEY);
            } else {
                MDC.put(TraceIdConstants.MDC_KEY, previous);
            }
        }
    }
}
