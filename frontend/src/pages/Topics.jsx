import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { listCategories } from '../api/categoryApi.js'
import PageHeader from '../components/PageHeader.jsx'
import EmptyState from '../components/EmptyState.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
export default function Topics() {
  const [topics, setTopics] = useState([]), [loading, setLoading] = useState(true), [error, setError] = useState(''), [retry, setRetry] = useState(0)
  useEffect(() => {
    let active = true; setLoading(true); setError(''); setTopics([])
    listCategories().then((r) => { if (active) setTopics(r.data || []) }).catch((e) => { if (active) setError(e.message) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [retry])
  return <section className="content-view topic-view"><PageHeader title="浏览话题" description="找到感兴趣的话题，关注其中的公开文章与问题。" />
    <div className="topic-shortcuts"><Link to="/following">关注动态</Link><Link to="/me/topics">我的话题</Link></div>
    {loading && <LoadingSpinner text="正在加载话题…" />}<MessageBanner message={error} type="error" />
    {error && <button className="ghost-button" onClick={() => setRetry((v) => v + 1)}>重试话题</button>}
    <div className="topic-directory">{topics.map((t) => <article className="profile-panel topic-summary" key={t.id}><h2><Link to={`/topics/${t.id}`}>{t.name}</Link></h2><p>{t.description || '围绕这个话题分享经验，参与讨论。'}</p><Link className="ghost-button inline-button" to={`/topics/${t.id}`}>浏览内容</Link></article>)}</div>
    {!loading && !error && !topics.length && <EmptyState message="暂时没有开放的话题。" />}
  </section>
}
