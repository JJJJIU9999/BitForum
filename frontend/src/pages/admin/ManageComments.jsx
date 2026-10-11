import { useEffect, useState } from 'react'
import { deleteAdminComment, pageAdminComments } from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import Pagination from '../../components/Pagination.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'

function ManageComments() {
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
        const result = await pageAdminComments({ pageNum, pageSize: 6 })
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

  function doDelete(id) {
    setLoading(true)
    setMessage('')
    deleteAdminComment(id)
      .then(() => { setMessage(`评论 #${id} 已删除。`); setRefreshKey(k => k + 1) })
      .catch(e => setMessage(e.message))
      .finally(() => setLoading(false))
  }

  return (
    <div className="content-view admin-view">
      <PageHeader title="评论管理" description="管理所有评论，支持删除违规评论。" badge={`${pageInfo.total} 条`} />

      {loading && <LoadingSpinner />}
      <MessageBanner message={message} type={message.includes('已') ? 'info' : 'error'} />

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr><th>ID</th><th>文章ID</th><th>用户ID</th><th>内容</th><th>操作</th></tr>
          </thead>
          <tbody>
            {records.map((c) => (
              <tr key={c.id}>
                <td className="mono-cell">#{c.id}</td>
                <td className="mono-cell">{c.articleId}</td>
                <td className="mono-cell">{c.userId}</td>
                <td className="truncate-cell">{c.content}</td>
                <td className="action-cell">
                  <button className="danger-button" disabled={loading}
                    onClick={() => setDialog({ id: c.id })}>删除</button>
                </td>
              </tr>
            ))}
            {records.length === 0 && <tr><td className="empty-cell" colSpan="5">暂无评论。</td></tr>}
          </tbody>
        </table>
      </div>

      {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}

      {dialog && (
        <ConfirmDialog title="删除评论" message={`确定要永久删除评论 #${dialog.id} 吗？`}
          onConfirm={() => { setDialog(null); doDelete(dialog.id) }}
          onCancel={() => setDialog(null)} />
      )}
    </div>
  )
}

export default ManageComments
