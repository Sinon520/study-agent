package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.ReviewReport;
import com.studyagent.mapper.ReviewReportMapper;
import com.studyagent.service.ReviewReportService;
import org.springframework.stereotype.Service;

/**
 * 复盘报告 业务实现
 */
@Service
public class ReviewReportServiceImpl extends ServiceImpl<ReviewReportMapper, ReviewReport> implements ReviewReportService {
}