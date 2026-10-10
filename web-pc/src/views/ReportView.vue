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
          <el-tag size="small" type="success">每周日 20:00 自动生成</el-tag>
          <div class="header-actions">
            <el-button
              size="small"
              type="primary"
              plain
              :loading="generating"
              @click="handleGenerate"
            >{{ generating ? '生成中…' : '立即生成' }}</el-button>
            <el-button size="small" :icon="'Refresh'" :loading="reportLoading" @click="loadReports" />
          </div>
        </div>
      </template>

      <el-table v-loading="reportLoading" :data="reports" size="default" @row-click="openDetail">
        <el-table-column label="统计周期" min-width="220">
          <template #default="{ row }">
            {{ row.periodStart }} ~ {{ row.periodEnd }}
          </template>
        </el-table-column>
        <el-table-column prop="reportType" label="类型" width="90" />
        <el-table-column prop="createTime" label="生成时间" min-width="170" />
        <el-table-column label="操作" width="90" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无历史报告">
            <div class="empty-hint">
              每周日 20:00 由 <code>@Scheduled</code> 触发，聚合本周学习记录与错题数据 →
              调 Python <code>/agent/report</code> 生成 markdown 建议 →
              落 <code>review_report</code> 表并推送到「消息通知」。<br />
              想马上看效果，点右上角「立即生成」（默认统计本周一 ~ 今天）。
            </div>
          </el-empty>
        </template>
      </el-table>
    </el-card>

    <!-- 报告详情抽屉 -->
    <el-drawer v-model="detailVisible" size="640px" :title="detailTitle" destroy-on-close>
      <div v-loading="detailLoading" class="report-detail">
        <template v-if="detail">
          <!-- 数据卡片：与报告同源的 statsJson -->
          <el-row :gutter="10">
            <el-col :span="6">
              <div class="mini-stat">
                <div class="mini-value">{{ statsOf('totalMinutes') }}</div>
                <div class="mini-label">学习时长（分钟）</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-stat">
                <div class="mini-value">{{ statsOf('completionRate') }}<span class="unit">%</span></div>
                <div class="mini-label">任务完成率</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-stat">
                <div class="mini-value">{{ statsOf('taskDone') }}/{{ statsOf('taskTotal') }}</div>
                <div class="mini-label">完成任务</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-stat">
                <div class="mini-value">{{ statsOf('wrongNew') }}</div>
                <div class="mini-label">本周新增错题</div>
              </div>
            </el-col>
          </el-row>

          <!-- AI 要点 -->
          <div v-if="detail.highlights?.length" class="highlight-tags">
            <el-tag
              v-for="(item, i) in detail.highlights"
              :key="i"
              size="small"
              type="warning"
              effect="plain"
            >{{ item }}</el-tag>
          </div>

          <!-- markdown 正文 -->
          <div class="markdown report-md" v-html="renderedContent"></div>

          <!-- 行动建议 -->
          <el-card v-if="detail.suggestions?.length" shadow="never" class="suggest-card">
            <template #header><span>下周行动建议</span></template>
            <ol class="suggest-list">
              <li v-for="(item, i) in detail.suggestions" :key="i">{{ item }}</li>
            </ol>
          </el-card>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onActivated, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { progressApi } from '@/api/progress'
import { reportApi } from '@/api/report'

defineOptions({ name: 'Report' })

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

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

/* ------------------------------ 历史报告列表 ------------------------------ */

const reports = ref([])
const reportLoading = ref(false)
const generating = ref(false)

async function loadReports() {
  reportLoading.value = true
  try {
    const data = await reportApi.page({ pageNum: 1, pageSize: 50 })
    reports.value = data?.records || []
  } catch (e) {
    // 拦截器已提示
  } finally {
    reportLoading.value = false
  }
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailTitle = computed(() => {
  if (!detail.value) return '复盘报告'
  return `${detail.value.reportType} · ${detail.value.periodStart} ~ ${detail.value.periodEnd}`
})
const renderedContent = computed(() => md.render(detail.value?.content || ''))

function statsOf(key) {
  const value = detail.value?.stats?.[key]
  return value === undefined || value === null ? 0 : value
}

async function openDetail(row) {
  if (!row?.id) return
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await reportApi.detail(row.id)
  } catch (e) {
    // 拦截器已提示
  } finally {
    detailLoading.value = false
  }
}

/* ------------------------------ 立即生成 ------------------------------ */

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function handleGenerate() {
  await ElMessageBox.confirm(
    '将统计「本周一 ~ 今天」的学习数据并调用 AI 生成复盘报告，通常 10~60 秒完成。继续？',
    '生成本周复盘报告',
    { confirmButtonText: '开始生成', cancelButtonText: '取消', type: 'info' },
  ).catch(() => Promise.reject(new Error('cancel')))

  generating.value = true
  try {
    const data = await reportApi.generate({})
    const start = data?.periodStart
    const end = data?.periodEnd
    ElMessage.info(data?.message || '生成任务已提交，正在等待结果…')

    // 记录旧版本（同周期重复生成时 createTime 会变化，以此判断"新报告已到"）
    const before = reports.value.find((r) => r.periodStart === start && r.periodEnd === end)
    const beforeTime = before?.createTime || null

    // 轮询：LLM 生成是秒级到分钟级，前端最多等 100 秒
    for (let i = 0; i < 20; i++) {
      await sleep(5000)
      await loadReports()
      const hit = reports.value.find((r) => r.periodStart === start && r.periodEnd === end)
      if (hit && hit.createTime !== beforeTime) {
        ElMessage.success('复盘报告已生成，点击「查看」阅读')
        openDetail(hit)
        return
      }
    }
    ElMessage.warning('报告仍在生成中，可稍后点「刷新」查看')
  } catch (e) {
    if (e?.message !== 'cancel') {
      // 接口错误由拦截器提示
    }
  } finally {
    generating.value = false
  }
}

function resizeAll() {
  Object.values(charts.value).forEach((chart) => chart && chart.resize())
}

onMounted(() => {
  loadAll()
  loadReports()
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

.header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.mini-stat {
  padding: 10px 8px;
  text-align: center;
  background-color: #f7f8fa;
  border-radius: 6px;
}

.mini-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.mini-label {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.highlight-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 12px 0;
}

.report-md {
  padding: 4px 2px 8px;
  font-size: 13.5px;
  line-height: 1.9;
  color: #303133;
}

.report-md :deep(h1),
.report-md :deep(h2),
.report-md :deep(h3) {
  margin: 14px 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.report-md :deep(p) {
  margin: 0 0 8px;
}

.report-md :deep(ul),
.report-md :deep(ol) {
  padding-left: 20px;
  margin: 0 0 8px;
}

.suggest-card {
  margin-top: 6px;
}

.suggest-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 2;
  color: #303133;
}

.report-detail {
  min-height: 200px;
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
