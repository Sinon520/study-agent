package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.ReviewReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 复盘报告 Mapper
 */
@Mapper
public interface ReviewReportMapper extends BaseMapper<ReviewReport> {

    /**
     * 幂等写入：同一用户同一周期（唯一键 {@code uk_user_period}）已存在则更新。
     *
     * <p>为什么走 upsert 而不是"先查后插"：MQ 至少一次投递（重试、重投、
     * 手动重复触发）下，两个同周期消息可能被并发消费，"先查后插"赢不了 TOCTOU 竞态
     * （踩坑 #20/#21 的教训）。唯一键 + ON DUPLICATE KEY UPDATE 是数据库层面的最终防线。</p>
     *
     * @return 影响行数：1=新插入，2=更新，0=值完全相同没有变化
     */
    @Insert("""
            INSERT INTO review_report
                (user_id, report_type, period_start, period_end, content, stats_json, create_time)
            VALUES
                (#{r.userId}, #{r.reportType}, #{r.periodStart}, #{r.periodEnd},
                 #{r.content}, #{r.statsJson}, NOW())
            AS incoming
            ON DUPLICATE KEY UPDATE
                content     = incoming.content,
                stats_json  = incoming.stats_json,
                create_time = NOW()
            """)
    int upsert(@Param("r") ReviewReport report);

    /** 按用户 + 周期取报告（upsert 后回查 ID 用，也是幂等判定的快速路径） */
    @Select("""
            SELECT *
              FROM review_report
             WHERE user_id      = #{userId}
               AND report_type  = #{reportType}
               AND period_start = #{periodStart}
               AND period_end   = #{periodEnd}
            """)
    ReviewReport findByPeriod(@Param("userId") Long userId,
                              @Param("reportType") String reportType,
                              @Param("periodStart") LocalDate periodStart,
                              @Param("periodEnd") LocalDate periodEnd);
}
