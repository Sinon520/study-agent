package com.studyagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.ReviewReport;
import com.studyagent.mapper.ReviewReportMapper;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.ReportVO;
import com.studyagent.service.ReviewReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 复盘报告 业务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewReportServiceImpl extends ServiceImpl<ReviewReportMapper, ReviewReport>
        implements ReviewReportService {

    private final ObjectMapper objectMapper;

    @Override
    public PageResult<ReportVO> pageReports(Long userId, PageQuery query) {
        Page<ReviewReport> page = query.toPage();
        // 不 select content/stats_json：报告正文上千字，列表页用不到，查了纯浪费带宽
        Page<ReviewReport> result = page(page, Wrappers.<ReviewReport>lambdaQuery()
                .select(ReviewReport::getId, ReviewReport::getReportType,
                        ReviewReport::getPeriodStart, ReviewReport::getPeriodEnd,
                        ReviewReport::getCreateTime)
                .eq(ReviewReport::getUserId, userId)
                .orderByDesc(ReviewReport::getPeriodStart)
                .orderByDesc(ReviewReport::getId));
        return PageResult.of(result, ReportVO::fromListItem);
    }

    @Override
    public ReportVO getReportDetail(Long userId, Long id) {
        ReviewReport report = requireOwned(userId, id);
        return ReportVO.fromDetail(report, parseStatsJson(report.getStatsJson(), id));
    }

    @Override
    public ReviewReport upsertReport(Long userId, String reportType, LocalDate periodStart, LocalDate periodEnd,
                                     String content, String statsJson) {
        ReviewReport report = new ReviewReport();
        report.setUserId(userId);
        report.setReportType(reportType);
        report.setPeriodStart(periodStart);
        report.setPeriodEnd(periodEnd);
        report.setContent(content);
        report.setStatsJson(statsJson);

        int affected = baseMapper.upsert(report);
        // upsert 不返回主键：回查一次拿到 ID（通知的 bizId 要引用它）
        ReviewReport saved = baseMapper.findByPeriod(userId, reportType, periodStart, periodEnd);
        if (saved == null) {
            // 理论上不可达：upsert 成功必然能查到
            throw new BizException(Result.CODE_FAIL, "报告落库后回查失败，请重试");
        }
        log.info("复盘报告已落库 userId={} 周期={} ~ {} 报告ID={} upsert影响行数={} 正文={}字",
                userId, periodStart, periodEnd, saved.getId(), affected,
                content == null ? 0 : content.length());
        return saved;
    }

    // ------------------------------------------------------------------ 内部

    private ReviewReport requireOwned(Long userId, Long id) {
        ReviewReport report = getById(id);
        if (report == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "报告不存在或已被删除：" + id);
        }
        if (!report.getUserId().equals(userId)) {
            log.warn("水平越权拦截：userId={} 试图访问 reportId={}（属于 userId={}）",
                    userId, id, report.getUserId());
            throw new BizException(Result.CODE_FORBIDDEN, "无权访问该报告");
        }
        return report;
    }

    /**
     * 解析 stats_json。历史脏数据只降级（返回 null → 详情没有图表数据），不让整页打不开。
     */
    private JsonNode parseStatsJson(String statsJson, Long reportId) {
        if (statsJson == null || statsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(statsJson);
        } catch (Exception e) {
            log.warn("报告 stats_json 解析失败（降级为无图表数据）reportId={}: {}", reportId, e.getMessage());
            return null;
        }
    }
}
