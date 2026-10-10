import request from '@/utils/request'

/**
 * 复盘报告接口，对应 ReportController（第 4 周 Day 24）。
 *
 * 生成是异步的：generate() 只是提交任务（MQ），随后轮询 page() 直到新报告出现。
 */
export const reportApi = {
  /** 分页查询我的报告（按周期倒序，不含正文大字段） */
  page(params) {
    return request.get('/report/page', { params })
  },

  /** 报告详情：markdown 正文 + 统计数据 + 要点 + 建议 */
  detail(id) {
    return request.get(`/report/${id}`)
  },

  /**
   * 手动触发生成本人报告。
   * @param {{periodStart?: string, periodEnd?: string}} data 不传 = 本周一 ~ 今天
   */
  generate(data) {
    return request.post('/report/generate', data || {})
  },
}
