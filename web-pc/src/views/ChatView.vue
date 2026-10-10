<template>
  <div class="chat-page">
    <!-- 左侧会话列表 -->
    <aside class="session-pane">
      <div class="session-head">
        <el-button type="primary" :icon="'Plus'" class="new-btn" @click="newSession">
          新建会话
        </el-button>
      </div>

      <div class="session-list">
        <div
          v-for="item in sessions"
          :key="item.id"
          class="session-item"
          :class="{ active: item.id === activeSessionId }"
          @click="switchSession(item)"
        >
          <el-icon><ChatLineRound /></el-icon>
          <span class="session-title">{{ item.title }}</span>
        </div>
        <el-empty v-if="!sessions.length" description="还没有会话" :image-size="60" />
      </div>

      <div class="session-foot">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="第 3 周接入 RAG"
          description="上传资料后可基于资料问答，答案底部会给出引用来源（文档名 + 页码）。"
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
          </div>
        </div>
        <div>
          <el-tooltip content="第 3 周接入知识库上传" placement="bottom">
            <el-button :icon="'Upload'" disabled>上传资料</el-button>
          </el-tooltip>
        </div>
      </header>

      <div ref="scrollRef" class="message-body">
        <el-empty
          v-if="!messages.length"
          description="问点什么吧，比如「帮我梳理一下考研英语二的复习重点」"
        >
          <div class="prompt-list">
            <el-tag
              v-for="p in prompts"
              :key="p"
              class="prompt-tag"
              @click="input = p"
            >
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
              <el-collapse-item title="思考过程" name="r">
                <div class="reasoning-text">{{ msg.reasoning }}</div>
              </el-collapse-item>
            </el-collapse>

            <div v-if="msg.role === 'assistant'" class="msg-content markdown" v-html="render(msg.content)"></div>
            <div v-else class="msg-content">{{ msg.content }}</div>

            <!-- 引用溯源 -->
            <div v-if="msg.citations?.length" class="citations">
              <el-collapse>
                <el-collapse-item :title="`引用来源（${msg.citations.length}）`" name="c">
                  <div v-for="(c, i) in msg.citations" :key="i" class="citation-item">
                    <div class="citation-head">
                      <el-tag size="small" type="success">{{ i + 1 }}</el-tag>
                      <span class="citation-name">{{ c.documentName }}</span>
                      <span v-if="c.pageNo" class="citation-page">第 {{ c.pageNo }} 页</span>
                      <span v-if="c.score" class="citation-score">相关度 {{ c.score }}</span>
                    </div>
                    <div class="citation-snippet">{{ c.snippet }}</div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>
        </div>

        <div v-if="streaming" class="msg assistant">
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
          placeholder="输入问题，Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact.prevent="onSend"
        />
        <el-button
          type="primary"
          :icon="'Promotion'"
          :loading="streaming"
          :disabled="!input.trim()"
          class="send-btn"
          @click="onSend"
        >
          发送
        </el-button>
      </footer>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { chatStream } from '@/api/chat'

defineOptions({ name: 'Chat' })

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

const prompts = [
  '帮我梳理考研英语二的复习重点',
  '数学定积分有哪些常见题型',
  '政治马原怎么背效率最高',
]

const sessions = ref([])
const activeSessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const scrollRef = ref()

let abortController = null

const activeSession = computed(
  () => sessions.value.find((item) => item.id === activeSessionId.value) || null,
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

function newSession() {
  // 会话 id 先本地生成（负数），第 3 周由后端 /chat/session 返回真实 id
  const id = -Date.now()
  sessions.value.unshift({ id, title: '新的会话' })
  activeSessionId.value = id
  messages.value = []
}

function switchSession(item) {
  activeSessionId.value = item.id
  messages.value = []
}

async function onSend() {
  const question = input.value.trim()
  if (!question || streaming.value) return

  if (!activeSessionId.value) newSession()

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

  abortController = new AbortController()
  try {
    await chatStream(
      { sessionId: activeSessionId.value, question },
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
        },
        onError: (message) => {
          ElMessage.error(message)
        },
      },
    )
  } catch (e) {
    if (e.name !== 'AbortError') {
      // 拦截器/onError 已经提示过
    }
  } finally {
    streaming.value = false
    abortController = null
    // 用首问前 20 字作为会话标题
    const session = activeSession.value
    if (session && session.title === '新的会话') {
      session.title = question.slice(0, 20)
    }
    scrollToBottom()
  }
}

onMounted(() => {
  newSession()
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
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
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
  height: 56px;
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
</style>
