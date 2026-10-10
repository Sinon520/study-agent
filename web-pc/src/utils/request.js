import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * axios 封装。
 *
 * 三件事：请求拦截塞 token、响应拦截统一处理业务码、401 时清理登录态并跳登录页。
 *
 * 后端约定（见 server-java 的 Result）：
 *   HTTP 状态码恒为 200，业务结果看 body.code；只有鉴权失败才真的返回 HTTP 401。
 *   所以拦截器要同时处理"HTTP 层 401"与"body.code=401"两种情况。
 */
const service = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

/** 防止 401 并发时弹出多个登录过期提示 */
let redirecting = false

function toLogin(message) {
  if (redirecting) return
  redirecting = true
  ElMessage.error(message || '登录已过期，请重新登录')

  // 延迟清 token：让 store 有机会把 refreshToken 一起清掉，避免依赖循环
  localStorage.removeItem('sa_access_token')
  localStorage.removeItem('sa_refresh_token')
  localStorage.removeItem('sa_user_info')

  const current = window.location.pathname + window.location.search
  const target = current.startsWith('/login')
    ? '/login'
    : `/login?redirect=${encodeURIComponent(current)}`
  setTimeout(() => {
    window.location.replace(target)
    redirecting = false
  }, 600)
}

service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('sa_access_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // 每次请求带上 traceId，便于和后端日志对齐（后端没有就用它生成）
    config.headers['X-Trace-Id'] = genTraceId()
    return config
  },
  (error) => Promise.reject(error),
)

service.interceptors.response.use(
  (response) => {
    // 文件流（Excel 导出）直接返回 blob，不走统一返回体解析
    if (response.config.responseType === 'blob') {
      return response
    }
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) {
        return body.data
      }
      if (body.code === 401) {
        toLogin(body.msg)
        return Promise.reject(new Error(body.msg || '未登录'))
      }
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    // 非标准返回体（例如 /health）原样返回
    return body
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data

    if (status === 401) {
      toLogin(body?.msg)
      return Promise.reject(error)
    }
    if (status === 403) {
      ElMessage.error(body?.msg || '没有权限执行该操作')
      return Promise.reject(error)
    }
    if (status === 404) {
      ElMessage.error('接口不存在，请检查前端 baseURL 与后端路由是否一致')
      return Promise.reject(error)
    }
    if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查后端服务是否已启动')
      return Promise.reject(error)
    }
    if (!error.response) {
      ElMessage.error('无法连接后端服务（http://127.0.0.1:8080），请确认服务已启动')
      return Promise.reject(error)
    }
    ElMessage.error(body?.msg || `请求失败（HTTP ${status}）`)
    return Promise.reject(error)
  },
)

/** 生成 32 位 traceId，与后端 TraceIdFilter 的格式保持一致 */
function genTraceId() {
  const chars = '0123456789abcdef'
  let out = ''
  for (let i = 0; i < 32; i += 1) {
    out += chars[Math.floor(Math.random() * 16)]
  }
  return out
}

/** 下载文件（Excel 导出用），把后端返回的 blob 存成文件 */
export async function downloadFile(url, params, fallbackName) {
  const response = await service.get(url, { params, responseType: 'blob' })
  const disposition = response.headers['content-disposition'] || ''
  let fileName = fallbackName || 'export.xlsx'

  // 优先解析 RFC 5987 的 filename*=UTF-8''xxx（中文名不会乱码）
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (utf8Match) {
    fileName = decodeURIComponent(utf8Match[1])
  } else {
    const plainMatch = /filename="?([^";]+)"?/i.exec(disposition)
    if (plainMatch) fileName = plainMatch[1]
  }

  const blob = new Blob([response.data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(link.href)
  return fileName
}

export { ElMessageBox }
export default service
