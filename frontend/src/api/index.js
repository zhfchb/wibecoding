import axios from 'axios'

// Axios 实例：统一 baseURL + JWT 拦截器
const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

// 请求拦截器：自动带 Authorization header
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：401 跳登录
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)

export default api

// ======== 认证 API ========
export const authApi = {
  login: (username, password) =>
    api.post('/auth/login', { username, password }),
  register: (username, password) =>
    api.post('/auth/register', { username, password }),
  me: () => api.get('/auth/me'),
}

// ======== 聊天 API ========
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
}

// SSE 流式对话：用 fetch + ReadableStream 逐 token 读取
export async function streamChat(message, sessionId, onToken, onDone, onError) {
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

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 格式：data: xxx\n\n
      const lines = buffer.split('\n')
      buffer = lines.pop() // 保留最后不完整的行
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (data) onToken(data)
        }
      }
    }
    onDone()
  } catch (err) {
    onError(err)
  }
}

// ======== 多 Agent 任务 API ========
export const agentApi = {
  submit: (question) =>
    api.post('/agent/multi-agent', { question }),
  getTask: (taskId) =>
    api.get(`/agent/tasks/${taskId}`),
  listPending: () =>
    api.get('/agent/tasks/pending'),
  approve: (taskId) =>
    api.post(`/agent/tasks/${taskId}/approve`),
  reject: (taskId, feedback) =>
    api.post(`/agent/tasks/${taskId}/reject`, { feedback }),
}

// ======== 知识库 API ========
export const knowledgeApi = {
  upload: (file) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post('/knowledge/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  listDocuments: (page = 1, size = 10) =>
    api.get('/knowledge/documents', { params: { page, size } }),
}
