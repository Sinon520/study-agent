<template>
  <div class="sa-page">
    <div class="sa-page-header">
      <div>
        <h2 class="sa-page-title">复盘报告</h2>
        <p class="sa-page-subtitle">每周自动汇总学习数据并生成文字建议（第 4 周由定时任务 + LLM 生成）</p>
      </div>
      <el-button :icon="'Refresh'" :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <!-- 本地实时汇总：不依赖第 4 周的报告生成链路，现在就能看 -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>本周实时汇总（本地统计）</span>
          <el-tag size="small" type="info">数据来自 /progress/recent</el-tag>
        </div>
      </template>

      <el-row :gutter="12">
        <el-col :xs="12" :sm="6">
          <div class="metric">
            <div class="sa-stat-value">{{ overview.totalMinutes || 0 }}<span class="unit">分钟</span></div>
            <div class="sa-stat-label">近 7 天学习时长</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="metric">
            <div class="sa-stat-value">{{ overview.activeDays || 0 }}<span class="unit">天</span></div>
            <div class="sa-stat-label">有学习记录</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="metric">
            <div class="sa-stat-value">{{ overview.taskDone || 0 }} / {{ overview.taskTotal || 0 }}</div>
            <div class="sa-stat-label">完成任务 / 总任务</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="metric">
            <div class="sa-stat-value">{{ overview.completionRate ?? 0 }}<span class="unit">%</span></div>
            <div class="sa-stat-label">完成率</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="12">
      <el-col :xs="24" :lg="14">
        <el-card shadow="never" class="block">
          <template #header><span>科目时间分布</span></template>
          <div ref="subjectChartRef" class="chart"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="10">
        <el-card shadow="never" class="block">
          <template #header><span>错题掌握度</span></template>
          <div ref="masteryChartRef" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>历史复盘报告</span>
          <el-tag size="small" type="warning">第 4 周接入</el-tag>
        </div>
      </template>

      <el-empty description="暂无历史报告">
        <div class="empty-hint">
          第 4 周会由 <code>@Scheduled</code> 每周日 20:00 触发，
          聚合本周学习记录与错题数据 → 调 Python <code>/agent/report</code> 生成 markdown 建议 →
          落 <code>review_report</code> 表并推送到「消息通知」。
        </div>
      </el-empty>
    </el-card>
  </div>
</template>

<script setup>
import { onActivated, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { progressApi } from '@/api/progress'

const loading = ref(false)
const overview = ref({})

const subjectChartRef = ref()
const masteryChartRef = ref()
const charts = shallowRef({})

function initChart(key, el) {
  if (!el) return null
  if (!charts.value[key]) {
    charts.value[key] = echarts.init(el)
  }
  return charts.value[key]
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
        type: 'pie',
        radius: ['40%', '64%'],
        center: ['50%', '45%'],
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
    grid: { left: 46, right: 18, top: 22, bottom: 30 },
    xAxis: { type: 'category', data: list.map((item) => item.mastery), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', minInterval: 1, nameTextStyle: { fontSize: 10 } },
    series: [
      {
        type: 'bar',
        barWidth: 38,
        itemStyle: {
          color: (params) => ['#f56c6c', '#e6a23c', '#67c23a'][params.dataIndex] || '#409eff',
        },
        data: list.map((item) => item.count || 0),
      },
    ],
  })
}

async function loadAll() {
  loading.value = true
  try {
    const data = await progressApi.recent(7)
    overview.value = data?.overview || {}
    renderSubject(data?.subjectDistribution)
    renderMastery(data?.masteryDistribution)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function resizeAll() {
  Object.values(charts.value).forEach((chart) => chart && chart.resize())
}

onMounted(() => {
  loadAll()
  window.addEventListener('resize', resizeAll)
})

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
.block {
  margin-bottom: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.metric {
  padding: 6px 0;
}

.unit {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.chart {
  width: 100%;
  height: 260px;
}

.empty-hint {
  max-width: 540px;
  margin: 0 auto;
  font-size: 12px;
  line-height: 1.9;
  color: #909399;
  text-align: left;
}

.empty-hint code {
  padding: 1px 4px;
  background-color: #f5f7fa;
  border-radius: 3px;
  font-size: 12px;
}
</style>
