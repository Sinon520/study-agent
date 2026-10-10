package com.studyagent.service;

import com.studyagent.model.vo.ProgressVO;

import java.time.LocalDate;

/**
 * 进度统计服务。
 */
public interface ProgressService {

    /**
     * 进度页完整数据（一次请求拿全 4 张图 + 概览卡片）。
     *
     * @param userId 当前用户
     * @param start  统计开始日期
     * @param end    统计结束日期
     */
    ProgressVO.ProgressDashboard dashboard(Long userId, LocalDate start, LocalDate end);

    /**
     * 便捷入口：近 N 天（含今天）。
     *
     * @param days 天数，会被限制在 1~365
     */
    ProgressVO.ProgressDashboard recent(Long userId, int days);
}
