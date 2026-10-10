<template>
  <view class="sa-page">
    <!-- 今日概览 -->
    <view class="sa-card hero">
      <view class="sa-between">
        <view>
          <view class="sa-title">{{ todayLabel }}</view>
          <view class="sa-sub">{{ today.tip || '加载中…' }}</view>
        </view>
        <view class="hero-badge">
          <text class="badge-num">{{ today.done || 0 }}</text>
          <text class="badge-total">/{{ today.total || 0 }}</text>
        </view>
      </view>

      <view class="progress-track">
        <view class="progress-bar" :style="{ width: (today.progress || 0) + '%' }"></view>
      </view>
      <view class="sa-between progress-meta">
        <text class="sa-muted">已完成 {{ today.progress || 0 }}%</text>
        <text class="sa-muted">今日 {{ today.doneMinutes || 0 }} / {{ today.planMinutes || 0 }} 分钟</text>
      </view>
    </view>

    <!-- 今日任务 -->
    <view class="sa-card">
      <view class="sa-between section-head">
        <text class="section-title">今日任务</text>
        <text class="sa-muted" @tap="load">刷新</text>
      </view>

      <view v-if="loading" class="sa-empty">加载中…</view>

      <view v-else-if="!today.tasks || !today.tasks.length" class="sa-empty">
        今天还没有任务，去 PC 端创建一个学习计划吧
      </view>

      <view v-else>
        <view
          v-for="task in today.tasks"
          :key="task.id"
          class="task-item"
          :class="{ done: task.status === '完成' }"
        >
          <view class="checkbox" :class="{ checked: task.status === '完成' }" @tap="toggle(task)">
            <text v-if="task.status === '完成'">✓</text>
          </view>
          <view class="task-body">
            <view class="task-title">{{ task.title }}</view>
            <view class="task-meta">
              <text class="sa-tag">{{ task.subject || '综合' }}</text>
              <text class="sa-muted">{{ task.estimatedMinutes }} 分钟</text>
              <text v-if="task.status === '跳过'" class="sa-tag sa-tag-warn">已跳过</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- 快捷入口 -->
    <view class="sa-card">
      <view class="section-head">
        <text class="section-title">快捷入口</text>
      </view>
      <view class="quick-grid">
        <view class="quick-item" @tap="go('/pages/wrong/wrong', true)">
          <text class="quick-icon">📕</text>
          <text class="quick-text">错题本</text>
        </view>
        <view class="quick-item" @tap="go('/pages/chat/chat', true)">
          <text class="quick-icon">💬</text>
          <text class="quick-text">AI 问答</text>
        </view>
        <view class="quick-item" @tap="go('/pages/progress/progress')">
          <text class="quick-icon">📈</text>
          <text class="quick-text">学习进度</text>
        </view>
        <view class="quick-item" @tap="go('/pages/my/my', true)">
          <text class="quick-icon">👤</text>
          <text class="quick-text">我的</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { api } from '@/utils/request'

export default {
  data() {
    return {
      loading: true,
      today: { tasks: [], total: 0, done: 0, progress: 0 },
    }
  },
  computed: {
    todayLabel() {
      const d = new Date()
      const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
      return `${d.getMonth() + 1} 月 ${d.getDate()} 日 星期${week}`
    },
  },
  onShow() {
    // 用 onShow 而不是 onLoad：从其他页面返回时要刷新勾选状态
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const data = await api.todayTasks()
        this.today = data || { tasks: [] }
      } catch (e) {
        this.today = { tasks: [] }
      } finally {
        this.loading = false
      }
    },
    async toggle(task) {
      const checked = task.status !== '完成'
      try {
        await api.changeTaskStatus({
          taskId: task.id,
          status: checked ? '完成' : '待办',
          actualMinutes: checked ? task.estimatedMinutes : undefined,
        })
        uni.showToast({ title: checked ? '已完成' : '已取消', icon: 'none' })
        await this.load()
      } catch (e) {
        // request 层已提示
      }
    },
    go(url, isTab) {
      if (isTab) {
        uni.switchTab({ url })
      } else {
        uni.navigateTo({ url })
      }
    },
  },
}
</script>

<style scoped>
.hero-badge {
  display: flex;
  align-items: baseline;
}

.badge-num {
  font-size: 52rpx;
  font-weight: 700;
  color: #409eff;
}

.badge-total {
  font-size: 26rpx;
  color: #909399;
}

.progress-track {
  height: 14rpx;
  margin-top: 24rpx;
  background-color: #ebeef5;
  border-radius: 10rpx;
  overflow: hidden;
}

.progress-bar {
  height: 100%;
  background-color: #409eff;
  border-radius: 10rpx;
  transition: width 0.3s;
}

.progress-meta {
  margin-top: 12rpx;
}

.section-head {
  margin-bottom: 16rpx;
}

.section-title {
  font-size: 30rpx;
  font-weight: 600;
}

.task-item {
  display: flex;
  align-items: flex-start;
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f2f3f5;
}

.task-item:last-child {
  border-bottom: none;
}

.checkbox {
  flex-shrink: 0;
  width: 40rpx;
  height: 40rpx;
  margin-right: 20rpx;
  border: 2rpx solid #c0c4cc;
  border-radius: 8rpx;
  text-align: center;
  line-height: 36rpx;
  font-size: 26rpx;
  color: #fff;
}

.checkbox.checked {
  background-color: #409eff;
  border-color: #409eff;
}

.task-body {
  flex: 1;
  min-width: 0;
}

.task-title {
  font-size: 28rpx;
  line-height: 1.5;
}

.task-item.done .task-title {
  color: #a8abb2;
  text-decoration: line-through;
}

.task-meta {
  margin-top: 8rpx;
}

.quick-grid {
  display: flex;
}

.quick-item {
  flex: 1;
  text-align: center;
  padding: 12rpx 0;
}

.quick-icon {
  display: block;
  font-size: 46rpx;
  line-height: 1.2;
}

.quick-text {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #606266;
}
</style>
