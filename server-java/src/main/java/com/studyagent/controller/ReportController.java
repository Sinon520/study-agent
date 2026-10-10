package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.config.ReportProperties;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.dto.ReportGenerateRequest;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.ReportVO;
import com.studyagent.mq.MessagePublisher;
import com.studyagent.mq.MqTopics;
import com.studyagent.mq.message.ReportGenerateMessage;
import com.studyagent.security.UserContext;
import com.studyagent.service.ReviewReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 复盘报告接口（Day 24）。
 *
 * <p>生成是异步的：定时任务/手动触发都只是往 {@code report-generate} 发消息，
 * 真正的聚合+LLM+落库在 MQ 消费者里做（LLM 秒级到分钟级，同步接口撑不住）。
 * 前端"立即生成"后轮询列表即可。</p>
 */
@Slf4j
@RestController
@RequestMapping("/report")
@RequiredArgsConstructor
@Tag(name = "07-复盘报告", description = "周报列表 / 详情 / 手动触发生成")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private final ReviewReportService reviewReportService;
    private final MessagePublisher publisher;
    private final ReportProperties reportProperties;

    @GetMapping("/page")
    @Operation(summary = "分页查询我的报告", description = "按周期倒序；列表不含正文大字段")
    public Result<PageResult<ReportVO>> page(PageQuery query) {
        return Result.ok(reviewReportService.pageReports(UserContext.userId(), query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "报告详情", description = "含 markdown 正文、统计 JSON、要点与建议；越权访问返回 403")
    public Result<ReportVO> detail(@PathVariable Long id) {
        return Result.ok(reviewReportService.getReportDetail(UserContext.userId(), id));
    }

    @PostMapping("/generate")
    @Operation(summary = "手动触发本人生成报告",
            description = "异步生成：提交后轮询 /report/page 查看。周期默认本周一~今天，可指定（最长 31 天）")
    public Result<Map<String, Object>> generate(@RequestBody(required = false) ReportGenerateRequest request) {
        Long userId = UserContext.userId();
        LocalDate today = LocalDate.now();
        LocalDate start = request == null ? null : request.getPeriodStart();
        LocalDate end = request == null ? null : request.getPeriodEnd();

        if (start == null && end == null) {
            // 默认与定时任务同口径：本周一 ~ 今天
            end = today;
            start = end.with(DayOfWeek.MONDAY);
        } else if (start == null || end == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "periodStart 与 periodEnd 需要同时传或同时不传");
        }

        if (end.isBefore(start)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "统计结束日期不能早于开始日期");
        }
        if (end.isAfter(today)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "统计结束日期不能超过今天");
        }
        long spanDays = ChronoUnit.DAYS.between(start, end) + 1;
        if (spanDays > reportProperties.getMaxPeriodDays()) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "统计跨度最长 " + reportProperties.getMaxPeriodDays() + " 天，当前 " + spanDays + " 天");
        }

        publisher.send(MqTopics.REPORT_GENERATE,
                new ReportGenerateMessage(userId, start, end, MessagePublisher.currentTraceId()));
        log.info("手动触发报告生成 userId={} 周期={} ~ {}", userId, start, end);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("periodStart", start);
        data.put("periodEnd", end);
        data.put("message", "已提交生成任务，通常 10~60 秒内完成，稍后刷新查看");
        return Result.ok("生成任务已提交", data);
    }
}
