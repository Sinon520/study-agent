package com.studyagent.mq;

import com.studyagent.common.util.TraceIdConstants;
import com.studyagent.config.ReportProperties;
import com.studyagent.mapper.StudyRecordMapper;
import com.studyagent.mq.message.ReportGenerateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 周报定时触发（Day 24）：每周日 20:00 扫活跃用户，为每人发一条
 * {@code report-generate} 消息，真正的生成在 {@link ReportGenerateConsumer} 里异步做。
 *
 * <h3>设计要点</h3>
 * <ol>
 *   <li><b>触发与生成解耦</b>：定时任务本身很轻（一次查询 + N 次发消息，秒级完成），
 *       LLM 生成这种分钟级操作放消费者。单个用户生成失败可独立重试，
 *       不会卡住定时任务、也不影响其他用户（下面的 try/catch 逐用户兜底）。</li>
 *   <li><b>统计周期 = 本周一 ~ 今天</b>：周日 20:00 触发时恰好是完整的一周
 *       （现在是一周的末尾，起点回退到周一而不是"近 7 天"——让周报的周期
 *       和自然周对齐，用户看日期就明白统计的哪一周）。</li>
 *   <li><b>cron 可配置</b>：演示/联调时不用等周日，改 yml 或直接调手动触发接口。</li>
 * </ol>
 *
 * <p>简历口径可写 XXL-Job：单机部署用 {@code @Scheduled} 就够（本项目场景），
 * 多实例部署时换成分布式调度保证只有一个实例触发即可 —— 消费端天然幂等，
 * 即使触发重复也不会产生重复报告。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportScheduler {

    private final StudyRecordMapper studyRecordMapper;
    private final MessagePublisher publisher;
    private final ReportProperties reportProperties;

    /** 每周日 20:00（Spring cron：秒 分 时 日 月 周） */
    @Scheduled(cron = "${report.cron:0 0 20 * * SUN}")
    public void weeklyReportSchedule() {
        if (!reportProperties.isSchedulerEnabled()) {
            log.info("周报定时任务已关闭（report.scheduler-enabled=false）");
            return;
        }

        // 定时任务没有 HTTP 请求，traceId 要自己生成（跨语言链路从这条消息开始）
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put(TraceIdConstants.MDC_KEY, traceId);
        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.with(DayOfWeek.MONDAY);

            List<Long> userIds = studyRecordMapper.listActiveUserIds(start, end);
            log.info("周报定时任务启动：周期={} ~ {} 活跃用户 {} 人", start, end, userIds.size());

            int sent = 0;
            for (Long userId : userIds) {
                try {
                    publisher.send(MqTopics.REPORT_GENERATE,
                            new ReportGenerateMessage(userId, start, end, traceId));
                    sent++;
                } catch (Exception e) {
                    // 单个用户发送失败（如 Broker 抖动）不中断整轮：继续发其他人，
                    // 本轮漏掉的人下一周还会覆盖到
                    log.error("周报消息发送失败 userId={}（继续处理其他用户）: {}", userId, e.getMessage());
                }
            }
            log.info("周报定时任务完成：成功投递 {}/{}", sent, userIds.size());
        } finally {
            MDC.remove(TraceIdConstants.MDC_KEY);
        }
    }
}
