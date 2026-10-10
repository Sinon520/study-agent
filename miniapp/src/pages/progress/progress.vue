<template>
  <view class="sa-page">
    <!-- 概览 -->
    <view class="sa-card">
      <view class="sa-between">
        <text class="sa-title">学习进度</text>
        <view class="range-tabs">
          <text
            v-for="item in ranges"
            :key="item.value"
            class="range-tab"
            :class="{ active: days === item.value }"
            @tap="switchRange(item.value)"
          >
            {{ item.label }}
          </text>
        </view>
      </view>

      <view class="grid">
        <view class="grid-item">
          <text class="grid-num">{{ overview.totalMinutes || 0 }}</text>
          <text class="grid-label">总时长(分)</text>
        </view>
        <view class="grid-item">
          <text class="grid-num">{{ overview.avgMinutes || 0 }}</text>
          <text class="grid-label">日均(分)</text>
        </view>
        <view class="grid-item">
          <text class="grid-num">{{ overview.taskDone || 0 }}/{{ overview.taskTotal || 0 }}</text>
          <text class="grid-label">完成任务</text>
        </view>
        <view class="grid-item">
          <text class="grid-num">{{ overview.completionRate || 0 }}%</text>
          <text class="grid-label">完成率</text>
        </view>
      </view>
    </view>

    <!-- 时长趋势：小程序端不引 ucharts，用纯 CSS 柱状图，轻量且够用 -->
    <view class="sa-card">
      <view class="section-head">
        <text class="section-title">每日学习时长</text>
        <text class="sa-muted">最近 {{ days }} 天</text>
      </view>

      <view v-if="!dailyMinutes.length" class="sa-empty">暂无数据</view>

      <view v-else class="chart">
        <view
          v-for="(item, index) in dailyMinutes"
          :key="index"
          class="chart-col"
        >
          <view class="chart-bar-wrap">
            <view class="chart-bar" :style="{ height: barHeight(item.minutes) }"></view>
          </view>
          <text class="chart-label">{{ item.date.slice(8) }}</text>
        </view>
      </view>

      <view class="chart-legend">
        <text class="sa-muted">最高 {{ maxMinutes }} 分钟 · 有记录 {{ overview.activeDays || 0 }} 天</text>
      </view>
    </view>

    <!-- 科目分布 -->
    <view class="sa-card">
      <view class="section-head">
        <text class="section-title">科目时间分布</text>
      </view>

      <view v-if="!subjects.length" class="sa-empty">暂无数据</view>

      <view v-else>
        <view v-for="(item, index) in subjects" :key="index" class="subject-row">
          <view class="sa-between">
            <text class="subject-name">{{ item.subject }}</text>
            <text class="sa-muted">{{ item.minutes }} 分钟 · {{ item.percent }}%</text>
          </view>
          <view class="subject-track">
            <view class="subject-bar" :style="{ width: item.percent + '%' }"></view>
          </view>
        </view>
      </view>
    </view>

    <!-- 错题掌握度 -->
    <view class="sa-card">
      <view class="section-head">
        <text class="section-title">错题掌握度</text>
      </view>

      <view class="mastery-row">
        <view v-for="(item, index) in mastery" :key="index" class="mastery-item">
          <text class="mastery-num">{{ item.count }}</text>
          <text class="mastery-label">{{ item.mastery }}</text>
        </view>
      </view>

      <view class="sa-muted mastery-tip">
        已掌握 {{ overview.wrongMastered || 0 }} / {{ overview.wrongTotal || 0 }} 道错题
      </view>
    </view>
  </view>
</template>

<script>
import { api } from '@/utils/request'

export default {
  data() {
    return {
      days: 7,
      ranges: [
        { label: '7 天', value: 7 },
        { label: '30 天', value: 30 },
        { label: '90 天', value: 90 },
      ],
      overview: {},
      dailyMinutes: [],
      subjects: [],
      mastery: [],
    }
  },
  computed: {
    maxMinutes() {
      return this.dailyMinutes.reduce((max, item) => Math.max(max, item.minutes || 0), 0)
    },
  },
  onLoad() {
    this.load()
  },
  methods: {
    async load() {
      try {
        const data = await api.progressRecent(this.days)
        this.overview = (data && data.overview) || {}
        this.dailyMinutes = (data && data.dailyMinutes) || []
        this.subjects = (data && data.subjectDistribution) || []
        this.mastery = (data && data.masteryDistribution) || []
      } catch (e) {
        // request 层已提示
      }
    },
    switchRange(value) {
      this.days = value
      this.load()
    },
    barHeight(minutes) {
      if (!this.maxMinutes || !minutes) return '2rpx'
      // 最小 4%，避免有数据但柱子看不见
      const percent = Math.max(4, Math.round((minutes / this.maxMinutes) * 100))
      return percent + '%'
    },
  },
}
</script>

<style scoped>
.range-tabs {
  display: flex;
}

.range-tab {
  padding: 6rpx 18rpx;
  margin-left: 8rpx;
  font-size: 23rpx;
  color: #606266;
  background-color: #f5f7fa;
  border-radius: 24rpx;
}

.range-tab.active {
  color: #fff;
  background-color: #409eff;
}

.grid {
  display: flex;
  flex-wrap: wrap;
  margin-top: 16rpx;
}

.grid-item {
  width: 50%;
  padding: 14rpx 0;
}

.grid-num {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  color: #409eff;
}

.grid-label {
  display: block;
  margin-top: 4rpx;
  font-size: 23rpx;
  color: #909399;
}

.section-head {
  margin-bottom: 20rpx;
}

.section-title {
  font-size: 30rpx;
  font-weight: 600;
}

.chart {
  display: flex;
  align-items: flex-end;
  height: 260rpx;
}

.chart-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
}

.chart-bar-wrap {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.chart-bar {
  width: 60%;
  min-height: 4rpx;
  background-color: #409eff;
  border-radius: 6rpx 6rpx 0 0;
  transition: height 0.3s;
}

.chart-label {
  margin-top: 8rpx;
  font-size: 19rpx;
  color: #a8abb2;
}

.chart-legend {
  margin-top: 16rpx;
}

.subject-row {
  margin-bottom: 22rpx;
}

.subject-name {
  font-size: 27rpx;
}

.subject-track {
  height: 14rpx;
  margin-top: 10rpx;
  background-color: #ebeef5;
  border-radius: 8rpx;
  overflow: hidden;
}

.subject-bar {
  height: 100%;
  background-color: #67c23a;
  border-radius: 8rpx;
}

.mastery-row {
  display: flex;
}

.mastery-item {
  flex: 1;
  text-align: center;
  padding: 16rpx 0;
}

.mastery-num {
  display: block;
  font-size: 44rpx;
  font-weight: 600;
}

.mastery-label {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #909399;
}

.mastery-tip {
  margin-top: 16rpx;
  text-align: center;
}
</style>
