/**
 * AI 问答的 SSE 客户端。
 *
 * 为什么不用原生 EventSource：
 *   EventSource 只支持 GET，而问答必须 POST（问题体可能很长）；
 *   所以用 fetch + ReadableStream 手动解析 SSE 帧。
 *
 * 为什么走 /api 而不是直连 Python 8000：
 *   权限校验统一在 Java 侧（JWT 只发一个入口），Python 只对内网暴露。
 *   Java 会把 SSE 流原样透传，并负责把消息落库。
 *
 * 约定的事件帧（第 3 周由 Python /rag/chat 产出，Java /chat/ask 透传）：
 *   event: reasoning   data: {"text": "..."}     思考过程增量
 *   event: message     data: {"text": "..."}     正式回答增量
 *   event: citations   data: [{...}]             引用溯源（一次性，放最后）
 *   event: error       data: {"message": "..."}  错误
 *   data: [DONE]                                 正常结束
 */

const API_BASE = '/api'

/** 解析一个 SSE 帧（可能含多行 data） */
function parseFrame(raw) {
  const lines = raw.split('\n')
  let event = 'message'
  const dataLines = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  return { event, data: dataLines.join('\n') }
}

/**
 * 发起一次流式问答。
 *
 * @param {object} payload            { sessionId, question }
 * @param {object} handlers
 * @param {(text:string)=>void} handlers.onReasoning  思考过程增量
 * @param {(text:string)=>void} handlers.onContent    正式回答增量
 * @param {(list:Array)=>void}  handlers.onCitations  引用来源
 * @param {(msg:string)=>void}  handlers.onError      错误提示
 * @param {AbortSignal}         handlers.signal       中断信号（用户点停止/离开页面）
 */
export async function chatStream(payload, handlers = {}) {
  const { onReasoning, onContent, onCitations, onError, signal } = handlers

  const token = localStorage.getItem('sa_access_token')
  const response = await fetch(`${API_BASE}/chat/ask`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(payload),
    signal,
  })

  if (response.status === 401) {
    throw new Error('未登录或登录已过期')
  }
  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(`问答接口返回 HTTP ${response.status}：${text.slice(0, 200)}`)
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应（response.body 为空）')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // SSE 帧以空行分隔；\r\n\r\n 与 \n\n 都要兼容
      let separatorIndex = buffer.search(/\r?\n\r?\n/)
      while (separatorIndex !== -1) {
        const rawFrame = buffer.slice(0, separatorIndex)
        buffer = buffer.slice(separatorIndex).replace(/^\r?\n\r?\n/, '')

        const frame = parseFrame(rawFrame)
        if (frame.data === '[DONE]') {
          return
        }
        if (frame.data) {
          dispatch(frame, { onReasoning, onContent, onCitations, onError })
        }
        separatorIndex = buffer.search(/\r?\n\r?\n/)
      }
    }
  } finally {
    reader.releaseLock?.()
  }
}

function dispatch(frame, { onReasoning, onContent, onCitations, onError }) {
  let parsed
  try {
    parsed = JSON.parse(frame.data)
  } catch {
    // 不是 JSON 就按纯文本处理，便于后端早期联调
    parsed = { text: frame.data }
  }

  switch (frame.event) {
    case 'reasoning':
      onReasoning?.(parsed.text || '')
      break
    case 'message':
    case 'content':
      onContent?.(parsed.text || '')
      break
    case 'citations':
      onCitations?.(Array.isArray(parsed) ? parsed : parsed.citations || [])
      break
    case 'error':
      onError?.(parsed.message || '模型服务异常')
      break
    default:
      if (parsed.text) onContent?.(parsed.text)
  }
}
