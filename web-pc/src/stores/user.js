import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'

const ACCESS_KEY = 'sa_access_token'
const REFRESH_KEY = 'sa_refresh_token'
const USER_KEY = 'sa_user_info'

/**
 * 用户状态。
 *
 * token 存 localStorage 而不是 cookie：前后端分离 + JWT 的标准做法，
 * 不依赖 cookie 的同源策略，小程序端也能复用同一套 token 语义。
 * 代价是要自己防 XSS，所以本项目不使用 v-html 渲染用户输入。
 */
export const useUserStore = defineStore('user', () => {
  const accessToken = ref(localStorage.getItem(ACCESS_KEY) || '')
  const refreshToken = ref(localStorage.getItem(REFRESH_KEY) || '')
  const userInfo = ref(JSON.parse(localStorage.getItem(USER_KEY) || 'null'))

  const isLoggedIn = computed(() => Boolean(accessToken.value))
  const displayName = computed(
    () => userInfo.value?.nickname || userInfo.value?.username || '未登录',
  )

  function setTokens(access, refresh) {
    accessToken.value = access || ''
    refreshToken.value = refresh || ''
    if (access) {
      localStorage.setItem(ACCESS_KEY, access)
    } else {
      localStorage.removeItem(ACCESS_KEY)
    }
    if (refresh) {
      localStorage.setItem(REFRESH_KEY, refresh)
    } else {
      localStorage.removeItem(REFRESH_KEY)
    }
  }

  function setUserInfo(info) {
    userInfo.value = info || null
    if (info) {
      localStorage.setItem(USER_KEY, JSON.stringify(info))
    } else {
      localStorage.removeItem(USER_KEY)
    }
  }

  function clear() {
    setTokens('', '')
    setUserInfo(null)
  }

  async function login(payload) {
    const data = await authApi.login(payload)
    setTokens(data.accessToken, data.refreshToken)
    setUserInfo(data.userInfo)
    return data
  }

  async function register(payload) {
    return authApi.register(payload)
  }

  async function fetchProfile() {
    const info = await authApi.me()
    setUserInfo(info)
    return info
  }

  async function logout() {
    try {
      await authApi.logout(refreshToken.value)
    } catch (e) {
      // 后端登出失败不影响前端清理：本地 token 一定要清掉
      console.warn('登出接口调用失败，仍清理本地登录态', e)
    } finally {
      clear()
    }
  }

  return {
    accessToken,
    refreshToken,
    userInfo,
    isLoggedIn,
    displayName,
    setTokens,
    setUserInfo,
    clear,
    login,
    register,
    fetchProfile,
    logout,
  }
})
