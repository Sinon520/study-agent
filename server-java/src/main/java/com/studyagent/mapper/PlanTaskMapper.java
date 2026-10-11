package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.PlanTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 计划任务 Mapper
 */
@Mapper
public interface PlanTaskMapper extends BaseMapper<PlanTask> {

    /**
     * 当天有**待办**任务（未完成且未跳过）的用户，用于学习提醒推送（Day 26）。
     *
     * <p>口径：任务日期 = 今天、未逻辑删除、状态既不是「完成」也不是「跳过」。
     * 只按日期过滤而不看状态的话，已经全部完成的用户也会被骚扰。</p>
     */
    @Select("""
            SELECT DISTINCT user_id
              FROM plan_task
             WHERE task_date = #{date}
               AND deleted = 0
               AND (status IS NULL OR status NOT IN ('完成', '跳过'))
            """)
    List<Long> listUserIdsWithPendingTasks(@Param("date") LocalDate date);

    /** 某用户当天的待办任务数 */
    @Select("""
            SELECT COUNT(*)
              FROM plan_task
             WHERE user_id   = #{userId}
               AND task_date = #{date}
               AND deleted   = 0
               AND (status IS NULL OR status NOT IN ('完成', '跳过'))
            """)
    int countPendingTasks(@Param("userId") Long userId, @Param("date") LocalDate date);
}
