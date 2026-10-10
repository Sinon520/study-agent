package com.studyagent.model.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.studyagent.entity.ReviewReport;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复盘报告视图。
 *
 * <p>列表与详情共用：列表接口不查 {@code content/stats_json} 两个大字段
 * （报告正文上千字、统计 JSON 可能几十 KB，翻列表时全带上纯属浪费带宽），
 * 详情接口才解析并填充。</p>
 */
@Data
@Schema(description = "复盘报告")
public class ReportVO {

    @Schema(description = "报告 ID")
    private Long id;

    @Schema(description = "类型：周报/月报")
    private String reportType;

    @Schema(description = "统计周期开始")
    private LocalDate periodStart;

    @Schema(description = "统计周期结束")
    private LocalDate periodEnd;

    @Schema(description = "markdown 正文（仅详情返回）")
    private String content;

    @Schema(description = "统计数据（仅详情返回）")
    private Map<String, Object> stats;

    @Schema(description = "数据卡片要点（仅详情返回）")
    private List<String> highlights = new ArrayList<>();

    @Schema(description = "行动建议（仅详情返回）")
    private List<String> suggestions = new ArrayList<>();

    @Schema(description = "生成时间")
    private LocalDateTime createTime;

    /** 列表项：只带基础字段（不含大字段与解析开销） */
    public static ReportVO fromListItem(ReviewReport report) {
        ReportVO vo = new ReportVO();
        vo.setId(report.getId());
        vo.setReportType(report.getReportType());
        vo.setPeriodStart(report.getPeriodStart());
        vo.setPeriodEnd(report.getPeriodEnd());
        vo.setCreateTime(report.getCreateTime());
        return vo;
    }

    /**
     * 详情：解析 {@code stats_json}。
     *
     * <p>落库结构统一为 {@code {"stats": {...}, "highlights": [...], "suggestions": [...]}}。
     * 解析失败不抛异常：历史脏数据只降级为"没有图表数据"，正文照常可读。</p>
     */
    public static ReportVO fromDetail(ReviewReport report, JsonNode statsRoot) {
        ReportVO vo = fromListItem(report);
        vo.setContent(report.getContent());
        if (statsRoot != null && statsRoot.isObject()) {
            JsonNode stats = statsRoot.path("stats");
            if (stats.isObject()) {
                vo.setStats(parseMap(stats));
            }
            JsonNode highlights = statsRoot.path("highlights");
            if (highlights.isArray()) {
                highlights.forEach(node -> {
                    String text = node.asText("");
                    if (!text.isBlank()) {
                        vo.getHighlights().add(text);
                    }
                });
            }
            JsonNode suggestions = statsRoot.path("suggestions");
            if (suggestions.isArray()) {
                suggestions.forEach(node -> {
                    String text = node.asText("");
                    if (!text.isBlank()) {
                        vo.getSuggestions().add(text);
                    }
                });
            }
        }
        return vo;
    }

    private static Map<String, Object> parseMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            if (value.isNumber()) {
                map.put(entry.getKey(), value.numberValue());
            } else if (value.isArray() || value.isObject()) {
                map.put(entry.getKey(), value);
            } else {
                map.put(entry.getKey(), value.asText());
            }
        });
        return map;
    }
}
