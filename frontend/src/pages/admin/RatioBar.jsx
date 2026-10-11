import { formatNumber } from './adminHelpers.js'

/**
 * 比例条：用一条横向填充表达「部分 / 整体」，同时给出精确数量和百分比。
 *
 * 这里刻意不用环形图：管理员需要的是能核对的 `43 / 44 · 97.7%`，
 * 而不是一个要靠估读角度的图形。总数为 0 时百分比固定为 0.0%，不会出现 NaN。
 */
function RatioBar({ label, value, total, tone = 'forest' }) {
  const safeTotal = Math.max(Number(total) || 0, 0)
  const safeValue = Math.max(Number(value) || 0, 0)
  const percent = safeTotal > 0 ? Math.min((safeValue / safeTotal) * 100, 100) : 0
  const figure = `${formatNumber(safeValue)} / ${formatNumber(safeTotal)} · ${percent.toFixed(1)}%`

  return (
    <li className="ratio-bar">
      <div className="ratio-bar-head">
        <span className="ratio-bar-label">{label}</span>
        <span className="ratio-bar-figure">{figure}</span>
      </div>
      <div className="ratio-bar-track" aria-hidden="true">
        <span
          className={`ratio-bar-fill tone-${tone}${safeValue > 0 ? ' has-value' : ''}`}
          style={{ width: `${percent}%` }}
        />
      </div>
    </li>
  )
}

export default RatioBar
