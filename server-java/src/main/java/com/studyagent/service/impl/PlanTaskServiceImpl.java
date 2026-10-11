package com.studyagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.Result;
import com.studyagent.common.cache.CacheKeys;
import com.studyagent.common.cache.RedisCacheHelper;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.PlanTask;
import com.studyagent.entity.StudyPlan;
import com.studyagent.entity.StudyRecord;
import com.studyagent.mapper.PlanTaskMapper;
import com.studyagent.mapper.StudyPlanMapper;
import com.studyagent.model.dto.PlanTaskRequest;
import com.studyagent.model.dto.PlanTaskStatusRequest;
import com.studyagent.model.vo.PlanTaskVO;
import com.studyagent.model.vo.TodayTaskVO;
import com.studyagent.service.PlanTaskService;
import com.studyagent.service.StudyRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计划任务 业务实现。
 *
 * <h3>任务状态与学习记录的一致性</h3>
 * <p>{@code study_record} 是进度统计的唯一数据源，而它由"任务被勾选完成"这个动作产生。
 * 所以这里必须处理三种状态迁移，否则统计会算错：</p>
 * <ul>
 *   <li>待办/跳过 → 完成：写一条 study_record；</li>
 *   <li>完成 → 待办（用户点错了要取消）：删掉那条 study_record；</li>
 *   <li>完成 → 完成（重复提交）：先删再写，保证幂等，不会累计两条。</li>
 * </ul>
 *
 * <h3>为什么这里注入的是 StudyPlanMapper 而不是 StudyPlanService</h3>
 * <p>{@code StudyPlanServiceImpl} 需要 {@link com.studyagent.service.PlanTaskService}
 * 来批量落任务，如果这里再反向依赖 {@code StudyPlanService}，两个 Bean 就构成循环依赖，
 * Spring Boot 2.6+ 默认直接启动失败。校验计划归属只需要一次按主键查询，
 * 用 Mapper 完全够用，同时把循环依赖从设计上消除（而不是靠
 * {@code spring.main.allow-circular-references=true} 掩盖）。</p>
 *
 * <h3>任务写操作要连带失效计划详情缓存（Day 27）</h3>
 * <p>计划详情里有 taskTotal / taskDone / progress / totalMinutes 等统计字段，
 * 它们来自任务表 —— 任务的任何增删改都会让"计划详情"的展示随之变化。
 * 这个依赖不在同一个文件里，最容易漏；四个写方法（增/改/删/状态）都要删缓存。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanTaskServiceImpl extends ServiceImpl<PlanTaskMapper, PlanTask> implements PlanTaskService {

    private static final String STATUS_TODO = "待办";
    private static final String STATUS_DONE = "完成";
    private static final String STATUS_SKIPPED = "跳过";
    private static final List<String> VALID_STATUS = List.of(STATUS_TODO, STATUS_DONE, STATUS_SKIPPED);

    private final StudyRecordService studyRecordService;
    private final StudyPlanMapper studyPlanMapper;
    private final RedisCacheHelper cacheHelper;

    // ------------------------------------------------------------------ 查询

    @Override
    public List<PlanTask> listTasks(Long userId, Long planId, LocalDate start, LocalDate end, String status) {
        return list(Wrappers.<PlanTask>lambdaQuery()
                .eq(PlanTask::getUserId, userId)
                .eq(planId != null, PlanTask::getPlanId, planId)
                .ge(start != null, PlanTask::getTaskDate, start)
                .le(end != null, PlanTask::getTaskDate, end)
                .eq(status != null && !status.isBlank(), PlanTask::getStatus, status)
                .orderByAsc(PlanTask::getTaskDate)
                .orderByAsc(PlanTask::getSort)
                .orderByAsc(PlanTask::getId));
    }

    @Override
    public TodayTaskVO todayTasks(Long userId, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        List<PlanTask> tasks = list(Wrappers.<PlanTask>lambdaQuery()
                .eq(PlanTask::getUserId, userId)
                .eq(PlanTask::getTaskDate, day)
                .orderByAsc(PlanTask::getSort)
                .orderByAsc(PlanTask::getId));

        TodayTaskVO vo = new TodayTaskVO();
        vo.setDate(day);
        vo.setTasks(tasks.stream().map(PlanTaskVO::from).toList());
        vo.setTotal(tasks.size());

        int done = 0, skipped = 0, planMinutes = 0;
        for (PlanTask task : tasks) {
            int minutes = task.getEstimatedMinutes() == null ? 0 : task.getEstimatedMinutes();
            planMinutes += minutes;
            if (STATUS_DONE.equals(task.getStatus())) {
                done++;
                vo.setDoneMinutes(vo.getDoneMinutes() + minutes);
            } else if (STATUS_SKIPPED.equals(task.getStatus())) {
                skipped++;
            }
        }
        vo.setDone(done);
        vo.setSkipped(skipped);
        vo.setTodo(tasks.size() - done - skipped);
        vo.setPlanMinutes(planMinutes);

        int denominator = tasks.size() - skipped;
        double progress = denominator <= 0 ? 0.0 : Math.round(done * 1000.0 / denominator) / 10.0;
        vo.setProgress(progress);
        vo.setTip(buildTip(tasks.size(), done, progress));
        return vo;
    }

    @Override
    public PlanTask requireOwnedTask(Long userId, Long taskId) {
        PlanTask task = getById(taskId);
        if (task == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "任务不存在或已被删除：" + taskId);
        }
        if (!task.getUserId().equals(userId)) {
            log.warn("水平越权拦截：userId={} 试图访问 taskId={}（属于 userId={}）",
                    userId, taskId, task.getUserId());
            throw new BizException(Result.CODE_FORBIDDEN, "无权访问该任务");
        }
        return task;
    }

    // ------------------------------------------------------------------ 写入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanTask addTask(Long userId, PlanTaskRequest request) {
        // 校验 planId 归属，防止往别人的计划里塞任务
        StudyPlan plan = requireOwnedPlan(userId, request.getPlanId());
        validateTaskDate(plan, request.getTaskDate());

        PlanTask task = new PlanTask();
        task.setPlanId(plan.getId());
        task.setUserId(userId);
        task.setTaskDate(request.getTaskDate());
        task.setTitle(request.getTitle().trim());
        task.setContent(request.getContent());
        task.setSubject(request.getSubject());
        task.setEstimatedMinutes(request.getEstimatedMinutes() == null ? 60 : request.getEstimatedMinutes());
        task.setStatus(STATUS_TODO);
        task.setSort(request.getSort() == null ? 0 : request.getSort());
        save(task);
        cacheHelper.evictAfterCommit(CacheKeys.planDetail(userId, plan.getId()));

        log.info("新增任务 taskId={} planId={} userId={}", task.getId(), plan.getId(), userId);
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanTask updateTask(Long userId, Long taskId, PlanTaskRequest request) {
        PlanTask task = requireOwnedTask(userId, taskId);
        Long originalPlanId = task.getPlanId();

        // 允许把任务挪到自己的另一个计划下，但同样要校验目标计划归属
        StudyPlan targetPlan = studyPlanMapper.selectById(task.getPlanId());
        if (request.getPlanId() != null && !request.getPlanId().equals(task.getPlanId())) {
            targetPlan = requireOwnedPlan(userId, request.getPlanId());
            task.setPlanId(request.getPlanId());
        }
        // 任务日期必须落在所属计划范围内（口径与创建计划时一致）
        validateTaskDate(targetPlan, request.getTaskDate());

        task.setTaskDate(request.getTaskDate());
        task.setTitle(request.getTitle().trim());
        task.setContent(request.getContent());
        task.setSubject(request.getSubject());
        if (request.getEstimatedMinutes() != null) {
            task.setEstimatedMinutes(request.getEstimatedMinutes());
        }
        if (request.getSort() != null) {
            task.setSort(request.getSort());
        }
        updateById(task);
        // 任务可能被挪走：原计划与新计划的详情缓存都要删，否则两个计划的统计都会对不上
        cacheHelper.evictAfterCommit(CacheKeys.planDetail(userId, originalPlanId));
        if (!originalPlanId.equals(task.getPlanId())) {
            cacheHelper.evictAfterCommit(CacheKeys.planDetail(userId, task.getPlanId()));
        }
        log.info("修改任务 taskId={} userId={}", taskId, userId);
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long userId, Long taskId) {
        PlanTask task = requireOwnedTask(userId, taskId);
        // 任务被删掉时，它产生的学习记录也应移除，否则进度统计会凭空多出时长
        studyRecordService.remove(Wrappers.<StudyRecord>lambdaQuery().eq(StudyRecord::getPlanTaskId, taskId));
        removeById(taskId);
        cacheHelper.evictAfterCommit(CacheKeys.planDetail(userId, task.getPlanId()));
        log.info("删除任务 taskId={} userId={}（含其学习记录）", taskId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanTask changeStatus(Long userId, PlanTaskStatusRequest request) {
        if (!VALID_STATUS.contains(request.getStatus())) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "任务状态不合法：" + request.getStatus() + "，可选 " + VALID_STATUS);
        }
        PlanTask task = requireOwnedTask(userId, request.getTaskId());
        String target = request.getStatus();
        String previous = task.getStatus();

        // 幂等处理：先清掉这个任务已有的学习记录，再按目标状态决定是否重建
        studyRecordService.remove(Wrappers.<StudyRecord>lambdaQuery().eq(StudyRecord::getPlanTaskId, task.getId()));

        task.setStatus(target);
        if (STATUS_DONE.equals(target)) {
            task.setFinishTime(LocalDateTime.now());
            studyRecordService.save(buildRecord(task, request));
        } else {
            // 回到待办/跳过：清空完成时间，语义上"没完成"
            task.setFinishTime(null);
        }
        updateById(task);
        cacheHelper.evictAfterCommit(CacheKeys.planDetail(userId, task.getPlanId()));

        log.info("任务状态变更 taskId={} {} -> {} userId={}", task.getId(), previous, target, userId);
        return task;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 校验计划存在且属于该用户。
     * <p>用 Mapper 而不是 StudyPlanService，是为了避免与 StudyPlanServiceImpl
     * 构成循环依赖（详见类注释）。</p>
     */
    private StudyPlan requireOwnedPlan(Long userId, Long planId) {
        StudyPlan plan = studyPlanMapper.selectById(planId);
        if (plan == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "计划不存在或已被删除：" + planId);
        }
        if (!plan.getUserId().equals(userId)) {
            log.warn("水平越权拦截：userId={} 试图向 planId={}（属于 userId={}）添加任务",
                    userId, planId, plan.getUserId());
            throw new BizException(Result.CODE_FORBIDDEN, "无权操作该计划");
        }
        return plan;
    }

    /**
     * 任务日期必须落在所属计划的起止范围内。
     * <p>不回溯校验存量任务 —— 计划日期被编辑后，历史任务可能"越界"，
     * 那是用户自己的选择；这里只拦新增/修改的入口。</p>
     */
    private void validateTaskDate(StudyPlan plan, LocalDate taskDate) {
        if (plan == null || taskDate == null) {
            return;
        }
        LocalDate start = plan.getStartDate();
        LocalDate end = plan.getTargetDate();
        if ((start != null && taskDate.isBefore(start)) || (end != null && taskDate.isAfter(end))) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "任务日期 " + taskDate + " 不在计划范围（" + start + " ~ " + end + "）内");
        }
    }

    private StudyRecord buildRecord(PlanTask task, PlanTaskStatusRequest request) {
        StudyRecord record = new StudyRecord();
        record.setUserId(task.getUserId());
        record.setPlanTaskId(task.getId());
        // 统计维度用任务日期而不是"当前时间"：用户补勾昨天的任务时，
        // 时长必须算在昨天，否则"近 30 天曲线"会失真
        record.setStudyDate(task.getTaskDate());
        record.setMinutes(request.getActualMinutes() != null && request.getActualMinutes() > 0
                ? request.getActualMinutes()
                : (task.getEstimatedMinutes() == null ? 0 : task.getEstimatedMinutes()));
        record.setSubject(task.getSubject());
        record.setNote(request.getNote());
        return record;
    }

    private String buildTip(int total, int done, double progress) {
        if (total == 0) {
            return "今天没有安排任务，去创建一个学习计划吧～";
        }
        if (done == total) {
            return "今日任务全部完成，收工！记得明天继续保持 💪";
        }
        if (progress >= 60) {
            return "已经完成大半了，再坚持一下就能全部勾完。";
        }
        if (progress > 0) {
            return "已经开始了，节奏保持住，专注 25 分钟再休息。";
        }
        return "今天还没开始，从最简单的那一条任务入手最容易启动。";
    }
}
