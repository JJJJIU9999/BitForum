import { useEffect, useState } from 'react'
import {
  markModerationHandled, pageModerationRecords, submitModerationFeedback,
} from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import Pagination from '../../components/Pagination.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import { formatDate } from './adminHelpers.js'

// AI 审核记录管理页（M16）。
//
// 展示 AI 对文章/评论的审核判断，并提供人工反馈闭环。
// 注意这里的定位：AI 只给建议，页面上没有"按 AI 判断直接驳回内容"的按钮 ——
// 驳回/删除始终走原有的文章审核流程由管理员决定（见 task_plan.md 的 M16 实施决策）。
const DECISION_LABELS = { PASS: '放行', REVIEW: '转人工', REJECT: '拒绝' }
const ACTION_LABELS = {
  AUTO_APPROVED: '系统自动放行',
  NO_ACTION: '无需处理',
  PENDING_REVIEW: '待人工复核',
  HIGH_PRIORITY_REVIEW: '高优先级待复核',
  ANALYSIS_FAILED: '分析失败转人工',
}
const TARGET_LABELS = { ARTICLE: '文章', COMMENT: '评论' }
const DIMENSION_LABELS = {
  harmful: '有害内容', promotion: '广告推广', fraud: '诈骗钓鱼',
  spam: '灌水刷屏', sensitive: '敏感信息', none: '无',
}

function ModerationRecords() {
  const [records, setRecords] = useState([])
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [pageNum, setPageNum] = useState(1)
  const [pendingOnly, setPendingOnly] = useState(true)
  const [decision, setDecision] = useState('')
  const [targetType, setTargetType] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [expandedId, setExpandedId] = useState(null)

  useEffect(() => {
    async function load() {
      setLoading(true)
      setMessage('')
      try {
        const params = { pageNum, pageSize: 10, pendingOnly }
        if (decision) params.decision = decision
        if (targetType) params.targetType = targetType
        const result = await pageModerationRecords(params)
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
  }, [pageNum, pendingOnly, decision, targetType, refreshKey])

  function doAction(action, okMessage) {
    setLoading(true)
    setMessage('')
    action()
      .then(() => { setMessage(okMessage); setRefreshKey((key) => key + 1) })
      .catch((error) => setMessage(error.message))
      .finally(() => setLoading(false))
  }

  function feedback(record, value) {
    doAction(
      () => submitModerationFeedback(record.id, value),
      `#${record.id} 已标记为「${value === 'CORRECT' ? 'AI 判断正确' : 'AI 判断错误'}」。`,
    )
  }

  return (
    <div className="content-view admin-view">
      <PageHeader
        title="AI 审核记录"
        description="AI 对文章与评论的风险判断。它只提供建议：放行可按配置自动化，驳回与删除始终由管理员决定。"
        badge={`${pageInfo.total} 条`}
      />

      <div className="filter-row">
        <label className="filter-check">
          <input
            type="checkbox"
            checked={pendingOnly}
            onChange={(event) => { setPendingOnly(event.target.checked); setPageNum(1) }}
          />
          只看待处理
        </label>
        <select value={decision} onChange={(event) => { setDecision(event.target.value); setPageNum(1) }}>
          <option value="">全部判断</option>
          <option value="REJECT">拒绝（REJECT）</option>
          <option value="REVIEW">转人工（REVIEW）</option>
          <option value="PASS">放行（PASS）</option>
        </select>
        <select value={targetType} onChange={(event) => { setTargetType(event.target.value); setPageNum(1) }}>
          <option value="">全部对象</option>
          <option value="ARTICLE">文章</option>
          <option value="COMMENT">评论</option>
        </select>
      </div>

      {loading && <LoadingSpinner text="加载中..." />}
      <MessageBanner message={message} type={message.includes('已标记') ? 'info' : 'error'} />

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>对象</th>
              <th>内容摘要</th>
              <th>AI 判断</th>
              <th>风险</th>
              <th>系统动作</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {records.map((record) => (
              <tr key={record.id}>
                <td className="mono-cell">#{record.id}</td>
                <td className="mono-cell">
                  {TARGET_LABELS[record.targetType] || record.targetType} #{record.targetId}
                </td>
                <td className="truncate-cell">
                  {record.targetPreview}
                  {record.summary && (
                    <button
                      type="button"
                      className="text-button compact-button"
                      onClick={() => setExpandedId(expandedId === record.id ? null : record.id)}
                    >
                      {expandedId === record.id ? '收起理由' : '查看理由'}
                    </button>
                  )}
                  {expandedId === record.id && (
                    <div className="moderation-detail">
                      <p>{record.summary}</p>
                      <ul>
                        <li>有害内容 {Number(record.harmfulScore ?? 0).toFixed(2)}：{record.harmfulReason || '-'}</li>
                        <li>广告推广 {Number(record.promotionScore ?? 0).toFixed(2)}：{record.promotionReason || '-'}</li>
                        <li>诈骗钓鱼 {Number(record.fraudScore ?? 0).toFixed(2)}：{record.fraudReason || '-'}</li>
                        <li>灌水刷屏 {Number(record.spamScore ?? 0).toFixed(2)}：{record.spamReason || '-'}</li>
                        <li>敏感信息 {Number(record.sensitiveScore ?? 0).toFixed(2)}：{record.sensitiveReason || '-'}</li>
                      </ul>
                      <p className="muted">动作依据：{record.actionReason || '-'}（模型 {record.model || '-'}，耗时 {record.latencyMs ?? '-'} ms）</p>
                    </div>
                  )}
                </td>
                <td>
                  <span className={`decision-badge decision-${String(record.decision || '').toLowerCase()}`}>
                    {DECISION_LABELS[record.decision] || record.decision}
                  </span>
                  <div className="muted">置信 {Number(record.confidence ?? 0).toFixed(2)}</div>
                  {record.feedback && (
                    <div className="muted">
                      人工：{record.feedback === 'CORRECT' ? '判断正确' : '判断错误'}
                    </div>
                  )}
                </td>
                <td>
                  <strong>{Number(record.riskScore ?? 0).toFixed(2)}</strong>
                  <div className="muted">{DIMENSION_LABELS[record.maxDimension] || record.maxDimension}</div>
                </td>
                <td>
                  {ACTION_LABELS[record.action] || record.action}
                  <div className="muted">{record.handled ? '已处理' : '待处理'}</div>
                </td>
                <td className="mono-cell">{formatDate(record.createTime)}</td>
                <td>
                  <div className="multi-action-cell">
                    <button className="ghost-button compact-button" disabled={loading}
                      onClick={() => feedback(record, 'CORRECT')}>判断正确</button>
                    <button className="ghost-button compact-button" disabled={loading}
                      onClick={() => feedback(record, 'WRONG')}>判断错误</button>
                    {!record.handled && (
                      <button className="primary-button compact-button" disabled={loading}
                        onClick={() => doAction(() => markModerationHandled(record.id), `#${record.id} 已标记为处理完毕。`)}>
                        标记已处理
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {records.length === 0 && (
              <tr>
                <td className="empty-cell" colSpan="8">
                  {pendingOnly ? '当前没有待处理的审核记录。' : '暂无审核记录。'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}
    </div>
  )
}

export default ModerationRecords
