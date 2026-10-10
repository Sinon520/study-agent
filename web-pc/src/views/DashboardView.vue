<template>
  <div class="sa-page">
    <!-- 顶部：日期 + 鼓励语 -->
    <el-card shadow="never" class="hero-card">
      <div class="hero">
        <div>
          <div class="hero-date">{{ todayLabel }}</div>
          <div class="hero-tip">{{ today.tip || '正在加载今日任务…' }}</div>
        </div>
        <div class="hero-actions">
          <el-button type="primary" :icon="'Plus'" @click="router.push('/plan')">新建计划</el-button>
          <el-button :icon="'Refresh'" :loading="loading" @click="loadAll">刷新</el-button>
        </div>
      </div>

      <el-row :gutter="12" class="hero-stats">
        <el-col :xs="12" :sm="6">
          <div class="stat">
            <div class="sa-stat-value">{{ today.done || 0 }} / {{ today.total || 0 }}</div>
            <div class="sa-stat-label">今日任务完成</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat">
            <div class="sa-stat-value">{{ today.doneMinutes || 0 }}<span class="unit">分钟</span></div>
            <div class="sa-stat-label">今日学习时长</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat">
            <div class="sa-stat-value">{{ overview.streakDays || 0 }}<span class="unit">天</span></div>
            <div class="sa-stat-label">连续学习</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="stat">
            <div class="sa-stat-value">{{ overview.completionRate ?? 0 }}<span class="unit">%</span></div>
            <div class="sa-stat-label">近 30 天完成率</div>
          </div>
        </el-col>
      </el-row>

      <el-progress
        :percentage="today.progress || 0"
        :stroke-width="10"
        :status="today.progress >= 100 ? 'success' : undefined"
        class="hero-progress"
      />
    </el-card>

    <el-row :gutter="12">
      <!-- 今日任务 -->
      <el-col :xs="24" :lg="15">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>今日任务</span>
              <el-tag v-if="today.todo" type="warning" size="small">待办 {{ today.todo }}</el-tag>
              <el-tag v-else-if="today.total" type="success" size="small">全部完成</el-tag>
            </div>
          </template>

          <el-skeleton v-if="loading" :rows="4" animated />

          <el-empty v-else-if="!today.tasks?.length" description="今天还没有任务">
            <el-button type="primary" @click="router.push('/plan')">去创建学习计划</el-button>
          </el-empty>

          <div v-else class="task-list">
            <div
              v-for="task in today.tasks"
              :key="task.id"
              class="task-item"
              :class="{ 'task-done': task.status === '完成' }"
            >
              <el-checkbox
                :model-value="task.status === '完成'"
                :disabled="savingId === task.id"
                @change="(val) => toggleTask(task, val)"
              />
              <div class="task-body">
                <div class="task-title">{{ task.title }}</div>
                <div class="task-meta">
                  <el-tag size="small" type="info">{{ task.subject || '综合' }}</el-tag>
                  <span>预计 {{ task.estimatedMinutes }} 分钟</span>
                  <el-tag v-if="task.status === '跳过'" size="small" type="warning">已跳过</el-tag>
                </div>
              </div>
              <el-button
                v-if="task.status !== '跳过'"
                link
                type="info"
                @click="skipTask(task)"
              >
                跳过
              </el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：计划进度 + 快捷入口 -->
      <el-col :xs="24" :lg="9">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>进行中的计划</span>
              <el-button link type="primary" @click="router.push('/plan')">全部</el-button>
            </div>
          </template>

          <el-skeleton v-if="loading" :rows="3" animated />

          <el-empty v-else-if="!plans.length" description="暂无进行中的计划" :image-size="70" />

          <div v-else class="plan-list">
            <div v-for="plan in plans" :key="plan.id" class="plan-item">
              <div class="plan-head">
                <span class="plan-title">{{ plan.title }}</span>
                <el-tag size="small" :type="plan.daysLeft < 30 ? 'danger' : 'info'">
                  剩 {{ plan.daysLeft }} 天
                </el-tag>
              </div>
              <el-progress :percentage="plan.progress || 0" :stroke-width="8" />
              <div class="plan-meta">
                {{ plan.taskDone }} / {{ plan.taskTotal }} 任务 · 每日 {{ plan.dailyMinutes }} 分钟
              </div>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header><span>快捷入口</span></template>
          <div class="quick">
            <el-button :icon="'ChatDotRound'" @click="router.push('/chat')">AI 问答</el-button>
            <el-button :icon="'Notebook'" @click="router.push('/wrong')">错题本</el-button>
            <el-button :icon="'TrendCharts'" @click="router.push('/progress')">学习进度</el-button>
            <el-button :icon="'Document'" @click="router.push('/report')">复盘报告</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { planApi } from '@/api/plan'
import { progressApi } from '@/api/progress'

const router = useRouter()

const loading = ref(false)
const savingId = ref(null)
const today = ref({ tasks: [], total: 0, done: 0, todo: 0, progress: 0 })
const plans = ref([])
const overview = ref({})

const todayLabel = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 星期${week}`
})

async function loadAll() {
  loading.value = true
  try {
    const [todayData, planPage, ov] = await Promise.all([
      planApi.today(),
      planApi.page({ pageNum: 1, pageSize: 3, status: '进行中' }),
      progressApi.overview(30),
    ])
    today.value = todayData || { tasks: [] }
    plans.value = planPage?.records || []
    overview.value = ov || {}
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function toggleTask(task, checked) {
  savingId.value = task.id
  try {
    await planApi.changeTaskStatus({
      taskId: task.id,
      status: checked ? '完成' : '待办',
      actualMinutes: checked ? task.estimatedMinutes : undefined,
    })
    ElMessage.success(checked ? '已完成，学习时长已累计' : '已取消完成')
    await loadAll()
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingId.value = null
  }
}

async function skipTask(task) {
  savingId.value = task.id
  try {
    await planApi.changeTaskStatus({ taskId: task.id, status: '跳过' })
    ElMessage.info('已跳过该任务')
    await loadAll()
  } catch (e) {
    // 拦截器已提示
  } finally {
    savingId.value = null
  }
}

onMounted(loadAll)
</script>

<style scoped>
.hero-card {
  margin-bottom: 12px;
}

.hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.hero-date {
  font-size: 16px;
  font-weight: 600;
}

.hero-tip {
  margin-top: 4px;
  font-size: 13px;
  color: #606266;
}

.hero-stats {
  margin-top: 16px;
}

.stat {
  padding: 8px 0;
}

.unit {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.hero-progress {
  margin-top: 8px;
}

.block {
  margin-bottom: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.task-list {
  display: flex;
  flex-direction: column;
}

.task-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed #ebeef5;
}

.task-item:last-child {
  border-bottom: none;
}

.task-done .task-title {
  color: #a8abb2;
  text-decoration: line-through;
}

.task-body {
  flex: 1;
  min-width: 0;
}

.task-title {
  font-size: 14px;
  line-height: 1.5;
}

.task-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.plan-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.plan-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  gap: 8px;
}

.plan-title {
  font-size: 13px;
  font-weight: 500;
}

.plan-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.quick .el-button {
  margin-left: 0;
}
</style>
