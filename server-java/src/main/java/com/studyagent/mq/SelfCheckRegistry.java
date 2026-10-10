package com.studyagent.mq;

import com.studyagent.mq.message.SelfCheckMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自检消息的回执登记处：消费线程记录、请求线程等待。
 *
 * <p>是个最小化的一次性"生产者→消费者"桥：请求线程发出消息后在这里等待，
 * 消费者线程收到消息后往这里写一笔。用 {@link ConcurrentHashMap} +
 * 轮询而不是 {@code CountDownLatch}，是因为一次自检可能被消费多次
 * （消费组广播/重复投递），Map 按 checkId 幂等，不会出现"latch 被消费两次"。</p>
 */
@Component
public class SelfCheckRegistry {

    private final Map<String, SelfCheckMessage> received = new ConcurrentHashMap<>();

    /** 消费线程调用：登记一条回执。 */
    public void record(SelfCheckMessage message) {
        if (message != null && message.getCheckId() != null) {
            received.put(message.getCheckId(), message);
        }
    }

    /**
     * 请求线程调用：等待指定 checkId 的回执。
     *
     * @return 收到的消息（含消费者视角的时间戳）；超时返回 null
     */
    public SelfCheckMessage await(String checkId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            SelfCheckMessage message = received.remove(checkId);
            if (message != null) {
                return message;
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** 当前挂起未消费的回执数（排查用）。 */
    public int pending() {
        return received.size();
    }
}
