package com.studyagent.mq;

import com.studyagent.common.util.TraceIdConstants;
import com.studyagent.entity.WrongQuestion;
import com.studyagent.model.vo.ClassifyResultVO;
import com.studyagent.mq.message.WrongClassifyMessage;
import com.studyagent.service.PythonClient;
import com.studyagent.service.WrongQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 错题异步归类消费者（Day 23）：收到 {@code wrong-classify} 消息 →
 * 调 Python {@code /agent/classify} → 回写知识点标签。
 *
 * <h3>两个消费语义的设计点</h3>
 * <ol>
 *   <li><b>失败要重试，终态要放行</b>：调 Python 失败 / 落库失败 → 抛异常，
 *       RocketMQ 按重试策略重投（并发消费默认最多 16 次，间隔递增），
 *       仍失败进死信队列 {@code %DLQ%study-agent-wrong-classify}；
 *       但"错题已被用户删除"是**终态** —— 重试多少次都不会成功，
 *       直接 return 让消息消费成功，不浪费重试配额。</li>
 *   <li><b>幂等是义务不是选项</b>：RocketMQ 保证至少一次投递，
 *       同一条消息可能被消费多次（重试、rebalance、broker 重投）。
 *       打标关系写入用"先查后插 + 唯一键兜底"，重复消费不会打出重复标签
 *       （踩坑记录 #20 的教训：光靠'先查后插'挡不住并发，唯一键才是最终防线）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.WRONG_CLASSIFY,
        consumerGroup = MqTopics.GROUP_WRONG_CLASSIFY
)
public class WrongClassifyConsumer implements RocketMQListener<WrongClassifyMessage> {

    private final PythonClient pythonClient;
    private final WrongQuestionService wrongQuestionService;

    @Override
    public void onMessage(WrongClassifyMessage message) {
        String previous = MDC.get(TraceIdConstants.MDC_KEY);
        MDC.put(TraceIdConstants.MDC_KEY, message.getTraceId() == null ? "mq-no-trace" : message.getTraceId());
        try {
            Long questionId = message.getWrongQuestionId();
            if (questionId == null) {
                log.warn("归类消息缺少 wrongQuestionId，直接丢弃: {}", message);
                return;
            }

            // 终态判断：错题可能已被用户删除（消息晚到 / 重试期间删除）
            WrongQuestion question = wrongQuestionService.getById(questionId);
            if (question == null) {
                log.warn("错题已不存在，跳过归类 wrongQuestionId={}", questionId);
                return;
            }

            long started = System.currentTimeMillis();
            ClassifyResultVO result = pythonClient.classify(questionId, message.getContent(), message.getSubject());
            int added = wrongQuestionService.appendTags(question.getUserId(), questionId, result.getTags());

            log.info("错题自动打标完成 wrongQuestionId={} subject={} tags={} 新增关系={} 耗时={}ms",
                    questionId, result.getSubject(), result.getTags(), added,
                    System.currentTimeMillis() - started);
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
