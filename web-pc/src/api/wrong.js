import request, { downloadFile } from '@/utils/request'

/**
 * 错题本接口，对应 WrongQuestionController。
 *
 * 注意：标签是数组，用 axios 默认的 params 序列化会变成 tags[]=a&tags[]=b，
 * 后端 Spring 的 @RequestParam List 认不出来。这里统一用 tags=a&tags=b 形式，
 * 通过 paramsSerializer 指定 repeat 模式。
 */
const tagParamsSerializer = {
  paramsSerializer: {
    serialize(params) {
      const search = new URLSearchParams()
      Object.entries(params || {}).forEach(([key, value]) => {
        if (value === undefined || value === null || value === '') return
        if (Array.isArray(value)) {
          value.forEach((item) => search.append(key, item))
        } else {
          search.append(key, value)
        }
      })
      return search.toString()
    },
  },
}

export const wrongApi = {
  /** 分页查询错题 */
  page(params) {
    return request.get('/wrong-question/page', { params, ...tagParamsSerializer })
  },

  /** 错题详情 */
  detail(id) {
    return request.get(`/wrong-question/${id}`)
  },

  /** 新增错题 */
  create(data) {
    return request.post('/wrong-question', data)
  },

  /** 修改错题 */
  update(id, data) {
    return request.put(`/wrong-question/${id}`, data)
  },

  /** 删除错题 */
  remove(id) {
    return request.delete(`/wrong-question/${id}`)
  },

  /** 批量删除 */
  batchRemove(ids) {
    return request.delete('/wrong-question/batch', { data: { ids } })
  },

  /** 批量标记掌握度 */
  batchMastery(ids, mastery) {
    return request.put('/wrong-question/batch/mastery', { ids, mastery })
  },

  /** 标签列表 */
  tags() {
    return request.get('/wrong-question/tags')
  },

  /** 掌握度选项 */
  masteryOptions() {
    return request.get('/wrong-question/mastery-options')
  },

  /** 导出 Excel（触发浏览器下载） */
  exportExcel(params) {
    return downloadFile('/wrong-question/export', params, '错题本.xlsx')
  },
}
