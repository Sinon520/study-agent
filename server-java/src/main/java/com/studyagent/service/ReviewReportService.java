package com.studyagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.studyagent.entity.ReviewReport;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.ReportVO;

import java.time.LocalDate;

/**
 * 复盘报告 业务接口。
 *
 * <p>报告的"生成"链路（定时/手动触发 → MQ → 聚合 → LLM → 落库）见
 * {@code ReportGenerateConsumer}；这里只负责读写：列表、详情、幂等 upsert。</p>
 */
public interface ReviewReportService extends IService<ReviewReport> {

    /** 分页查询当前用户的报告列表（不含正文大字段，倒序） */
    PageResult<ReportVO> pageReports(Long userId, PageQuery query);

    /** 报告详情（解析 stats_json；横向越权返回 403） */
    ReportVO getReportDetail(Long userId, Long id);

    /**
     * 幂等写入报告：同用户同周期已存在则覆盖内容（唯一键兜底）。
     *
     * @return 落库后的完整行（含 ID，供通知的 bizId 引用）
     */
    ReviewReport upsertReport(Long userId, String reportType, LocalDate periodStart, LocalDate periodEnd,
                              String content, String statsJson);
}
