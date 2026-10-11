import { useEffect, useRef, useState } from 'react'
import { Link, useOutletContext } from 'react-router'
import { pageFollowedTopics, unfollowTopic } from '../api/topicApi.js'
import AccountNav from '../components/AccountNav.jsx'
import PageHeader from '../components/PageHeader.jsx'
import Pagination from '../components/Pagination.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import EmptyState from '../components/EmptyState.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
export default function MyTopics() {
  const { currentUser } = useOutletContext()
  return <TopicAccount key={currentUser?.userId} />
}
function TopicAccount() {
  const [pageNum, setPageNum] = useState(1), [retry, setRetry] = useState(0), [page, setPage] = useState({ records: [], current: 1, pages: 1, total: 0 }), [loading, setLoading] = useState(true), [error, setError] = useState(''), [actionError, setActionError] = useState(''), [busyId, setBusyId] = useState(null)
  const actionGeneration = useRef(0), pending = useRef(false)
  useEffect(() => { const value = ++actionGeneration.current; return () => { if (actionGeneration.current === value) actionGeneration.current++ } }, [])
  useEffect(() => { let active = true; setLoading(true); setError(''); setPage({ records: [], current: pageNum, pages: 1, total: 0 }); pageFollowedTopics({ pageNum, pageSize: 8 }).then((r) => { if (active) setPage(r.data) }).catch((e) => { if (active) setError(e.message) }).finally(() => { if (active) setLoading(false) }); return () => { active = false } }, [pageNum, retry])
  async function cancel(id) {
    if (pending.current) return
    const value = actionGeneration.current; pending.current = true; setBusyId(id); setActionError('')
    try { await unfollowTopic(id); if (value === actionGeneration.current) { if (page.records.length === 1 && pageNum > 1) setPageNum(pageNum - 1); else setRetry((n) => n + 1) } }
    catch (e) { if (value === actionGeneration.current) setActionError(e.message) }
    finally { if (value === actionGeneration.current) { pending.current = false; setBusyId(null) } }
  }
  return <div className="account-layout topic-view"><AccountNav /><section className="account-main"><PageHeader title="我的话题" description="管理你已关注的话题。停用话题保留关注关系，可随时取消。" actions={<Link className="ghost-button inline-button" to="/topics">浏览话题</Link>} />
    {loading && <LoadingSpinner text="正在加载我的话题…" />}<MessageBanner message={error} type="error" /><MessageBanner message={actionError} type="error" />
    {error && <button className="ghost-button" onClick={() => setRetry((n) => n + 1)}>重试我的话题</button>}
    <div className="topic-directory">{page.records.map((t) => <article className="profile-panel topic-summary" key={t.categoryId}><h2>{t.status === 1 ? <Link to={`/topics/${t.categoryId}`}>{t.name}</Link> : t.name}</h2>{t.status !== 1 && <p className="muted">已停用</p>}<p>{t.description}</p><button className="ghost-button" disabled={busyId !== null} onClick={() => cancel(t.categoryId)}>{busyId === t.categoryId ? '处理中…' : `取消关注${t.name}`}</button></article>)}</div>
    {!loading && !error && !page.records.length && <EmptyState message="还没有关注话题，去浏览话题吧。" />}
    {page.total > 0 && <Pagination pageInfo={page} loading={loading || busyId !== null} onPageChange={setPageNum} />}
  </section></div>
}
