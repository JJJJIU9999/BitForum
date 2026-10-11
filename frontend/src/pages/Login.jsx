import { useState } from 'react'
import { Link, useNavigate, useOutletContext, useSearchParams } from 'react-router'
import { loginUser } from '../api/userApi.js'
import { getCurrentUser, saveAuth } from '../api/request.js'
import MessageBanner from '../components/MessageBanner.jsx'

function Login() {
  const { setCurrentUser } = useOutletContext()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [form, setForm] = useState({ username: '', password: '' })
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState('info')
  const [loading, setLoading] = useState(false)

  function updateField(event) {
    const { name, value } = event.target
    setForm((current) => ({ ...current, [name]: value }))
  }

  async function handleSubmit(event) {
    event.preventDefault()
    setMessage('')
    setLoading(true)

    try {
      const result = await loginUser(form)
      saveAuth(result.data)
      setCurrentUser(getCurrentUser())
      const redirectTo = searchParams.get('redirectTo')
      const destination = redirectTo?.startsWith('/') && !redirectTo.startsWith('//') ? redirectTo : '/'
      navigate(destination, { replace: true })
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
    } finally {
      setLoading(false)
    }
  }

  return (
    <form className="auth-form" onSubmit={handleSubmit}>
      <div className="form-heading">
        <p className="eyebrow">欢迎回来</p>
        <h1>登录 Bit Forum</h1>
        <p>登录后即可发布文章、点赞、收藏和评论。</p>
      </div>

      <label>
        用户名
        <input
          name="username"
          onChange={updateField}
          placeholder="请输入用户名"
          type="text"
          autoComplete="username"
          required
          value={form.username}
        />
      </label>

      <label>
        密码
        <input
          name="password"
          onChange={updateField}
          placeholder="请输入密码"
          type="password"
          autoComplete="current-password"
          required
          value={form.password}
        />
      </label>

      <button className="primary-button" disabled={loading} type="submit">
        {loading ? '登录中...' : '登录'}
      </button>

      <MessageBanner message={message} type={messageType} />
      <p className="auth-switch">还没有账号？<Link to="/register">创建账号</Link></p>
    </form>
  )
}

export default Login
