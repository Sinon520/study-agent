<template>
  <view class="sa-page">
    <!-- 用户信息 -->
    <view class="sa-card profile">
      <view class="avatar">{{ (user.nickname || user.username || '未').slice(0, 1) }}</view>
      <view class="profile-body">
        <view class="profile-name">{{ user.nickname || user.username || '未登录' }}</view>
        <view class="sa-muted">
          {{ user.examType ? '备考：' + user.examType : '未设置备考类型' }}
          <text v-if="user.wxBound"> · 已绑定微信</text>
        </view>
      </view>
    </view>

    <!-- 学习概览 -->
    <view class="sa-card">
      <view class="section-head">
        <text class="section-title">我的数据</text>
        <text class="sa-muted" @tap="loadOverview">刷新</text>
      </view>

      <view class="grid">
        <view class="grid-item">
          <text class="grid-num">{{ overview.streakDays || 0 }}</text>
          <text class="grid-label">连续学习天数</text>
        </view>
        <view class="grid-item">
          <text class="grid-num">{{ overview.activeDays || 0 }}</text>
          <text class="grid-label">近 30 天学习天数</text>
        </view>
        <view class="grid-item">
          <text class="grid-num">{{ overview.totalMinutes || 0 }}</text>
          <text class="grid-label">近 30 天时长(分)</text>
        </view>
        <view class="grid-item">
          <text class="grid-num">{{ overview.wrongTotal || 0 }}</text>
          <text class="grid-label">错题总数</text>
        </view>
      </view>
    </view>

    <!-- 未读通知 -->
    <view class="sa-card" @tap="loadNotices">
      <view class="sa-between">
        <text class="section-title">未读通知</text>
        <text class="sa-tag" :class="unread ? 'sa-tag-danger' : 'sa-tag-success'">
          {{ unread }} 条
        </text>
      </view>

      <view v-if="notices.length" class="notice-list">
        <view v-for="item in notices" :key="item.id" class="notice-item">
          <text class="notice-title">[{{ item.type }}] {{ item.title }}</text>
          <text class="sa-muted">{{ item.content }}</text>
        </view>
        <view class="link" @tap.stop="markAll">全部标记已读</view>
      </view>
      <view v-else class="sa-muted notice-empty">
        暂无未读通知（第 4 周接入定时提醒后这里会有内容）
      </view>
    </view>

    <!-- 功能入口 -->
    <view class="sa-card">
      <view class="cell" @tap="go('/pages/progress/progress')">
        <text>学习进度</text>
        <text class="cell-arrow">›</text>
      </view>
      <view class="cell" @tap="showTip('知识库上传将在第 3 周接入（PC 端操作，小程序只读）')">
        <text>我的资料</text>
        <text class="cell-arrow">›</text>
      </view>
      <view class="cell" @tap="requestSubscribe">
        <text>学习提醒设置</text>
        <text class="cell-value">{{ subscribeSummary }}</text>
      </view>
    </view>

    <view class="sa-btn sa-btn-plain logout" @tap="onLogout">退出登录</view>

    <view class="version">备考学习 Agent v1.0.0</view>
  </view>
</template>

<script>
import { api, clearAuth, getUserInfo } from '@/utils/request'

export default {
  data() {
    return {
      user: {},
      overview: {},
      unread: 0,
      notices: [],
      subscribes: [],
    }
  },
  computed: {
    subscribeSummary() {
      const total = this.subscribes.length
      if (!total) return '未授权 ›'
      const opened = this.subscribes.filter((item) => item.authorized).length
      return `${opened}/${total} 已开启 ›`
    },
  },
  onShow() {
    this.user = getUserInfo() || {}
    this.loadOverview()
    this.loadNotices()
    this.loadSubscribes()
  },
  methods: {
    /**
     * 发起订阅消息授权并把结果上报服务端。
     *
     * 微信语义：用户点一次「同意」= 我们获得一次发送额度（templateId: 'accept'）。
     * 所以这里**每次都把最新结果回传**，由服务端累加额度；拒绝只更新状态，
     * 不动已有额度（用户已经给的额度不会因为一次拒绝而消失）。
     */
    async requestSubscribe() {
      let templates = {}
      try {
        templates = (await api.subscribeTemplates()) || {}
      } catch (e) {
        this.showTip('获取订阅模板失败')
        return
      }

      const ids = Object.values(templates).filter(Boolean)
      if (!ids.length) {
        this.showTip('服务端还没有配置订阅模板')
        return
      }

      uni.requestSubscribeMessage({
        tmplIds: ids,
        success: async (res) => {
          const items = Object.entries(templates).map(([templateKey, templateId]) => ({
            templateKey,
            templateId,
            status: res[templateId] || 'unknown',
          }))
          try {
            await api.subscribeReport({ items })
            this.loadSubscribes()
            uni.showToast({ title: '订阅状态已更新', icon: 'none' })
          } catch (e) {
            // request 层已提示
          }
        },
        fail: (err) => {
          this.showTip('授权失败：' + (err.errMsg || '未知错误'))
        },
      })
    },
    async loadOverview() {
      try {
        this.overview = (await api.progressOverview(30)) || {}
      } catch (e) {
        this.overview = {}
      }
    },
    async loadNotices() {
      try {
        this.unread = (await api.noticeUnread()) || 0
        const data = await api.noticePage({ pageNum: 1, pageSize: 3, readFlag: 0 })
        this.notices = (data && data.records) || []
      } catch (e) {
        this.notices = []
      }
    },
    async markAll() {
      try {
        await api.noticeMarkAllRead()
        uni.showToast({ title: '已全部标记为已读', icon: 'none' })
        this.loadNotices()
      } catch (e) {
        // request 层已提示
      }
    },
    go(url) {
      uni.navigateTo({ url })
    },
    showTip(message) {
      uni.showToast({ title: message, icon: 'none', duration: 2500 })
    },
    onLogout() {
      uni.showModal({
        title: '提示',
        content: '确定要退出登录吗？',
        success: async (res) => {
          if (!res.confirm) return
          try {
            await api.logout()
          } catch (e) {
            // 后端失败也照样清本地
          }
          clearAuth()
          uni.reLaunch({ url: '/pages/login/login' })
        },
      })
    },
  },
}
</script>

<style scoped>
.profile {
  display: flex;
  align-items: center;
}

.avatar {
  width: 110rpx;
  height: 110rpx;
  margin-right: 24rpx;
  border-radius: 50%;
  background-color: #409eff;
  color: #fff;
  font-size: 44rpx;
  text-align: center;
  line-height: 110rpx;
}

.profile-body {
  flex: 1;
  min-width: 0;
}

.profile-name {
  font-size: 34rpx;
  font-weight: 600;
  margin-bottom: 8rpx;
}

.section-head {
  margin-bottom: 20rpx;
}

.section-title {
  font-size: 30rpx;
  font-weight: 600;
}

.grid {
  display: flex;
  flex-wrap: wrap;
}

.grid-item {
  width: 50%;
  padding: 16rpx 0;
}

.grid-num {
  display: block;
  font-size: 42rpx;
  font-weight: 600;
  color: #409eff;
}

.grid-label {
  display: block;
  margin-top: 4rpx;
  font-size: 23rpx;
  color: #909399;
}

.notice-list {
  margin-top: 12rpx;
}

.notice-item {
  padding: 14rpx 0;
  border-bottom: 1rpx solid #f2f3f5;
}

.notice-title {
  display: block;
  font-size: 27rpx;
  margin-bottom: 6rpx;
}

.notice-empty {
  padding: 20rpx 0;
}

.link {
  margin-top: 16rpx;
  font-size: 26rpx;
  color: #409eff;
}

.cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 0;
  border-bottom: 1rpx solid #f2f3f5;
  font-size: 28rpx;
}

.cell:last-child {
  border-bottom: none;
}

.cell-arrow {
  color: #c0c4cc;
  font-size: 34rpx;
}

.cell-value {
  color: #909399;
  font-size: 26rpx;
}

.logout {
  margin-top: 20rpx;
  color: #f56c6c;
  border-color: #f56c6c;
  background-color: #fff;
}

.version {
  margin: 32rpx 0 20rpx;
  text-align: center;
  font-size: 22rpx;
  color: #c0c4cc;
}
</style>
