package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.WrongQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 错题 Mapper
 */
@Mapper
public interface WrongQuestionMapper extends BaseMapper<WrongQuestion> {

    /** 某用户在某时刻之后新增的错题数（周报"本周新增"用） */
    @Select("""
            SELECT COUNT(*)
              FROM wrong_question
             WHERE user_id = #{userId}
               AND deleted = 0
               AND create_time >= #{from}
            """)
    int countCreatedSince(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    /**
     * 未掌握错题数达到阈值的用户（错题复习提醒用，Day 26）。
     *
     * <p>一次 SQL 就把"谁需要提醒"算出来：如果先查所有用户再逐个 count，
     * 用户量一大就是 N+1 查询，定时任务会越跑越慢。</p>
     */
    @Select("""
            SELECT user_id AS userId, COUNT(*) AS cnt
              FROM wrong_question
             WHERE deleted = 0
               AND mastery = #{mastery}
             GROUP BY user_id
            HAVING cnt >= #{threshold}
            """)
    List<Map<String, Object>> listUsersWithMastery(@Param("mastery") String mastery,
                                                    @Param("threshold") long threshold);
}
