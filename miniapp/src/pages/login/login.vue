<template>
  <view class="login-page">
    <view class="brand">
      <view class="brand-logo">📚</view>
      <view class="brand-title">备考学习 Agent</view>
      <view class="brand-sub">今日计划 · 错题本 · AI 资料问答</view>
    </view>

    <view class="form-card">
      <!-- 账号密码登录：方便在模拟器里快速验证，不用走微信授权 -->
      <view class="field">
        <text class="label">账号</text>
        <input v-model="form.username" class="input" placeholder="请输入用户名" />
      </view>
      <view class="field">
        <text class="label">密码</text>
        <input v-model="form.password" class="input" password placeholder="请输入密码" />
      </view>

      <view class="sa-btn" :class="{ disabled: loading }" @tap="onLogin">
        {{ loading ? '登录中…' : '登录' }}
      </view>

      <view class="divider">
        <text class="divider-text">或</text>
      </view>

      <view class="sa-btn sa-btn-plain" @tap="onWxLogin">微信一键登录</view>

      <view class="tip" @tap="fillDemo">演示账号：demo / 123456（点击填入）</view>
    </view>

    <view class="footer">第 1 周 mock 登录已打通，第 4 周接真实 AppID 与订阅消息</view>
  </view>
</template>

<script>
import { api, wxLogin } from '@/utils/request'

export default {
  data() {
    return {
      loading: false,
      form: { username: '', password: '' },
    }
  },
  onLoad() {
    // 已登录直接进首页，避免每次都重新登录
    if (uni.getStorageSync('sa_access_token')) {
      uni.switchTab({ url: '/pages/plan/plan' })
    }
  },
  methods: {
    fillDemo() {
      this.form.username = 'demo'
      this.form.password = '123456'
    },
    async onLogin() {
      if (!this.form.username || !this.form.password) {
        uni.showToast({ title: '请输入账号和密码', icon: 'none' })
        return
      }
      this.loading = true
      try {
        const data = await api.login({ username: this.form.username, password: this.form.password })
        uni.setStorageSync('sa_access_token', data.accessToken)
        uni.setStorageSync('sa_refresh_token', data.refreshToken)
        uni.setStorageSync('sa_user_info', data.userInfo)
        uni.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => uni.switchTab({ url: '/pages/plan/plan' }), 500)
      } catch (e) {
        // request 层已 toast
      } finally {
        this.loading = false
      }
    },
    async onWxLogin() {
      this.loading = true
      try {
        await wxLogin()
        uni.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => uni.switchTab({ url: '/pages/plan/plan' }), 500)
      } catch (e) {
        uni.showToast({ title: '微信登录失败', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
  },
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  padding: 120rpx 48rpx 40rpx;
  background: linear-gradient(160deg, #e8f0fe 0%, #f5f7fa 50%, #e6f4ea 100%);
}

.brand {
  text-align: center;
  margin-bottom: 60rpx;
}

.brand-logo {
  font-size: 90rpx;
  line-height: 1;
}

.brand-title {
  margin-top: 16rpx;
  font-size: 42rpx;
  font-weight: 600;
}

.brand-sub {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #909399;
}

.form-card {
  padding: 40rpx 32rpx;
  background-color: #fff;
  border-radius: 24rpx;
  box-shadow: 0 8rpx 30rpx rgb(0 0 0 / 6%);
}

.field {
  margin-bottom: 28rpx;
}

.label {
  display: block;
  margin-bottom: 10rpx;
  font-size: 26rpx;
  color: #606266;
}

.input {
  height: 84rpx;
  padding: 0 20rpx;
  background-color: #f5f7fa;
  border-radius: 12rpx;
  font-size: 28rpx;
}

.sa-btn {
  margin-top: 12rpx;
}

.disabled {
  opacity: 0.6;
}

.divider {
  position: relative;
  margin: 32rpx 0;
  text-align: center;
}

.divider::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 0;
  right: 0;
  height: 1rpx;
  background-color: #ebeef5;
}

.divider-text {
  position: relative;
  padding: 0 20rpx;
  background-color: #fff;
  font-size: 24rpx;
  color: #c0c4cc;
}

.tip {
  margin-top: 28rpx;
  font-size: 24rpx;
  color: #909399;
  text-align: center;
}

.footer {
  margin-top: 40rpx;
  font-size: 22rpx;
  color: #a8abb2;
  text-align: center;
  line-height: 1.6;
}
</style>
