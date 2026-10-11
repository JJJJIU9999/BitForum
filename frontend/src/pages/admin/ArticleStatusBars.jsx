import { formatNumber } from './adminHelpers.js'

/**
 * 只包含真正的文章状态。
 *
 * 「近 7 天新增」是时间指标而非状态，故意不在这里 —— 它由看板标题区的小标签展示，
 * 否则会把「某段时间写了多少」和「现在处于什么状态」混成同一个分布。
 */
const ARTICLE_STATUSES = [
  { key: 'draft', label: '草稿', tone: 'mustard' },
  { key: 'pending', label: '待审核', tone: 'terracotta' },
  { key: 'published', label: '已发布', tone: 'forest' },
  { key: 'rejected', label: '已驳回', tone: 'danger' },
  { key: 'offline', label: '已下架', tone: 'muted' },
]

/**
 * 文章状态分布：五种状态各占一行，条形长度按五种状态中的最大值等比缩放，
 * 因此比较的是状态之间的相对差距，而不是各自占文章总数的比例。
 *
 * 数值为 0 时同样保留名称和数字：状态存在但为空，和状态根本不存在是两回事。
 * 每行都带文字标签与精确数字，颜色只是辅助，不承担唯一的信息通道。
 */
function ArticleStatusBars({ articleStats = {} }) {
  const rows = ARTICLE_STATUSES.map((status) => ({
    ...status,
    value: Math.max(Number(articleStats[status.key]) || 0, 0),
  }))
  const max = rows.reduce((acc, row) => Math.max(acc, row.value), 0)

  return (
    <ul className="article-status-list">
      {rows.map((row) => {
        const percent = max > 0 ? (row.value / max) * 100 : 0
        return (
          <li className="article-status-row" key={row.key}>
            <span className="article-status-label">{row.label}</span>
            <span className="article-status-track" aria-hidden="true">
              <span
                className={`article-status-fill tone-${row.tone}${row.value > 0 ? ' has-value' : ''}`}
                style={{ width: `${percent}%` }}
              />
            </span>
            <span className="article-status-value">{formatNumber(row.value)}</span>
          </li>
        )
      })}
    </ul>
  )
}

export default ArticleStatusBars
