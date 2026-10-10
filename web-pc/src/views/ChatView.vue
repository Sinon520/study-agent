<template>
  <div class="chat-page">
    <!-- 左侧会话列表 -->
    <aside class="session-pane">
      <div class="session-head">
        <el-button type="primary" :icon="'Plus'" class="new-btn" @click="newSession">
          新建会话
        </el-button>
      </div>

      <div class="session-list" v-loading="sessionLoading">
        <div
          v-for="item in sessions"
          :key="item.id"
          class="session-item"
          :class="{ active: item.id === activeSessionId }"
          @click="switchSession(item)"
        >
          <el-icon><ChatLineRound /></el-icon>
          <span class="session-title">{{ item.title }}</span>
          <el-icon class="session-del" @click.stop="removeSession(item)"><Delete /></el-icon>
        </div>
        <el-empty v-if="!sessions.length" description="还没有会话" :image-size="60" />
      </div>

      <div class="session-foot">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          :title="readyDocCount ? `资料库就绪 ${readyDocCount} 份` : '资料库为空'"
          :description="
            readyDocCount
              ? '回答会基于这些资料，并在底部给出引用来源。'
              : '先点右上角「资料库」上传 PDF / Word，问答才会带上引用溯源。'
          "
        />
      </div>
    </aside>

    <!-- 右侧消息区 -->
    <section class="message-pane">
      <header class="message-head">
        <div>
          <div class="message-title">{{ activeSession?.title || '新的会话' }}</div>
          <div class="message-sub">
            模型 qwen3.7-flash · 思考过程与正式回答分开流式返回
            <span v-if="lastChunkCount" class="sub-tag">本次召回 {{ lastChunkCount }} 段</span>
          </div>
        </div>
        <div class="head-actions">
          <el-badge :value="readyDocCount" :hidden="!readyDocCount" :max="99">
            <el-button :icon="'Folder'" @click="openKb">资料库</el-button>
          </el-badge>
          <el-button
            v-if="streaming"
            type="danger"
            plain
            :icon="'CircleClose'"
            @click="stopStreaming"
          >
            停止生成
          </el-button>
        </div>
      </header>

      <div ref="scrollRef" class="message-body">
        <el-empty
          v-if="!messages.length"
          description="问点什么吧，比如「帮我梳理一下考研英语二的复习重点」"
        >
          <div class="prompt-list">
            <el-tag v-for="p in prompts" :key="p" class="prompt-tag" @click="input = p">
              {{ p }}
            </el-tag>
          </div>
        </el-empty>

        <div v-for="msg in messages" :key="msg.key" class="msg" :class="msg.role">
          <div class="msg-avatar">
            <el-icon v-if="msg.role === 'user'"><User /></el-icon>
            <el-icon v-else><MagicStick /></el-icon>
          </div>
          <div class="msg-body">
            <!-- 思考过程：默认折叠，符合"思考中…"的展示习惯 -->
            <el-collapse v-if="msg.reasoning" class="reasoning">
              <el-collapse-item :title="`思考过程（${msg.reasoning.length} 字）`" name="r">
                <div class="reasoning-text">{{ msg.reasoning }}</div>
              </el-collapse-item>
            </el-collapse>

            <div
              v-if="msg.role === 'assistant'"
              class="msg-content markdown"
              v-html="render(msg.content)"
            ></div>
            <div v-else class="msg-content">{{ msg.content }}</div>

            <!-- 引用溯源 -->
            <div v-if="msg.citations?.length" class="citations">
              <el-collapse>
                <el-collapse-item :title="`引用来源（${msg.citations.length}）`" name="c">
                  <div v-for="(c, i) in msg.citations" :key="i" class="citation-item">
                    <div class="citation-head">
                      <el-tag size="small" type="success">{{ c.index ?? i + 1 }}</el-tag>
                      <span class="citation-name">{{ c.documentName }}</span>
                      <span v-if="c.pageNo" class="citation-page">第 {{ c.pageNo }} 页</span>
                      <span v-if="c.score" class="citation-score">
                        相关度 {{ Number(c.score).toFixed(3) }}
                      </span>
                    </div>
                    <div class="citation-snippet">{{ c.snippet }}</div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>
        </div>

        <div v-if="streaming && !hasAnyContent" class="msg assistant">
          <div class="msg-avatar"><el-icon><MagicStick /></el-icon></div>
          <div class="msg-body">
            <div class="typing"><span></span><span></span><span></span></div>
          </div>
        </div>
      </div>

      <footer class="message-foot">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          maxlength="2000"
          :disabled="streaming"
          placeholder="输入问题，Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact.prevent="onSend"
        />
        <el-button
          type="primary"
          :icon="'Promotion'"
          :loading="streaming"
          :disabled="!input.trim() || streaming"
          class="send-btn"
          @click="onSend"
        >
          发送
        </el-button>
      </footer>
    </section>

    <!-- ------------------------- 资料库抽屉 --------------- -->
    <el-drawer v-model="kbVisible" title="资料库" size="640px">
      <div class="kb-toolbar">
        <input
          ref="fileInputRef"
          type="file"
          accept=".pdf,.docx,.doc,.txt,.md"
          style="display: none"
          @change="onFileChange"
        />
        <el-button type="primary" :icon="'Upload'" :loading="uploading" @click="pickFile">
          上传资料
        </el-button>
        <span class="kb-hint">支持 PDF / Word / txt / md，单文件 ≤ 50MB</span>
        <el-button :icon="'Refresh'" :loading="kbLoading" @click="loadDocuments">刷新</el-button>
      </div>

      <el-progress
        v-if="uploading"
        :percentage="uploadPercent"
        :stroke-width="8"
        class="kb-progress"
      />

      <el-alert
        v-if="uploading"
        type="info"
        :closable="false"
        show-icon
        title="上传后会在后台解析入库"
        description="首次解析需要加载本地向量模型（CPU 推理，可能耗时数十秒到几分钟），状态会从「解析中」变为「完成」。"
        class="kb-alert"
      />

      <el-table v-loading="kbLoading" :data="documents" size="small" border>
        <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="fileSizeText" label="大小" width="80" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.parseStatus)">
              {{ row.parseStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="切片" width="60" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.parseStatus === '失败'"
              link
              type="primary"
              @click="reingest(row)"
            >
              重试
            </el-button>
            <el-button link type="danger" @click="removeDocument(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="hasFailedDoc" class="kb-error-tip">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ failedReason }}</span>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { chatStream } from '@/api/chat'
import { chatApi, kbApi } from '@/api/kb'

defineOptions({ name: 'Chat' })

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const prompts = [
  '帮我梳理考研英语二的复习重点',
  '数学定积分有哪些常见题型',
  '政治马原怎么背效率最高',
]

// ---------------------------------------------------------------- 会话

const sessions = ref([])
const activeSessionId = ref(null)
const sessionLoading = ref(false)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const scrollRef = ref()

let abortController = null
let pollTimer = null

const activeSession = computed(
  () => sessions.value.find((item) => item.id === activeSessionId.value) || null,
)
const hasAnyContent = computed(() =>
  messages.value.some((m) => m.role === 'assistant' && m.content),
)

function render(text) {
  return md.render(text || '')
}

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function loadSessions() {
  sessionLoading.value = true
  try {
    const data = await chatApi.pageSessions({ pageNum: 1, pageSize: 50 })
    sessions.value = data?.records || []
  } catch (e) {
    sessions.value = []
  } finally {
    sessionLoading.value = false
  }
}

function newSession() {
  activeSessionId.value = null
  messages.value = []
}

async function switchSession(item) {
  activeSessionId.value = item.id
  messages.value = []
  try {
    const list = await chatApi.listMessages(item.id)
    messages.value = (list || []).map((msg) => ({
      key: `h-${msg.id}`,
      role: msg.role,
      content: msg.content || '',
      reasoning: msg.reasoningContent || '',
      citations: msg.citations || [],
    }))
    scrollToBottom()
  } catch (e) {
    // 拦截器已提示
  }
}

async function removeSession(item) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${item.title}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await chatApi.removeSession(item.id)
  ElMessage.success('已删除')
  if (activeSessionId.value === item.id) {
    newSession()
  }
  await loadSessions()
}

// ---------------------------------------------------------------- 资料库

const kbVisible = ref(false)
const kbLoading = ref(false)
const documents = ref([])
const uploading = ref(false)
const uploadPercent = ref(0)
const fileInputRef = ref()

const readyDocCount = computed(
  () => documents.value.filter((d) => d.parseStatus === '完成').length,
)
const hasFailedDoc = computed(() => documents.value.some((d) => d.parseStatus === '失败'))
const failedReason = computed(() => {
  const failed = documents.value.find((d) => d.parseStatus === '失败')
  return failed?.errorMsg || '有资料解析失败，可在列表里点「重试」'
})

function statusType(status) {
  if (status === '完成') return 'success'
  if (status === '失败') return 'danger'
  if (status === '解析中') return 'warning'
  return 'info'
}

async function openKb() {
  kbVisible.value = true
  await loadDocuments()
}

async function loadDocuments() {
  kbLoading.value = true
  try {
    const data = await kbApi.page({ pageNum: 1, pageSize: 50 })
    documents.value = data?.records || []
  } catch (e) {
    documents.value = []
  } finally {
    kbLoading.value = false
  }
}

function pickFile() {
  fileInputRef.value?.click()
}

async function onFileChange(event) {
  const file = event.target.files?.[0]
  // 必须清空 input 的 value，否则连续上传同一个文件不会触发 change
  event.target.value = ''
  if (!file) return

  uploading.value = true
  uploadPercent.value = 0
  try {
    const doc = await kbApi.upload(file, (percent) => {
      uploadPercent.value = percent
    })
    ElMessage.success(`「${doc.fileName}」已提交解析，请稍候查看状态`)
    await loadDocuments()
    startPolling()
  } catch (e) {
    // 拦截器已提示
  } finally {
    uploading.value = false
  }
}

/**
 * 轮询解析状态。
 *
 * 解析是异步的（Java 落盘后在线程池里调 Python），前端拿不到推送，
 * 所以在上传后短时轮询。解析完成或失败就停，避免无意义的后台请求。
 */
function startPolling() {
  stopPolling()
  let rounds = 0
  pollTimer = setInterval(async () => {
    rounds += 1
    await loadDocuments()
    const pending = documents.value.some(
      (d) => d.parseStatus === '解析中' || d.parseStatus === '待解析',
    )
    if (!pending || rounds >= 60) {
      stopPolling()
      if (!pending) {
        const done = documents.value.filter((d) => d.parseStatus === '完成').length
        ElMessage.success(`解析结束，资料库已就绪 ${done} 份`)
      }
    }
  }, 5000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function reingest(row) {
  await kbApi.reingest(row.id)
  ElMessage.info('已重新提交解析')
  await loadDocuments()
  startPolling()
}

async function removeDocument(row) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.fileName}」会同时清理它的 ${row.chunkCount} 个切片与向量，确定吗？`,
      '删除确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await kbApi.remove(row.id)
  ElMessage.success('已删除')
  await loadDocuments()
}

// ---------------------------------------------------------------- 问答

const lastChunkCount = ref(0)

async function onSend() {
  const question = input.value.trim()
  if (!question || streaming.value) return

  messages.value.push({ key: `u-${Date.now()}`, role: 'user', content: question })
  input.value = ''
  scrollToBottom()

  const reply = {
    key: `a-${Date.now()}`,
    role: 'assistant',
    content: '',
    reasoning: '',
    citations: [],
  }
  messages.value.push(reply)
  streaming.value = true
  lastChunkCount.value = 0

  abortController = new AbortController()
  const isNewSession = !activeSessionId.value

  try {
    await chatStream(
      { sessionId: activeSessionId.value || undefined, question },
      {
        signal: abortController.signal,
        onReasoning: (text) => {
          reply.reasoning += text
        },
        onContent: (text) => {
          reply.content += text
          scrollToBottom()
        },
        onCitations: (list) => {
          reply.citations = list || []
          lastChunkCount.value = reply.citations.length
        },
        onError: (message) => {
          ElMessage.error(message)
        },
      },
    )
  } catch (e) {
    if (e.name !== 'AbortError') {
      // 错误提示已在 chatStream / onError 里给出
    }
    if (!reply.content) {
      // 没拿到任何内容时给一句兜底，避免出现空气泡
      reply.content = '_本次没有生成内容。可在「资料库」确认资料已解析完成后再试。_'
    }
  } finally {
    streaming.value = false
    abortController = null
    // 新会话需要刷新列表拿到后端生成的 sessionId
    if (isNewSession) {
      await loadSessions()
      if (sessions.value.length) {
        activeSessionId.value = sessions.value[0].id
      }
    }
    scrollToBottom()
  }
}

function stopStreaming() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  streaming.value = false
  ElMessage.info('已停止生成')
}

onMounted(async () => {
  await loadSessions()
  await loadDocuments()
})

onUnmounted(() => {
  stopPolling()
  if (abortController) {
    abortController.abort()
  }
})
</script>

<style scoped>
.chat-page {
  display: flex;
  height: calc(100vh - 56px);
}

.session-pane {
  display: flex;
  flex-direction: column;
  width: 240px;
  background-color: #fff;
  border-right: 1px solid #e4e7ed;
}

.session-head {
  padding: 12px;
  border-bottom: 1px solid #f0f0f0;
}

.new-btn {
  width: 100%;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: #606266;
  cursor: pointer;
}

.session-item:hover {
  background-color: #f5f7fa;
}

.session-item.active {
  background-color: #ecf5ff;
  color: #409eff;
}

.session-title {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.session-del {
  opacity: 0;
  color: #f56c6c;
  transition: opacity 0.15s;
}

.session-item:hover .session-del {
  opacity: 1;
}

.session-foot {
  padding: 10px;
  border-top: 1px solid #f0f0f0;
}

.message-pane {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  background-color: #fff;
}

.message-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid #f0f0f0;
}

.message-title {
  font-size: 14px;
  font-weight: 600;
}

.message-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.sub-tag {
  margin-left: 8px;
  color: #67c23a;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.message-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background-color: #fafbfc;
}

.prompt-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}

.prompt-tag {
  cursor: pointer;
}

.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.msg.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background-color: #ecf5ff;
  color: #409eff;
}

.msg.user .msg-avatar {
  background-color: #f0f9eb;
  color: #67c23a;
}

.msg-body {
  max-width: 78%;
  min-width: 0;
}

.msg.user .msg-body {
  text-align: right;
}

.msg-content {
  display: inline-block;
  padding: 10px 13px;
  background-color: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.75;
  text-align: left;
  word-break: break-word;
  white-space: pre-wrap;
}

.msg.user .msg-content {
  background-color: #ecf5ff;
  border-color: #d9ecff;
}

/* markdown 渲染后的样式（v-html 内容不受 scoped 影响，用 :deep） */
.markdown {
  white-space: normal;
}

.markdown :deep(p) {
  margin: 0 0 8px;
}

.markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown :deep(code) {
  padding: 1px 5px;
  background-color: #f5f7fa;
  border-radius: 3px;
  font-size: 13px;
}

.markdown :deep(pre) {
  padding: 10px;
  background-color: #f5f7fa;
  border-radius: 6px;
  overflow-x: auto;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  padding-left: 20px;
  margin: 6px 0;
}

.markdown :deep(table) {
  border-collapse: collapse;
}

.markdown :deep(th),
.markdown :deep(td) {
  border: 1px solid #ebeef5;
  padding: 4px 8px;
}

.reasoning {
  margin-bottom: 6px;
}

.reasoning-text {
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
  white-space: pre-wrap;
}

.citations {
  margin-top: 8px;
}

.citation-item {
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
}

.citation-item:last-child {
  border-bottom: none;
}

.citation-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.citation-name {
  font-weight: 500;
  color: #303133;
}

.citation-page,
.citation-score {
  color: #909399;
}

.citation-snippet {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
}

.typing {
  display: inline-flex;
  gap: 4px;
  padding: 12px 14px;
  background-color: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
}

.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #c0c4cc;
  animation: blink 1.2s infinite ease-in-out;
}

.typing span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.3;
  }
  40% {
    opacity: 1;
  }
}

.message-foot {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 12px 16px;
  border-top: 1px solid #f0f0f0;
}

.send-btn {
  height: 54px;
}

/* ---------------- 资料库抽屉 ---------------- */

.kb-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.kb-hint {
  flex: 1;
  font-size: 12px;
  color: #909399;
}

.kb-progress {
  margin-bottom: 12px;
}

.kb-alert {
  margin-bottom: 12px;
}

.kb-error-tip {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 12px;
  padding: 8px 10px;
  background-color: #fef0f0;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #f56c6c;
}
</style>
