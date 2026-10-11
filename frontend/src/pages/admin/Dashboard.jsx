import { useEffect, useState } from 'react'
import { useOutletContext } from 'react-router'
import { checkAdminHealth, getAdminDashboardSummary } from '../../api/adminApi.js'
import AiInsightCard from '../../components/AiInsightCard.jsx'
import PageHeader from '../../components/PageHeader.jsx'
import LoadingSpinner from '../../components/LoadingSpinner.jsx'
import MessageBanner from '../../components/MessageBanner.jsx'
import ArticleStatusBars from './ArticleStatusBars.jsx'
import RatioBar from './RatioBar.jsx'
import ReportStatusSummary from './ReportStatusSummary.jsx'
import { formatNumber } from './adminHelpers.js'

function Dashboard() {
  const { currentUser } = useOutletContext()
  const [dashboardSummary, setDashboardSummary] = useState(null)
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    async function load() {
      if (!currentUser) return
      setLoading(true)
      setMessage('')
      try {
        await checkAdminHealth()
        const result = await getAdminDashboardSummary()
        setDashboardSummary(result.data)
      } catch (error) {
        setMessage(error.message)
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [currentUser])

  if (loading) return <LoadingSpinner text="加载看板数据..." />
  if (message) return <MessageBanner message={message} type="error" />
  if (!dashboardSummary) return <MessageBanner message="暂无看板数据。" type="error" />

  const {
    userStats = {},
    articleStats = {},
    commentStats = {},
    categoryStats = {},
    favoriteStats = {},
    notificationStats = {},
    reportStats = {},
    hotArticles = [],
  } = dashboardSummary

  // 顶部四张 KPI 卡片：只放精确数字和一句辅助说明，图表一律放到下面 —— 数字和图形混在一起会两头都不好读。
  const headlineStats = [
    { title: '用户总数', value: userStats.total, detail: `启用 ${formatNumber(userStats.enabled)} / 禁用 ${formatNumber(userStats.disabled)}` },
    { title: '文章总数', value: articleStats.total, detail: `今日新增 ${formatNumber(articleStats.todayCreated)}` },
    { title: '评论总数', value: commentStats.total, detail: `近 7 天 ${formatNumber(commentStats.last7DaysCreated)}` },
    { title: '待处理举报', value: reportStats.pending, detail: `举报总数 ${formatNumber(reportStats.total)}` },
  ]

  // 内容与互动：这几项量纲不同（评论 / 收藏），只保留紧凑数字摘要，不做成同一张柱状图。
  const contentStats = [
    { label: '今日评论', value: commentStats.todayCreated },
    { label: '近 7 天评论', value: commentStats.last7DaysCreated },
    { label: '收藏总数', value: favoriteStats.total },
  ]

  return (
    <div className="content-view admin-view">
      <PageHeader title="数据看板" description="平台核心数据概览与统计。" />

      <div className="dashboard-board">
        <div className="dashboard-stat-grid">
          {headlineStats.map((s) => (
            <article className="dashboard-stat-card" key={s.title}>
              <span>{s.title}</span>
              <strong>{formatNumber(s.value)}</strong>
              {s.detail && <small>{s.detail}</small>}
            </article>
          ))}
        </div>

        {/* 主次双栏：文章状态是描述性最强的图，给更宽的一栏；用户/板块比例条放窄栏。 */}
        <div className="dashboard-section-grid">
          <section className="dashboard-section">
            <div className="sub-heading">
              <h3>文章状态分布</h3>
              {/* 「近 7 天新增」是时间指标，只作为标题区的次要标签，不进入下方条形图 */}
              <span className="dashboard-inline-metric">
                近 7 天新增 {formatNumber(articleStats.last7DaysCreated)}
              </span>
            </div>
            <ArticleStatusBars articleStats={articleStats} />
            <p className="dashboard-section-note">
              共 {formatNumber(articleStats.total)} 篇文章，条形长度按五种状态中的最大值等比缩放。
            </p>
          </section>

          <section className="dashboard-section">
            <div className="sub-heading">
              <h3>用户与板块状态</h3>
              <span>{formatNumber(userStats.total)} 位用户</span>
            </div>

            <h4 className="dashboard-subsection-title">用户角色</h4>
            <ul className="ratio-bar-list">
              <RatioBar label="普通用户" value={userStats.normalUsers} total={userStats.total} tone="forest" />
              <RatioBar label="管理员" value={userStats.admins} total={userStats.total} tone="terracotta" />
            </ul>

            <h4 className="dashboard-subsection-title">账号状态</h4>
            <ul className="ratio-bar-list">
              <RatioBar label="启用用户" value={userStats.enabled} total={userStats.total} tone="forest" />
              <RatioBar label="禁用用户" value={userStats.disabled} total={userStats.total} tone="muted" />
            </ul>

            <h4 className="dashboard-subsection-title">板块状态</h4>
            <ul className="ratio-bar-list">
              <RatioBar label="启用板块" value={categoryStats.enabled} total={categoryStats.total} tone="forest" />
              <RatioBar label="禁用板块" value={categoryStats.disabled} total={categoryStats.total} tone="muted" />
            </ul>
          </section>
        </div>

        <div className="dashboard-section-grid">
          <section className="dashboard-section">
            <div className="sub-heading">
              <h3>内容与互动</h3>
              <span>评论与收藏</span>
            </div>
            <div className="dashboard-mini-grid">
              {contentStats.map((m) => (
                <div className="dashboard-mini-stat" key={m.label}>
                  <span>{m.label}</span>
                  <strong>{formatNumber(m.value)}</strong>
                </div>
              ))}
            </div>
            <h4 className="dashboard-subsection-title">通知未读率</h4>
            <ul className="ratio-bar-list">
              <RatioBar
                label="未读通知"
                value={notificationStats.unread}
                total={notificationStats.total}
                tone="terracotta"
              />
            </ul>
          </section>

          <section className="dashboard-section">
            <div className="sub-heading">
              <h3>举报治理</h3>
              <span>{formatNumber(reportStats.total)} 条举报</span>
            </div>
            <ReportStatusSummary reportStats={reportStats} />
          </section>
        </div>

        {/* M17：AI 运营洞察 —— 由管理员主动触发生成，异步返回后展示 */}
        <AiInsightCard />

        <section className="dashboard-section">
          <div className="sub-heading">
            <h3>热门文章 Top 10</h3>
            <span>{hotArticles.length} 条</span>
          </div>
          <div className="dashboard-hot-list">
            {hotArticles.length === 0 ? (
              <p>暂无热门文章。</p>
            ) : (
              hotArticles.map((article, index) => (
                <div className="dashboard-hot-item" key={article.articleId}>
                  <span>{String(index + 1).padStart(2, '0')}</span>
                  <strong title={article.title}>{article.title}</strong>
                  <div className="dashboard-hot-metrics">
                    <small>热度 {formatNumber(article.hotScore)}</small>
                    <small>浏览 {formatNumber(article.viewCount)}</small>
                    <small>点赞 {formatNumber(article.likeCount)}</small>
                  </div>
                </div>
              ))
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

export default Dashboard
