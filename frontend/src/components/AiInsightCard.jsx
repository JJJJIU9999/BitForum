import { useEffect, useState } from 'react'
import { Sparkles } from 'lucide-react'
import { generateInsight, getInsightStatus, getLatestInsight } from '../api/adminApi.js'
import MarkdownText from './MarkdownText.jsx'
import MessageBanner from './MessageBanner.jsx'

/** 生成中的轮询间隔：模型一次生成约数秒，2 秒查一次足够且不至于刷屏。 */
const POLL_INTERVAL_MS = 2000

/**
 * 管理员看板的「AI 运营洞察」卡片（M17）。
 *
 * 与后端的约定（见 AdminAiInsightController）：
 * - `generate` 只受理并立刻返回，**不等模型** —— 所以这里点完要先进入"生成中"状态；
 * - 之后靠 `status` 轮询，一旦不再返回 PENDING 就说明结束（成功或失败都会结束）；
 * - 结束时再取一次 `latest`（它只返回**成功**的报告，失败记录不会显示成"当前洞察"）。
 */
function AiInsightCard() {
  const [report, setReport] = useState(null)
  const [generating, setGenerating] = useState(false)
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState('info')
  const [loaded, setLoaded] = useState(false)

  async function loadLatest() {
    try {
      const result = await getLatestInsight()
      setReport(result.data || null)
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
    } finally {
      setLoaded(true)
    }
  }

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        // 先看有没有正在生成的任务（例如刷新页面时上一次还没跑完）
        const status = await getInsightStatus()
        if (cancelled) return
        if (status.data) {
          setGenerating(true)
          setMessage('正在生成运营洞察，请稍候…')
          return
        }
      } catch {
        // 状态查询失败不阻塞：退化为直接展示已有报告
      }
      if (!cancelled) {
        await loadLatest()
      }
    }
    load()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 生成中：轮询直到任务离开 PENDING
  useEffect(() => {
    if (!generating) return undefined
    const timer = setInterval(async () => {
      try {
        const status = await getInsightStatus()
        if (!status.data) {
          setGenerating(false)
          setMessage('运营洞察已更新。')
          setMessageType('info')
          await loadLatest()
        }
      } catch {
        // 轮询失败就继续等下一轮，不打断用户
      }
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [generating])

  async function handleGenerate() {
    setMessage('')
    try {
      await generateInsight()
      setGenerating(true)
      setMessage('已开始生成，请稍候…')
      setMessageType('info')
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
    }
  }

  return (
    <section className="dashboard-section ai-insight-card">
      <div className="sub-heading">
        <div>
          <p className="eyebrow">AI 运营洞察</p>
          <h3>
            <Sparkles size={18} aria-hidden="true" />
            社区运营分析
          </h3>
        </div>
        <button
          className="ghost-button compact-button icon-text-button"
          type="button"
          disabled={generating}
          onClick={handleGenerate}
        >
          {generating ? '生成中…' : '生成洞察'}
        </button>
      </div>

      <MessageBanner message={message} type={messageType} />

      {!loaded && <p>正在加载运营洞察…</p>}
      {loaded && !report && !generating && (
        <p>暂无运营洞察报告。点击右上角「生成洞察」，由 AI 读取看板数据后给出分析与建议。</p>
      )}
      {report && (
        <>
          {/* 快照说明放在正文之前：报告里的数字是生成那一刻的，与顶部实时统计可能不一致，
              管理员必须先看到这一点，再去读下面的分析。 */}
          <p className="ai-insight-meta">
            数据快照生成于 {(report.createTime || '').replace('T', ' ').slice(0, 16)} · 非实时数据
            {report.model ? ` · 模型 ${report.model}` : ''}
            {report.latencyMs ? ` · 耗时 ${report.latencyMs} ms` : ''}
          </p>
          <MarkdownText content={report.content || ''} />
        </>
      )}
    </section>
  )
}

export default AiInsightCard
