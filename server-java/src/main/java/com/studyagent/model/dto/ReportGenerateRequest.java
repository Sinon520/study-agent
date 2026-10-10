package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 手动触发复盘报告生成（POST /report/generate）。
 *
 * <p>周期参数可省：都不传时默认"本周一 ~ 今天"（与定时任务口径一致）；
 * 传了就必须同时传 start/end，避免只传一半产生歧义。</p>
 */
@Data
@Schema(description = "手动生成报告参数")
public class ReportGenerateRequest {

    @Schema(description = "统计周期开始（yyyy-MM-dd），与 periodEnd 同时传或同时不传", example = "2026-10-05")
    private LocalDate periodStart;

    @Schema(description = "统计周期结束（yyyy-MM-dd）", example = "2026-10-11")
    private LocalDate periodEnd;
}
