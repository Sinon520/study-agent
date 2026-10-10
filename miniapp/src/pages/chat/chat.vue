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

    <!-- 版本标记：用于确认开发者工具加载的是不是最新构建（v2 = 分块接收 + 整包兜底） -->
    <view class="build-tag">v2 · 分块接收</view>
  </view>
</template>

<script>
import { getToken } from '@/utils/request'

/**
 * 手写 UTF-8 解码：部分小程序运行环境没有 TextDecoder，
 * 而分块接收拿到的都是 ArrayBuffer，没有解码器就全是乱码/丢失。
 * 只处理完整序列，非法字节替换为 U+FFFD。
 */
function utf8Decode(bytes) {
  let out = ''
  let i = 0
  const len = bytes.length
  while (i < len) {
    const b = bytes[i]
    if (b < 0x80) {
      out += String.fromCharCode(b)
      i += 1
    } else if (b >= 0xc0 && b < 0xe0 && i + 1 < len) {
      out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f))
      i += 2
    } else if (b >= 0xe0 && b < 0xf0 && i + 2 < len) {
      out += String.fromCharCode(
        ((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f)
      )
      i += 3
    } else if (b >= 0xf0 && i + 3 < len) {
      const cp =
        ((b & 0x07) << 18) |
        ((bytes[i + 1] & 0x3f) << 12) |
        ((bytes[i + 2] & 0x3f) << 6) |
        (bytes[i + 3] & 0x3f)
      const u = cp - 0x10000
      out += String.fromCharCode(0xd800 + (u >> 10), 0xdc00 + (u & 0x3ff))
      i += 4
    } else {
      out += '\uFFFD'
      i += 1
    }
  }
  return out
}

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
     * 三个实测出来的关键点（都踩过）：
     * 1. **uni.request 不支持 enableChunked** —— @dcloudio 框架包里根本没有这个参数
     *    （全量 grep 验证过：传进去会被忽略，返回的 task 上也没有 onChunkReceived），
     *    分块数据永远收不到、还不报错 → 答案静默丢失。所以 MP-WEIXIN 下必须
     *    用原生 `wx.request` 才能开启分块接收。
     * 2. **必须有"整包兜底"**：即使分块不可用（老基础库 / 其它端），
     *    success 回调里也要能拿完整响应体走同一套帧解析，退化为一次性显示 ——
     *    否则就会出现"发了问题但气泡里什么都没有"。
     * 3. TextDecoder 在部分小程序环境不存在，准备了手写 UTF-8 解码兜底。
     */
    async onSend() {
      const question = this.input.trim()
      if (!question || this.streaming) return

      this.messages.push({ role: 'user', content: question, reasoning: '', citations: [] })
      this.input = ''
      this.scrollToBottom()

      const reply = { role: 'assistant', content: '', reasoning: '', citations: [], error: '' }
      this.messages.push(reply)
      this.streaming = true

      const token = getToken()
      const url = 'http://127.0.0.1:8080/chat/ask'
      const header = {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      }

      // 分块解码：优先 TextDecoder 流式解码；没有它就攒原始字节、结束时整体解码
      const hasTextDecoder = typeof TextDecoder !== 'undefined'
      const decoder = hasTextDecoder ? new TextDecoder('utf-8') : null
      let buffer = ''
      const rawChunks = []

      const consumeFrames = () => {
        let index = buffer.indexOf('\n\n')
        while (index !== -1) {
          const frame = buffer.slice(0, index)
          buffer = buffer.slice(index + 2)
          this.handleFrame(frame, reply)
          index = buffer.indexOf('\n\n')
        }
      }

      const onChunk = (chunkData) => {
        if (!chunkData) return
        try {
          const bytes = new Uint8Array(chunkData)
          if (decoder) {
            buffer += decoder.decode(bytes, { stream: true })
            consumeFrames()
          } else {
            rawChunks.push(bytes)
          }
        } catch (e) {
          // 单个分片解码异常不能中断整条流（宁可少一片，不能整条丢）
        }
        this.scrollToBottom()
      }

      // 请求收尾（成功/失败都走这里）：处理分块残留；分块通道没拿到东西就用整包兜底
      const settle = (fallbackText) => {
        if (decoder) {
          buffer += decoder.decode()
          consumeFrames()
        }
        if (!reply.content && !reply.reasoning && !reply.error) {
          const text = fallbackText || (rawChunks.length ? this.decodeBytes(rawChunks) : '')
          if (text && text.trim()) {
            this.parseSseText(text, reply)
          }
        }
        if (!reply.content && !reply.reasoning && !reply.error) {
          reply.content = '没有收到回答：请确认已登录、开发者工具勾选了「不校验合法域名」，且后端 8080 正在运行。'
        }
        this.streaming = false
        this.scrollToBottom()
      }

      const onFail = (err) => {
        const msg = (err && err.errMsg) || '网络错误'
        reply.error = msg
        reply.content = /domain|url not in/i.test(msg)
          ? '请求被域名校验拦截：请到开发者工具「详情 → 本地设置」勾选「不校验合法域名…」后重试。'
          : `请求失败：${msg}（请确认后端 8080 正在运行）`
        settle('')
      }

      // #ifdef MP-WEIXIN
      const task = wx.request({
        url,
        method: 'POST',
        enableChunked: true, // 微信原生能力：分块接收（uni.request 不透传，必须原生调）
        timeout: 120000, // 首答可能触发本地向量模型冷启动（实测曾到 82 秒）
        header,
        data: { question },
        success: (res) => settle(this.decodeAny(res.data)),
        fail: onFail,
      })
      if (task && typeof task.onChunkReceived === 'function') {
        task.onChunkReceived((res) => onChunk(res.data))
      }
      // #endif

      // #ifndef MP-WEIXIN
      uni.request({
        url,
        method: 'POST',
        timeout: 120000,
        header,
        data: { question },
        success: (res) => settle(this.decodeAny(res.data)),
        fail: onFail,
      })
      // #endif
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

    /**
     * 整包兜底解析：分块通道没拿到数据时，用完整响应体走同一套帧解析。
     * 内容不是 SSE（没有 event:/data: 行）时按 JSON 错误体处理 ——
     * Java 在开流前返回的 401/500 就是这种形态，直接展示 msg 比静默强。
     */
    parseSseText(text, reply) {
      const body = (text || '').trim()
      if (!body) return
      if (body.indexOf('event:') === -1 && body.indexOf('data:') === -1) {
        try {
          const obj = JSON.parse(body)
          reply.content = `请求失败：${obj.msg || obj.message || '未知错误'}`
        } catch (e) {
          reply.content = `请求失败：${body.slice(0, 200)}`
        }
        return
      }
      body.split('\n\n').forEach((frame) => {
        if (frame.trim()) this.handleFrame(frame, reply)
      })
    },

    /** 合并若干 Uint8Array 后整体 UTF-8 解码（无 TextDecoder 环境的兜底） */
    decodeBytes(chunks) {
      let total = 0
      chunks.forEach((c) => {
        total += c.length
      })
      const merged = new Uint8Array(total)
      let offset = 0
      chunks.forEach((c) => {
        merged.set(c, offset)
        offset += c.length
      })
      return utf8Decode(merged)
    },

    decodeAny(data) {
      if (typeof data === 'string') return data
      if (data instanceof ArrayBuffer) return utf8Decode(new Uint8Array(data))
      if (data && data.buffer instanceof ArrayBuffer) return utf8Decode(new Uint8Array(data.buffer))
      return ''
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

.build-tag {
  padding: 4rpx 0 10rpx;
  text-align: center;
  font-size: 20rpx;
  color: #c0c4cc;
  background-color: #fff;
}
</style>
