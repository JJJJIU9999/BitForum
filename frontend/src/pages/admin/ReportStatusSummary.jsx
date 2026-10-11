import { formatNumber } from './adminHelpers.js'

/**
 * 举报构成：上部是一条按数量分段的轨道，下部是三行可核对的数字。
 *
 * 「待处理」排在最前并单独着色 —— 管理员真正关心的是积压，
 * 而不是已经处理掉的历史量，但这里只用一层浅色底强调，不做告警式的红闪效果。
 */
const REPORT_SEGMENTS = [
  { key: 'pending', label: '待处理', tone: 'terracotta' },
  { key: 'resolved', label: '已处理', tone: 'forest' },
  { key: 'rejected', label: '已驳回', tone: 'muted' },
]

function ReportStatusSummary({ reportStats = {} }) {
  const rows = REPORT_SEGMENTS.map((segment) => ({
    ...segment,
    value: Math.max(Number(reportStats[segment.key]) || 0, 0),
  }))
  const total = Math.max(Number(reportStats.total) || 0, 0)

  return (
    <div className="report-summary">
      <div className="report-summary-track" aria-hidden="true">
        {rows.map((row) => (
          <span
            className={`report-summary-segment tone-${row.tone}`}
            key={row.key}
            style={{ flexGrow: row.value }}
          />
        ))}
      </div>
      <ul className="report-summary-list">
        {rows.map((row) => {
          const percent = total > 0 ? (row.value / total) * 100 : 0
          return (
            <li
              className={`report-summary-item${row.key === 'pending' ? ' is-pending' : ''}`}
              key={row.key}
            >
              <span className="report-summary-label">{row.label}</span>
              <span className="report-summary-value">{`${formatNumber(row.value)} · ${percent.toFixed(1)}%`}</span>
            </li>
          )
        })}
      </ul>
      <p className="report-summary-foot">
        {`举报总数 ${formatNumber(total)} · 近 7 天举报 ${formatNumber(reportStats.last7DaysCreated)}`}
      </p>
    </div>
  )
}

export default ReportStatusSummary
