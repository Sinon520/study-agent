import request from '@/utils/request'

/**
 * 进度统计接口，对应 ProgressController。
 */
export const progressApi = {
  /** 进度页完整数据（概览 + 4 张图） */
  dashboard(params) {
    return request.get('/progress/dashboard', { params })
  },

  /** 近 N 天进度 */
  recent(days = 30) {
    return request.get('/progress/recent', { params: { days } })
  },

  /** 只要概览数字 */
  overview(days = 30) {
    return request.get('/progress/overview', { params: { days } })
  },
}

/**
 * 站内通知接口，对应 NoticeController。
 */
export const noticeApi = {
  page(params) {
    return request.get('/notice/page', { params })
  },

  unreadCount() {
    return request.get('/notice/unread-count')
  },

  markRead(id) {
    return request.put(`/notice/${id}/read`)
  },

  markReadBatch(ids) {
    return request.put('/notice/read/batch', { ids })
  },

  markAllRead() {
    return request.put('/notice/read/all')
  },

  remove(id) {
    return request.delete(`/notice/${id}`)
  },
}
