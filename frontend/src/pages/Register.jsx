import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { registerUser } from '../api/userApi.js'
import MessageBanner from '../components/MessageBanner.jsx'

function Register() {
  const navigate = useNavigate()
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
      await registerUser(form)
      setForm({ username: '', password: '' })
      navigate('/login', { replace: true })
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
        <p className="eyebrow">加入讨论</p>
        <h1>创建你的账号</h1>
        <p>注册后即可加入社区，参与文章发布与互动。</p>
      </div>

      <label>
        用户名
        <input
          name="username"
          onChange={updateField}
          placeholder="3 到 30 个字符"
          type="text"
          autoComplete="username"
          minLength="3"
          maxLength="30"
          required
          value={form.username}
        />
      </label>

      <label>
        密码
        <input
          name="password"
          onChange={updateField}
          placeholder="6 到 30 个字符"
          type="password"
          autoComplete="new-password"
          minLength="6"
          maxLength="30"
          required
          value={form.password}
        />
      </label>

      <button className="primary-button" disabled={loading} type="submit">
        {loading ? '注册中...' : '注册'}
      </button>

      <MessageBanner message={message} type={messageType} />
      <p className="auth-switch">已有账号？<Link to="/login">去登录</Link></p>
    </form>
  )
}

export default Register
