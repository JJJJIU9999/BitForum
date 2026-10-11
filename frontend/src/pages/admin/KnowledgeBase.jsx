import { useEffect, useState } from 'react'
import { Database, RefreshCw } from 'lucide-react'
import { getKbStats, rebuildKnowledgeBase } from '../../api/adminApi.js'
import PageHeader from '../../components/PageHeader.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'

// AI 知识库管理页（M15）。
// 知识库是 RAG 的数据基础：已发布文章被切成片段并向量化存入 Redis，
// AI 助手回答时检索这些片段并在回答下方给出引用来源。
function KnowledgeBase() {
  const [stats, setStats] = useState(null)
  const [loading, setLoading] = useState(true)
  const [rebuilding, setRebuilding] = useState(false)
  const [message, setMessage] = useState('')

  useEffect(() => {
    loadStats()
  }, [])

  async function loadStats() {
    setLoading(true)
    try {
      const result = await getKbStats()
      setStats(result.data)
    } catch (error) {
      setMessage(error.message)
    } finally {
      setLoading(false)
    }
  }

  async function handleRebuild() {
    setRebuilding(true)
    setMessage('')
    try {
      const result = await rebuildKnowledgeBase()
      const data = result.data
      setMessage(
        `重建完成：已发布 ${data.publishedArticles} 篇 → 更新 ${data.indexed}、跳过 ${data.skipped}、` +
          `移除 ${data.removed}、失败 ${data.failed}，耗时 ${data.elapsedMillis} ms。`,
      )
      await loadStats()
    } catch (error) {
      setMessage(error.message)
    } finally {
      setRebuilding(false)
    }
  }

  const notIndexed = stats ? stats.publishedArticles - stats.indexedDocuments : 0
  const hasProblem = stats ? stats.failedDocuments > 0 || notIndexed > 0 : false
  const messageType = message.startsWith('重建完成') ? 'info' : 'error'

  return (
    <div className="content-view">
      <PageHeader
        title="AI 知识库"
        description="已发布文章会被切分成片段并向量化，供 AI 助手检索后在回答中引用。审核通过、下架或删除文章时会自动更新。"
        actions={
          <button className="primary-button" type="button" onClick={handleRebuild} disabled={rebuilding}>
            <RefreshCw size={16} aria-hidden="true" />
            {rebuilding ? '重建中…' : '全量重建'}
          </button>
        }
      />

      <MessageBanner message={message} type={messageType} />

      {loading && !stats ? (
        <LoadingSpinner text="正在读取知识库状态…" />
      ) : (
        <>
          <div className="kb-stat-grid">
            <article className="kb-stat-card">
              <span className="kb-stat-label">已发布文章</span>
              <strong>{stats?.publishedArticles ?? 0}</strong>
              <span className="kb-stat-hint">知识库的候选范围</span>
            </article>
            <article className="kb-stat-card">
              <span className="kb-stat-label">已入库文档</span>
              <strong>{stats?.indexedDocuments ?? 0}</strong>
              <span className="kb-stat-hint">可被 AI 检索到</span>
            </article>
            <article className="kb-stat-card">
              <span className="kb-stat-label">待处理</span>
              <strong>{stats?.pendingDocuments ?? 0}</strong>
              <span className="kb-stat-hint">排队等待索引</span>
            </article>
            <article className={`kb-stat-card ${stats?.failedDocuments > 0 ? 'is-warning' : ''}`}>
              <span className="kb-stat-label">失败</span>
              <strong>{stats?.failedDocuments ?? 0}</strong>
              <span className="kb-stat-hint">需要重建修复</span>
            </article>
            <article className="kb-stat-card">
              <span className="kb-stat-label">知识片段</span>
              <strong>{stats?.chunks ?? 0}</strong>
              <span className="kb-stat-hint">实际参与检索的向量数</span>
            </article>
          </div>

          <p className="kb-model-line">
            <Database size={15} aria-hidden="true" />
            当前嵌入模型：<strong>{stats?.embeddingModel || '未知'}</strong>
            <span className="kb-model-hint">（更换模型后必须全量重建，向量维度与语义空间都会变化）</span>
          </p>

          {hasProblem && (
            <p className="kb-warning">
              有 {Math.max(notIndexed, 0)} 篇已发布文章尚未入库
              {stats?.failedDocuments > 0 ? `，另有 ${stats.failedDocuments} 篇索引失败` : ''}
              。点击「全量重建」可补齐。
            </p>
          )}
        </>
      )}
    </div>
  )
}

export default KnowledgeBase
