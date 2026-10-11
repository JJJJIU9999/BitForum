import { useEffect, useState } from 'react'
import { approveArticle, pageAuditArticles, rejectArticle } from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import Pagination from '../../components/Pagination.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'
import { formatDate } from './adminHelpers.js'

function AuditArticles() {
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
        const result = await pageAuditArticles({ status: 'PENDING', pageNum, pageSize: 6 })
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

  function doApprove(id) {
    setLoading(true)
    setMessage('')
    approveArticle(id)
      .then(() => { setMessage(`文章 #${id} 已审核通过。`); setRefreshKey(k => k + 1) })
      .catch(e => setMessage(e.message))
      .finally(() => setLoading(false))
  }

  function doReject(id, reason) {
    setLoading(true)
    setMessage('')
    rejectArticle({ articleId: id, reason })
      .then(() => { setMessage(`文章 #${id} 已驳回。`); setRefreshKey(k => k + 1) })
      .catch(e => setMessage(e.message))
      .finally(() => setLoading(false))
  }

  return (
    <div className="content-view admin-view">
      <PageHeader title="文章审核" description="审核待发布的文章，通过后进入公开列表。" badge={`${pageInfo.total} 条`} />

      {loading && <LoadingSpinner text="加载中..." />}
      <MessageBanner message={message} type={message.includes('通过') || message.includes('驳回') ? 'info' : 'error'} />

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr><th>ID</th><th>标题</th><th>类型 / 话题</th><th>作者</th><th>提交时间</th><th>操作</th></tr>
          </thead>
          <tbody>
            {records.map((a) => (
              <tr key={a.id}>
                <td className="mono-cell">#{a.id}</td>
                <td className="strong-cell truncate-cell">{a.title}</td>
                <td className="truncate-cell">{a.contentType === 'QUESTION' ? '问题 · ' : '文章 · '}{a.categoryName || '-'}</td>
                <td className="mono-cell">{a.userId}</td>
                <td className="mono-cell">{formatDate(a.createTime)}</td>
                <td className="multi-action-cell">
                  <button className="ghost-button compact-button" disabled={loading}
                    onClick={() => doApprove(a.id)}>通过</button>
                  <button className="danger-button" disabled={loading}
                    onClick={() => setDialog({ type: 'reject', id: a.id })}>驳回</button>
                </td>
              </tr>
            ))}
            {records.length === 0 && <tr><td className="empty-cell" colSpan="6">暂无待审核文章。</td></tr>}
          </tbody>
        </table>
      </div>

      {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}

      {dialog?.type === 'reject' && (
        <ConfirmDialog
          title="驳回文章"
          message={`确定要驳回文章 #${dialog.id} 吗？请填写驳回原因。`}
          inputLabel="驳回原因"
          onConfirm={(reason) => { setDialog(null); doReject(dialog.id, reason) }}
          onCancel={() => setDialog(null)}
        />
      )}
    </div>
  )
}

export default AuditArticles
