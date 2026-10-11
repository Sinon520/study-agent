package com.studyagent.mq;

import com.studyagent.common.util.TraceIdConstants;
import com.studyagent.config.WxProperties;
import com.studyagent.config.WxTemplateKeys;
import com.studyagent.mapper.PlanTaskMapper;
import com.studyagent.mapper.WrongQuestionMapper;
import com.studyagent.mq.message.WxPushMessage;
import com.studyagent.service.WxSubscribeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订阅消息定时推送（Day 26）。
 *
 * <p>两个定时任务都**只投递 MQ 消息**，不直接调微信接口 ——
 * 真正发送在 {@link WxPushConsumer} 里做（可重试、失败进死信、不阻塞调度线程）。</p>
 *
 * <h3>为什么先过滤授权再发消息</h3>
 * <p>微信的订阅消息是"一次订阅 = 一次发送额度"。给没授权的用户发，
 * 微信会直接返回 43101（用户拒收）——每次都是徒劳的接口调用，
 * 还会消耗接口额度、在死信队列里堆垃圾。所以在**调度阶段**就先按
 * 授权表过滤掉，消息只发给真正能收到的人。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemindScheduler {

    /** 未掌握掌握度取值（与 WrongQuestion.mastery 一致） */
    private static final String MASTERY_NOT = "未掌握";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");

    private final PlanTaskMapper planTaskMapper;
    private final WrongQuestionMapper wrongQuestionMapper;
    private final WxSubscribeService wxSubscribeService;
    private final MessagePublisher publisher;
    private final WxProperties wxProperties;

    /**
     * 每天 8:00：给"当天有待办任务且已授权提醒"的用户发学习提醒。
     */
    @Scheduled(cron = "${wx.reminder-cron:0 0 8 * * *}")
    public void dailyStudyReminder() {
        if (!pushEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now();
        String traceId = newTraceId();
        List<Long> userIds = planTaskMapper.listUserIdsWithPendingTasks(today);
        log.info("学习提醒定时任务启动：日期={} 待办用户 {} 人 traceId={}", today, userIds.size(), traceId);

        int sent = 0;
        for (Long userId : userIds) {
            if (!wxSubscribeService.hasApproved(userId, WxTemplateKeys.REMINDER)) {
                continue;
            }
            int pending = planTaskMapper.countPendingTasks(userId, today);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("thing1", Map.of("value", "今日学习提醒"));
            data.put("thing2", Map.of("value", "今天还有 " + pending + " 个任务待完成"));
            data.put("time3", Map.of("value", today.atStartOfDay().withHour(8).format(TIME_FMT)));
            try {
                publisher.send(MqTopics.WX_PUSH, new WxPushMessage(
                        userId, WxTemplateKeys.REMINDER, null, "pages/plan/plan", data, traceId));
                sent++;
            } catch (Exception e) {
                log.error("学习提醒消息发送失败 userId={}（继续其他用户）: {}", userId, e.getMessage());
            }
        }
        log.info("学习提醒定时任务完成：投递 {}/{}", sent, userIds.size());
    }

    /**
     * 每天 20:00：给"未掌握错题达阈值且已授权复习"的用户发错题复习提醒。
     */
    @Scheduled(cron = "${wx.review-cron:0 0 20 * * *}")
    public void wrongReviewReminder() {
        if (!pushEnabled()) {
            return;
        }
        long threshold = Math.max(1, wxProperties.getReviewWrongThreshold());
        String traceId = newTraceId();
        List<Map<String, Object>> rows = wrongQuestionMapper.listUsersWithMastery(MASTERY_NOT, threshold);
        log.info("错题复习提醒定时任务启动：阈值={} 命中用户 {} 人", threshold, rows.size());

        int sent = 0;
        for (Map<String, Object> row : rows) {
            Long userId = toLong(row.get("userId"));
            if (userId == null || !wxSubscribeService.hasApproved(userId, WxTemplateKeys.REVIEW)) {
                continue;
            }
            int count = row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).intValue();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("thing1", Map.of("value", "错题复习提醒"));
            data.put("thing2", Map.of("value", "有 " + count + " 道错题未掌握"));
            data.put("time3", Map.of("value",
                    LocalDateTime.now().withHour(20).withMinute(0).format(TIME_FMT)));
            try {
                publisher.send(MqTopics.WX_PUSH, new WxPushMessage(
                        userId, WxTemplateKeys.REVIEW, null, "pages/wrong/wrong", data, traceId));
                sent++;
            } catch (Exception e) {
                log.error("错题复习消息发送失败 userId={}（继续其他用户）: {}", userId, e.getMessage());
            }
        }
        log.info("错题复习提醒定时任务完成：投递 {}/{}", sent, rows.size());
    }

    /** 总开关：关闭时也记一行日志，便于区分"关着"和"压根没跑" */
    private boolean pushEnabled() {
        if (!wxProperties.isPushSchedulerEnabled()) {
            log.info("订阅消息定时推送已关闭（wx.push-scheduler-enabled=false）");
            return false;
        }
        return true;
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }
}
