const LABELS = {
  DRAFT: '草稿',
  PENDING: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
  OFFLINE: '已下架',
  RESOLVED: '已处理',
}

function StatusBadge({ status, label }) {
  const text = label || LABELS[status] || status
  const cls = `status-badge status-${status?.toLowerCase()}`

  return <span className={cls}>{text}</span>
}

export default StatusBadge
