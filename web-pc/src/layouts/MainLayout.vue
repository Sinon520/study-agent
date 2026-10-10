<template>
  <el-container class="layout">
    <!-- 左侧菜单 -->
    <el-aside :width="collapsed ? '64px' : '210px'" class="layout-aside">
      <div class="logo" :class="{ 'logo-collapsed': collapsed }">
        <el-icon :size="22" color="#409eff"><Reading /></el-icon>
        <span v-show="!collapsed" class="logo-text">备考学习 Agent</span>
      </div>

      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="layout-menu"
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶部栏 -->
      <el-header class="layout-header">
        <div class="header-left">
          <el-button text :icon="collapsed ? 'Expand' : 'Fold'" @click="collapsed = !collapsed" />
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="header-right">
          <!-- 未读通知角标 -->
          <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
            <el-button text :icon="'Bell'" @click="router.push('/notice')">通知</el-button>
          </el-badge>

          <el-dropdown @command="onCommand">
            <span class="user-trigger">
              <el-avatar :size="28" :src="userStore.userInfo?.avatar">
                {{ userStore.displayName.slice(0, 1) }}
              </el-avatar>
              <span class="user-name">{{ userStore.displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">账号信息</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main class="layout-main">
        <router-view v-slot="{ Component }">
          <keep-alive :include="keepAliveViews">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { noticeApi } from '@/api/progress'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(false)
const unreadCount = ref(0)

/** 菜单从路由表里派生，避免菜单和路由两处维护、加页面忘了加菜单 */
const menus = [
  { path: '/dashboard', title: '今日概览', icon: 'HomeFilled' },
  { path: '/plan', title: '学习计划', icon: 'Calendar' },
  { path: '/chat', title: 'AI 问答', icon: 'ChatDotRound' },
  { path: '/wrong', title: '错题本', icon: 'Notebook' },
  { path: '/progress', title: '学习进度', icon: 'TrendCharts' },
  { path: '/report', title: '复盘报告', icon: 'Document' },
  { path: '/notice', title: '消息通知', icon: 'Bell' },
]

/** 需要缓存的页面：列表页来回切换时不重新请求 */
const keepAliveViews = ['Plan', 'Wrong', 'Progress']

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta?.title || '')

async function loadUnread() {
  try {
    unreadCount.value = (await noticeApi.unreadCount()) || 0
  } catch (e) {
    // 角标属于锦上添花，失败不打扰用户
    unreadCount.value = 0
  }
}

async function onCommand(command) {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
    } catch {
      return // 用户取消
    }
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
    return
  }
  if (command === 'profile') {
    ElMessage.info(
      `账号：${userStore.userInfo?.username || '-'}　备考类型：${userStore.userInfo?.examType || '-'}`,
    )
  }
}

let timer = null
onMounted(() => {
  loadUnread()
  // 30 秒轮询未读数：个人项目不做 WebSocket，轮询简单够用
  timer = setInterval(loadUnread, 30000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.layout {
  height: 100vh;
}

.layout-aside {
  background-color: #fff;
  border-right: 1px solid #e4e7ed;
  transition: width 0.2s;
  overflow-x: hidden;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid #f0f0f0;
  white-space: nowrap;
}

.logo-collapsed {
  justify-content: center;
  padding: 0;
}

.logo-text {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.layout-menu {
  border-right: none;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 16px;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  outline: none;
}

.user-name {
  font-size: 13px;
  color: #303133;
}

.layout-main {
  padding: 0;
  overflow-y: auto;
  background-color: #f5f7fa;
}
</style>
