import { useEffect, useState } from 'react'
import { approveImage, pagePendingImages, rejectImage } from '../../api/adminApi.js'
import ConfirmDialog from '../../components/ConfirmDialog.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import PageHeader from '../../components/PageHeader.jsx'
import Pagination from '../../components/Pagination.jsx'
import PrivateImagePreview from '../../components/PrivateImagePreview.jsx'
import { formatDate } from './adminHelpers.js'

function ImageModeration() {
  const [pageNum, setPageNum] = useState(1)
  const [page, setPage] = useState({ records: [], current: 1, pages: 1, total: 0 })
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState(false)
  const [decision, setDecision] = useState(null)

  async function refresh() {
    const result = await pagePendingImages({ pageNum, pageSize: 10 })
    setPage(result.data || { records: [], current: 1, pages: 1, total: 0 })
  }

  useEffect(() => {
    refresh().catch((e) => { setMessage(e.message); setError(true) })
  }, [pageNum])

  async function decide(reason) {
    const current = decision
    setDecision(null)
    setBusy(true)
    setMessage('')
    try {
      if (current.approve) await approveImage(current.id)
      else await rejectImage(current.id, reason)
      await refresh()
      setMessage(current.approve ? '图片已通过审核。' : '图片已驳回，作者可以重新上传。')
      setError(false)
    } catch (e) {
      setMessage(e.message)
      setError(true)
    } finally { setBusy(false) }
  }

  return <div className="content-view admin-view">
    <PageHeader title="图片审核" description="逐张查看头像与封面；只有通过后才会公开。历史外链需驳回并请作者站内重传。" badge={`${page.total} 张待审`} />
    {busy && <LoadingSpinner text="正在处理图片…" />}
    <MessageBanner message={message} type={error ? 'error' : 'info'} />
    <section className="admin-section">
      <h2>人工待办</h2>
      <div className="admin-table-wrap"><table className="admin-table"><thead><tr><th>预览</th><th>资源</th><th>来源</th><th>提交时间</th><th>决定</th></tr></thead><tbody>
        {page.records.map((image) => {
          const external = image.legacyUrl && !image.legacyUrl.startsWith('/uploads/')
          return <tr key={image.id}>
            <td>{external ? <span>外链不可预览</span> : <PrivateImagePreview imageId={image.id} alt={`${image.kind === 'AVATAR' ? '头像' : '封面'} #${image.id} 待审预览`} className="admin-image-preview" />}</td>
            <td><strong>#{image.id}</strong> · {image.kind === 'AVATAR' ? '头像' : '封面'}<br />作者 #{image.ownerId}{image.targetId && <> · 对象 #{image.targetId}</>}<br />{image.originalFilename}</td>
            <td>{image.status === 'LEGACY_REVIEW' ? '历史待排查' : '新上传'}{external && <p className="review-target-text">外链：{image.legacyUrl}</p>}</td>
            <td>{formatDate(image.createTime)}</td>
            <td className="multi-action-cell"><button className="ghost-button compact-button" disabled={busy || external} onClick={() => setDecision({ id: image.id, approve: true })}>通过</button><button className="danger-button" disabled={busy} onClick={() => setDecision({ id: image.id, approve: false })}>驳回</button></td>
          </tr>
        })}
        {page.records.length === 0 && <tr><td className="empty-cell" colSpan="5">暂无图片审核待办。</td></tr>}
      </tbody></table></div>
      {page.total > 0 && <Pagination pageInfo={page} onPageChange={setPageNum} loading={busy} />}
    </section>
    {decision && <ConfirmDialog title={decision.approve ? '通过图片' : '驳回图片'}
      message={`确认处理图片 #${decision.id}？通过后可能立即更新公开头像或封面。`}
      inputLabel={decision.approve ? undefined : '驳回原因'}
      onConfirm={decide} onCancel={() => setDecision(null)} />}
  </div>
}

export default ImageModeration
