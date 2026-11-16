import api from '../../shared/api/client.js'
export const chatApi = {
  // RAG 对话（非流式）
  ragChat: (message, sessionId) =>
    api.post('/chat', { message, sessionId }),
  // 简单对话（无 RAG）
  simpleChat: (message) =>
    api.post('/chat/simple', { message }),
  // 对话历史
  history: (sessionId) =>
    api.get(`/chat/history/${sessionId}`),
  // 会话列表（每个会话最后一条消息）
  sessions: () =>
    api.get('/chat/sessions'),
  updateSession: (sessionId, data) => api.patch(`/chat/sessions/${encodeURIComponent(sessionId)}`, data),
  // 删除单个会话
  deleteSession: (sessionId) =>
    api.delete(`/chat/sessions/${sessionId}`),
}

// SSE 流式对话：用 fetch + ReadableStream 逐 token 读取
export async function streamChat(message, sessionId, onToken, onDone, onError, onSources = () => {}) {
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: token ? `Bearer ${token}` : '',
      },
      body: JSON.stringify({ message, sessionId }),
    })
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    let eventName = ''
    let eventData = []
    const dispatch = () => {
      if (eventData.length) {
        const data = eventData.join('\n')
        if (eventName === 'sources') onSources(JSON.parse(data))
        else onToken(data)
      }
      eventName = ''; eventData = []
    }
    const consume = (text) => {
      buffer += text
      const lines = buffer.split('\n')
      buffer = lines.pop()
      for (const raw of lines) {
        const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw
        if (!line) dispatch()
        else if (line.startsWith('event:')) eventName = line.slice(6).trim()
        else if (line.startsWith('data:')) eventData.push(line.slice(5).replace(/^ /, ''))
      }
    }
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      consume(decoder.decode(value, { stream: true }))
    }
    consume(decoder.decode() + '\n\n')
    onDone()
  } catch (err) {
    onError(err)
  }
}

