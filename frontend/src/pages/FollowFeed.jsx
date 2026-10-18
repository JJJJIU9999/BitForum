import { useEffect, useRef, useState } from 'react'
import { Link, useOutletContext, useSearchParams } from 'react-router'
import { getFollowFeed } from '../api/topicApi.js'
import PageHeader from '../components/PageHeader.jsx'
import ArticleCard from '../components/ArticleCard.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import EmptyState from '../components/EmptyState.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
import ContentFeedNav from '../components/ContentFeedNav.jsx'
export default function FollowFeed() {
  const { currentUser } = useOutletContext() || {}, [params, setParams] = useSearchParams(), [refresh, setRefresh] = useState(0)
  const type = ['ARTICLE', 'QUESTION'].includes(params.get('type')) ? params.get('type') : ''
  return <section className="content-view topic-view"><PageHeader title="关注动态" description="你关注的话题与作者发布的公开文章和问题，按创建时间排列。" />
    <ContentFeedNav active="following" currentUser={currentUser} />
    <div className="topic-shortcuts"><Link to="/topics">浏览话题</Link><Link to="/me/topics">我的话题</Link></div>
    {currentUser ? <>
    <div className="topic-toolbar"><label>内容类型<select value={type} onChange={(e) => { const next = new URLSearchParams(params); e.target.value ? next.set('type', e.target.value) : next.delete('type'); setParams(next) }}><option value="">全部内容</option><option value="ARTICLE">文章</option><option value="QUESTION">问题</option></select></label><button className="ghost-button" onClick={() => setRefresh((v) => v + 1)}>刷新动态</button></div>
    <FeedBatch key={`${currentUser?.userId}:${type}:${refresh}`} type={type} />
    </> : <div className="feed-discovery"><p>登录后查看你关注的话题与作者动态。</p><Link className="primary-button inline-button" to="/login?redirectTo=%2Ffollowing">登录查看关注</Link></div>}
  </section>
}
function FeedBatch({ type }) {
  const [records, setRecords] = useState([]), [cursor, setCursor] = useState(null), [more, setMore] = useState(false), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const generation = useRef(0), pending = useRef(false)
  async function load(boundary, requestGeneration) {
    if (pending.current) return
    pending.current = true; setLoading(true); setError('')
    try {
      const r = await getFollowFeed({ pageSize: 8, ...(type && { contentType: type }), ...(boundary && { cursor: boundary }) })
      if (generation.current !== requestGeneration) return
      setRecords((old) => [...new Map([...old, ...(r.data.records || [])].map((a) => [a.id, a])).values()]); setCursor(r.data.nextCursor); setMore(r.data.hasMore)
    } catch (e) { if (generation.current === requestGeneration) setError(e.message) }
    finally { if (generation.current === requestGeneration) { pending.current = false; setLoading(false) } }
  }
  useEffect(() => { const value = ++generation.current; load(null, value); return () => { generation.current++; pending.current = false } }, [])
  return <div className="follow-feed-results" aria-busy={loading}>
    <div className="article-list">{records.map((a) => <ArticleCard key={a.id} article={a} />)}</div>
    {loading && <LoadingSpinner text="正在加载关注动态…" />}<MessageBanner message={error} type="error" />
    {error && <button className="ghost-button" disabled={loading} onClick={() => load(cursor, generation.current)}>重试动态</button>}
    {!loading && !error && !records.length && <div className="feed-discovery"><EmptyState message="暂无关注动态。试试关注话题，或在作者主页关注作者。" /><Link to="/topics">发现感兴趣的话题</Link><Link to="/">从最新内容发现作者</Link></div>}
    {!error && more && <button className="ghost-button feed-more" disabled={loading} onClick={() => load(cursor, generation.current)}>加载下一批</button>}
    {!loading && !error && records.length > 0 && !more && <p className="muted" role="status">已读完当前关注动态。</p>}
  </div>
}
