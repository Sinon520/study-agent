package com.studyagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.studyagent.common.Result;
import com.studyagent.common.cache.CacheKeys;
import com.studyagent.common.cache.RedisCacheHelper;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.PlanTask;
import com.studyagent.entity.StudyPlan;
import com.studyagent.mapper.StudyPlanMapper;
import com.studyagent.model.dto.AgentPlanGenerateRequest;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.dto.StudyPlanCreateRequest;
import com.studyagent.model.vo.AgentPlanGenerateVO;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.StudyPlanVO;
import com.studyagent.service.PlanTaskGenerator;
import com.studyagent.service.PlanTaskService;
import com.studyagent.service.PythonClient;
import com.studyagent.service.StudyPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习计划 业务实现。
 *
 * <h3>计划详情缓存（Day 27）</h3>
 * <p>{@code getPlanDetail} 每次要查计划行 + 该计划全部任务做内存聚合。
 * 计划页/详情抽屉会反复打开同一份数据，读多写少，适合 Cache Aside：
 * 读 → 缓存（key 含 userId，命中即等于归属校验通过）；写 → 事务提交后删缓存。
 * 注意"任务"也是计划详情的一部分（taskDone/totalMinutes 等统计字段），
 * 所以任务的增删改在 {@code PlanTaskServiceImpl} 里同样要删这份缓存。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudyPlanServiceImpl extends ServiceImpl<StudyPlanMapper, StudyPlan> implements StudyPlanService {

    private static final String STATUS_ONGOING = "进行中";
    private static final String STATUS_FINISHED = "已完成";
    private static final String STATUS_ABANDONED = "已放弃";
    private static final List<String> VALID_STATUS = List.of(STATUS_ONGOING, STATUS_FINISHED, STATUS_ABANDONED);

    private final PlanTaskService planTaskService;
    private final PlanTaskGenerator planTaskGenerator;
    private final PythonClient pythonClient;
    private final RedisCacheHelper cacheHelper;

    // ------------------------------------------------------------------ 查询

    @Override
    public PageResult<StudyPlanVO> pagePlans(Long userId, PageQuery query, String status, String examType) {
        Page<StudyPlan> page = query.toPage();
        var wrapper = Wrappers.<StudyPlan>lambdaQuery()
                .eq(StudyPlan::getUserId, userId)
                .eq(StringUtils.hasText(status), StudyPlan::getStatus, status)
                .eq(StringUtils.hasText(examType), StudyPlan::getExamType, examType)
                // 进行中的计划排前面，再按目标日期升序（越急的越靠前）
                .orderByAsc(StudyPlan::getStatus)
                .orderByAsc(StudyPlan::getTargetDate)
                .orderByDesc(StudyPlan::getId);

        Page<StudyPlan> result = page(page, wrapper);

        // 一次性把这批计划的任务统计查出来，避免 N+1
        Map<Long, TaskStats> statsMap = loadTaskStats(userId, result.getRecords().stream().map(StudyPlan::getId).toList());

        return PageResult.of(result, plan -> {
            StudyPlanVO vo = StudyPlanVO.from(plan);
            fillStats(vo, statsMap.get(plan.getId()));
            return vo;
        });
    }

    @Override
    public StudyPlanVO getPlanDetail(Long userId, Long planId) {
        // Cache Aside：key 含 userId，命中即等价于"归属校验已通过"（详见 CacheKeys 注释）。
        // miss 时 loader 里先做归属校验再查库 —— 越权请求走 loader 直接抛 403，不会产生缓存。
        return cacheHelper.getOrLoad(CacheKeys.planDetail(userId, planId),
                StudyPlanVO.class, CacheKeys.PLAN_DETAIL_TTL, () -> {
                    StudyPlan plan = requireOwnedPlan(userId, planId);
                    StudyPlanVO vo = StudyPlanVO.from(plan);
                    fillStats(vo, loadTaskStats(userId, List.of(planId)).get(planId));
                    return vo;
                });
    }

    // ------------------------------------------------------------------ 写入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StudyPlanVO createPlan(Long userId, StudyPlanCreateRequest request) {
        LocalDate start = request.getStartDate() != null ? request.getStartDate() : LocalDate.now();
        if (request.getTargetDate().isBefore(start)) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "目标考试日期（" + request.getTargetDate() + "）不能早于开始日期（" + start + "）");
        }

        StudyPlan plan = new StudyPlan();
        plan.setUserId(userId);
        plan.setTitle(request.getTitle().trim());
        plan.setExamType(request.getExamType());
        plan.setTargetDate(request.getTargetDate());
        plan.setStartDate(start);
        plan.setDailyMinutes(request.getDailyMinutes());
        plan.setStatus(STATUS_ONGOING);
        plan.setSource("手动");
        save(plan);

        // 计划落库拿到 id 之后才生成任务：任务表需要 plan_id
        List<PlanTask> tasks = planTaskGenerator.generateBySpread(plan, parseSubjects(request.getSubjects()));
        planTaskService.saveBatch(tasks, 200);

        log.info("创建学习计划 planId={} userId={} 自动生成任务 {} 条", plan.getId(), userId, tasks.size());

        StudyPlanVO vo = StudyPlanVO.from(plan);
        fillStats(vo, loadTaskStats(userId, List.of(plan.getId())).get(plan.getId()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StudyPlanVO updatePlan(Long userId, Long planId, StudyPlanCreateRequest request) {
        StudyPlan plan = requireOwnedPlan(userId, planId);

        LocalDate start = request.getStartDate() != null ? request.getStartDate() : plan.getStartDate();
        if (request.getTargetDate().isBefore(start)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "目标考试日期不能早于开始日期");
        }

        plan.setTitle(request.getTitle().trim());
        plan.setExamType(request.getExamType());
        plan.setTargetDate(request.getTargetDate());
        plan.setStartDate(start);
        plan.setDailyMinutes(request.getDailyMinutes());
        updateById(plan);
        cacheHelper.evictAfterCommit(CacheKeys.planDetail(userId, planId));

        log.info("修改学习计划 planId={} userId={}（不重新生成任务，避免冲掉已勾选进度）", planId, userId);

        StudyPlanVO vo = StudyPlanVO.from(plan);
        fillStats(vo, loadTaskStats(userId, List.of(planId)).get(planId));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePlan(Long userId, Long planId) {
        requireOwnedPlan(userId, planId);
        // 先删任务再删计划：两个都是逻辑删除，保证列表页不会出现"孤儿任务"
        planTaskService.remove(Wrappers.<PlanTask>lambdaQuery().eq(PlanTask::getPlanId, planId));
        removeById(planId);
        cacheHelper.evictAfterCommit(CacheKeys.planDetail(userId, planId));
        log.info("删除学习计划 planId={} userId={}（含其下任务，逻辑删）", planId, userId);
    }

    @Override
    public void changePlanStatus(Long userId, Long planId, String status) {
        if (!VALID_STATUS.contains(status)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "计划状态不合法：" + status + "，可选 " + VALID_STATUS);
        }
        StudyPlan plan = requireOwnedPlan(userId, planId);
        plan.setStatus(status);
        updateById(plan);
        cacheHelper.evictAfterCommit(CacheKeys.planDetail(userId, planId));
        log.info("变更计划状态 planId={} status={}", planId, status);
    }

    // ------------------------------------------------------------------ AI 生成

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentPlanGenerateVO generateByAgent(Long userId, AgentPlanGenerateRequest request) {
        // 1) 调 Python 规划 Agent（只生成，不落库）
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        if (StringUtils.hasText(request.getTitle())) {
            payload.put("title", request.getTitle().trim());
        }
        payload.put("examType", request.getExamType());
        payload.put("targetDate", request.getTargetDate().toString());
        payload.put("dailyMinutes", request.getDailyMinutes());
        payload.put("subjects", parseSubjects(request.getSubjects()));
        if (StringUtils.hasText(request.getLevel())) {
            payload.put("level", request.getLevel());
        }

        JsonNode result = pythonClient.generatePlan(payload);
        JsonNode tasksNode = result.get("tasks");
        if (tasksNode == null || !tasksNode.isArray() || tasksNode.isEmpty()) {
            throw new BizException(Result.CODE_FAIL,
                    "AI 没有生成任何任务，请检查目标日期与每日时长是否合理后重试");
        }

        AgentPlanGenerateVO vo = new AgentPlanGenerateVO();
        vo.setTitle(result.path("title").asText(request.getTitle()));
        vo.setTargetDate(result.path("targetDate").asText(request.getTargetDate().toString()));
        vo.setDailyMinutes(result.path("dailyMinutes").asInt(request.getDailyMinutes()));
        vo.setDays(result.path("days").asInt(0));
        vo.setTotalTasks(tasksNode.size());
        vo.setRetries(result.path("retries").asInt(0));
        vo.setValid(result.path("valid").asBoolean(true));
        List<String> warnings = new ArrayList<>();
        for (JsonNode item : result.path("warnings")) {
            warnings.add(item.asText());
        }
        vo.setWarnings(warnings);

        if (!Boolean.TRUE.equals(request.getPersist())) {
            return vo;
        }

        // 2) 落库（计划 + 任务），source 固定为「Agent生成」
        StudyPlan plan = new StudyPlan();
        plan.setUserId(userId);
        plan.setTitle(vo.getTitle() == null ? "AI 生成计划" : vo.getTitle());
        plan.setExamType(request.getExamType());
        plan.setTargetDate(request.getTargetDate());
        plan.setStartDate(LocalDate.now());
        plan.setDailyMinutes(vo.getDailyMinutes());
        plan.setStatus(STATUS_ONGOING);
        plan.setSource("Agent生成");
        save(plan);

        List<PlanTask> tasks = new ArrayList<>(tasksNode.size());
        int sort = 0;
        LocalDate lastDate = null;
        for (JsonNode item : tasksNode) {
            String dateText = item.path("taskDate").asText("");
            String title = item.path("title").asText("");
            if (dateText.isBlank() || title.isBlank()) {
                continue;
            }
            LocalDate taskDate;
            try {
                taskDate = LocalDate.parse(dateText.substring(0, 10));
            } catch (Exception e) {
                log.warn("跳过日期无法解析的 Agent 任务: {}", dateText);
                continue;
            }
            if (!taskDate.equals(lastDate)) {
                sort = 0;
                lastDate = taskDate;
            }
            PlanTask task = new PlanTask();
            task.setPlanId(plan.getId());
            task.setUserId(userId);
            task.setTaskDate(taskDate);
            task.setTitle(title.length() <= 128 ? title : title.substring(0, 128));
            task.setContent(item.path("content").asText(null));
            task.setSubject(item.path("subject").asText(null));
            task.setEstimatedMinutes(item.path("estimatedMinutes").asInt(60));
            task.setStatus("待办");
            task.setSort(sort++);
            tasks.add(task);
        }
        if (!tasks.isEmpty()) {
            planTaskService.saveBatch(tasks, 200);
        }

        vo.setPlanId(plan.getId());
        vo.setSavedTasks(tasks.size());
        log.info("AI 生成计划落库成功 userId={} planId={} 任务数={} 重试={} 告警={}",
                userId, plan.getId(), tasks.size(), vo.getRetries(), warnings.size());
        return vo;
    }

    // ------------------------------------------------------------------ 越权防护

    @Override
    public StudyPlan requireOwnedPlan(Long userId, Long planId) {
        StudyPlan plan = getById(planId);
        if (plan == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "计划不存在或已被删除：" + planId);
        }
        if (!plan.getUserId().equals(userId)) {
            // 明确区分"不存在"和"不是你的"有风险（能被用来探测 id 是否存在），
            // 但这里对调用方是同一套账号体系，提示清楚更利于前端排查；生产可统一成"无权访问"。
            log.warn("水平越权拦截：userId={} 试图访问 planId={}（属于 userId={}）",
                    userId, planId, plan.getUserId());
            throw new BizException(Result.CODE_FORBIDDEN, "无权访问该计划");
        }
        return plan;
    }

    // ------------------------------------------------------------------ 统计

    /** 一个计划的任务统计中间结果 */
    private record TaskStats(int total, int done, int skipped, int totalMinutes) {
        static TaskStats empty() {
            return new TaskStats(0, 0, 0, 0);
        }
    }

    /**
     * 批量取任务统计。
     *
     * <p>实现上直接查这批计划的所有任务后在内存里聚合，而不是每个计划发一条
     * {@code group by}：单用户的计划数是个位数量级，任务总量也就几百条，
     * 一次查询 + 内存聚合比 N 次聚合查询更快，也更容易加缓存。</p>
     */
    private Map<Long, TaskStats> loadTaskStats(Long userId, List<Long> planIds) {
        Map<Long, TaskStats> map = new HashMap<>();
        if (planIds == null || planIds.isEmpty()) {
            return map;
        }
        List<PlanTask> tasks = planTaskService.list(Wrappers.<PlanTask>lambdaQuery()
                .eq(PlanTask::getUserId, userId)
                .in(PlanTask::getPlanId, planIds));

        Map<Long, int[]> acc = new HashMap<>();
        for (PlanTask task : tasks) {
            int[] a = acc.computeIfAbsent(task.getPlanId(), k -> new int[4]);
            a[0]++;                                                          // total
            if ("完成".equals(task.getStatus())) {
                a[1]++;                                                     // done
            } else if ("跳过".equals(task.getStatus())) {
                a[2]++;                                                     // skipped
            }
            if (task.getEstimatedMinutes() != null) {
                a[3] += task.getEstimatedMinutes();                          // totalMinutes
            }
        }
        for (Long planId : planIds) {
            int[] a = acc.get(planId);
            map.put(planId, a == null ? TaskStats.empty() : new TaskStats(a[0], a[1], a[2], a[3]));
        }
        return map;
    }

    private void fillStats(StudyPlanVO vo, TaskStats stats) {
        TaskStats s = stats == null ? TaskStats.empty() : stats;
        vo.setTaskTotal(s.total());
        vo.setTaskDone(s.done());
        vo.setTaskSkipped(s.skipped());
        vo.setTotalMinutes(s.totalMinutes());
        // 完成率的分母用"总数 - 跳过"：被跳过的不该拉低完成率
        int denominator = s.total() - s.skipped();
        vo.setProgress(denominator <= 0 ? 0.0
                : Math.round(s.done() * 1000.0 / denominator) / 10.0);
    }

    private List<String> parseSubjects(String subjects) {
        if (!StringUtils.hasText(subjects)) {
            return new ArrayList<>();
        }
        return Arrays.stream(subjects.split("[,，、\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }
}
