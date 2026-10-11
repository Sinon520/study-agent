package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.config.WxTemplateKeys;
import com.studyagent.mq.MessagePublisher;
import com.studyagent.mq.MqTopics;
import com.studyagent.mq.SelfCheckRegistry;
import com.studyagent.mq.message.SelfCheckMessage;
import com.studyagent.mq.message.WxPushMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MQ 环回自检（内部接口，X-Internal-Token + 来源 IP 保护）。
 *
 * <p>发一条自检消息到 {@code mq-selfcheck} topic，等待本进程的消费者回执，
 * 一次性验证：NameServer 路由 → Broker 收消息 → 消费者组订阅 → JSON 序列化/反序列化
 * → traceId 续接 整条链路。Day 22 的"producer→consumer 自检 demo"。</p>
 *
 * <p>用 HTTP 接口而不是启动时自检：Broker 未启动时不应该拖垮应用启动
 * （演示/开发时经常只起一半中间件），需要验证时手动调一次即可。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/mq")
@RequiredArgsConstructor
@Tag(name = "MQ 自检", description = "内部接口：验证 RocketMQ producer→consumer 收发链路")
public class MqSelfCheckController {

    private final MessagePublisher publisher;
    private final SelfCheckRegistry registry;

    @PostMapping("/selfcheck")
    @Operation(summary = "MQ 环回自检",
            description = "发一条自检消息并等待消费者回执；返回发送状态、投递耗时与载荷回显是否一致")
    public Result<Map<String, Object>> selfCheck(
            @RequestParam(name = "timeoutMs", defaultValue = "8000") long timeoutMs) throws InterruptedException {

        long waitMs = Math.max(1000, Math.min(timeoutMs, 30000));
        String checkId = UUID.randomUUID().toString().replace("-", "");
        String payload = "ping-" + checkId.substring(0, 8);

        SelfCheckMessage message = new SelfCheckMessage(
                checkId, payload, System.currentTimeMillis(), MessagePublisher.currentTraceId());

        long started = System.currentTimeMillis();
        SendResult sent = publisher.send(MqTopics.SELF_CHECK, message);

        SelfCheckMessage receipt = registry.await(checkId, waitMs);
        long roundTripMs = System.currentTimeMillis() - started;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkId", checkId);
        data.put("topic", MqTopics.SELF_CHECK);
        data.put("consumerGroup", MqTopics.GROUP_SELF_CHECK);
        data.put("msgId", sent.getMsgId());
        data.put("sendStatus", String.valueOf(sent.getSendStatus()));
        data.put("queueId", MessagePublisher.queueId(sent));
        data.put("received", receipt != null);
        data.put("roundTripMs", roundTripMs);
        if (receipt != null) {
            data.put("echo", receipt.getPayload());
            data.put("echoOk", Objects.equals(payload, receipt.getPayload()));
        } else {
            data.put("pending", registry.pending());
        }
        return Result.ok(receipt != null ? "MQ 收发链路正常" : "发送成功但未等到消费回执", data);
    }

    /**
     * 手动投递一条订阅消息（仅联调/验收用）。
     *
     * <p>为什么需要它：定时任务是"周日 20:00 / 每天 8:00"才跑，
     * 想当场验证推送链路就得等。这个接口直接往 {@code wx-push} 发一条消息，
     * 后续的发送、记账、日志全走真实消费者，因此验证结果等价于线上路径
     * （除了"什么时候触发"这件事）。</p>
     */
    @PostMapping("/wx-push")
    @Operation(summary = "手动投递一条订阅消息",
            description = "内部接口：往 wx-push topic 发消息，由消费者完成发送与记账（联调/验收用）")
    public Result<Map<String, Object>> pushTest(
            @RequestParam Long userId,
            @RequestParam(name = "templateKey", defaultValue = WxTemplateKeys.REMINDER) String templateKey) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("thing1", Map.of("value", "学习提醒"));
        data.put("thing2", Map.of("value", "这是一条联调触发的订阅消息"));

        SendResult sent = publisher.send(MqTopics.WX_PUSH, new WxPushMessage(
                userId, templateKey, null, "pages/plan/plan", data,
                MessagePublisher.currentTraceId()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("templateKey", templateKey);
        result.put("msgId", sent.getMsgId());
        result.put("topic", MqTopics.WX_PUSH);
        result.put("message", "已投递，推送结果见服务日志与 wx_push_log");
        return Result.ok("订阅消息已投递", result);
    }
}
