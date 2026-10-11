import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useOutletContext, useParams, useSearchParams } from 'react-router'
import { followTopic, getTopic, pageTopicContents, unfollowTopic } from '../api/topicApi.js'
import PageHeader from '../components/PageHeader.jsx'
import ArticleCard from '../components/ArticleCard.jsx'
import Pagination from '../components/Pagination.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import EmptyState from '../components/EmptyState.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
export default function TopicDetail() {
  const { categoryId } = useParams(), { currentUser } = useOutletContext()
  return <TopicContent key={`${categoryId}:${currentUser?.userId || 'anonymous'}`} categoryId={categoryId} currentUser={currentUser} />
}
function TopicContent({ categoryId, currentUser }) {
  const navigate = useNavigate(), [params, setParams] = useSearchParams()
  const [topic, setTopic] = useState(null), [topicLoading, setTopicLoading] = useState(true), [topicError, setTopicError] = useState(''), [topicRetry, setTopicRetry] = useState(0)
  const [records, setRecords] = useState([]), [page, setPage] = useState({ current: 1, total: 0, pages: 1 }), [loading, setLoading] = useState(true), [error, setError] = useState(''), [listRetry, setListRetry] = useState(0)
  const [busy, setBusy] = useState(false), [actionError, setActionError] = useState(''), [keyword, setKeyword] = useState(params.get('q') || '')
  const actionGeneration = useRef(0), busyRef = useRef(false)
  const type = params.get('type') || '', q = params.get('q') || '', pageNum = Math.max(1, Number(params.get('page')) || 1)
  useEffect(() => { const generation = ++actionGeneration.current; return () => { if (actionGeneration.current === generation) actionGeneration.current++ } }, [])
  useEffect(() => {
    let active = true; setTopicLoading(true); setTopicError(''); setTopic(null)
    getTopic(categoryId).then((r) => { if (active) setTopic(r.data) }).catch((e) => { if (active) setTopicError(e.message) }).finally(() => { if (active) setTopicLoading(false) })
    return () => { active = false }
  }, [categoryId, topicRetry])
  useEffect(() => { setKeyword(q) }, [q])
  useEffect(() => {
    let active = true; setLoading(true); setError(''); setRecords([]); setPage({ current: pageNum, total: 0, pages: 1 })
    pageTopicContents(categoryId, { pageNum, pageSize: 8, ...(type && { contentType: type }), ...(q && { keyword: q }) }).then((r) => { if (active) { setRecords(r.data.records || []); setPage(r.data) } }).catch((e) => { if (active) setError(e.message) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [categoryId, pageNum, type, q, listRetry])
  function query(changes) { const next = new URLSearchParams(params); Object.entries(changes).forEach(([k, v]) => v ? next.set(k, String(v)) : next.delete(k)); setParams(next) }
  async function toggle() {
    if (!currentUser) { navigate(`/login?redirectTo=${encodeURIComponent(`/topics/${categoryId}${params.size ? `?${params}` : ''}`)}`); return }
    if (busyRef.current) return
    const generation = actionGeneration.current; busyRef.current = true; setBusy(true); setActionError('')
    try { const r = await (topic.followedByCurrentUser ? unfollowTopic : followTopic)(categoryId); if (generation === actionGeneration.current) setTopic((t) => ({ ...t, followedByCurrentUser: r.data.followedByCurrentUser })) }
    catch (e) { if (generation === actionGeneration.current) setActionError(e.message) }
    finally { if (generation === actionGeneration.current) { busyRef.current = false; setBusy(false) } }
  }
  return <section className="content-view topic-view"><PageHeader title="话题详情" description="关注一个话题，持续阅读其中的公开讨论。" actions={<Link className="ghost-button inline-button" to="/topics">全部话题</Link>} />
    {topicLoading && <LoadingSpinner text="正在打开话题…" />}<MessageBanner message={topicError} type="error" />
    {topicError && <button className="ghost-button" onClick={() => setTopicRetry((v) => v + 1)}>重试话题</button>}
    {topic && <section className="profile-panel topic-heading"><div><h2>{topic.name}</h2><p>{topic.description || '欢迎分享经验与解法。'}</p></div><button className={topic.followedByCurrentUser ? 'ghost-button' : 'primary-button'} disabled={busy} onClick={toggle}>{busy ? '处理中…' : topic.followedByCurrentUser ? '取消关注话题' : '关注话题'}</button></section>}
    <MessageBanner message={actionError} type="error" />
    <form className="topic-toolbar" onSubmit={(e) => { e.preventDefault(); query({ q: keyword.trim(), page: '' }) }}>
      <label>内容类型<select value={type} onChange={(e) => query({ type: e.target.value, page: '' })}><option value="">全部内容</option><option value="ARTICLE">文章</option><option value="QUESTION">问题</option></select></label>
      <label>搜索内容<input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索标题或正文" /></label><button className="primary-button">搜索</button>
    </form>
    {loading && <LoadingSpinner text="正在加载话题内容…" />}<MessageBanner message={error} type="error" />
    {error && <button className="ghost-button" onClick={() => setListRetry((v) => v + 1)}>重试内容</button>}
    <div className="article-list">{records.map((a) => <ArticleCard key={a.id} article={a} />)}</div>
    {!loading && !error && !records.length && <EmptyState message="还没有符合条件的公开内容。" />}
    {page.total > 0 && <Pagination pageInfo={page} loading={loading} onPageChange={(n) => query({ page: n })} />}
  </section>
}
