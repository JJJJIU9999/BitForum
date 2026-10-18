import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { Bot, MessageSquarePlus, Send, Sparkles, X } from 'lucide-react'
import {
  createConversation,
  listConversations,
  listMessages,
  recommendForQuery,
  sendMessage,
} from '../api/aiApi.js'
import MarkdownText from './MarkdownText.jsx'
import { getToken } from '../api/request.js'

// 同一页面中的发送与新建共享锁，重新挂载面板也不能重复提交。
const submissions = new Set()
const uncertainText = '发送状态待确认，请核对历史；确认前此会话暂停发送，不会自动重发。'

function AiAssistantPanel({ userId }) {
  const authToken = getToken()
  const [open, setOpen] = useState(false)
  const [conversations, setConversations] = useState([])
  const [activeId, setActiveId] = useState(null)
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [loading, setLoading] = useState(false)
  const [verified, setVerified] = useState(true)
  const [uncertain, setUncertain] = useState(false)
  const [relatedPosts, setRelatedPosts] = useState([])
  const [error, setError] = useState('')
  const bottomRef = useRef(null)
  const alive = useRef(true)
  const version = useRef(0)
  const active = useRef(null)
  const drafts = useRef({})
  const histories = useRef({})
  const key = (id) => `bitforum-ai-pending:${userId}:${id}`
  const valid = (ticket) => alive.current && ticket === version.current && getToken() === authToken

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; version.current += 1 }
  }, [])

  // 同一账号直接重新登录也会更换 token；旧请求必须与新登录会话隔离。
  useEffect(() => {
    version.current += 1
    active.current = null
    drafts.current = {}
    histories.current = {}
    setOpen(false)
    setConversations([])
    setActiveId(null)
    setMessages([])
    setInput('')
    setRelatedPosts([])
    setError('')
    setUncertain(false)
    setVerified(true)
    setLoading(false)
    setSending(submissions.has(userId))
  }, [userId, authToken])

  useEffect(() => {
    if (!open) return
    const ticket = version.current
    setSending(submissions.has(userId))
    refreshConversations(ticket)
    if (active.current) readHistory(active.current, ticket)
    return () => { version.current += 1 }
  }, [open])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [messages, sending])

  async function refreshConversations(ticket) {
    try {
      const result = await listConversations()
      if (valid(ticket)) setConversations(result.data || [])
    } catch (e) {
      if (valid(ticket)) setError(e.message)
    }
  }

  // 只读核对。空历史或旧回答不能证明一个超时请求已经结束。
  function reconcile(id, rows) {
    const stored = sessionStorage.getItem(key(id))
    const marker = stored ? JSON.parse(stored) : null
    let blocked = rows.at(-1)?.role === 'user'
    if (marker) {
      const offset = marker.afterId == null ? 0 : rows.findIndex((m) => m.id === marker.afterId) + 1
      const added = offset > 0 || marker.afterId == null ? rows.slice(offset) : []
      const userIndex = added.findIndex((m) => m.role === 'user')
      const completed = userIndex >= 0 && added.slice(userIndex + 1).some((m) => m.role === 'assistant')
      blocked = !completed || blocked
      if (!blocked) sessionStorage.removeItem(key(id))
    }
    return blocked
  }

  async function readHistory(id, ticket) {
    if (valid(ticket)) { setLoading(true); setVerified(false) }
    try {
      const result = await listMessages(id)
      if (!valid(ticket)) return false
      const rows = result.data || []
      const blocked = reconcile(id, rows)
      histories.current[id] = rows
      setMessages(rows)
      setUncertain(blocked)
      setVerified(true)
      setError(blocked ? uncertainText : '')
      return !blocked
    } catch (e) {
      if (valid(ticket)) {
        setError(`${uncertainText} 历史读取失败：${e.message}`)
        setVerified(false)
        setUncertain(true)
      }
      return false
    } finally {
      if (valid(ticket)) setLoading(false)
    }
  }

  function selectConversation(id) {
    const ticket = ++version.current
    active.current = id
    setActiveId(id)
    setInput(drafts.current[id] || '')
    setMessages([])
    setRelatedPosts([])
    setError('')
    setUncertain(false)
    readHistory(id, ticket)
  }

  async function handleNewConversation() {
    if (submissions.has(userId)) return
    submissions.add(userId)
    const ticket = ++version.current
    setSending(true)
    setError('')
    try {
      const result = await createConversation()
      if (!valid(ticket)) return
      setConversations((prev) => [result.data, ...prev])
      active.current = result.data.id
      setActiveId(result.data.id)
      setMessages([])
      setInput('')
      setRelatedPosts([])
      setUncertain(false)
      setVerified(true)
    } catch (e) {
      if (valid(ticket)) setError(e.message)
    } finally {
      submissions.delete(userId)
      if (alive.current) setSending(false)
    }
  }

  async function loadRelatedPosts(query, ticket) {
    try {
      const result = await recommendForQuery(query, 3)
      if (valid(ticket)) setRelatedPosts(result.data || [])
    } catch {
      if (valid(ticket)) setRelatedPosts([])
    }
  }

  async function handleSend(event) {
    event.preventDefault()
    const content = input.trim()
    if (!content || submissions.has(userId) || loading || !verified || uncertain) return
    submissions.add(userId)
    const ticket = version.current
    let conversationId = active.current
    let posted = false
    setSending(true)
    setError('')
    setRelatedPosts([])
    try {
      if (!conversationId) {
        const created = await createConversation()
        // 关闭面板或切换会话期间，不再发出模型请求。
        if (!valid(ticket)) return
        conversationId = created.data.id
        active.current = conversationId
        setActiveId(conversationId)
        setConversations((prev) => [created.data, ...prev])
      }
      drafts.current[conversationId] = input
      // 标记只保存消息位置，不存提问或密钥；刷新后先核对，不自动续发。
      sessionStorage.setItem(key(conversationId), JSON.stringify({
        afterId: histories.current[conversationId]?.at(-1)?.id ?? null,
      }))
      posted = true
      await sendMessage(conversationId, content)
      if (!valid(ticket)) return
      const confirmed = await readHistory(conversationId, ticket)
      if (confirmed && valid(ticket)) {
        setInput('')
        delete drafts.current[conversationId]
        refreshConversations(ticket)
        loadRelatedPosts(content, ticket)
      }
    } catch (e) {
      if (!valid(ticket)) return
      if (posted) {
        // 即便 POST 报错也只读回历史，不重发。输入保留供核对。
        const confirmed = await readHistory(conversationId, ticket)
        if (valid(ticket)) setError(confirmed
          ? '请求返回异常，服务端已保存回答；请核对历史，输入已保留。'
          : `${uncertainText} ${e.message}`)
      } else {
        setError(e.message)
      }
    } finally {
      submissions.delete(userId)
      if (alive.current) setSending(false)
    }
  }

  if (!open) {
    return (
      <button
        className="ai-fab"
        type="button"
        onClick={() => setOpen(true)}
        aria-label="打开 AI 助手"
        title="AI 助手"
      >
        <Sparkles size={20} aria-hidden="true" />
        <span>AI 助手</span>
      </button>
    )
  }

  return (
    <section className="ai-panel" aria-label="AI 助手">
      <header className="ai-panel-header">
        <strong>
          <Bot size={16} aria-hidden="true" />
          AI 助手
        </strong>
        <div className="ai-panel-header-actions">
          <button type="button" onClick={handleNewConversation} disabled={sending} title="新建对话" aria-label="新建对话">
            <MessageSquarePlus size={16} aria-hidden="true" />
          </button>
          <button type="button" onClick={() => { version.current += 1; setOpen(false) }} title="收起" aria-label="收起 AI 助手">
            <X size={16} aria-hidden="true" />
          </button>
        </div>
      </header>

      {conversations.length > 0 && (
        <div className="ai-conversation-list">
          {conversations.map((conversation) => (
            <button
              key={conversation.id}
              type="button"
              className={conversation.id === activeId ? 'is-active' : ''}
              onClick={() => selectConversation(conversation.id)}
              title={conversation.title}
            >
              {conversation.title}
            </button>
          ))}
        </div>
      )}

      <div className="ai-panel-body">
        {messages.length === 0 && !sending && !loading && (
          <div className="ai-empty">
            <p>你好，我是 BitForum 的 AI 助手。</p>
            <p className="ai-empty-hint">
              可以多轮讨论技术问题、检索公开文章，并查看回答下方的参考来源。不能代你修改站内数据。
            </p>
          </div>
        )}

        {messages.map((message) => (
          <div key={message.id} className={`ai-message ai-message-${message.role}`}>
            {/* 助手回答是 Markdown，需要渲染；用户输入按纯文本显示，避免被当作语法解析 */}
            {message.role === 'assistant' ? (
              <>
                <MarkdownText content={message.content} />
                {/* M15：检索命中的站内文章，点击跳到原帖 */}
                {message.citations?.length > 0 && (
                  <div className="ai-citations">
                    <span className="ai-citations-label">参考来源</span>
                    <ul>
                      {message.citations.map((citation) => (
                        <li key={citation.articleId}>
                          <Link to={`/articles/${citation.articleId}`} className="ai-citation-link">
                            {citation.title}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </>
            ) : (
              message.content
            )}
          </div>
        ))}

        {/* M17：回答末尾推荐相关帖子。只在有结果时出现，且回答生成中不显示旧的推荐 */}
        {relatedPosts.length > 0 && !sending && (
          <div className="ai-related">
            <span className="ai-citations-label">相关帖子</span>
            <ul>
              {relatedPosts.map((item) => (
                <li key={item.articleId}>
                  <Link to={`/articles/${item.articleId}`} className="ai-citation-link">
                    {item.title}
                  </Link>
                  {item.reason && <small className="ai-related-reason">{item.reason}</small>}
                </li>
              ))}
            </ul>
          </div>
        )}

        {loading && <p role="status">正在核对历史…</p>}
        {sending && <div className="ai-message ai-message-assistant ai-typing">正在生成回答…</div>}

        {error && <p className="ai-error" role="alert">{error}</p>}
        {activeId && (uncertain || !verified) && (
          <button type="button" disabled={sending || loading} onClick={() => readHistory(activeId, version.current)}>核对历史</button>
        )}
        <div ref={bottomRef} />
      </div>

      <form className="ai-panel-footer" onSubmit={handleSend}>
        <input
          type="text"
          value={input}
          maxLength={2000}
          onChange={(event) => { setInput(event.target.value); drafts.current[activeId || 'new'] = event.target.value }}
          placeholder="输入你的问题…"
          aria-label="消息内容"
          disabled={sending || loading}
        />
        <button type="submit" disabled={sending || loading || !verified || uncertain || !input.trim()} aria-label="发送">
          <Send size={16} aria-hidden="true" />
        </button>
      </form>
    </section>
  )
}

export default AiAssistantPanel
