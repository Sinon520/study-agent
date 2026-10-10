import request from '@/utils/request'

/**
 * 学习计划与任务接口，对应 StudyPlanController。
 */
export const planApi = {
  /** 分页查询计划 */
  page(params) {
    return request.get('/plan/page', { params })
  },

  /** 创建计划（会自动均摊生成任务） */
  create(data) {
    return request.post('/plan', data)
  },

  /** 计划详情（含任务统计） */
  detail(planId) {
    return request.get(`/plan/${planId}`)
  },

  /** 修改计划基础信息 */
  update(planId, data) {
    return request.put(`/plan/${planId}`, data)
  },

  /** 删除计划（逻辑删，连带任务） */
  remove(planId) {
    return request.delete(`/plan/${planId}`)
  },

  /** 变更计划状态 */
  changeStatus(planId, status) {
    return request.put(`/plan/${planId}/status`, null, { params: { status } })
  },

  /** 任务列表（按计划与日期区间） */
  listTasks(params) {
    return request.get('/plan/task/list', { params })
  },

  /** 今日任务 */
  today(date) {
    return request.get('/plan/task/today', { params: date ? { date } : {} })
  },

  /** 新增任务 */
  addTask(data) {
    return request.post('/plan/task', data)
  },

  /** 修改任务 */
  updateTask(taskId, data) {
    return request.put(`/plan/task/${taskId}`, data)
  },

  /** 删除任务 */
  removeTask(taskId) {
    return request.delete(`/plan/task/${taskId}`)
  },

  /** 勾选/取消任务完成 */
  changeTaskStatus(data) {
    return request.put('/plan/task/status', data)
  },

  /**
   * AI 生成计划（LangGraph 规划 Agent）。
   * 生成 + 落库都在后端完成，成功后直接刷新列表即可。
   * 首次调用会触发本地 BGE-M3 模型加载，故超时放宽到 5 分钟。
   */
  agentGenerate(data) {
    return request.post('/plan/agent-generate', data, { timeout: 300000 })
  },
}
