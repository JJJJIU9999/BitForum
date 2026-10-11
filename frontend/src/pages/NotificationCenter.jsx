import { useEffect, useState } from 'react'
import { CheckCheck } from 'lucide-react'
import { useNavigate, useOutletContext } from 'react-router'
import { markAllNotificationsRead, markNotificationRead, pageNotifications } from '../api/notificationApi.js'
import PageHeader from '../components/PageHeader.jsx'
import StatusBadge from '../components/StatusBadge.jsx'
import Pagination from '../components/Pagination.jsx'
import EmptyState from '../components/EmptyState.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import MessageBanner from '../components/MessageBanner.jsx'

const typeLabels = { COMMENT: '评论', LIKE: '点赞', FAVORITE: '收藏', AUDIT_APPROVED: '审核通过', AUDIT_REJECTED: '审核驳回', ARTICLE_OFFLINE: '文章下架' }

function NotificationCenter() {
  const { refreshUnreadCount } = useOutletContext()
  const navigate = useNavigate()
  const [notifications, setNotifications] = useState([])
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [pageNum, setPageNum] = useState(1)
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState('info')
  const [loading, setLoading] = useState(false)

  async function loadNotifications() {
    setLoading(true)
    setMessage('')
    try {
      const result = await pageNotifications({ pageNum, pageSize: 10 })
      setNotifications(result.data.records || [])
      setPageInfo({ current: result.data.current, pages: result.data.pages, total: result.data.total })
    } catch (error) {
      setNotifications([])
      setMessage(error.message)
      setMessageType('error')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { loadNotifications() }, [pageNum])

  async function handleRead(notificationId) {
    setLoading(true)
    try {
      await markNotificationRead(notificationId)
      await loadNotifications()
      await refreshUnreadCount()
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
      setLoading(false)
    }
  }

  async function handleReadAll() {
    setLoading(true)
    try {
      await markAllNotificationsRead()
      await loadNotifications()
      await refreshUnreadCount()
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
      setLoading(false)
    }
  }

  return (
    <div className="content-view notification-view">
      <PageHeader title="通知中心" description="评论、点赞、收藏和审核结果都会在这里汇总。" actions={<button className="ghost-button icon-text-button" disabled={loading || notifications.length === 0} type="button" onClick={handleReadAll}><CheckCheck size={17} aria-hidden="true" />全部已读</button>} />
      {loading && <LoadingSpinner text="正在整理你的通知…" />}
      <MessageBanner message={message} type={messageType} />
      <div className="notification-list">
        {notifications.map((notification) => <article className={`notification-item ${notification.readStatus === 0 ? 'unread' : ''}`} key={notification.id}><div className="notification-main"><StatusBadge status={notification.type} label={typeLabels[notification.type] || notification.type} /><div><h2>{notification.title}</h2><p>{notification.content}</p><small>{notification.createTime}</small></div></div><div className="notification-actions">{notification.articleId && <button className="text-button" type="button" onClick={() => navigate(`/articles/${notification.articleId}`)}>查看文章</button>}{notification.readStatus === 0 ? <button className="ghost-button compact-button" disabled={loading} type="button" onClick={() => handleRead(notification.id)}>标记已读</button> : <span className="status-badge status-published">已读</span>}</div></article>)}
        {!loading && notifications.length === 0 && <EmptyState message="暂时没有新通知。" />}
      </div>
      {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}
    </div>
  )
}

export default NotificationCenter
