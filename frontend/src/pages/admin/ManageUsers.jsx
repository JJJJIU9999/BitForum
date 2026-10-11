import { useEffect, useState } from 'react'
import { disableUser, enableUser, pageAdminUsers } from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import Pagination from '../../components/Pagination.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'
import { formatDate } from './adminHelpers.js'

function ManageUsers() {
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
        const result = await pageAdminUsers({ pageNum, pageSize: 6 })
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

  function doToggle(userId, disabled) {
    setLoading(true)
    setMessage('')
    const action = disabled ? enableUser(userId) : disableUser(userId)
    action.then(() => { setMessage(`用户 #${userId} 已${disabled ? '启用' : '禁用'}。`); setRefreshKey(k => k + 1) })
      .catch(e => setMessage(e.message))
      .finally(() => setLoading(false))
  }

  return (
    <div className="content-view admin-view">
      <PageHeader title="用户管理" description="管理注册用户，支持启用和禁用操作。" badge={`${pageInfo.total} 条`} />

      {loading && <LoadingSpinner />}
      <MessageBanner message={message} type={message.includes('已') ? 'info' : 'error'} />

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr><th>ID</th><th>用户名</th><th>角色</th><th>状态</th><th>注册时间</th><th>操作</th></tr>
          </thead>
          <tbody>
            {records.map((u) => {
              const disabled = u.status === 0
              return (
                <tr key={u.userId}>
                  <td className="mono-cell">#{u.userId}</td>
                  <td className="strong-cell truncate-cell">{u.username}</td>
                  <td>{u.role}</td>
                  <td><span className={`status-badge ${disabled ? 'status-offline' : 'status-published'}`}>{disabled ? '禁用' : '启用'}</span></td>
                  <td className="mono-cell">{formatDate(u.createTime)}</td>
                  <td className="action-cell">
                    <button
                      className={disabled ? 'ghost-button compact-button' : 'danger-button'}
                      disabled={loading}
                      onClick={() => setDialog({ userId: u.userId, disabled })}>
                      {disabled ? '启用' : '禁用'}
                    </button>
                  </td>
                </tr>
              )
            })}
            {records.length === 0 && <tr><td className="empty-cell" colSpan="6">暂无用户。</td></tr>}
          </tbody>
        </table>
      </div>

      {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}

      {dialog && (
        <ConfirmDialog
          title={dialog.disabled ? '启用用户' : '禁用用户'}
          message={`确定要${dialog.disabled ? '启用' : '禁用'}用户 #${dialog.userId} 吗？`}
          onConfirm={() => { doToggle(dialog.userId, dialog.disabled); setDialog(null) }}
          onCancel={() => setDialog(null)}
        />
      )}
    </div>
  )
}

export default ManageUsers
