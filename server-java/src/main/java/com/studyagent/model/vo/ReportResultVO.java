package com.studyagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 复盘报告生成结果（Python {@code /agent/report} 的返回）。
 *
 * <p>只有"文字类"产物：markdown 正文 + 数据卡片要点 + 行动建议。
 * 统计数据本体由 Java 聚合（{@code ReportAggregator}），二者在消费者里
 * 合并成 {@code stats_json} 落库 —— Python 不碰数、Java 不编文，职责边界清晰。</p>
 */
@Data
@Schema(description = "AI 复盘报告结果")
public class ReportResultVO {

    @Schema(description = "markdown 正文（含本周概览/亮点/改进/建议四段）")
    private String content;

    @Schema(description = "数据卡片要点（3~5 条，每条不超过 20 字）")
    private List<String> highlights = new ArrayList<>();

    @Schema(description = "下周行动建议（3~5 条）")
    private List<String> suggestions = new ArrayList<>();
}
