<template>
  <div class="sa-page">
    <div class="sa-page-header">
      <div>
        <h2 class="sa-page-title">消息通知</h2>
        <p class="sa-page-subtitle">站内通知；第 4 周起学习提醒与报告通知会由定时任务与 RocketMQ 自动产生</p>
      </div>
      <div>
        <el-button :icon="'Check'" :disabled="!rows.length" @click="onMarkAll">全部已读</el-button>
        <el-button :icon="'Refresh'" :loading="loading" @click="loadRows">刷新</el-button>
      </div>
    </div>

    <div class="sa-filter-bar">
      <el-radio-group v-model="type" @change="onSearch">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="提醒">提醒</el-radio-button>
        <el-radio-button value="复习">复习</el-radio-button>
        <el-radio-button value="报告">报告</el-radio-button>
      </el-radio-group>
      <el-checkbox v-model="onlyUnread" @change="onSearch">只看未读</el-checkbox>
      <el-tag v-if="unreadCount" type="danger" size="small">未读 {{ unreadCount }}</el-tag>
    </div>

    <el-card shadow="never">
      <el-skeleton v-if="loading" :rows="5" animated />

      <el-empty v-else-if="!rows.length" description="暂无通知" />

      <div v-else class="notice-list">
        <div
          v-for="item in rows"
          :key="item.id"
          class="notice-item"
          :class="{ unread: !item.read }"
        >
          <div class="notice-main" @click="onOpen(item)">
            <div class="notice-head">
              <el-tag size="small" :type="typeTag(item.type)">{{ item.type }}</el-tag>
              <span class="notice-title">{{ item.title }}</span>
              <el-badge v-if="!item.read" is-dot />
            </div>
            <div class="notice-content">{{ item.content }}</div>
            <div class="notice-time">{{ formatTime(item.sendTime || item.createTime) }}</div>
          </div>
          <div class="notice-actions">
            <el-button v-if="!item.read" link type="primary" @click="onMarkRead(item)">标记已读</el-button>
            <el-button link type="danger" @click="onDelete(item)">删除</el-button>
          </div>
        </div>
      </div>

      <div class="sa-pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadRows"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { noticeApi } from '@/api/progress'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const unreadCount = ref(0)
const type = ref('')
const onlyUnread = ref(false)

const query = reactive({ pageNum: 1, pageSize: 10 })

function typeTag(value) {
  if (value === '报告') return 'success'
  if (value === '复习') return 'warning'
  return 'primary'
}

function formatTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

async function loadRows() {
  loading.value = true
  try {
    const data = await noticeApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      type: type.value || undefined,
      readFlag: onlyUnread.value ? 0 : undefined,
    })
    rows.value = data?.records || []
    total.value = Number(data?.total || 0)
    unreadCount.value = (await noticeApi.unreadCount()) || 0
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
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

async function onMarkRead(item) {
  await noticeApi.markRead(item.id)
  ElMessage.success('已标记为已读')
  await loadRows()
}

async function onMarkAll() {
  const count = await noticeApi.markAllRead()
  ElMessage.success(`已标记 ${count} 条为已读`)
  await loadRows()
}

async function onDelete(item) {
  try {
    await ElMessageBox.confirm('确定删除这条通知吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await noticeApi.remove(item.id)
  ElMessage.success('已删除')
  await loadRows()
}

/** 点通知正文：标记已读，并按 bizId/type 跳到目标页 */
async function onOpen(item) {
  if (!item.read) {
    try {
      await noticeApi.markRead(item.id)
      item.read = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    } catch (e) {
      // 忽略：不影响跳转
    }
  }
  if (item.type === '报告') {
    router.push('/report')
  } else if (item.type === '复习') {
    router.push('/wrong')
  } else {
    router.push('/plan')
  }
}

onMounted(loadRows)
</script>

<style scoped>
.notice-list {
  display: flex;
  flex-direction: column;
}

.notice-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 8px;
  border-bottom: 1px dashed #ebeef5;
}

.notice-item:last-child {
  border-bottom: none;
}

.notice-item.unread {
  background-color: #fafcff;
}

.notice-main {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}

.notice-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.notice-title {
  font-size: 14px;
  font-weight: 500;
}

.notice-content {
  margin-top: 4px;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}

.notice-time {
  margin-top: 4px;
  font-size: 12px;
  color: #a8abb2;
}

.notice-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
</style>
