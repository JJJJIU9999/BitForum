import axios from 'axios'

// localStorage 的 key 集中定义，避免不同页面写出不一致的字符串。
const TOKEN_KEY = 'bit_forum_token'
const USER_KEY = 'bit_forum_user'

// 统一的 axios 实例。开发环境下 /api 会被 Vite 代理到 http://localhost:8080。
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
})

// 读取当前 token。请求拦截器和页面都可以复用这个函数。
export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

// 登录成功后保存认证信息；当前项目先用 localStorage 做演示。
export function saveAuth(loginData) {
  localStorage.setItem(TOKEN_KEY, loginData.token)
  localStorage.setItem(
    USER_KEY,
    JSON.stringify({
      userId: loginData.userId,
      username: loginData.username,
      role: loginData.role,
    }),
  )
}

// 退出登录时清理本地认证信息。
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

// 页面刷新后，用这个函数恢复当前用户展示状态。
export function getCurrentUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null

  try {
    return JSON.parse(raw)
  } catch {
    clearAuth()
    return null
  }
}

// 请求拦截器：每次发请求前，如果本地有 token，就自动补 Authorization。
request.interceptors.request.use((config) => {
  const token = getToken()

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

// 响应拦截器：把后端统一 Result 包装在这里处理，页面只关心成功数据或错误信息。
request.interceptors.response.use(
  (response) => {
    const body = response.data

    if (body && body.code !== undefined && body.code !== 200) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }

    return body
  },
  (error) => {
    const isMultipartRequest =
      typeof FormData !== 'undefined' && error.config?.data instanceof FormData

    if (error.message === 'Network Error' && isMultipartRequest) {
      return Promise.reject(new Error('上传请求被中断，请检查图片大小：头像不超过 2MB，文章封面不超过 5MB。'))
    }

    // 后端没启动、代理连不上时，axios 通常只给 Network Error。
    if (error.message === 'Network Error') {
      return Promise.reject(new Error('无法连接后端服务，请确认 Spring Boot 已在 8080 端口启动。'))
    }

    const message =
      error.response?.data?.message || error.message || '网络请求失败'

    return Promise.reject(new Error(message))
  },
)

export default request
