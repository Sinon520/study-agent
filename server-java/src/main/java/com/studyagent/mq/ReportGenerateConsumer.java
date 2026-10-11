package com.studyagent.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.util.TraceIdConstants;
import com.studyagent.config.WxTemplateKeys;
import com.studyagent.entity.NoticeMessage;
import com.studyagent.entity.ReviewReport;
import com.studyagent.mq.message.ReportGenerateMessage;
import com.studyagent.mq.message.WxPushMessage;
import com.studyagent.model.vo.ReportResultVO;
import com.studyagent.service.PythonClient;
import com.studyagent.service.ReportAggregator;
import com.studyagent.service.ReviewReportService;
import com.studyagent.service.NoticeMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 复盘报告生成消费者（Day 24）：收到 {@code report-generate} 消息 →
 * 聚合统计数据 → 调 Python {@code /agent/report} 生成文字 → 落 {@code review_report}
 * → 写站内通知 → 发 {@code wx-push}。
 *
 * <h3>链路分工（面试可讲）</h3>
 * <ol>
 *   <li><b>触发与消费解耦</b>：定时任务（或手动接口）只负责"投递谁要报告"，
 *       生成过程（LLM 调用+落库+推送）全部在消费者里异步做。定时任务不阻塞、
 *       失败可重试，单个用户生成失败不影响其他用户。</li>
 *   <li><b>数字与文字的边界</b>：统计由 Java 聚合（口径与进度页一致），
 *       LLM 只写文字 —— 杜绝"模型编数据"。</li>
 *   <li><b>幂等</b>：报告落库走唯一键 upsert；站内通知按
 *       (userId, type=报告, bizId) 查重后再写 —— 消息重投/重试不会产生
 *       重复报告与重复通知，微信推送也只在"首次建通知"时发。</li>
 *   <li><b>失败要重试，无效消息要放行</b>：调 Python / 落库失败抛异常交
 *       RocketMQ 重试（进死信队列兜底）；参数本身无效（缺 userId、
 *       周期倒挂）的消息重试多少次都不会成功，直接 return 消费成功。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.REPORT_GENERATE,
        consumerGroup = MqTopics.GROUP_REPORT_GENERATE
)
public class ReportGenerateConsumer implements RocketMQListener<ReportGenerateMessage> {

    private final ReportAggregator aggregator;
    private final PythonClient pythonClient;
    private final ReviewReportService reviewReportService;
    private final NoticeMessageService noticeMessageService;
    private final MessagePublisher publisher;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(ReportGenerateMessage message) {
        String previous = MDC.get(TraceIdConstants.MDC_KEY);
        MDC.put(TraceIdConstants.MDC_KEY, message.getTraceId() == null ? "mq-no-trace" : message.getTraceId());
        try {
            Long userId = message.getUserId();
            LocalDate start = message.getPeriodStart();
            LocalDate end = message.getPeriodEnd();

            // 无效参数是终态：重试无意义，直接放行
            if (userId == null || start == null || end == null) {
                log.warn("报告消息缺少必要参数，直接丢弃: {}", message);
                return;
            }
            if (end.isBefore(start)) {
                log.warn("报告消息周期倒挂（start={} > end={}），直接丢弃", start, end);
                return;
            }

            long started = System.currentTimeMillis();

            // 1) 聚合统计（口径与进度页一致）
            Map<String, Object> stats = aggregator.aggregate(userId, start, end);

            // 2) LLM 生成文字（失败抛异常 → MQ 重试）
            ReportResultVO result = pythonClient.generateReport(
                    userId, start.toString(), end.toString(), stats);

            // 3) 幂等落库：同周期重复消费表现为"覆盖再生一次"
            String statsJson = buildStatsJson(stats, result);
            ReviewReport saved = reviewReportService.upsertReport(
                    userId, "周报", start, end, result.getContent(), statsJson);

            // 4) 站内通知（按 bizId 查重，重投不会重复通知）
            boolean noticeCreated = ensureReportNotice(userId, saved.getId(), stats);

            // 5) 微信订阅消息：只有"首次建通知"才发，避免重投产生重复触达；
            //    发送失败不影响报告与站内通知（推送是尽力而为的外部依赖）
            if (noticeCreated) {
                sendWxPush(userId, start, end, saved.getId(), stats);
            }

            log.info("复盘报告生成完成 userId={} 周期={} ~ {} 报告ID={} 正文={}字 提示{}条 建议{}条 耗时={}ms",
                    userId, start, end, saved.getId(),
                    result.getContent() == null ? 0 : result.getContent().length(),
                    result.getHighlights().size(), result.getSuggestions().size(),
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

    // ------------------------------------------------------------------ 内部

    /** 组装落库用的 stats_json：{"stats": {...}, "highlights": [...], "suggestions": [...]} */
    private String buildStatsJson(Map<String, Object> stats, ReportResultVO result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stats", stats);
        payload.put("highlights", result.getHighlights());
        payload.put("suggestions", result.getSuggestions());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // 序列化失败说明聚合出了循环引用之类的问题，是代码缺陷，交给重试暴露
            throw new IllegalStateException("stats_json 序列化失败", e);
        }
    }

    /**
     * 幂等写站内通知：同一份报告已有通知则跳过。
     *
     * @return true=本次新建了通知（微信推送的触发条件）
     */
    private boolean ensureReportNotice(Long userId, Long reportId, Map<String, Object> stats) {
        long existing = noticeMessageService.count(Wrappers.<NoticeMessage>lambdaQuery()
                .eq(NoticeMessage::getUserId, userId)
                .eq(NoticeMessage::getType, "报告")
                .eq(NoticeMessage::getBizId, reportId));
        if (existing > 0) {
            log.info("报告通知已存在，跳过重复写入 reportId={}", reportId);
            return false;
        }
        String content = String.format("本周学习 %s 分钟，任务完成率 %s%%，新增错题 %s 道。点击查看完整复盘。",
                value(stats.get("totalMinutes")), value(stats.get("completionRate")), value(stats.get("wrongNew")));
        noticeMessageService.push(userId, "报告", "本周学习复盘报告已生成", content, reportId);
        return true;
    }

    /**
     * 发微信订阅消息。
     *
     * <p>消息里只带**业务场景 key**（{@code report}），不带模板 ID ——
     * 模板 ID 属于配置（小程序后台申请、可换），由 {@code WxPushConsumer}
     * 按 {@code wx.templates.*} 解析成当前模板。这样换模板不用处理历史消息，
     * 也避免占位 ID 散落在业务代码里。</p>
     *
     * <p>推送是**尽力而为**的外部依赖：发送失败只告警，不影响已落库的报告与站内通知。</p>
     */
    private void sendWxPush(Long userId, LocalDate start, LocalDate end, Long reportId, Map<String, Object> stats) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("thing1", Map.of("value", "本周学习复盘报告"));
        data.put("thing2", Map.of("value",
                "学习 " + value(stats.get("totalMinutes")) + " 分钟 · 完成率 " + value(stats.get("completionRate")) + "%"));
        data.put("time3", Map.of("value", start + " ~ " + end));

        try {
            // 小程序端目前没有独立的报告页，点击后落进度页
            // TODO: 小程序补上复盘报告页后，把 page 换成 pages/report/report
            publisher.send(MqTopics.WX_PUSH, new WxPushMessage(
                    userId, WxTemplateKeys.REPORT, null, "pages/progress/progress", data,
                    MessagePublisher.currentTraceId()));
        } catch (Exception e) {
            log.warn("报告微信推送消息发送失败（不影响报告与站内通知）userId={} reportId={}: {}",
                    userId, reportId, e.getMessage());
        }
    }

    private static String value(Object o) {
        return o == null ? "0" : String.valueOf(o);
    }
}
