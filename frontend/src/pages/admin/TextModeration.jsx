import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import {
  createTextRule, decideTextPending, deleteTextRule, executeTextRescan,
  listTextRules, pageTextOperations, pageTextPending, previewTextRescan, updateTextRule,
} from '../../api/adminApi.js'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import PageHeader from '../../components/PageHeader.jsx'
import Pagination from '../../components/Pagination.jsx'
import { formatDate } from './adminHelpers.js'

function TextModeration() {
  const [rules, setRules] = useState([])
  const [operations, setOperations] = useState([])
  const [pending, setPending] = useState([])
  const [pageNum, setPageNum] = useState(1)
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [phrase, setPhrase] = useState('')
  const [editing, setEditing] = useState(null)
  const [preview, setPreview] = useState(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState(false)
  const [dialog, setDialog] = useState(null)

  async function refresh(page = pageNum) {
    const [ruleResult, pendingResult, operationResult] = await Promise.all([
      listTextRules(), pageTextPending({ pageNum: page, pageSize: 10 }),
      pageTextOperations({ pageNum: 1, pageSize: 10 }),
    ])
    setRules(ruleResult.data || [])
    setPending(pendingResult.data?.records || [])
    setPageInfo(pendingResult.data || { current: 1, pages: 1, total: 0 })
    setOperations(operationResult.data?.records || [])
  }

  useEffect(() => {
    refresh(pageNum).catch((e) => { setMessage(e.message); setError(true) })
  }, [pageNum])

  async function run(action, success) {
    setBusy(true)
    setMessage('')
    try {
      await action()
      await refresh()
      setPreview(null)
      setMessage(success)
      setError(false)
      return true
    } catch (e) {
      setMessage(e.message)
      setError(true)
      return false
    } finally {
      setBusy(false)
    }
  }

  async function addRule(event) {
    event.preventDefault()
    if (await run(() => createTextRule(phrase.trim()), '规则已添加，请预览历史内容。')) setPhrase('')
  }

  async function loadPreview() {
    setBusy(true)
    setMessage('')
    try {
      const result = await previewTextRescan()
      setPreview(result.data)
      setError(false)
    } catch (e) {
      setMessage(e.message)
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  async function runRescan() {
    setDialog(null)
    setBusy(true)
    setMessage('')
    try {
      let cursor = null
      let scanned = 0
      let held = 0
      let complete = false
      while (!complete) {
        const result = await executeTextRescan({ ruleVersion: preview.ruleVersion, cursor })
        const batch = result.data
        scanned += batch.scanned
        held += batch.held
        cursor = batch.nextCursor
        complete = batch.complete
        setMessage(`已检查 ${scanned} 条，转待审 ${held} 条。`)
      }
      await refresh()
      setPreview(null)
      setMessage(`复扫完成：检查 ${scanned} 条，转待审 ${held} 条。`)
      setError(false)
    } catch (e) {
      setMessage(`复扫已停止：${e.message}。请重新预览后再执行。`)
      setError(true)
      setPreview(null)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="content-view admin-view">
      <PageHeader title="文字治理" description="管理敏感文字规则，先预览历史命中，再决定是否转入人工审核。" badge={`${pageInfo.total} 条待审`} />
      {busy && <LoadingSpinner text="处理中..." />}
      <MessageBanner message={message} type={error ? 'error' : 'info'} />

      <section className="admin-section">
        <h2>规则</h2>
        <form className="profile-form text-rule-form" onSubmit={addRule}>
          <label>规则文字<input value={phrase} maxLength="255" onChange={(e) => setPhrase(e.target.value)} placeholder="输入要人工审核的文字" required /></label>
          <button className="primary-button" disabled={busy || !phrase.trim()} type="submit">添加规则</button>
        </form>
        <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>规则</th><th>状态</th><th>更新者</th><th>操作</th></tr></thead><tbody>
          {rules.map((rule) => <tr key={rule.id}>
            <td>{editing?.id === rule.id ? <input aria-label="编辑规则" value={editing.phrase} onChange={(e) => setEditing({ ...editing, phrase: e.target.value })} /> : rule.phrase}</td>
            <td>{rule.enabled ? '启用' : '停用'}</td><td>#{rule.updatedBy}</td>
            <td className="multi-action-cell">
              {editing?.id === rule.id ? <><button className="ghost-button compact-button" disabled={busy} onClick={() => run(() => updateTextRule(rule.id, { phrase: editing.phrase }), '规则已更新，请重新预览。').then((ok) => { if (ok) setEditing(null) })}>保存</button><button className="ghost-button compact-button" onClick={() => setEditing(null)}>取消</button></> : <button className="ghost-button compact-button" onClick={() => setEditing({ id: rule.id, phrase: rule.phrase })}>编辑</button>}
              <button className="ghost-button compact-button" disabled={busy} onClick={() => run(() => updateTextRule(rule.id, { enabled: !rule.enabled }), '状态已更新，请重新预览。')}>{rule.enabled ? '停用' : '启用'}</button>
              <button className="danger-button" disabled={busy} onClick={() => setDialog({ type: 'delete', id: rule.id })}>删除</button>
            </td>
          </tr>)}
          {rules.length === 0 && <tr><td className="empty-cell" colSpan="4">暂无规则。</td></tr>}
        </tbody></table></div>
      </section>

      <section className="admin-section">
        <h2>历史复扫</h2>
        <p>预览只读取公开内容。确认后每批检查 100 条，命中的文章、评论和资料立即隐藏并进入待审。</p>
        <button className="ghost-button compact-button" disabled={busy} onClick={loadPreview}>生成预览</button>
        {preview && <div className="admin-callout"><p>规则版本 {preview.ruleVersion}：检查 {preview.scanned} 条，预计命中 {preview.matched} 条。</p>
          {preview.samples.length > 0 && <ul>{preview.samples.map((sample) => <li key={`${sample.targetType}-${sample.targetId}`}><strong>{sample.targetType} #{sample.targetId}</strong> · 命中 {sample.matchedRules} · {sample.excerpt}</li>)}</ul>}
          <button className="danger-button" disabled={busy || preview.matched === 0} onClick={() => setDialog({ type: 'rescan' })}>确认执行复扫</button>
        </div>}
      </section>

      <section className="admin-section">
        <h2>人工待办</h2>
        <p>文章请到 <Link to="/admin/audit">文章审核</Link> 处理；此处处理评论和个人资料。</p>
        <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>类型</th><th>对象</th><th>命中规则</th><th>来源</th><th>提交时间</th><th>操作</th></tr></thead><tbody>
          {pending.map((item) => <tr key={item.id}>
            <td>{({ ARTICLE: '文章', COMMENT: '评论', PROFILE: '个人资料' })[item.targetType]}</td>
            <td>#{item.targetId}<details><summary>查看待审原文</summary><p className="review-target-text">{item.targetText}</p></details></td>
            <td>{item.matchedRules}</td><td>{item.source === 'RESCAN' ? '历史复扫' : '新提交'}</td><td>{formatDate(item.createTime)}</td>
            <td className="multi-action-cell">{item.targetType === 'ARTICLE' ? <Link to="/admin/audit">去审核</Link> : <><button className="ghost-button compact-button" disabled={busy} onClick={() => setDialog({ type: 'approve', id: item.id })}>通过</button><button className="danger-button" disabled={busy} onClick={() => setDialog({ type: 'reject', id: item.id })}>驳回</button></>}</td>
          </tr>)}
          {pending.length === 0 && <tr><td className="empty-cell" colSpan="6">暂无文字审核待办。</td></tr>}
        </tbody></table></div>
        {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={busy} />}
      </section>

      <section className="admin-section"><h2>最近规则操作</h2><div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>时间</th><th>规则 ID</th><th>操作</th><th>操作者</th><th>版本</th></tr></thead><tbody>
        {operations.map((item) => <tr key={item.id}><td>{formatDate(item.createTime)}</td><td>#{item.ruleId}</td><td>{item.action}</td><td>#{item.actorId}</td><td>{item.ruleVersion}</td></tr>)}
        {operations.length === 0 && <tr><td className="empty-cell" colSpan="5">暂无操作记录。</td></tr>}
      </tbody></table></div></section>

      {dialog && <ConfirmDialog
        title={{ delete: '删除规则', rescan: '执行历史复扫', approve: '通过审核', reject: '驳回审核' }[dialog.type]}
        message={dialog.type === 'rescan' ? `确认按版本 ${preview?.ruleVersion} 复扫，并隐藏预计命中的 ${preview?.matched} 条公开内容？` : `确认处理 #${dialog.id}？`}
        inputLabel={dialog.type === 'reject' ? '驳回原因' : undefined}
        onConfirm={(reason) => {
          const current = dialog
          setDialog(null)
          if (current.type === 'rescan') runRescan()
          if (current.type === 'delete') run(() => deleteTextRule(current.id), '规则已删除，请重新预览。')
          if (current.type === 'approve') run(() => decideTextPending(current.id, { approved: true }), '审核已通过。')
          if (current.type === 'reject') run(() => decideTextPending(current.id, { approved: false, reason }), '已驳回。')
        }}
        onCancel={() => setDialog(null)}
      />}
    </div>
  )
}

export default TextModeration
