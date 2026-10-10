import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 路由表。
 *
 * meta 约定：
 *   requiresAuth  true 表示需要登录（路由守卫会校验）
 *   title         浏览器标题与面包屑
 *   icon          左侧菜单图标（Element Plus 图标组件名）
 */
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '今日概览', icon: 'HomeFilled', requiresAuth: true },
      },
      {
        path: 'plan',
        name: 'Plan',
        component: () => import('@/views/PlanView.vue'),
        meta: { title: '学习计划', icon: 'Calendar', requiresAuth: true },
      },
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: 'AI 问答', icon: 'ChatDotRound', requiresAuth: true },
      },
      {
        path: 'wrong',
        name: 'Wrong',
        component: () => import('@/views/WrongView.vue'),
        meta: { title: '错题本', icon: 'Notebook', requiresAuth: true },
      },
      {
        path: 'progress',
        name: 'Progress',
        component: () => import('@/views/ProgressView.vue'),
        meta: { title: '学习进度', icon: 'TrendCharts', requiresAuth: true },
      },
      {
        path: 'report',
        name: 'Report',
        component: () => import('@/views/ReportView.vue'),
        meta: { title: '复盘报告', icon: 'Document', requiresAuth: true },
      },
      {
        path: 'notice',
        name: 'Notice',
        component: () => import('@/views/NoticeView.vue'),
        meta: { title: '消息通知', icon: 'Bell', requiresAuth: true },
      },
    ],
  },
  {
    // 兜底：未匹配到的路径回首页，避免白屏
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 全局前置守卫：校验登录态。
 *
 * 这里只做"本地有没有 token"的粗判，不发请求验证。
 * 真正的鉴权以后端为准：axios 响应拦截器遇到 401 会清 token 并跳登录页，
 * 所以即便本地 token 已过期，用户最坏也只是多点一次跳转，不会看到错误数据。
 */
router.beforeEach((to) => {
  const userStore = useUserStore()
  const needAuth = to.meta?.requiresAuth !== false

  if (needAuth && !userStore.isLoggedIn) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'Login' && userStore.isLoggedIn) {
    // 已登录还去登录页，直接回首页
    return { path: '/dashboard' }
  }
  return true
})

router.afterEach((to) => {
  const base = '备考学习 Agent'
  document.title = to.meta?.title ? `${to.meta.title} · ${base}` : base
})

export default router
