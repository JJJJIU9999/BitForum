import { useEffect, useState } from 'react'
import {
  createCategory, deleteCategory, disableCategory,
  enableCategory, pageAdminCategories, updateCategory,
} from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import Pagination from '../../components/Pagination.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'

const EMPTY_FORM = { categoryId: null, name: '', description: '', sortOrder: 0 }

function ManageCategories() {
  const [records, setRecords] = useState([])
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [pageNum, setPageNum] = useState(1)
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [form, setForm] = useState(EMPTY_FORM)
  const [dialog, setDialog] = useState(null)

  useEffect(() => {
    async function load() {
      setLoading(true)
      setMessage('')
      try {
        const result = await pageAdminCategories({ pageNum, pageSize: 6 })
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
    action().then(() => { setMessage(okMsg); setRefreshKey(k => k + 1); setForm(EMPTY_FORM) })
      .catch(e => setMessage(e.message))
      .finally(() => setLoading(false))
  }

  function editCategory(cat) {
    setForm({ categoryId: cat.id, name: cat.name || '', description: cat.description || '', sortOrder: cat.sortOrder ?? 0 })
  }

  function submitCategory(event) {
    event.preventDefault()
    const payload = { name: form.name, description: form.description, sortOrder: Number(form.sortOrder || 0) }
    if (form.categoryId) {
      doAction(() => updateCategory({ ...payload, categoryId: form.categoryId }), `板块 #${form.categoryId} 已更新。`)
    } else {
      doAction(() => createCategory(payload), '板块已创建。')
    }
  }

  return (
    <div className="content-view admin-view">
      <PageHeader title="板块管理" description="创建、编辑和管理文章板块。" badge={`${pageInfo.total} 条`} />

      <form className="admin-inline-form" onSubmit={submitCategory}>
        <input name="name" onChange={(e) => setForm(f => ({ ...f, name: e.target.value }))} placeholder="板块名称" value={form.name} />
        <input name="description" onChange={(e) => setForm(f => ({ ...f, description: e.target.value }))} placeholder="板块描述" value={form.description} />
        <input name="sortOrder" type="number" min="0" onChange={(e) => setForm(f => ({ ...f, sortOrder: e.target.value }))} placeholder="排序" value={form.sortOrder} />
        <button className="primary-button compact-button" disabled={loading} type="submit">
          {form.categoryId ? '保存' : '新建'}
        </button>
        {form.categoryId && (
          <button className="ghost-button compact-button" type="button" onClick={() => setForm(EMPTY_FORM)}>取消</button>
        )}
      </form>

      {loading && <LoadingSpinner />}
      <MessageBanner message={message} type={message.includes('已') ? 'info' : 'error'} />

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr><th>ID</th><th>名称</th><th>描述</th><th>排序</th><th>状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            {records.map((c) => {
              const disabled = c.status === 0
              return (
                <tr key={c.id}>
                  <td className="mono-cell">#{c.id}</td>
                  <td className="strong-cell truncate-cell">{c.name}</td>
                  <td className="truncate-cell">{c.description || '-'}</td>
                  <td className="mono-cell">{c.sortOrder}</td>
                  <td><span className={`status-badge ${disabled ? 'status-offline' : 'status-published'}`}>{disabled ? '禁用' : '启用'}</span></td>
                  <td className="multi-action-cell">
                    <button className="ghost-button compact-button" disabled={loading} onClick={() => editCategory(c)}>编辑</button>
                    <button className={disabled ? 'ghost-button compact-button' : 'danger-button'} disabled={loading}
                      onClick={() => setDialog({ id: c.id, disabled })}>{disabled ? '启用' : '禁用'}</button>
                    <button className="danger-button" disabled={loading}
                      onClick={() => setDialog({ id: c.id, del: true })}>删除</button>
                  </td>
                </tr>
              )
            })}
            {records.length === 0 && <tr><td className="empty-cell" colSpan="6">暂无板块。</td></tr>}
          </tbody>
        </table>
      </div>

      {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}

      {dialog && !dialog.del && (
        <ConfirmDialog title={dialog.disabled ? '启用板块' : '禁用板块'}
          message={`确定要${dialog.disabled ? '启用' : '禁用'}板块 #${dialog.id} 吗？`}
          onConfirm={() => {
            const action = dialog.disabled ? enableCategory(dialog.id) : disableCategory(dialog.id)
            doAction(() => action, `板块 #${dialog.id} 已${dialog.disabled ? '启用' : '禁用'}。`)
            setDialog(null)
          }}
          onCancel={() => setDialog(null)} />
      )}
      {dialog?.del && (
        <ConfirmDialog title="删除板块" message={`确定要永久删除板块 #${dialog.id} 吗？此操作不可撤销。`}
          onConfirm={() => { doAction(() => deleteCategory(dialog.id), `板块 #${dialog.id} 已删除。`); setDialog(null) }}
          onCancel={() => setDialog(null)} />
      )}
    </div>
  )
}

export default ManageCategories
