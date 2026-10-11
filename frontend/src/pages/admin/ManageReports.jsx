import { useEffect, useState } from 'react'
import { pageAdminReports, rejectReport, resolveReport } from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import StatusBadge from '../../components/StatusBadge.jsx'
import Pagination from '../../components/Pagination.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'
import { REPORT_STATUS_LABELS, TARGET_TYPE_LABELS } from './adminHelpers.js'

function ManageReports() {
  const [records, setRecords] = useState([])
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [pageNum, setPageNum] = useState(1)
  const [reportStatus, setReportStatus] = useState('PENDING')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [dialog, setDialog] = useState(null)

  useEffect(() => {
    async function load() {
      setLoading(true)
      setMessage('')
      try {
        const result = await pageAdminReports({ status: reportStatus, pageNum, pageSize: 6 })
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
  }, [pageNum, refreshKey, reportStatus])

  function doAction(action, okMsg) {
    setLoading(true)
    setMessage('')
    action().then(() => { setMessage(okMsg); setRefreshKey(k => k + 1) })
      .catch(e => setMessage(e.message))
      .finally(() => setLoading(false))
  }

  return (
    <div className="content-view admin-view">
      <PageHeader title="举报处理" description="审核用户提交的举报，作出处理或驳回。" badge={`${pageInfo.total} 条`} />

      <label className="filter-row admin-filter-row">
        举报状态
        <select value={reportStatus} onChange={(e) => { setReportStatus(e.target.value); setPageNum(1) }}>
          <option value="PENDING">待处理</option>
          <option value="RESOLVED">已处理</option>
          <option value="REJECTED">已驳回</option>
        </select>
      </label>

      {loading && <LoadingSpinner />}
      <MessageBanner message={message} type={message.includes('已') || message.includes('成立') ? 'info' : 'error'} />

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr><th>ID</th><th>对象</th><th>对象ID</th><th>举报人</th><th>作者</th><th>原因</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            {records.map((r) => (
              <tr key={r.id}>
                <td className="mono-cell">#{r.id}</td>
                <td>{TARGET_TYPE_LABELS[r.targetType] || r.targetType}</td>
                <td className="mono-cell">#{r.targetId}</td>
                <td className="mono-cell">{r.reporterId}</td>
                <td className="mono-cell">{r.targetOwnerId}</td>
                <td className="truncate-cell">{r.reason}</td>
                <td><StatusBadge status={r.status} label={REPORT_STATUS_LABELS[r.status]} /></td>
                <td className="multi-action-cell">
                  {r.status === 'PENDING' ? (
                    <>
                      <button className="ghost-button compact-button" disabled={loading}
                        onClick={() => setDialog({ type: 'resolve', id: r.id })}>成立</button>
                      <button className="danger-button" disabled={loading}
                        onClick={() => setDialog({ type: 'reject', id: r.id })}>驳回</button>
                    </>
                  ) : (
                    <span className="muted-cell">{r.handleResult || '-'}</span>
                  )}
                </td>
              </tr>
            ))}
            {records.length === 0 && <tr><td className="empty-cell" colSpan="8">暂无举报记录。</td></tr>}
          </tbody>
        </table>
      </div>

      {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}

      {dialog?.type === 'resolve' && (
        <ConfirmDialog title="处理举报" message={`确定举报 #${dialog.id} 成立？请填写处理说明。`} inputLabel="处理说明"
          onConfirm={(result) => { setDialog(null); doAction(() => resolveReport({ reportId: dialog.id, handleResult: result }), `举报 #${dialog.id} 已处理。`) }}
          onCancel={() => setDialog(null)} />
      )}
      {dialog?.type === 'reject' && (
        <ConfirmDialog title="驳回举报" message={`确定驳回举报 #${dialog.id} 吗？请填写驳回说明。`} inputLabel="驳回说明"
          onConfirm={(result) => { setDialog(null); doAction(() => rejectReport({ reportId: dialog.id, handleResult: result }), `举报 #${dialog.id} 已驳回。`) }}
          onCancel={() => setDialog(null)} />
      )}
    </div>
  )
}

export default ManageReports
