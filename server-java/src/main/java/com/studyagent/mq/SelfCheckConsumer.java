package com.studyagent.mq;

import com.studyagent.common.util.TraceIdConstants;
import com.studyagent.mq.message.SelfCheckMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 自检消息消费者：收到后登记回执，供 {@code /internal/mq/selfcheck} 完成环回。
 *
 * <p>它同时是"消费端 traceId 续接"的样板：消费线程把消息体里的 traceId
 * 重新放进 MDC，消费期间的日志就能和产生消息的那次 HTTP 请求对上；
 * 处理完恢复现场（消费线程是复用的，不清理会污染下一条消息的日志）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.SELF_CHECK,
        consumerGroup = MqTopics.GROUP_SELF_CHECK
)
public class SelfCheckConsumer implements RocketMQListener<SelfCheckMessage> {

    private final SelfCheckRegistry registry;

    @Override
    public void onMessage(SelfCheckMessage message) {
        String previous = MDC.get(TraceIdConstants.MDC_KEY);
        MDC.put(TraceIdConstants.MDC_KEY, message.getTraceId() == null ? "mq-no-trace" : message.getTraceId());
        try {
            long latency = System.currentTimeMillis() - message.getSentAt();
            log.info("MQ 自检消息已消费 checkId={} payload={} 投递耗时={}ms",
                    message.getCheckId(), message.getPayload(), latency);
            registry.record(message);
        } finally {
            // 恢复现场：线程会被复用，不清理会污染后续消息的日志
            if (previous == null) {
                MDC.remove(TraceIdConstants.MDC_KEY);
            } else {
                MDC.put(TraceIdConstants.MDC_KEY, previous);
            }
        }
    }
}
