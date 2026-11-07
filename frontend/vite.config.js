import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Vite 开发服务器配置：proxy 代理后端 API，避免跨域
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 所有 /api 请求代理到后端 8080
      '/api': {
        target: process.env.API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
