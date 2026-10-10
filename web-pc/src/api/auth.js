import request from '@/utils/request'

/**
 * 认证接口。
 * 与 server-java 的 AuthController 一一对应。
 */
export const authApi = {
  /** 账号密码注册 */
  register(data) {
    return request.post('/auth/register', data)
  },

  /** 账号密码登录，返回 { accessToken, refreshToken, expiresIn, userInfo } */
  login(data) {
    return request.post('/auth/login', data)
  },

  /** 用 refreshToken 换新令牌对 */
  refresh(refreshToken) {
    return request.post('/auth/refresh', { refreshToken })
  },

  /** 退出登录；不传 refreshToken 表示全端下线 */
  logout(refreshToken) {
    return request.post('/auth/logout', refreshToken ? { refreshToken } : {})
  },

  /** 当前登录用户信息（查库） */
  me() {
    return request.get('/auth/me')
  },

  /** 微信小程序登录（PC 端不用，保留给联调） */
  wxLogin(data) {
    return request.post('/auth/wx-login', data)
  },
}
