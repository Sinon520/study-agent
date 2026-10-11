<template>
  <div class="sa-page">
    <div class="sa-page-header">
      <div>
        <h2 class="sa-page-title">学习计划</h2>
        <p class="sa-page-subtitle">创建计划自动拆成逐日任务；勾选完成会累计学习时长</p>
      </div>
      <div>
        <el-button type="primary" :icon="'Plus'" @click="openCreate">新建计划</el-button>
        <el-button
          type="primary"
          :icon="'MagicStick'"
          :loading="generating"
          @click="openAgentDialog"
        >
          AI 生成计划
        </el-button>
      </div>
    </div>

    <div class="sa-filter-bar">
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="loadPlans">
        <el-option label="进行中" value="进行中" />
        <el-option label="已完成" value="已完成" />
        <el-option label="已放弃" value="已放弃" />
      </el-select>
      <el-select v-model="query.examType" placeholder="考试类型" clearable style="width: 130px" @change="loadPlans">
        <el-option label="考研" value="考研" />
        <el-option label="考公" value="考公" />
      </el-select>
      <el-button :icon="'Refresh'" :loading="loading" @click="loadPlans">刷新</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="5" animated />

    <el-empty v-else-if="!plans.length" description="还没有学习计划">
      <el-button type="primary" @click="openCreate">创建第一个计划</el-button>
    </el-empty>

    <el-row v-else :gutter="12">
      <el-col v-for="plan in plans" :key="plan.id" :xs="24" :sm="12" :lg="8">
        <el-card shadow="hover" class="plan-card">
          <div class="plan-card-head">
            <div class="plan-title">{{ plan.title }}</div>
            <el-dropdown @command="(cmd) => onPlanCommand(cmd, plan)">
              <el-icon class="more-icon"><MoreFilled /></el-icon>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item v-if="plan.status === '进行中'" command="finish">标记完成</el-dropdown-item>
                  <el-dropdown-item v-if="plan.status === '进行中'" command="abandon">放弃计划</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <div class="plan-tags">
            <el-tag size="small">{{ plan.examType }}</el-tag>
            <el-tag size="small" :type="statusType(plan.status)">{{ plan.status }}</el-tag>
            <el-tag size="small" type="warning" v-if="plan.source === 'Agent生成'">AI 生成</el-tag>
          </div>

          <div class="plan-info">
            <span>目标：{{ plan.targetDate }}</span>
            <span class="days-left" :class="{ urgent: plan.daysLeft < 30 }">
              剩 {{ plan.daysLeft }} 天
            </span>
          </div>

          <el-progress :percentage="plan.progress || 0" :stroke-width="8" />

          <div class="plan-footer">
            <span>{{ plan.taskDone }} / {{ plan.taskTotal }} 任务</span>
            <span>每日 {{ plan.dailyMinutes }} 分钟</span>
            <el-button link type="primary" @click="openTasks(plan)">查看任务</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <div class="sa-pagination" v-if="total > query.pageSize">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[6, 12, 24]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadPlans"
        @size-change="onSizeChange"
      />
    </div>

    <!-- ------------------------- 创建/编辑计划 --------------- -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑计划' : '新建学习计划'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="计划标题" prop="title">
          <el-input v-model="form.title" placeholder="如：3 个月考研英语二冲刺" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="考试类型" prop="examType">
          <el-radio-group v-model="form.examType">
            <el-radio-button value="考研">考研</el-radio-button>
            <el-radio-button value="考公">考公</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="开始日期" prop="startDate">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="目标考试日期" prop="targetDate">
          <el-date-picker v-model="form.targetDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="每日时长" prop="dailyMinutes">
          <el-input-number v-model="form.dailyMinutes" :min="10" :max="1440" :step="10" />
          <span class="form-hint">分钟，作为计划的目标档位</span>
        </el-form-item>
        <!-- 任务清单：手动建计划由用户自己输入（旧的"套模板均摊生成"已移除） -->
        <el-form-item v-if="!form.id" label="任务清单" required>
          <div class="task-editor">
            <div v-for="(item, idx) in taskDraft" :key="idx" class="task-editor-row">
              <div class="task-editor-line">
                <el-date-picker
                  v-model="item.taskDate"
                  type="date"
                  size="small"
                  value-format="YYYY-MM-DD"
                  :disabled-date="disabledTaskDate"
                  style="width: 126px"
                />
                <el-select
                  v-model="item.subject"
                  size="small"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  placeholder="科目"
                  style="width: 106px"
                >
                  <el-option v-for="s in subjectOptions" :key="s" :label="s" :value="s" />
                </el-select>
                <el-input-number
                  v-model="item.estimatedMinutes"
                  size="small"
                  :min="1"
                  :max="1440"
                  :step="10"
                  controls-position="right"
                  style="width: 110px"
                />
                <el-button link type="danger" :icon="'Delete'" @click="removeDraftTask(idx)" />
              </div>
              <el-input
                v-model="item.title"
                size="small"
                placeholder="任务内容，如：背 50 个核心词 + 精读 1 篇真题阅读"
                maxlength="128"
              />
            </div>
            <div class="task-editor-footer">
              <el-button size="small" :icon="'Plus'" @click="addDraftTask">添加任务</el-button>
              <span class="form-hint">
                共 {{ taskDraft.length }} 条（上限 100），日期需在计划起止范围内；
                想要自动规划可改用「AI 生成计划」
              </span>
            </div>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">
          {{ form.id ? '保存' : '创建计划' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ------------------------- AI 生成计划 --------------- -->
    <el-dialog v-model="agentVisible" title="AI 生成学习计划" width="560px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="规划 Agent 会按目标日期与每日时长拆分逐日任务"
        description="Agent 内部会校验日期连续性与每日时长上限，不合格自动重新生成（最多 2 次）。生成后直接入库，来源标记为「AI 生成」。"
        class="agent-tip"
      />

      <el-form :model="agentForm" label-width="110px">
        <el-form-item label="考试类型" required>
          <el-radio-group v-model="agentForm.examType">
            <el-radio-button value="考研">考研</el-radio-button>
            <el-radio-button value="考公">考公</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="目标考试日期" required>
          <el-date-picker
            v-model="agentForm.targetDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择考试日期"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="每日时长" required>
          <el-input-number v-model="agentForm.dailyMinutes" :min="10" :max="1440" :step="10" />
          <span class="form-hint">分钟</span>
        </el-form-item>

        <el-form-item label="要覆盖的科目" required>
          <el-select
            v-model="agentSubjects"
            multiple
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option v-for="s in subjectOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>

        <el-form-item label="基础水平">
          <el-input
            v-model="agentForm.level"
            type="textarea"
            :rows="2"
            maxlength="200"
            placeholder="可选，如「英语基础一般，政治零基础」——描述越具体，任务安排越贴合"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="agentVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="onAgentCreate">
          {{ generating ? 'Agent 正在规划…' : '开始生成' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ------------------------- 任务抽屉 --------------- -->
    <el-drawer v-model="drawerVisible" :title="currentPlan?.title || '任务列表'" size="620px">
      <div class="drawer-toolbar">
        <el-date-picker
          v-model="taskRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          @change="loadTasks"
        />
        <el-select v-model="taskStatus" placeholder="状态" clearable style="width: 110px" @change="loadTasks">
          <el-option label="待办" value="待办" />
          <el-option label="完成" value="完成" />
          <el-option label="跳过" value="跳过" />
        </el-select>
        <el-button type="primary" size="small" :icon="'Plus'" @click="openAddTask">添加任务</el-button>
      </div>

      <el-skeleton v-if="taskLoading" :rows="6" animated />

      <el-empty v-else-if="!tasks.length" description="该条件下没有任务" :image-size="70" />

      <div v-else class="task-timeline">
        <div v-for="group in groupedTasks" :key="group.date" class="task-group">
          <div class="group-date">
            {{ group.date }}
            <el-tag size="small" type="info">{{ group.items.length }} 条</el-tag>
          </div>
          <div
            v-for="task in group.items"
            :key="task.id"
            class="task-row"
            :class="{ done: task.status === '完成' }"
          >
            <el-checkbox
              :model-value="task.status === '完成'"
              @change="(val) => toggleTask(task, val)"
            />
            <div class="task-row-body">
              <div class="task-row-title">{{ task.title }}</div>
              <div class="task-row-meta">
                <el-tag size="small" type="info">{{ task.subject || '综合' }}</el-tag>
                <span>{{ task.estimatedMinutes }} 分钟</span>
                <el-tag v-if="task.status === '跳过'" size="small" type="warning">已跳过</el-tag>
              </div>
            </div>
            <el-button link type="danger" :icon="'Delete'" @click="removeTask(task)" />
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- ------------------------- 添加任务（抽屉内） --------------- -->
    <el-dialog v-model="addTaskVisible" title="添加任务" width="520px">
      <el-form label-width="80px">
        <el-form-item label="任务日期" required>
          <el-date-picker
            v-model="addTaskForm.taskDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled-date="disabledAddTaskDate"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="科目">
          <el-select
            v-model="addTaskForm.subject"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="可选"
            style="width: 100%"
          >
            <el-option v-for="s in subjectOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="任务内容" required>
          <el-input
            v-model="addTaskForm.title"
            placeholder="如：背 50 个核心词 + 精读 1 篇真题阅读"
            maxlength="128"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="预计时长">
          <el-input-number v-model="addTaskForm.estimatedMinutes" :min="1" :max="1440" :step="10" />
          <span class="form-hint">分钟</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addTaskVisible = false">取消</el-button>
        <el-button type="primary" :loading="addTaskSubmitting" @click="submitAddTask">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { planApi } from '@/api/plan'

defineOptions({ name: 'Plan' })

const subjectOptions = ['英语', '数学', '政治', '专业课', '行测', '申论']

const loading = ref(false)
const submitting = ref(false)
const generating = ref(false)
const plans = ref([])
const total = ref(0)

const query = reactive({ pageNum: 1, pageSize: 6, status: '进行中', examType: undefined })

const dialogVisible = ref(false)
const formRef = ref()
const form = reactive({
  id: null,
  title: '',
  examType: '考研',
  startDate: '',
  targetDate: '',
  dailyMinutes: 120,
})
// 建计划：用户自定义任务清单（旧的"科目均摊生成模板任务"已移除）
const taskDraft = ref([])

function makeDraftTask() {
  return {
    taskDate: form.startDate || new Date().toISOString().slice(0, 10),
    subject: '',
    title: '',
    estimatedMinutes: 60,
  }
}

function addDraftTask() {
  if (taskDraft.value.length >= 100) {
    ElMessage.warning('单次最多 100 条任务，更多任务可创建后在任务抽屉里补充')
    return
  }
  taskDraft.value.push(makeDraftTask())
}

function removeDraftTask(idx) {
  taskDraft.value.splice(idx, 1)
}

/** 任务日期限制在计划的起止范围内（按本地自然日比对，避免时区偏移误禁） */
function outsidePlanRange(day, start, end) {
  const t = day.getTime()
  if (start && t < new Date(start + 'T00:00:00').getTime()) return true
  if (end && t > new Date(end + 'T23:59:59').getTime()) return true
  return false
}

function disabledTaskDate(day) {
  return outsidePlanRange(day, form.startDate, form.targetDate)
}

function disabledAddTaskDate(day) {
  return outsidePlanRange(day, currentPlan.value?.startDate, currentPlan.value?.targetDate)
}

// 添加任务弹窗（任务抽屉内）
const addTaskVisible = ref(false)
const addTaskSubmitting = ref(false)
const addTaskForm = reactive({ taskDate: '', subject: '', title: '', estimatedMinutes: 60 })

function openAddTask() {
  Object.assign(addTaskForm, {
    taskDate: new Date().toISOString().slice(0, 10),
    subject: '',
    title: '',
    estimatedMinutes: 60,
  })
  addTaskVisible.value = true
}

async function submitAddTask() {
  if (!addTaskForm.title.trim()) {
    ElMessage.warning('请填写任务内容')
    return
  }
  if (!addTaskForm.taskDate) {
    ElMessage.warning('请选择任务日期')
    return
  }
  addTaskSubmitting.value = true
  try {
    await planApi.addTask({
      planId: currentPlan.value?.id,
      taskDate: addTaskForm.taskDate,
      title: addTaskForm.title.trim(),
      subject: addTaskForm.subject || undefined,
      estimatedMinutes: addTaskForm.estimatedMinutes,
    })
    ElMessage.success('任务已添加')
    addTaskVisible.value = false
    await Promise.all([loadTasks(), loadPlans()])
  } catch (e) {
    // 拦截器已提示
  } finally {
    addTaskSubmitting.value = false
  }
}

// AI 生成计划弹窗
const agentVisible = ref(false)
const agentSubjects = ref(['英语', '政治'])
const agentForm = reactive({
  examType: '考研',
  targetDate: '',
  dailyMinutes: 120,
  level: '',
})

const rules = {
  title: [{ required: true, message: '请输入计划标题', trigger: 'blur' }],
  examType: [{ required: true, message: '请选择考试类型', trigger: 'change' }],
  targetDate: [{ required: true, message: '请选择目标考试日期', trigger: 'change' }],
  dailyMinutes: [{ required: true, message: '请填写每日时长', trigger: 'blur' }],
}

// 任务抽屉
const drawerVisible = ref(false)
const currentPlan = ref(null)
const tasks = ref([])
const taskLoading = ref(false)
const taskRange = ref([])
const taskStatus = ref('')

const groupedTasks = computed(() => {
  const map = new Map()
  tasks.value.forEach((task) => {
    if (!map.has(task.taskDate)) {
      map.set(task.taskDate, [])
    }
    map.get(task.taskDate).push(task)
  })
  return Array.from(map.entries())
    .sort((a, b) => (a[0] < b[0] ? -1 : 1))
    .map(([dateVal, items]) => ({ date: dateVal, items }))
})

function statusType(status) {
  if (status === '进行中') return 'primary'
  if (status === '已完成') return 'success'
  return 'info'
}

async function loadPlans() {
  loading.value = true
  try {
    const data = await planApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status || undefined,
      examType: query.examType || undefined,
    })
    plans.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function onSizeChange(size) {
  query.pageSize = size
  query.pageNum = 1
  loadPlans()
}

function openCreate() {
  Object.assign(form, {
    id: null,
    title: '',
    examType: '考研',
    startDate: new Date().toISOString().slice(0, 10),
    targetDate: '',
    dailyMinutes: 120,
  })
  taskDraft.value = [makeDraftTask()]
  dialogVisible.value = true
}

function openEdit(plan) {
  Object.assign(form, {
    id: plan.id,
    title: plan.title,
    examType: plan.examType,
    startDate: plan.startDate,
    targetDate: plan.targetDate,
    dailyMinutes: plan.dailyMinutes,
  })
  dialogVisible.value = true
}

async function onSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  const creating = !form.id

  let taskItems
  if (creating) {
    if (!taskDraft.value.length) {
      ElMessage.warning('请至少添加一条任务')
      return
    }
    if (taskDraft.value.some((t) => !t.title || !t.title.trim())) {
      ElMessage.warning('有任务还没填内容，补全后再创建')
      return
    }
    if (taskDraft.value.some((t) => !t.taskDate)) {
      ElMessage.warning('有任务还没选日期，补全后再创建')
      return
    }
    taskItems = taskDraft.value.map((t) => ({
      taskDate: t.taskDate,
      title: t.title.trim(),
      subject: t.subject || undefined,
      estimatedMinutes: t.estimatedMinutes,
    }))
  }

  const payload = {
    title: form.title,
    examType: form.examType,
    startDate: form.startDate || undefined,
    targetDate: form.targetDate,
    dailyMinutes: form.dailyMinutes,
  }
  if (creating) {
    payload.tasks = taskItems
  }

  submitting.value = true
  try {
    if (form.id) {
      await planApi.update(form.id, payload)
      ElMessage.success('计划已更新')
    } else {
      const created = await planApi.create(payload)
      ElMessage.success(`创建成功，共 ${created?.taskTotal ?? 0} 条任务`)
    }
    dialogVisible.value = false
    await loadPlans()
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

async function onPlanCommand(command, plan) {
  if (command === 'edit') {
    openEdit(plan)
    return
  }
  if (command === 'delete') {
    try {
      await ElMessageBox.confirm(
        `删除计划「${plan.title}」会同时删除它下面的 ${plan.taskTotal} 条任务，确定吗？`,
        '删除确认',
        { type: 'warning', confirmButtonText: '确定删除' },
      )
    } catch {
      return
    }
    await planApi.remove(plan.id)
    ElMessage.success('已删除')
    await loadPlans()
    return
  }
  if (command === 'finish' || command === 'abandon') {
    const status = command === 'finish' ? '已完成' : '已放弃'
    await planApi.changeStatus(plan.id, status)
    ElMessage.success(`已标记为${status}`)
    await loadPlans()
  }
}

async function openTasks(plan) {
  currentPlan.value = plan
  drawerVisible.value = true
  // 默认展示整段计划的日期区间
  taskRange.value = [plan.startDate, plan.targetDate]
  taskStatus.value = ''
  await loadTasks()
}

async function loadTasks() {
  if (!currentPlan.value) return
  taskLoading.value = true
  try {
    const params = { planId: currentPlan.value.id }
    if (Array.isArray(taskRange.value) && taskRange.value.length === 2) {
      params.startDate = taskRange.value[0]
      params.endDate = taskRange.value[1]
    }
    if (taskStatus.value) params.status = taskStatus.value
    tasks.value = (await planApi.listTasks(params)) || []
  } catch (e) {
    tasks.value = []
  } finally {
    taskLoading.value = false
  }
}

async function toggleTask(task, checked) {
  try {
    await planApi.changeTaskStatus({
      taskId: task.id,
      status: checked ? '完成' : '待办',
      actualMinutes: checked ? task.estimatedMinutes : undefined,
    })
    ElMessage.success(checked ? '已完成，时长已累计' : '已取消完成')
    await Promise.all([loadTasks(), loadPlans()])
  } catch (e) {
    // 拦截器已提示
  }
}

async function removeTask(task) {
  try {
    await ElMessageBox.confirm(`确定删除任务「${task.title}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await planApi.removeTask(task.id)
  ElMessage.success('任务已删除')
  await Promise.all([loadTasks(), loadPlans()])
}

/**
 * AI 生成计划：调后端 /plan/agent-generate。
 *
 * 后端内部编排：Java 校验登录用户 → Python LangGraph 规划 Agent 生成逐日任务
 * （信息补全 → 生成 → 校验，不合格自动重试最多 2 次）→ Java 落库（source=Agent生成）。
 *
 * 为什么用对话框而不是直接点一下就用当前筛选条件：
 * Agent 需要"考试类型 / 目标日期 / 每日时长"这几个必填信息才能规划，
 * 弹窗收集一次比让用户先去建个空计划再改更合理。
 */
function openAgentDialog() {
  Object.assign(agentForm, {
    examType: '考研',
    targetDate: '',
    dailyMinutes: 120,
    level: '',
  })
  agentSubjects.value = ['英语', '政治']
  agentVisible.value = true
}

async function onAgentCreate() {
  if (!agentForm.targetDate) {
    ElMessage.warning('请选择目标考试日期')
    return
  }
  if (!agentSubjects.value.length) {
    ElMessage.warning('请至少选择一个科目')
    return
  }

  generating.value = true
  try {
    const result = await planApi.agentGenerate({
      examType: agentForm.examType,
      targetDate: agentForm.targetDate,
      dailyMinutes: agentForm.dailyMinutes,
      subjects: agentSubjects.value.join(','),
      level: agentForm.level || undefined,
      persist: true,
    })

    const lines = [
      `已生成《${result.title}》，共 ${result.totalTasks} 条任务，覆盖 ${result.days} 天`,
    ]
    if (result.retries) {
      lines.push(`Agent 自检重试 ${result.retries} 次`)
    }
    if (result.warnings?.length) {
      lines.push(...result.warnings.slice(0, 3))
    }
    ElMessageBox.alert(lines.join('<br/>'), 'AI 生成完成', {
      dangerouslyUseHTMLString: true,
      confirmButtonText: '查看计划',
    }).catch(() => {})

    agentVisible.value = false
    await loadPlans()
  } catch (e) {
    // 拦截器已提示（后端会把"模型未就绪"翻译成可操作提示）
  } finally {
    generating.value = false
  }
}

onMounted(loadPlans)
</script>

<style scoped>
.plan-card {
  margin-bottom: 12px;
}

.plan-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.plan-title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}

.more-icon {
  cursor: pointer;
  color: #909399;
}

.plan-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 10px 0;
}

.plan-info {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
  margin-bottom: 8px;
}

.days-left {
  color: #909399;
}

.days-left.urgent {
  color: #f56c6c;
  font-weight: 600;
}

.plan-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
  gap: 6px;
}

.form-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.agent-tip {
  margin-bottom: 16px;
}

.task-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-editor-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.task-editor-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-editor-footer {
  display: flex;
  align-items: center;
  gap: 10px;
}

.drawer-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.task-group {
  margin-bottom: 16px;
}

.group-date {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 6px;
}

.task-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 0;
}

.task-row.done .task-row-title {
  color: #a8abb2;
  text-decoration: line-through;
}

.task-row-body {
  flex: 1;
  min-width: 0;
}

.task-row-title {
  font-size: 13px;
  line-height: 1.5;
}

.task-row-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
