import { createSSRApp } from 'vue'
import App from './App.vue'

/**
 * UniApp（Vue3）入口。
 *
 * 必须用 createSSRApp 而不是 createApp：小程序端是 SSR 形态的运行时，
 * 用 createApp 会在微信开发者工具里报 "createSSRApp" 相关错误。
 */
export function createApp() {
  const app = createSSRApp(App)
  return { app }
}
