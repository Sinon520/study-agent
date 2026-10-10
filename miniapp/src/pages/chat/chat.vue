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

    <!-- 版本/通道标记：v3 = 流式解码器 + 分块计数；用于确认构建版本与本次实际通道 -->
    <view class="build-tag">v3 · {{ recvInfo || '就绪' }}</view>
  </view>
</template>

<script>
import { getToken, BASE_URL } from '@/utils/request'

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

/**
 * 跨分片的流式 UTF-8 解码器（真机部分 iOS/安卓环境没有 TextDecoder，但流式体验不能丢）。
 *
 * 难点：一个汉字占 3 个字节，很可能被切在两个分片的边界上 ——
 * 每片单独解码会把边界处的汉字解成乱码。做法：解码前先扫描尾部，
 * 把"不完整的 UTF-8 序列"留到下一片拼接后再解，完整部分立即输出。
 */
function createStreamingUtf8Decoder() {
  let pending = new Uint8Array(0)
  return {
    decode(chunk) {
      const chunkBytes = chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk)
      let bytes = chunkBytes
      if (pending.length) {
        bytes = new Uint8Array(pending.length + chunkBytes.length)
        bytes.set(pending)
        bytes.set(chunkBytes, pending.length)
        pending = new Uint8Array(0)
      }
      // 从尾部回看：跳过续字节（10xxxxxx），找到最后一个序列的起始字节，
      // 若序列不完整（剩余字节不够）就把尾巴留给下一片
      let end = bytes.length
      let i = bytes.length - 1
      let continuation = 0
      while (i >= 0 && (bytes[i] & 0xc0) === 0x80 && continuation < 3) {
        i -= 1
        continuation += 1
      }
      if (i >= 0) {
        const b = bytes[i]
        let need = 1
        if (b >= 0xf0) {
          need = 4
        } else if (b >= 0xe0) {
          need = 3
        } else if (b >= 0xc0) {
          need = 2
        }
        if (bytes.length - i < need) {
          end = i
        }
      }
      pending = bytes.slice(end)
      return utf8Decode(bytes.slice(0, end))
    },
    flush() {
      const rest = pending
      pending = new Uint8Array(0)
      return rest.length ? utf8Decode(rest) : ''
    },
  }
}

export default {
  data() {
    return {
      input: '',
      messages: [],
      streaming: false,
      scrollTop: 0,
      recvInfo: '',
    }
  },
  methods: {
    /**
     * 流式问答。
     *
     * 四个实测出来的关键点（都踩过）：
     * 1. **uni.request 不支持 enableChunked** —— @dcloudio 框架包里根本没有这个参数
     *    （全量 grep 验证过：传进去会被忽略，返回的 task 上也没有 onChunkReceived），
     *    分块数据永远收不到、还不报错 → 答案静默丢失。所以 MP-WEIXIN 下必须
     *    用原生 `wx.request` 才能开启分块接收。
     * 2. **必须有"整包兜底"**：即使分块不可用（老基础库 / 其它端），
     *    success 回调里也要能拿完整响应体走同一套帧解析，退化为一次性显示 ——
     *    否则就会出现"发了问题但气泡里什么都没有"。
     * 3. **TextDecoder 在部分真机环境不存在**（iOS/安卓实测缺失），
     *    准备了跨分片的流式 UTF-8 解码器（汉字被切在分片边界也不会乱码）。
     * 4. **开发者工具模拟器会把分块缓冲到最后一次性回调**（微信平台已知行为，
     *    见官方社区"onChunkReceived 真机和开发者工具触发次数不一样"），
     *    所以"模拟器里一次性显示"属正常；真机（真机调试）才会逐块到达。
     *    页面底部的「分块×N / 整包」标记用于确认本次实际走通的通道。
     */
    async onSend() {
      const question = this.input.trim()
      if (!question || this.streaming) return

      this.messages.push({ role: 'user', content: question, reasoning: '', citations: [] })
      this.input = ''
      this.scrollToBottom()

      const reply = { role: 'assistant', content: '', reasoning: '', citations: [], error: '' }
      this.messages.push(reply)
      // ⚠️ Vue3 响应式的关键细节：push 进去的**原始对象**直接改不会触发视图更新，
      // 必须拿数组里返回的「响应式代理」来改。之前"先显示'思考中…'、结束后整段出现"
      // 的根因就在这里 —— 内容其实一直在累积，但视图自始至终没重绘，
      // 直到最后 streaming 翻转才触发唯一一次渲染，把全文一次性刷出来。
      const replyRef = this.messages[this.messages.length - 1]
      this.streaming = true

      const token = getToken()
      // 统一用 utils/request.js 的 BASE_URL（局域网 IP）：
      // 真机上 127.0.0.1 是手机自己的回环地址，连不上电脑
      const url = `${BASE_URL}/chat/ask`
      const header = {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      }

      // 分块解码：优先 TextDecoder 流式解码；真机（部分 iOS/安卓）没有它，
      // 退回自写的"跨分片 UTF-8 解码器"—— 保证真机上也是逐字流式，
      // 而不是攒到最后一次性显示
      let decodeChunk
      let flushDecoder
      if (typeof TextDecoder !== 'undefined') {
        const textDecoder = new TextDecoder('utf-8')
        decodeChunk = (bytes) => textDecoder.decode(bytes, { stream: true })
        flushDecoder = () => textDecoder.decode()
      } else {
        const manualDecoder = createStreamingUtf8Decoder()
        decodeChunk = (bytes) => manualDecoder.decode(bytes)
        flushDecoder = () => manualDecoder.flush()
      }
      let buffer = ''
      let chunkCount = 0

      const consumeFrames = () => {
        let index = buffer.indexOf('\n\n')
        while (index !== -1) {
          const frame = buffer.slice(0, index)
          buffer = buffer.slice(index + 2)
          this.handleFrame(frame, replyRef)
          index = buffer.indexOf('\n\n')
        }
      }

      const onChunk = (chunkData) => {
        if (!chunkData) return
        chunkCount += 1
        try {
          buffer += decodeChunk(new Uint8Array(chunkData))
          consumeFrames()
        } catch (e) {
          // 单个分片解码异常不能中断整条流（宁可少一片，不能整条丢）
        }
        this.scrollToBottom()
      }

      // 请求收尾（成功/失败都走这里）：先冲掉解码器残留；分块通道没拿到东西就用整包兜底
      const settle = (fallbackText) => {
        try {
          buffer += flushDecoder()
        } catch (e) {
          // 忽略：残留解码失败不影响已渲染内容
        }
        consumeFrames()
        // 记录本次实际走通的通道，显示在页面底部供验证：
        // 分块×N = onChunkReceived 生效；"整包" = 走了 success 一次性兜底
        this.recvInfo = chunkCount > 0 ? `分块×${chunkCount}` : '整包'
        if (!replyRef.content && !replyRef.reasoning && !replyRef.error) {
          const text = fallbackText || ''
          if (text && text.trim()) {
            this.parseSseText(text, replyRef)
          }
        }
        if (!replyRef.content && !replyRef.reasoning && !replyRef.error) {
          replyRef.content = '没有收到回答：请确认已登录、开发者工具勾选了「不校验合法域名」，且后端 8080 正在运行。'
        }
        this.streaming = false
        this.scrollToBottom()
      }

      const onFail = (err) => {
        const msg = (err && err.errMsg) || '网络错误'
        replyRef.error = msg
        replyRef.content = /domain|url not in/i.test(msg)
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

    decodeAny(data) {
      if (typeof data === 'string') return data
      if (data instanceof ArrayBuffer) return utf8Decode(new Uint8Array(data))
      if (data && data.buffer instanceof ArrayBuffer) return utf8Decode(new Uint8Array(data.buffer))
      return ''
    },

    scrollToBottom() {
      this.$nextTick(() => {
        // 交替两个超大值：scroll-top 数值不变时组件不会重新滚动（同值更新也会被跳过）
        this.scrollTop = this.scrollTop === 999999 ? 999998 : 999999
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
