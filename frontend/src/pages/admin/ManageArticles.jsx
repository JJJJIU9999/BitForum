import { useEffect, useState } from 'react'
import { deleteAdminArticle, offlineArticle, pageAdminArticles } from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import StatusBadge from '../../components/StatusBadge.jsx'
import Pagination from '../../components/Pagination.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'
import { STATUS_LABELS } from './adminHelpers.js'

function ManageArticles() {
  const [records, setRecords] = useState([])
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [pageNum, setPageNum] = useState(1)
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [dialog, setDialog] = useState(null)

  useEffect(() => {
    async function load() {
      setLoading(true)
      setMessage('')
      try {
        const result = await pageAdminArticles({ pageNum, pageSize: 6 })
        setRecords(result.data.records || [])
        setPageInfo({ current: result.data.current, pages: result.data.pages, total: result.data.total })
      } catch (error) {
        setRecords([])
        setMessage(error.message)
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [pageNum, refreshKey])

  function doAction(action, okMsg) {
    setLoading(true)
    setMessage('')
    action().then(() => { setMessage(okMsg); setRefreshKey(k => k + 1) })
      .catch(e => setMessage(e.message))
      .finally(() => setLoading(false))
  }

  return (
    <div className="content-view admin-view">
      <PageHeader title="文章管理" description="管理所有文章，支持下架和删除。" badge={`${pageInfo.total} 条`} />

      {loading && <LoadingSpinner />}
      <MessageBanner message={message} type={message.includes('成功') || message.includes('已') ? 'info' : 'error'} />

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr><th>ID</th><th>标题</th><th>类型 / 话题</th><th>状态</th><th>作者</th><th>浏览</th><th>点赞</th><th>操作</th></tr>
          </thead>
          <tbody>
            {records.map((a) => (
              <tr key={a.id}>
                <td className="mono-cell">#{a.id}</td>
                <td className="strong-cell truncate-cell">{a.title}</td>
                <td className="truncate-cell">{a.contentType === 'QUESTION' ? '问题 · ' : '文章 · '}{a.categoryName || '-'}</td>
                <td><StatusBadge status={a.status} label={STATUS_LABELS[a.status]} /></td>
                <td className="mono-cell">{a.userId}</td>
                <td className="mono-cell">{a.viewCount}</td>
                <td className="mono-cell">{a.likeCount}</td>
                <td className="multi-action-cell">
                  {a.status === 'PUBLISHED' && (
                    <button className="danger-button" disabled={loading}
                      onClick={() => setDialog({ type: 'offline', id: a.id })}>下架</button>
                  )}
                  <button className="danger-button" disabled={loading}
                    onClick={() => setDialog({ type: 'delete', id: a.id })}>删除</button>
                </td>
              </tr>
            ))}
            {records.length === 0 && <tr><td className="empty-cell" colSpan="8">暂无文章。</td></tr>}
          </tbody>
        </table>
      </div>

      {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}

      {dialog?.type === 'offline' && (
        <ConfirmDialog title="下架文章" message={`确定要下架文章 #${dialog.id} 吗？请填写原因。`} inputLabel="下架原因"
          onConfirm={(reason) => { setDialog(null); doAction(() => offlineArticle({ articleId: dialog.id, reason }), `文章 #${dialog.id} 已下架。`) }}
          onCancel={() => setDialog(null)} />
      )}
      {dialog?.type === 'delete' && (
        <ConfirmDialog title="删除文章" message={`确定要永久删除文章 #${dialog.id} 吗？此操作不可撤销。`}
          onConfirm={() => { setDialog(null); doAction(() => deleteAdminArticle(dialog.id), `文章 #${dialog.id} 已删除。`) }}
          onCancel={() => setDialog(null)} />
      )}
    </div>
  )
}

export default ManageArticles
