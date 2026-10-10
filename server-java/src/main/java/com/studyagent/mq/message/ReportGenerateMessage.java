package com.studyagent.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 复盘报告生成消息（topic: {@code report-generate}）。
 *
 * <p>由 {@code @Scheduled}（每周日 20:00）为每个活跃用户发出；
 * 消费者聚合该周期内的学习记录 / 任务完成情况 / 错题数据，调 Python
 * {@code /agent/report} 生成 markdown 报告，落 {@code review_report} 表，
 * 再发 {@code wx-push} 通知。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportGenerateMessage implements Serializable {

    private Long userId;

    /** 统计周期起始日（含） */
    private LocalDate periodStart;

    /** 统计周期结束日（含） */
    private LocalDate periodEnd;

    /** 产生这条消息的请求链路 ID */
    private String traceId;
}
