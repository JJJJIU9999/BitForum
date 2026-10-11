import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  // 启用 React 插件，让 Vite 能处理 JSX 和开发热更新。
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 开发环境代理：前端请求 /api/... 时，转发到 Spring Boot 后端。
      // 这样页面代码只需要写 /api，不需要硬编码 http://localhost:8080。
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
