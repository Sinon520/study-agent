import request from '@/utils/request'

/**
 * 知识库接口，对应 KnowledgeBaseController。
 */
export const kbApi = {
  /** 分页查询我的资料 */
  page(params) {
    return request.get('/kb/page', { params })
  },

  /** 文档详情 */
  detail(documentId) {
    return request.get(`/kb/${documentId}`)
  },

  /**
   * 上传资料。
   * 用 FormData 提交 multipart/form-data；不要手工设置 Content-Type，
   * 浏览器会自动带上正确的 boundary（手工设置反而会让后端解析失败）。
   */
  upload(file, onProgress) {
    const form = new FormData()
    form.append('file', file)
    return request.post('/kb/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
      onUploadProgress: (event) => {
        if (onProgress && event.total) {
          onProgress(Math.round((event.loaded * 100) / event.total))
        }
      },
    })
  },

  /** 重新解析 */
  reingest(documentId) {
    return request.post(`/kb/${documentId}/reingest`)
  },

  /** 删除资料（同时清向量与切片） */
  remove(documentId) {
    return request.delete(`/kb/${documentId}`)
  },
}

/**
 * 问答会话接口，对应 ChatController 的会话部分。
 */
export const chatApi = {
  pageSessions(params) {
    return request.get('/chat/session/page', { params })
  },

  listMessages(sessionId) {
    return request.get(`/chat/session/${sessionId}/messages`)
  },

  removeSession(sessionId) {
    return request.delete(`/chat/session/${sessionId}`)
  },
}
