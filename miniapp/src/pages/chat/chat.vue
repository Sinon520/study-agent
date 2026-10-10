<template>
  <view class="chat-page">
    <scroll-view scroll-y class="msg-scroll" :scroll-top="scrollTop" :scroll-with-animation="true">
      <view v-if="!messages.length" class="welcome">
        <view class="welcome-icon">💬</view>
        <view class="welcome-title">基于你的资料回答</view>
        <view class="welcome-sub">
          第 3 周接入 RAG 后，这里可以针对你上传的考研资料提问，
          回答底部会给出引用来源（文档名 + 页码）。
        </view>
      </view>

      <view v-for="(msg, index) in messages" :key="index" class="msg" :class="msg.role">
        <view class="bubble">
          <view v-if="msg.reasoning" class="reasoning">
            <text class="reasoning-label">思考过程</text>
            <text class="reasoning-text">{{ msg.reasoning }}</text>
          </view>
          <text class="msg-text">{{ msg.content || (streaming ? '思考中…' : '') }}</text>

          <view v-if="msg.citations && msg.citations.length" class="citations">
            <text class="citation-title">引用来源（{{ msg.citations.length }}）</text>
            <view v-for="(c, i) in msg.citations" :key="i" class="citation">
              <text class="citation-name">{{ i + 1 }}. {{ c.documentName }}</text>
              <text v-if="c.pageNo" class="sa-muted">第 {{ c.pageNo }} 页</text>
            </view>
          </view>
        </view>
      </view>

      <view class="scroll-holder"></view>
    </scroll-view>

    <view class="input-bar">
      <textarea
        v-model="input"
        class="input-box"
        :disabled="streaming"
        auto-height
        placeholder="输入问题…"
        placeholder-style="color:#a8abb2"
      />
      <view class="send-btn" :class="{ disabled: streaming }" @tap="onSend">
        {{ streaming ? '生成中' : '发送' }}
      </view>
    </view>
  </view>
</template>

<script>
import { getToken } from '@/utils/request'

export default {
  data() {
    return {
      input: '',
      messages: [],
      streaming: false,
      scrollTop: 0,
    }
  },
  methods: {
    /**
     * 流式问答。
     *
     * 小程序端没有 fetch/ReadableStream，uni.request 也不支持流式读取，
     * 所以第 3 周这里要换成：wx.request 的 enableChunked: true + onChunkReceived，
     * 或者退化为"一次性返回"（降级预案里小程序问答页可以被砍）。
     * 现在先把交互与数据结构做出来，接后端时只改这一个方法。
     */
    async onSend() {
      const question = this.input.trim()
      if (!question || this.streaming) return

      this.messages.push({ role: 'user', content: question, reasoning: '', citations: [] })
      this.input = ''
      this.scrollToBottom()

      const reply = { role: 'assistant', content: '', reasoning: '', citations: [] }
      this.messages.push(reply)
      this.streaming = true

      try {
        const token = getToken()
        // 小程序端请求：enableChunked 打开后可以在 onChunkReceived 里增量读取 SSE
        const task = uni.request({
          url: 'http://127.0.0.1:8080/chat/ask',
          method: 'POST',
          enableChunked: true,
          header: {
            'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          data: { question },
          success: () => {
            this.streaming = false
          },
          fail: () => {
            this.streaming = false
            uni.showToast({ title: '问答服务未就绪', icon: 'none' })
            reply.content = '第 3 周接入 RAG 问答后，这里会显示基于你资料的回答。'
          },
        })

        if (task && task.onChunkReceived) {
          const decoder = new TextDecoder('utf-8')
          let buffer = ''
          task.onChunkReceived((res) => {
            buffer += decoder.decode(new Uint8Array(res.data), { stream: true })
            let index = buffer.indexOf('\n\n')
            while (index !== -1) {
              const frame = buffer.slice(0, index)
              buffer = buffer.slice(index + 2)
              this.handleFrame(frame, reply)
              index = buffer.indexOf('\n\n')
            }
          })
        }
      } catch (e) {
        this.streaming = false
      } finally {
        // 兜底：15 秒后强制结束 loading，避免请求悬挂后按钮一直禁用
        setTimeout(() => {
          this.streaming = false
        }, 15000)
      }
    },

    handleFrame(frame, reply) {
      const lines = frame.split('\n')
      let event = 'message'
      const dataLines = []
      lines.forEach((line) => {
        if (line.indexOf('event:') === 0) event = line.slice(6).trim()
        if (line.indexOf('data:') === 0) dataLines.push(line.slice(5).trim())
      })
      const data = dataLines.join('\n')
      if (!data || data === '[DONE]') return

      let parsed
      try {
        parsed = JSON.parse(data)
      } catch (e) {
        parsed = { text: data }
      }

      if (event === 'reasoning') {
        reply.reasoning += parsed.text || ''
      } else if (event === 'citations') {
        reply.citations = Array.isArray(parsed) ? parsed : parsed.citations || []
      } else {
        reply.content += parsed.text || ''
      }
      this.scrollToBottom()
    },

    scrollToBottom() {
      this.$nextTick(() => {
        this.scrollTop = 999999
      })
    },
  },
}
</script>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #f5f7fa;
}

.msg-scroll {
  flex: 1;
  height: 0;
  padding: 24rpx;
  box-sizing: border-box;
}

.welcome {
  padding: 120rpx 60rpx;
  text-align: center;
}

.welcome-icon {
  font-size: 90rpx;
}

.welcome-title {
  margin-top: 20rpx;
  font-size: 32rpx;
  font-weight: 600;
}

.welcome-sub {
  margin-top: 16rpx;
  font-size: 25rpx;
  line-height: 1.8;
  color: #909399;
}

.msg {
  display: flex;
  margin-bottom: 24rpx;
}

.msg.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 20rpx 24rpx;
  background-color: #fff;
  border-radius: 16rpx;
}

.msg.user .bubble {
  background-color: #ecf5ff;
}

.msg-text {
  font-size: 28rpx;
  line-height: 1.75;
  word-break: break-all;
}

.reasoning {
  margin-bottom: 14rpx;
  padding: 12rpx 16rpx;
  background-color: #f5f7fa;
  border-radius: 10rpx;
}

.reasoning-label {
  display: block;
  font-size: 22rpx;
  color: #909399;
  margin-bottom: 6rpx;
}

.reasoning-text {
  font-size: 24rpx;
  line-height: 1.7;
  color: #909399;
}

.citations {
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #ebeef5;
}

.citation-title {
  display: block;
  font-size: 23rpx;
  color: #909399;
  margin-bottom: 10rpx;
}

.citation {
  padding: 10rpx 0;
}

.citation-name {
  display: block;
  font-size: 25rpx;
  color: #303133;
}

.input-bar {
  display: flex;
  align-items: flex-end;
  padding: 16rpx 20rpx;
  background-color: #fff;
  border-top: 1rpx solid #ebeef5;
}

.input-box {
  flex: 1;
  max-height: 200rpx;
  min-height: 72rpx;
  padding: 16rpx 20rpx;
  background-color: #f5f7fa;
  border-radius: 12rpx;
  font-size: 27rpx;
  box-sizing: border-box;
}

.send-btn {
  width: 140rpx;
  height: 72rpx;
  margin-left: 16rpx;
  line-height: 72rpx;
  text-align: center;
  font-size: 27rpx;
  color: #fff;
  background-color: #409eff;
  border-radius: 12rpx;
}

.send-btn.disabled {
  opacity: 0.6;
}

.scroll-holder {
  height: 20rpx;
}
</style>
