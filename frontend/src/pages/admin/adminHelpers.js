export function formatDate(dateText) {
  if (!dateText) return '-'
  return dateText.replace('T', ' ').slice(0, 16)
}

export function formatNumber(value) {
  return Number(value || 0).toLocaleString('zh-CN')
}

export const STATUS_LABELS = {
  DRAFT: '草稿',
  PENDING: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
  OFFLINE: '已下架',
}

export const REPORT_STATUS_LABELS = {
  PENDING: '待处理',
  RESOLVED: '已处理',
  REJECTED: '已驳回',
}

export const TARGET_TYPE_LABELS = {
  ARTICLE: '文章',
  COMMENT: '评论',
}
