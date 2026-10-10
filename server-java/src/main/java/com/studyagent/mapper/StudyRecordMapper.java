package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.StudyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 学习记录 Mapper。
 *
 * <p>统计类查询直接写 SQL {@code group by}，而不是把明细全查出来在内存里聚合：
 * 行数一多内存聚合就会成为瓶颈，而 {@code group by} 能命中
 * {@code idx_user_date (user_id, study_date)} 索引，走索引范围扫描 + 聚合。</p>
 */
@Mapper
public interface StudyRecordMapper extends BaseMapper<StudyRecord> {

    /**
     * 按日期聚合学习时长（每日时长折线图）。
     *
     * @return 每行 {@code {studyDate, minutes, recordCount}}
     */
    @Select("""
            SELECT study_date AS studyDate,
                   COALESCE(SUM(minutes), 0) AS minutes,
                   COUNT(*) AS recordCount
              FROM study_record
             WHERE user_id = #{userId}
               AND study_date BETWEEN #{start} AND #{end}
             GROUP BY study_date
             ORDER BY study_date
            """)
    List<Map<String, Object>> sumMinutesByDate(@Param("userId") Long userId,
                                               @Param("start") LocalDate start,
                                               @Param("end") LocalDate end);

    /**
     * 按科目聚合学习时长（科目分布饼图）。
     *
     * @return 每行 {@code {subject, minutes}}
     */
    @Select("""
            SELECT COALESCE(NULLIF(subject, ''), '未分类') AS subject,
                   COALESCE(SUM(minutes), 0) AS minutes
              FROM study_record
             WHERE user_id = #{userId}
               AND study_date BETWEEN #{start} AND #{end}
             GROUP BY subject
             ORDER BY minutes DESC
            """)
    List<Map<String, Object>> sumMinutesBySubject(@Param("userId") Long userId,
                                                  @Param("start") LocalDate start,
                                                  @Param("end") LocalDate end);

    /**
     * 区间总时长（概览卡片）。
     */
    @Select("""
            SELECT COALESCE(SUM(minutes), 0)
              FROM study_record
             WHERE user_id = #{userId}
               AND study_date BETWEEN #{start} AND #{end}
            """)
    Integer sumMinutes(@Param("userId") Long userId,
                       @Param("start") LocalDate start,
                       @Param("end") LocalDate end);

    /**
     * 有学习记录的全部日期（倒序），用于算连续学习天数。
     * <p>只取日期列，走覆盖索引，比拉明细便宜得多。</p>
     */
    @Select("""
            SELECT DISTINCT study_date
              FROM study_record
             WHERE user_id = #{userId}
             ORDER BY study_date DESC
             LIMIT 400
            """)
    List<LocalDate> listStudyDates(@Param("userId") Long userId);

    /**
     * 周期内有学习记录的用户（周报定时任务的"活跃用户"口径，Day 24）。
     *
     * <p>口径说明：以学习记录为准而不是"所有注册用户" —— 没有学习行为的人
     * 生成一份"本周零学习"的报告既浪费 LLM 调用，对用户也是骚扰。
     * 后续如果加了任务行为口径（计划完成但没记时长），在这里 union 扩展即可。</p>
     */
    @Select("""
            SELECT DISTINCT user_id
              FROM study_record
             WHERE study_date BETWEEN #{start} AND #{end}
            """)
    List<Long> listActiveUserIds(@Param("start") LocalDate start,
                                 @Param("end") LocalDate end);
}
