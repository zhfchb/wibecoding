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
    const status = error.response?.status
    // 登录/注册请求本身的 401 是"账号或密码错误"，交给页面弹窗提示，
    // 不做跳转刷新（否则弹窗刚弹出就被页面刷新冲掉）
    const isAuthRequest = error.config?.url?.includes('/auth/login') || error.config?.url?.includes('/auth/register')
    if (status === 401 && !isAuthRequest) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)

export default api

