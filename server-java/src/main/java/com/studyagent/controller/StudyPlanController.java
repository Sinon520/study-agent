package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.entity.PlanTask;
import com.studyagent.model.dto.AgentPlanGenerateRequest;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.dto.PlanTaskRequest;
import com.studyagent.model.dto.PlanTaskStatusRequest;
import com.studyagent.model.dto.StudyPlanCreateRequest;
import com.studyagent.model.vo.AgentPlanGenerateVO;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.PlanTaskVO;
import com.studyagent.model.vo.StudyPlanVO;
import com.studyagent.model.vo.TodayTaskVO;
import com.studyagent.security.UserContext;
import com.studyagent.service.PlanTaskService;
import com.studyagent.service.StudyPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习计划与计划任务接口。
 *
 * <p>所有接口都强制带 {@code userId} 过滤（取自 JWT），
 * 不存在"只凭 id 就能读别人数据"的接口。</p>
 */
@RestController
@RequestMapping("/plan")
@RequiredArgsConstructor
@Tag(name = "03-学习计划", description = "计划 CRUD、任务生成、任务勾选完成")
@SecurityRequirement(name = "bearerAuth")
public class StudyPlanController {

    private final StudyPlanService studyPlanService;
    private final PlanTaskService planTaskService;

    // ------------------------------------------------------------------ 计划

    @GetMapping("/page")
    @Operation(summary = "分页查询学习计划", description = "返回带任务完成统计的计划列表")
    public Result<PageResult<StudyPlanVO>> page(
            PageQuery query,
            @Parameter(description = "状态：进行中/已完成/已放弃") @RequestParam(required = false) String status,
            @Parameter(description = "考试类型：考研/考公") @RequestParam(required = false) String examType) {
        return Result.ok(studyPlanService.pagePlans(UserContext.userId(), query, status, examType));
    }

    @PostMapping
    @Operation(summary = "创建学习计划",
            description = "创建后按目标日期与每日时长自动均摊生成逐日任务（source=手动）")
    public Result<StudyPlanVO> create(@Valid @RequestBody StudyPlanCreateRequest request) {
        return Result.ok("计划创建成功，已自动生成任务", studyPlanService.createPlan(UserContext.userId(), request));
    }

    @GetMapping("/{planId}")
    @Operation(summary = "计划详情", description = "含任务完成统计")
    public Result<StudyPlanVO> detail(@PathVariable Long planId) {
        return Result.ok(studyPlanService.getPlanDetail(UserContext.userId(), planId));
    }

    @PutMapping("/{planId}")
    @Operation(summary = "修改计划基础信息",
            description = "只改计划本身，不重新生成任务（避免冲掉已勾选的进度）")
    public Result<StudyPlanVO> update(@PathVariable Long planId,
                                      @Valid @RequestBody StudyPlanCreateRequest request) {
        return Result.ok("修改成功", studyPlanService.updatePlan(UserContext.userId(), planId, request));
    }

    @DeleteMapping("/{planId}")
    @Operation(summary = "删除计划", description = "逻辑删除，同时删掉其下所有任务")
    public Result<Void> delete(@PathVariable Long planId) {
        studyPlanService.deletePlan(UserContext.userId(), planId);
        return Result.ok("删除成功", null);
    }

    @PutMapping("/{planId}/status")
    @Operation(summary = "变更计划状态", description = "进行中/已完成/已放弃")
    public Result<Void> changeStatus(@PathVariable Long planId,
                                     @Parameter(description = "目标状态") @RequestParam String status) {
        studyPlanService.changePlanStatus(UserContext.userId(), planId, status);
        return Result.ok("状态已更新", null);
    }

    // ------------------------------------------------------------------ 任务

    @GetMapping("/task/list")
    @Operation(summary = "查询任务列表", description = "按计划与日期区间查询，计划详情页/日历视图用")
    public Result<List<PlanTaskVO>> listTasks(
            @Parameter(description = "计划 ID，不传查全部计划") @RequestParam(required = false) Long planId,
            @Parameter(description = "开始日期") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "状态：待办/完成/跳过") @RequestParam(required = false) String status) {
        List<PlanTask> tasks = planTaskService.listTasks(UserContext.userId(), planId, startDate, endDate, status);
        return Result.ok(tasks.stream().map(PlanTaskVO::from).toList());
    }

    @GetMapping("/task/today")
    @Operation(summary = "今日任务", description = "小程序首页/PC 首页：今天要做什么 + 完成情况汇总")
    public Result<TodayTaskVO> today(
            @Parameter(description = "日期，不传默认今天") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(planTaskService.todayTasks(UserContext.userId(), date));
    }

    @PostMapping("/task")
    @Operation(summary = "新增任务", description = "在指定计划下手工加一条任务")
    public Result<PlanTaskVO> addTask(@Valid @RequestBody PlanTaskRequest request) {
        return Result.ok("任务已添加", PlanTaskVO.from(planTaskService.addTask(UserContext.userId(), request)));
    }

    @PutMapping("/task/{taskId}")
    @Operation(summary = "修改任务")
    public Result<PlanTaskVO> updateTask(@PathVariable Long taskId,
                                         @Valid @RequestBody PlanTaskRequest request) {
        return Result.ok("修改成功", PlanTaskVO.from(planTaskService.updateTask(UserContext.userId(), taskId, request)));
    }

    @DeleteMapping("/task/{taskId}")
    @Operation(summary = "删除任务", description = "逻辑删除，同时移除它产生的学习记录")
    public Result<Void> deleteTask(@PathVariable Long taskId) {
        planTaskService.deleteTask(UserContext.userId(), taskId);
        return Result.ok("删除成功", null);
    }

    @PutMapping("/task/status")
    @Operation(summary = "勾选/取消任务完成",
            description = "置为完成时自动写 study_record（进度统计的数据源）；改回待办会移除该记录")
    public Result<PlanTaskVO> changeTaskStatus(@Valid @RequestBody PlanTaskStatusRequest request) {
        return Result.ok("状态已更新", PlanTaskVO.from(planTaskService.changeStatus(UserContext.userId(), request)));
    }

    // ------------------------------------------------------------ AI 生成计划

    /**
     * 「AI 生成计划」入口。
     *
     * <p>编排：Java 校验登录用户 → 调 Python 的 LangGraph 规划 Agent 生成逐日任务
     * → Java 在本方法内落库（计划 + 任务，source=Agent生成）→ 返回结果。</p>
     *
     * <p>为什么不让 Python 直接落库：用户侧链路必须在一个事务里完成，
     * 且 userId 必须来自 JWT。让 Python 拿 userId 去调内部接口，
     * 等于把用户上下文交给了另一个进程，配额与审计也无处安放。</p>
     */
    @PostMapping("/agent-generate")
    @Operation(summary = "AI 生成学习计划（LangGraph 规划 Agent）",
            description = "生成逐日任务并自动入库，source=Agent生成。"
                    + "Agent 内部会做日期连续性与每日时长校验，不合格自动重试（最多 2 次）")
    public Result<AgentPlanGenerateVO> agentGenerate(@Valid @RequestBody AgentPlanGenerateRequest request) {
        Long userId = UserContext.userId();
        return Result.ok("计划已生成", studyPlanService.generateByAgent(userId, request));
    }
}
