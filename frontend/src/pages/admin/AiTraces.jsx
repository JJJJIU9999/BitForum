import { useEffect, useState } from 'react'
import {
  getAiTraceDetail, getAiUsageOverview, pageAiTraces,
} from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import Pagination from '../../components/Pagination.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import { formatDate, formatNumber } from './adminHelpers.js'

// AI 执行轨迹与用量（M18）。
//
// 这一页是 M18 的核心交付物：把"某一次 AI 调用到底做了什么"完整摊开 ——
// 路由到哪个 Agent、检索命中多少、模型给了什么、调了哪些工具、每步多久、花了多少 token。
//
// 两点刻意设计：
//   1. **用量与轨迹放在同一页**：它们回答的是同一个问题的两面 ——
//      "这次调用做了什么"（轨迹）与"这些调用一共花了多少"（用量）。
//   2. 列表接口不带 steps，展开某一行时才请求详情：轨迹表会随使用量增长，
//      列表页每行都带 JSON 会让响应迅速膨胀（后端也是同一条约定）。
const SCENE_LABELS = {
  CHAT: '问答助手',
  MODERATION: '内容审核',
  INSIGHT: '运营洞察',
  RECOMMEND: '智能推荐',
}

const STATUS_LABELS = {
  RUNNING: '进行中',
  SUCCESS: '成功',
  DEGRADED: '降级',
  FAILED: '失败',
}

const STEP_LABELS = {
  ROUTE: '路由',
  RETRIEVE: '知识库检索',
  LLM_CALL: '模型调用',
  TOOL_CALL: '工具调用',
  RECALL: '多路召回',
  FUSION: '融合排序',
  PERSIST: '落库',
  ASYNC: '异步交接',
  DEGRADE: '降级',
}

function AiTraces() {
  const [traces, setTraces] = useState([])
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [pageNum, setPageNum] = useState(1)
  const [scene, setScene] = useState('')
  const [status, setStatus] = useState('')
  const [usage, setUsage] = useState(null)
  const [usageDays, setUsageDays] = useState(7)
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [expandedTraceId, setExpandedTraceId] = useState(null)
  const [detail, setDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)

  useEffect(() => {
    async function load() {
      setLoading(true)
      setMessage('')
      try {
        const params = { pageNum, pageSize: 10 }
        if (scene) params.scene = scene
        if (status) params.status = status
        const result = await pageAiTraces(params)
        setTraces(result.data.records || [])
        setPageInfo({ current: result.data.current, pages: result.data.pages, total: result.data.total })
      } catch (error) {
        setTraces([])
        setMessage(error.message)
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [pageNum, scene, status, refreshKey])

  useEffect(() => {
    async function loadUsage() {
      try {
        const result = await getAiUsageOverview(usageDays)
        setUsage(result.data)
      } catch (error) {
        // 用量拉取失败不影响轨迹列表：两块数据各自独立可用
        setUsage(null)
        setMessage(error.message)
      }
    }
    loadUsage()
  }, [usageDays, refreshKey])

  async function toggleDetail(trace) {
    if (expandedTraceId === trace.traceId) {
      setExpandedTraceId(null)
      setDetail(null)
      return
    }
    setExpandedTraceId(trace.traceId)
    setDetail(null)
    setDetailLoading(true)
    try {
      const result = await getAiTraceDetail(trace.traceId)
      setDetail(result.data)
    } catch (error) {
      setMessage(error.message)
      setExpandedTraceId(null)
    } finally {
      setDetailLoading(false)
    }
  }

  const totals = usage?.totals

  return (
    <div className="content-view admin-view">
      <PageHeader
        title="AI 执行轨迹与用量"
        description="每一次 AI 调用都留下完整链路：路由、检索、工具调用、每步耗时与 token。AI 不可用时这里能看到降级原因。"
        actions={<button className="ghost-button" type="button" onClick={() => setRefreshKey((key) => key + 1)} disabled={loading}>刷新</button>}
      />

      <MessageBanner message={message} />

      <section className="trace-panel" aria-label="AI 用量概览">
        <div className="trace-panel-head">
          <div>
            <h2>用量概览</h2>
            <p className="muted">
              最近 {usage?.days ?? usageDays} 天的调用量与 token 消耗；成本按配置单价估算。
            </p>
          </div>
          <div className="trace-range" role="group" aria-label="统计区间">
            {[1, 7, 30].map((days) => (
              <button
                className={usageDays === days ? 'chip is-active' : 'chip'}
                key={days}
                type="button"
                onClick={() => setUsageDays(days)}
              >
                近 {days} 天
              </button>
            ))}
          </div>
        </div>

        {totals && (
          <>
            <div className="metric-grid">
              <div className="metric-card"><span>调用次数</span><strong>{formatNumber(totals.calls)}</strong></div>
              <div className="metric-card"><span>Token 合计</span><strong>{formatNumber(totals.totalTokens)}</strong></div>
              <div className="metric-card"><span>估算成本（元）</span><strong>{Number(totals.cost || 0).toFixed(4)}</strong></div>
              <div className="metric-card"><span>降级 / 失败</span><strong>{formatNumber(totals.abnormalCalls)}</strong></div>
            </div>

            <div className="trace-columns">
              <div>
                <h3>按 Agent</h3>
                {(usage?.byAgent || []).length === 0
                  ? <p className="muted">这个区间还没有记录。</p>
                  : (
                    <table className="data-table">
                      <thead><tr><th>Agent</th><th>调用</th><th>Token</th><th>成本（元）</th></tr></thead>
                      <tbody>
                        {usage.byAgent.map((item) => (
                          <tr key={item.agentType}>
                            <td>{SCENE_LABELS[item.agentType] || item.agentType}</td>
                            <td>{formatNumber(item.calls)}</td>
                            <td>{formatNumber(item.totalTokens)}</td>
                            <td>{Number(item.cost || 0).toFixed(4)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
              </div>
              <div>
                <h3>按天</h3>
                {(usage?.daily || []).length === 0
                  ? <p className="muted">这个区间还没有记录。</p>
                  : (
                    <table className="data-table">
                      <thead><tr><th>日期</th><th>调用</th><th>Token</th></tr></thead>
                      <tbody>
                        {usage.daily.map((item) => (
                          <tr key={item.statDate}>
                            <td>{item.statDate}</td>
                            <td>{formatNumber(item.calls)}</td>
                            <td>{formatNumber(item.totalTokens)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
              </div>
            </div>

            <p className="muted trace-price-note">
              计价口径：输入 {usage.inputPricePerMillion} 元 / 百万 token、输出 {usage.outputPricePerMillion} 元 / 百万 token。
              {usage.priceNote}
            </p>
          </>
        )}
      </section>

      <section className="trace-panel" aria-label="执行轨迹列表">
        <div className="trace-panel-head">
          <div>
            <h2>执行轨迹</h2>
            <p className="muted">列表只显示概要，展开某一行查看它每一步做了什么。</p>
          </div>
          <div className="trace-filters">
            <label>
              <span className="sr-only">场景</span>
              <select value={scene} onChange={(event) => { setScene(event.target.value); setPageNum(1) }}>
                <option value="">全部场景</option>
                {Object.entries(SCENE_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </label>
            <label>
              <span className="sr-only">状态</span>
              <select value={status} onChange={(event) => { setStatus(event.target.value); setPageNum(1) }}>
                <option value="">全部状态</option>
                {Object.entries(STATUS_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </label>
          </div>
        </div>

        {loading && traces.length === 0 && <LoadingSpinner />}

        {!loading && traces.length === 0 && (
          <p className="muted">还没有执行轨迹。AI 助手提问、内容审核、运营洞察或推荐理由生成后，这里会出现记录。</p>
        )}

        {traces.length > 0 && (
          <ul className="trace-list">
            {traces.map((trace) => (
              <li key={trace.traceId} className="trace-item">
                <button
                  className="trace-summary"
                  type="button"
                  aria-expanded={expandedTraceId === trace.traceId}
                  onClick={() => toggleDetail(trace)}
                >
                  <span className={`status-badge status-${(trace.status || '').toLowerCase()}`}>
                    {STATUS_LABELS[trace.status] || trace.status}
                  </span>
                  <span className="trace-scene">{SCENE_LABELS[trace.scene] || trace.scene}</span>
                  <span className="trace-route">{trace.route || '—'}</span>
                  <span className="trace-meta">
                    {trace.stepCount} 步 · {formatNumber(trace.totalTokens)} token · {formatNumber(trace.latencyMs)} ms
                  </span>
                  <span className="trace-time">{formatDate(trace.createTime)}</span>
                </button>

                {trace.degradeReason && (
                  <p className="trace-degrade">
                    降级原因 <code>{trace.degradeReason}</code>：{trace.message}
                  </p>
                )}

                {expandedTraceId === trace.traceId && (
                  <div className="trace-detail">
                    {detailLoading && <LoadingSpinner />}
                    {detail && (
                      <>
                        <p className="muted">
                          traceId <code>{detail.traceId}</code>
                          {detail.userId ? ` · 用户 ${detail.userId}` : ''}
                          {detail.model ? ` · 模型 ${detail.model}` : ''}
                        </p>
                        <ol className="trace-steps">
                          {(detail.steps || []).map((step) => (
                            <li key={step.seq} className={`trace-step step-${(step.type || '').toLowerCase()}`}>
                              <span className="trace-step-index">{step.seq}</span>
                              <div>
                                <p className="trace-step-name">
                                  <strong>{STEP_LABELS[step.type] || step.type}</strong>
                                  <span className="trace-step-detail-name">{step.name}</span>
                                </p>
                                <p className="trace-step-detail">{step.detail || '—'}</p>
                              </div>
                              <span className="trace-step-cost">
                                {formatNumber(step.latencyMs)} ms
                                {step.totalTokens ? ` · ${formatNumber(step.totalTokens)} token` : ''}
                              </span>
                            </li>
                          ))}
                        </ol>
                      </>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}

        {pageInfo.total > 0 && (
          <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />
        )}
      </section>
    </div>
  )
}

export default AiTraces
