package com.studyagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 复盘报告配置，对应 application.yml 中的 {@code report.*}（Day 24）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "report")
public class ReportProperties {

    /**
     * 周报定时触发 cron（Spring 六段式：秒 分 时 日 月 周）。
     * <p>默认每周日 20:00 —— 复盘一周刚好，周日晚发也符合"周末总结"的习惯。
     * 简历口径可写 XXL-Job：实现用 {@code @Scheduled}，单机够用；
     * 多实例部署时换成分布式调度（只让一个实例触发）即可，MQ 消费端已天然与触发端解耦。</p>
     */
    private String cron = "0 0 20 * * SUN";

    /** 定时任务开关（本地调试不想被定时打搅时置 false；手动触发接口不受影响） */
    private boolean schedulerEnabled = true;

    /** 报告通知的微信订阅消息模板 ID（真模板 Day 25~26 申请后替换） */
    private String wxTemplateId = "wx-template-report-placeholder";

    /** 手动触发允许的最大统计跨度（天），防误传全年把 LLM 打爆 */
    private int maxPeriodDays = 31;
}
