<template>
  <div class="sa-page">
    <div class="sa-page-header">
      <div>
        <h2 class="sa-page-title">学习进度</h2>
        <p class="sa-page-subtitle">学习时长、完成率、科目分布与错题掌握度</p>
      </div>
      <el-radio-group v-model="days" @change="loadAll">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 概览卡片 -->
    <el-row :gutter="12" class="stat-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never">
          <div class="sa-stat-value">{{ overview.totalMinutes || 0 }}<span class="unit">分钟</span></div>
          <div class="sa-stat-label">总学习时长</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never">
          <div class="sa-stat-value">{{ overview.avgMinutes || 0 }}<span class="unit">分钟</span></div>
          <div class="sa-stat-label">日均时长</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never">
          <div class="sa-stat-value">{{ overview.activeDays || 0 }}<span class="unit">天</span></div>
          <div class="sa-stat-label">有学习记录的天数</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never">
          <div class="sa-stat-value">{{ overview.streakDays || 0 }}<span class="unit">天</span></div>
          <div class="sa-stat-label">连续学习</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="stat-row">
      <el-col :xs="24" :sm="8">
        <el-card shadow="never">
          <div class="sa-stat-value">{{ overview.taskDone || 0 }} / {{ overview.taskTotal || 0 }}</div>
          <div class="sa-stat-label">完成任务 / 总任务</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="never">
          <div class="sa-stat-value">{{ overview.completionRate ?? 0 }}<span class="unit">%</span></div>
          <div class="sa-stat-label">总体完成率（分母扣除跳过）</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="8">
        <el-card shadow="never">
          <div class="sa-stat-value">{{ overview.wrongMastered || 0 }} / {{ overview.wrongTotal || 0 }}</div>
          <div class="sa-stat-label">已掌握错题 / 错题总数</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="chart-card">
          <template #header><span>每日学习时长</span></template>
          <div ref="minutesChartRef" class="chart"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="chart-card">
          <template #header><span>计划完成率趋势</span></template>
          <div ref="rateChartRef" class="chart"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="chart-card">
          <template #header><span>科目时间分布</span></template>
          <div ref="subjectChartRef" class="chart"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="chart-card">
          <template #header><span>错题掌握度分布</span></template>
          <div ref="masteryChartRef" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onActivated, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { progressApi } from '@/api/progress'

defineOptions({ name: 'Progress' })

const days = ref(30)
const overview = ref({})

const minutesChartRef = ref()
const rateChartRef = ref()
const subjectChartRef = ref()
const masteryChartRef = ref()

// 用 shallowRef 存实例：ECharts 实例是庞大的非响应式对象，
// 用 ref 会被 Vue 深度代理，既拖慢性能又可能干扰 ECharts 内部状态
const charts = shallowRef({})

function initChart(key, el) {
  if (!el) return null
  if (!charts.value[key]) {
    charts.value[key] = echarts.init(el)
  }
  return charts.value[key]
}

function renderMinutes(data) {
  const chart = initChart('minutes', minutesChartRef.value)
  if (!chart) return
  const list = data || []
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 46, right: 18, top: 26, bottom: 30 },
    xAxis: {
      type: 'category',
      data: list.map((item) => item.date.slice(5)),
      axisLabel: { fontSize: 10 },
    },
    yAxis: { type: 'value', name: '分钟', nameTextStyle: { fontSize: 10 } },
    series: [
      {
        name: '学习时长',
        type: 'line',
        smooth: true,
        showSymbol: false,
        areaStyle: { opacity: 0.18 },
        data: list.map((item) => item.minutes || 0),
      },
    ],
  })
}

function renderRate(data) {
  const chart = initChart('rate', rateChartRef.value)
  if (!chart) return
  const list = data || []
  chart.setOption({
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${v}%` },
    grid: { left: 46, right: 18, top: 26, bottom: 30 },
    xAxis: {
      type: 'category',
      data: list.map((item) => item.date.slice(5)),
      axisLabel: { fontSize: 10 },
    },
    yAxis: { type: 'value', max: 100, name: '%', nameTextStyle: { fontSize: 10 } },
    series: [
      {
        name: '完成率',
        type: 'line',
        smooth: true,
        showSymbol: false,
        areaStyle: { opacity: 0.18 },
        itemStyle: { color: '#67c23a' },
        data: list.map((item) => item.rate ?? 0),
      },
    ],
  })
}

function renderSubject(data) {
  const chart = initChart('subject', subjectChartRef.value)
  if (!chart) return
  const list = (data || []).filter((item) => item.minutes > 0)
  chart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 分钟（{d}%）' },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [
      {
        name: '科目分布',
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        label: { fontSize: 11 },
        data: list.map((item) => ({ name: item.subject, value: item.minutes })),
      },
    ],
  })
}

function renderMastery(data) {
  const chart = initChart('mastery', masteryChartRef.value)
  if (!chart) return
  const list = data || []
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 46, right: 18, top: 26, bottom: 30 },
    xAxis: { type: 'category', data: list.map((item) => item.mastery), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', name: '题数', nameTextStyle: { fontSize: 10 }, minInterval: 1 },
    series: [
      {
        name: '错题数',
        type: 'bar',
        barWidth: 42,
        itemStyle: {
          color: (params) => ['#f56c6c', '#e6a23c', '#67c23a'][params.dataIndex] || '#409eff',
        },
        data: list.map((item) => item.count || 0),
      },
    ],
  })
}

async function loadAll() {
  try {
    const data = await progressApi.recent(days.value)
    overview.value = data?.overview || {}
    renderMinutes(data?.dailyMinutes)
    renderRate(data?.completionTrend)
    renderSubject(data?.subjectDistribution)
    renderMastery(data?.masteryDistribution)
  } catch (e) {
    // 拦截器已提示
  }
}

/** 容器尺寸变化时重绘（侧边栏折叠、窗口缩放） */
function resizeAll() {
  Object.values(charts.value).forEach((chart) => chart && chart.resize())
}

onMounted(() => {
  loadAll()
  window.addEventListener('resize', resizeAll)
})

// 页面被 keep-alive 缓存后重新进入时，容器可能刚从 display:none 恢复，
// 此时 ECharts 需要 resize 一次才能正确测量宽高
onActivated(() => {
  setTimeout(resizeAll, 0)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeAll)
  Object.values(charts.value).forEach((chart) => chart && chart.dispose())
  charts.value = {}
})
</script>

<style scoped>
.stat-row {
  margin-bottom: 12px;
}

.stat-row .el-card {
  margin-bottom: 12px;
}

.unit {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.chart-card {
  margin-bottom: 12px;
}

.chart {
  width: 100%;
  height: 260px;
}
</style>
