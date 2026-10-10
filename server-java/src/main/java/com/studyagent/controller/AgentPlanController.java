package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.PlanTask;
import com.studyagent.entity.StudyPlan;
import com.studyagent.model.dto.AgentPlanCreateRequest;
import com.studyagent.model.vo.AgentPlanCreateVO;
import com.studyagent.service.PlanTaskService;
import com.studyagent.service.StudyPlanService;
import com.studyagent.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 计划落库接口。
 *
 * <p>放在 {@code /plan/agent-create}（与执行计划里约定的路径一致），
 * 但它是**内部接口**：由 Python 规划 Agent 调用，走 InternalTokenFilter 的
 * 令牌 + 来源校验，而不是 JWT。所以它虽然挂在 /plan 命名空间下，
 * 权限模型与其它 /plan 接口不同 —— 这里在注释里显式说明，避免后来者误解。</p>
 */
@Slf4j
@RestController
@RequestMapping("/plan")
@RequiredArgsConstructor
@Tag(name = "99-内部接口", description = "Agent 计划落库（需 X-Internal-Token）")
public class AgentPlanController {

    /** 与 Java 侧均摊版保持一致的上限口径 */
    private static final int MAX_TASKS = 2000;

    private final StudyPlanService studyPlanService;
    private final PlanTaskService planTaskService;
    private final SysUserService sysUserService;

    @PostMapping("/agent-create")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "落库 Agent 生成的计划与任务",
            description = "Python 规划 Agent 回调；任务直接采用 Agent 结果，不做均摊生成")
    public Result<AgentPlanCreateVO> agentCreate(@Valid @RequestBody AgentPlanCreateRequest request) {
        if (sysUserService.getById(request.getUserId()) == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "用户不存在：" + request.getUserId());
        }

        List<AgentPlanCreateRequest.Item> items = request.getTasks() == null
                ? List.of()
                : request.getTasks();
        if (items.size() > MAX_TASKS) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "任务数 " + items.size() + " 超过上限 " + MAX_TASKS);
        }

        LocalDate start = request.getStartDate() != null ? request.getStartDate() : LocalDate.now();
        if (request.getTargetDate().isBefore(start)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "目标考试日期不能早于开始日期");
        }

        // 1) 落计划。注意 source 固定为「Agent生成」，
        //    前端据此展示"AI 生成"标签，第 2 周的 source 字段就是为这一步预留的。
        StudyPlan plan = new StudyPlan();
        plan.setUserId(request.getUserId());
        plan.setTitle(request.getTitle().trim());
        plan.setExamType(request.getExamType());
        plan.setTargetDate(request.getTargetDate());
        plan.setStartDate(start);
        plan.setDailyMinutes(request.getDailyMinutes());
        plan.setStatus("进行中");
        plan.setSource("Agent生成");
        studyPlanService.save(plan);

        // 2) 落任务（不做均摊生成，直接用 Agent 结果）
        List<PlanTask> tasks = new ArrayList<>(items.size());
        int sort = 0;
        LocalDate lastDate = null;
        for (AgentPlanCreateRequest.Item item : items) {
            if (item.getTaskDate() == null || item.getTitle() == null || item.getTitle().isBlank()) {
                continue;
            }
            // 同一天内的顺序号从 0 重新开始，保证 sort 的语义是"当天第几条"
            if (!item.getTaskDate().equals(lastDate)) {
                sort = 0;
                lastDate = item.getTaskDate();
            }
            PlanTask task = new PlanTask();
            task.setPlanId(plan.getId());
            task.setUserId(request.getUserId());
            task.setTaskDate(item.getTaskDate());
            task.setTitle(item.getTitle().length() <= 128 ? item.getTitle() : item.getTitle().substring(0, 128));
            task.setContent(item.getContent());
            task.setSubject(item.getSubject());
            task.setEstimatedMinutes(item.getEstimatedMinutes() == null ? 60 : item.getEstimatedMinutes());
            task.setStatus("待办");
            task.setSort(sort++);
            tasks.add(task);
        }
        if (!tasks.isEmpty()) {
            planTaskService.saveBatch(tasks, 200);
        }

        log.info("Agent 计划落库成功 userId={} planId={} 任务数={}",
                request.getUserId(), plan.getId(), tasks.size());

        return Result.ok("计划已生成并入库", new AgentPlanCreateVO(plan.getId(), tasks.size()));
    }
}
