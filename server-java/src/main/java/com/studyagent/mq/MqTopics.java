package com.studyagent.mq;

/**
 * RocketMQ topic 与消费者组常量。
 *
 * <p>三类业务异步任务（对应执行计划第 4 周）：</p>
 * <ul>
 *   <li>{@link #WRONG_CLASSIFY}：新增错题后异步打知识点标签（Day 23）</li>
 *   <li>{@link #REPORT_GENERATE}：每周复盘报告生成（Day 24）</li>
 *   <li>{@link #WX_PUSH}：微信订阅消息推送（Day 25~26）</li>
 * </ul>
 *
 * <p>命名约定：topic 用业务短名（中划线分隔），消费者组统一 {@code study-agent-} 前缀
 * —— 组名和 topic 分开定义，而不是拼在监听注解里，避免两处各写一遍对不上。</p>
 */
public final class MqTopics {

    /** 错题异步归类：{@code {wrongQuestionId, content, subject, traceId}} */
    public static final String WRONG_CLASSIFY = "wrong-classify";
    public static final String GROUP_WRONG_CLASSIFY = "study-agent-wrong-classify";

    /** 复盘报告生成：{@code {userId, periodStart, periodEnd, traceId}} */
    public static final String REPORT_GENERATE = "report-generate";
    public static final String GROUP_REPORT_GENERATE = "study-agent-report-generate";

    /** 微信订阅消息推送：{@code {userId, templateId, data, traceId}} */
    public static final String WX_PUSH = "wx-push";
    public static final String GROUP_WX_PUSH = "study-agent-wx-push";

    /**
     * 自检专用环回 topic（生产者→消费者 收发验证），不承载业务数据。
     *
     * <p>为什么不复用业务 topic 做自检：自检消息会进真实消费者的消费链路，
     * 将来 Day 23/24 的消费者一旦对"内容不合法"的消息做重试/死信处理，
     * 自检消息就会污染业务消费。独立 topic 成本极低，边界却干净。</p>
     */
    public static final String SELF_CHECK = "mq-selfcheck";
    public static final String GROUP_SELF_CHECK = "study-agent-mq-selfcheck";

    private MqTopics() {
    }
}
