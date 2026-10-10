<template>
  <div class="sa-page">
    <div class="sa-page-header">
      <div>
        <h2 class="sa-page-title">错题本</h2>
        <p class="sa-page-subtitle">按科目 / 掌握度 / 标签筛选；支持批量标记与导出 Excel</p>
      </div>
      <div>
        <el-button :icon="'Download'" :loading="exporting" @click="onExport">导出 Excel</el-button>
        <el-button type="primary" :icon="'Plus'" @click="openCreate">新增错题</el-button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <div class="sa-filter-bar">
        <el-select v-model="query.subject" placeholder="科目" clearable style="width: 110px" @change="onSearch">
          <el-option v-for="s in subjectOptions" :key="s" :label="s" :value="s" />
        </el-select>

        <el-select v-model="query.mastery" placeholder="掌握度" clearable style="width: 120px" @change="onSearch">
          <el-option v-for="m in masteryOptions" :key="m" :label="m" :value="m" />
        </el-select>

        <el-select v-model="query.questionType" placeholder="题型" clearable style="width: 110px" @change="onSearch">
          <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
        </el-select>

        <el-select v-model="query.tags" placeholder="标签（可多选）" multiple clearable collapse-tags
                   style="width: 200px" @change="onSearch">
          <el-option v-for="t in tagOptions" :key="t" :label="t" :value="t" />
        </el-select>

        <el-input v-model="query.keyword" placeholder="搜题干 / 答案 / 解析" clearable
                  style="width: 200px" @keyup.enter="onSearch" @clear="onSearch" />

        <el-button type="primary" :icon="'Search'" @click="onSearch">查询</el-button>
        <el-button :icon="'Refresh'" @click="onReset">重置</el-button>
      </div>
    </el-card>

    <!-- 批量操作条：有选中才出现 -->
    <div v-if="selection.length" class="batch-bar">
      <span>已选 <b>{{ selection.length }}</b> 条</span>
      <el-select v-model="batchMastery" placeholder="批量设为掌握度" size="small" style="width: 150px">
        <el-option v-for="m in masteryOptions" :key="m" :label="m" :value="m" />
      </el-select>
      <el-button size="small" type="primary" :disabled="!batchMastery" @click="onBatchMastery">
        应用
      </el-button>
      <el-button size="small" type="danger" @click="onBatchDelete">批量删除</el-button>
      <el-button size="small" text @click="clearSelection">取消选择</el-button>
    </div>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        border
        stripe
        @selection-change="(val) => (selection = val)"
      >
        <el-table-column type="selection" width="44" reserve-selection />

        <el-table-column prop="subject" label="科目" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ row.subject }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="题干" min-width="260">
          <template #default="{ row }">
            <div class="sa-ellipsis-2">{{ row.content }}</div>
            <div class="row-tags" v-if="row.tags?.length">
              <el-tag v-for="t in row.tags" :key="t" size="small" type="success" effect="plain">
                {{ t }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="questionType" label="题型" width="80" />
        <el-table-column prop="difficulty" label="难度" width="70">
          <template #default="{ row }">
            <el-rate :model-value="row.difficulty" disabled size="small" />
          </template>
        </el-table-column>

        <el-table-column prop="mastery" label="掌握度" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="masteryType(row.mastery)">{{ row.mastery }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="录入时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="sa-pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="loadRows"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <!-- ------------------------- 新增/编辑 --------------- -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑错题' : '新增错题'" width="680px" top="6vh">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="科目" prop="subject">
              <el-select v-model="form.subject" filterable allow-create style="width: 100%">
                <el-option v-for="s in subjectOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="题型">
              <el-select v-model="form.questionType" clearable style="width: 100%">
                <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="难度">
              <el-rate v-model="form.difficulty" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="题干" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="5000" show-word-limit />
        </el-form-item>

        <el-form-item label="答案">
          <el-input v-model="form.answer" type="textarea" :rows="2" maxlength="5000" />
        </el-form-item>

        <el-form-item label="解析">
          <el-input v-model="form.analysis" type="textarea" :rows="3" maxlength="8000" />
        </el-form-item>

        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="掌握度">
              <el-select v-model="form.mastery" style="width: 100%">
                <el-option v-for="m in masteryOptions" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="来源">
              <el-input v-model="form.source" placeholder="真题 / 模拟题" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="标签">
              <el-select v-model="form.tags" multiple filterable allow-create default-first-option
                         placeholder="回车新建" style="width: 100%">
                <el-option v-for="t in tagOptions" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- ------------------------- 详情 --------------- -->
    <el-drawer v-model="detailVisible" title="错题详情" size="520px">
      <template v-if="detailRow">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="科目">{{ detailRow.subject }}</el-descriptions-item>
          <el-descriptions-item label="题型">{{ detailRow.questionType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="难度">{{ detailRow.difficulty }}</el-descriptions-item>
          <el-descriptions-item label="掌握度">
            <el-tag size="small" :type="masteryType(detailRow.mastery)">{{ detailRow.mastery }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="标签">
            <el-tag v-for="t in detailRow.tags" :key="t" size="small" type="success" effect="plain" class="mr4">
              {{ t }}
            </el-tag>
            <span v-if="!detailRow.tags?.length">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ detailRow.source || '-' }}</el-descriptions-item>
          <el-descriptions-item label="录入时间">{{ formatTime(detailRow.createTime) }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-block">
          <div class="detail-label">题干</div>
          <div class="detail-text">{{ detailRow.content }}</div>
        </div>
        <div class="detail-block">
          <div class="detail-label">答案</div>
          <div class="detail-text">{{ detailRow.answer || '-' }}</div>
        </div>
        <div class="detail-block">
          <div class="detail-label">解析</div>
          <div class="detail-text">{{ detailRow.analysis || '-' }}</div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { wrongApi } from '@/api/wrong'

defineOptions({ name: 'Wrong' })

const subjectOptions = ['英语', '数学', '政治', '专业课', '行测', '申论']
const typeOptions = ['单选', '多选', '填空', '解答', '论述']
const masteryOptions = ['未掌握', '复习中', '已掌握']

const loading = ref(false)
const submitting = ref(false)
const exporting = ref(false)
const rows = ref([])
const total = ref(0)
const tagOptions = ref([])
const selection = ref([])
const batchMastery = ref('')

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  subject: '',
  mastery: '',
  questionType: '',
  tags: [],
  keyword: '',
})

const dialogVisible = ref(false)
const formRef = ref()
const form = reactive({
  id: null,
  subject: '数学',
  questionType: '解答',
  content: '',
  answer: '',
  analysis: '',
  difficulty: 3,
  mastery: '未掌握',
  source: '',
  tags: [],
})

const detailVisible = ref(false)
const detailRow = ref(null)

const rules = {
  subject: [{ required: true, message: '请选择科目', trigger: 'change' }],
  content: [{ required: true, message: '请输入题干', trigger: 'blur' }],
}

function masteryType(mastery) {
  if (mastery === '已掌握') return 'success'
  if (mastery === '复习中') return 'warning'
  return 'danger'
}

function formatTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

function buildParams() {
  const params = {
    pageNum: query.pageNum,
    pageSize: query.pageSize,
    sortBy: 'createTime',
    asc: false,
  }
  if (query.subject) params.subject = query.subject
  if (query.mastery) params.mastery = query.mastery
  if (query.questionType) params.questionType = query.questionType
  if (query.keyword) params.keyword = query.keyword
  if (query.tags?.length) params.tags = query.tags
  return params
}

async function loadRows() {
  loading.value = true
  try {
    const data = await wrongApi.page(buildParams())
    rows.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadTags() {
  try {
    tagOptions.value = (await wrongApi.tags()) || []
  } catch (e) {
    tagOptions.value = []
  }
}

function onSearch() {
  query.pageNum = 1
  loadRows()
}

function onSizeChange(size) {
  query.pageSize = size
  query.pageNum = 1
  loadRows()
}

function onReset() {
  Object.assign(query, {
    pageNum: 1,
    pageSize: 10,
    subject: '',
    mastery: '',
    questionType: '',
    tags: [],
    keyword: '',
  })
  clearSelection()
  loadRows()
}

function clearSelection() {
  selection.value = []
}

function openCreate() {
  Object.assign(form, {
    id: null,
    subject: '数学',
    questionType: '解答',
    content: '',
    answer: '',
    analysis: '',
    difficulty: 3,
    mastery: '未掌握',
    source: '',
    tags: [],
  })
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    subject: row.subject,
    questionType: row.questionType,
    content: row.content,
    answer: row.answer,
    analysis: row.analysis,
    difficulty: row.difficulty,
    mastery: row.mastery,
    source: row.source,
    tags: [...(row.tags || [])],
  })
  dialogVisible.value = true
}

function openDetail(row) {
  detailRow.value = row
  detailVisible.value = true
}

async function onSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  const payload = { ...form }
  delete payload.id

  submitting.value = true
  try {
    if (form.id) {
      await wrongApi.update(form.id, payload)
      ElMessage.success('修改成功')
    } else {
      await wrongApi.create(payload)
      ElMessage.success('添加成功')
    }
    dialogVisible.value = false
    await Promise.all([loadRows(), loadTags()])
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm('确定删除这道错题吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await wrongApi.remove(row.id)
  ElMessage.success('已删除')
  await Promise.all([loadRows(), loadTags()])
}

async function onBatchDelete() {
  const ids = selection.value.map((item) => item.id)
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 道错题吗？`, '批量删除', {
      type: 'warning',
    })
  } catch {
    return
  }
  const count = await wrongApi.batchRemove(ids)
  ElMessage.success(`已删除 ${count} 条`)
  clearSelection()
  await Promise.all([loadRows(), loadTags()])
}

async function onBatchMastery() {
  const ids = selection.value.map((item) => item.id)
  const count = await wrongApi.batchMastery(ids, batchMastery.value)
  ElMessage.success(`已更新 ${count} 条`)
  batchMastery.value = ''
  clearSelection()
  await loadRows()
}

async function onExport() {
  exporting.value = true
  try {
    // 导出跟随当前筛选条件（但去掉分页参数由后端接管）
    const params = buildParams()
    delete params.pageNum
    delete params.pageSize
    const fileName = await wrongApi.exportExcel(params)
    ElMessage.success(`已导出：${fileName}`)
  } catch (e) {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  loadRows()
  loadTags()
})
</script>

<style scoped>
.filter-card {
  margin-bottom: 12px;
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background-color: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 6px;
  font-size: 13px;
}

.row-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.mr4 {
  margin-right: 4px;
}

.detail-block {
  margin-top: 14px;
}

.detail-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.detail-text {
  padding: 8px 10px;
  background-color: #f5f7fa;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
