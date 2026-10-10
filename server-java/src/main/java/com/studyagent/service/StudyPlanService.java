package com.studyagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.studyagent.entity.StudyPlan;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.dto.StudyPlanCreateRequest;
import com.studyagent.model.vo.PageResult;
import com.studyagent.model.vo.StudyPlanVO;

/**
 * 学习计划 业务接口。
 *
 * <p>在 MyBatis-Plus 的 {@link IService} 之上扩展业务动作；
 * {@code IService} 提供的 {@code save/getById/updateById/removeById} 等基础方法继续可用。</p>
 */
public interface StudyPlanService extends IService<StudyPlan> {

    /** 分页查询当前用户的计划（可按状态、考试类型过滤） */
    PageResult<StudyPlanVO> pagePlans(Long userId, PageQuery query, String status, String examType);

    /** 创建计划，并按每日时长均摊生成 plan_task */
    StudyPlanVO createPlan(Long userId, StudyPlanCreateRequest request);

    /** 计划详情（含任务完成统计） */
    StudyPlanVO getPlanDetail(Long userId, Long planId);

    /** 修改计划基础信息（不重新生成任务，避免把用户已勾选的任务冲掉） */
    StudyPlanVO updatePlan(Long userId, Long planId, StudyPlanCreateRequest request);

    /** 逻辑删除计划，同时删掉它下面的任务 */
    void deletePlan(Long userId, Long planId);

    /** 变更计划状态：进行中/已完成/已放弃 */
    void changePlanStatus(Long userId, Long planId, String status);

    /**
     * 取当前用户拥有的计划，不存在或不属于该用户时抛异常。
     * <p>所有涉及单个计划的操作都必须先过这一层，
     * 从根上避免"改 URL 里的 planId 就能操作别人数据"的水平越权。</p>
     */
    StudyPlan requireOwnedPlan(Long userId, Long planId);
}
