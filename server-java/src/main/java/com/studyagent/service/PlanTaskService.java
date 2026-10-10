package com.studyagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.studyagent.entity.PlanTask;
import com.studyagent.model.dto.PlanTaskRequest;
import com.studyagent.model.dto.PlanTaskStatusRequest;
import com.studyagent.model.vo.TodayTaskVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 计划任务 业务接口。
 */
public interface PlanTaskService extends IService<PlanTask> {

    /** 按条件查询任务列表（计划详情 / 日历 / 今日待办共用） */
    List<PlanTask> listTasks(Long userId, Long planId, LocalDate start, LocalDate end, String status);

    /** 新增单条任务 */
    PlanTask addTask(Long userId, PlanTaskRequest request);

    /** 修改单条任务 */
    PlanTask updateTask(Long userId, Long taskId, PlanTaskRequest request);

    /** 逻辑删除单条任务 */
    void deleteTask(Long userId, Long taskId);

    /**
     * 变更任务状态。
     * <p>置为"完成"时写一条 {@code study_record}（进度追踪的唯一数据源）；
     * 从"完成"改回"待办"时删掉对应记录，避免统计重复累计。</p>
     */
    PlanTask changeStatus(Long userId, PlanTaskStatusRequest request);

    /** 今日任务视图：今天要做什么 + 完成情况汇总 */
    TodayTaskVO todayTasks(Long userId, LocalDate date);

    /** 取当前用户拥有的任务，不存在或不属于该用户时抛异常 */
    PlanTask requireOwnedTask(Long userId, Long taskId);
}
