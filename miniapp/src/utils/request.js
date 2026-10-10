/**
 * 小程序端请求封装。
 *
 * 与 PC 端思路一致（拦截器塞 token、统一处理业务码、401 跳登录），
 * 但实现不同：小程序没有 axios，用 uni.request；没有 localStorage，用 uni.getStorageSync。
 */

// 后端地址（全端唯一入口：chat.vue 等页面统一从这里 import，不要各写各的）。
//
// 当前走 **局域网直连**（手机与电脑连同一个 Wi-Fi 即可）：
//   - 为什么不是 127.0.0.1：真机/真机调试时那是手机自己的回环地址；
//   - 本机 WLAN：192.168.1.23（Wi-Fi 名 2707_5G），Java 监听 0.0.0.0:8080；
//   - 防火墙已确认放行本项目的 D:\jdk-21\...\java.exe（公用网络类型下生效，
//     规则名显示为 "Node.js JavaScript Runtime" 属于系统命名误导，按程序路径判断）；
//   - 换 Wi-Fi / DHCP 续租后 IP 可能变，IP 变了改这里一处即可。
//
// 备选：Cloudflare 快速隧道（手机连什么网络都行，但依赖外网、地址每次重启会变）：
//   .workbuddy/tools/cloudflared.exe tunnel --url http://127.0.0.1:8080
//
// 上线前：换成自有的 https 域名并在小程序后台配置 request 合法域名。
export const BASE_URL = 'http://192.168.1.23:8080'

const ACCESS_KEY = 'sa_access_token'
const REFRESH_KEY = 'sa_refresh_token'
const USER_KEY = 'sa_user_info'

export function getToken() {
  return uni.getStorageSync(ACCESS_KEY) || ''
}

export function setTokens(access, refresh) {
  uni.setStorageSync(ACCESS_KEY, access || '')
  uni.setStorageSync(REFRESH_KEY, refresh || '')
}

export function getRefreshToken() {
  return uni.getStorageSync(REFRESH_KEY) || ''
}

export function setUserInfo(info) {
  uni.setStorageSync(USER_KEY, info || null)
}

export function getUserInfo() {
  return uni.getStorageSync(USER_KEY) || null
}

export function clearAuth() {
  uni.removeStorageSync(ACCESS_KEY)
  uni.removeStorageSync(REFRESH_KEY)
  uni.removeStorageSync(USER_KEY)
}

/** 32 位 traceId，格式与后端 TraceIdFilter 一致 */
function genTraceId() {
  const chars = '0123456789abcdef'
  let out = ''
  for (let i = 0; i < 32; i += 1) {
    out += chars[Math.floor(Math.random() * 16)]
  }
  return out
}

/** 登录页路径（小程序无 vue-router，用 uni.reLaunch） */
function toLogin() {
  clearAuth()
  const pages = getCurrentPages()
  const current = pages.length ? pages[pages.length - 1].route : ''
  if (current && current.indexOf('pages/login/login') === -1) {
    uni.reLaunch({ url: '/pages/login/login' })
  }
}

/**
 * 发起请求。
 *
 * @param {object} options
 * @param {string} options.url     路径，如 /plan/task/today
 * @param {string} options.method  GET/POST/PUT/DELETE
 * @param {object} options.data    请求体或查询参数
 * @param {boolean} options.auth   是否需要带 token，默认 true
 * @param {boolean} options.silent 是否静默（不弹错误提示），默认 false
 * @returns {Promise<any>} 解析后的 data
 */
export function request(options) {
  const { url, method = 'GET', data, auth = true, silent = false } = options

  const header = {
    'Content-Type': 'application/json',
    'X-Trace-Id': genTraceId(),
  }
  if (auth) {
    const token = getToken()
    if (token) {
      header.Authorization = `Bearer ${token}`
    }
  }

  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE_URL + url,
      method,
      data,
      header,
      timeout: 30000,
      success(res) {
        const body = res.data

        if (res.statusCode === 401) {
          if (!silent) {
            uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
          }
          toLogin()
          reject(new Error('未登录'))
          return
        }

        if (body && typeof body === 'object' && 'code' in body) {
          if (body.code === 200) {
            resolve(body.data)
            return
          }
          if (body.code === 401) {
            if (!silent) {
              uni.showToast({ title: body.msg || '登录已过期', icon: 'none' })
            }
            toLogin()
            reject(new Error(body.msg))
            return
          }
          if (!silent) {
            uni.showToast({ title: body.msg || '请求失败', icon: 'none' })
          }
          reject(new Error(body.msg || '请求失败'))
          return
        }

        // 非标准返回体
        resolve(body)
      },
      fail(err) {
        if (!silent) {
          uni.showToast({ title: '网络异常，请检查服务是否启动', icon: 'none' })
        }
        reject(err)
      },
    })
  })
}

/**
 * 微信登录：uni.login 拿 code → 后端换 openid → 返回 JWT。
 * 第 1 周后端 wx.mock-enabled=true 时会走本地 mock，无需真实 AppID。
 */
export function wxLogin() {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: 'weixin',
      success(res) {
        if (!res.code) {
          reject(new Error('未获取到 code'))
          return
        }
        request({
          url: '/auth/wx-login',
          method: 'POST',
          auth: false,
          data: { code: res.code },
        })
          .then((data) => {
            setTokens(data.accessToken, data.refreshToken)
            setUserInfo(data.userInfo)
            resolve(data)
          })
          .catch(reject)
      },
      fail(err) {
        reject(err)
      },
    })
  })
}

export const api = {
  // ---------------------------------------------------------- 认证
  login: (data) => request({ url: '/auth/login', method: 'POST', auth: false, data }),
  register: (data) => request({ url: '/auth/register', method: 'POST', auth: false, data }),
  me: () => request({ url: '/auth/me' }),
  logout: () =>
    request({ url: '/auth/logout', method: 'POST', data: { refreshToken: getRefreshToken() } }),

  // ---------------------------------------------------------- 计划与任务
  todayTasks: (date) =>
    request({ url: `/plan/task/today${date ? `?date=${date}` : ''}`, silent: true }),
  planPage: (params) => {
    const qs = Object.entries(params || {})
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
      .join('&')
    return request({ url: `/plan/page${qs ? `?${qs}` : ''}` })
  },
  planDetail: (planId) => request({ url: `/plan/${planId}` }),
  tasks: (params) => {
    const qs = Object.entries(params || {})
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
      .join('&')
    return request({ url: `/plan/task/list${qs ? `?${qs}` : ''}` })
  },
  changeTaskStatus: (data) => request({ url: '/plan/task/status', method: 'PUT', data }),

  // ---------------------------------------------------------- 错题
  wrongPage: (params) => {
    // 数组参数（tags）要重复同名 key，uni.request 不会自动处理
    const parts = []
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value === undefined || value === null || value === '') return
      if (Array.isArray(value)) {
        value.forEach((item) => parts.push(`${key}=${encodeURIComponent(item)}`))
      } else {
        parts.push(`${key}=${encodeURIComponent(value)}`)
      }
    })
    return request({ url: `/wrong-question/page${parts.length ? `?${parts.join('&')}` : ''}` })
  },
  wrongDetail: (id) => request({ url: `/wrong-question/${id}` }),
  wrongCreate: (data) => request({ url: '/wrong-question', method: 'POST', data }),
  wrongUpdate: (id, data) => request({ url: `/wrong-question/${id}`, method: 'PUT', data }),
  wrongDelete: (id) => request({ url: `/wrong-question/${id}`, method: 'DELETE' }),
  wrongTags: () => request({ url: '/wrong-question/tags', silent: true }),
  wrongBatchMastery: (ids, mastery) =>
    request({ url: '/wrong-question/batch/mastery', method: 'PUT', data: { ids, mastery } }),

  // ---------------------------------------------------------- 进度
  progressRecent: (days = 7) => request({ url: `/progress/recent?days=${days}` }),
  progressOverview: (days = 30) => request({ url: `/progress/overview?days=${days}` }),

  // ---------------------------------------------------------- 通知
  noticePage: (params) => {
    const qs = Object.entries(params || {})
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
      .join('&')
    return request({ url: `/notice/page${qs ? `?${qs}` : ''}` })
  },
  noticeUnread: () => request({ url: '/notice/unread-count', silent: true }),
  noticeMarkAllRead: () => request({ url: '/notice/read/all', method: 'PUT' }),
}

export default request
