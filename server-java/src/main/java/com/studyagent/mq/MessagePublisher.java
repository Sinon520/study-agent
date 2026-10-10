package com.studyagent.mq;

import com.studyagent.common.util.TraceIdConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * MQ 消息发布统一入口。
 *
 * <p>为什么要包一层而不是各处直接注 {@link RocketMQTemplate}：</p>
 * <ul>
 *   <li><b>traceId 注入收在一处</b>：所有消息的 traceId 字段由这里统一填充，
 *       业务代码只管构造消息体，不会有人忘填（跨语言 traceId 链路的关键一环）；</li>
 *   <li>发送方式（同步/超时/重试策略）将来要调时只改一个地方；</li>
 *   <li>业务 service 不必直接依赖 MQ 客户端类，"消息发不出去"的异常语义
 *       在要用它的地方（如错题新增）单独处理。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePublisher {

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 同步发送一条消息。
     *
     * <p>先同步发送的原因：错题归类/报告生成这类场景，"消息可靠送达 Broker"
     * 比"发得快"重要 —— 异步发送要在回调里处理失败重投，复杂度高一个量级。
     * 真需要异步的场景（如纯通知）后续再单独开方法。</p>
     *
     * @return 发送结果（含 msgId、queueId、sendStatus）
     */
    public SendResult send(String topic, Object payload) {
        String traceId = currentTraceId();
        SendResult result = rocketMQTemplate.syncSend(topic, payload);
        // 注意：rocketmq-client 5.x 的 SendResult 没有 getQueueId()，
        // 队列信息在 getMessageQueue() 里（MessageQueue#getQueueId）
        log.info("MQ 发送成功 topic={} msgId={} queue={} traceId={}",
                topic, result.getMsgId(), queueId(result), traceId);
        return result;
    }

    /** 取发送结果的队列号（兼容 messageQueue 为空的场景）。 */
    public static int queueId(SendResult result) {
        return result.getMessageQueue() == null ? -1 : result.getMessageQueue().getQueueId();
    }

    /** 当前请求的 traceId（MQ 是异步边界，必须显式传给消费者线程） */
    public static String currentTraceId() {
        return MDC.get(TraceIdConstants.MDC_KEY);
    }
}
