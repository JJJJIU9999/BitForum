import { useEffect, useRef, useState } from 'react'
import { ChevronLeft, UserMinus, UserPlus } from 'lucide-react'
import { useNavigate, useOutletContext, useParams, useSearchParams } from 'react-router'
import { followUser, getPublicUserProfile, pagePublicUserArticles, pageUserFollowers, pageUserFollowing, unfollowUser } from '../api/userApi.js'
import PageHeader from '../components/PageHeader.jsx'
import ArticleCard from '../components/ArticleCard.jsx'
import Pagination from '../components/Pagination.jsx'
import EmptyState from '../components/EmptyState.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
import { AuthorAvatar } from '../components/AuthorIdentity.jsx'

function PublicUserProfile() {
  const { userId } = useParams()
  const { currentUser } = useOutletContext()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [profile, setProfile] = useState(null)
  const [articles, setArticles] = useState([])
  const [articlePage, setArticlePage] = useState({ current: 1, pages: 1, total: 0 })
  const [articlePageNum, setArticlePageNum] = useState(1)
  const [followUsers, setFollowUsers] = useState([])
  const [followPage, setFollowPage] = useState({ current: 1, pages: 1, total: 0 })
  const [followPageNum, setFollowPageNum] = useState(1)
  const [message, setMessage] = useState('')
  const [articleError, setArticleError] = useState('')
  const [followError, setFollowError] = useState('')
  const [articleLoading, setArticleLoading] = useState(false)
  const [profileRetry, setProfileRetry] = useState(0)
  const [articleRetry, setArticleRetry] = useState(0)
  const [followRetry, setFollowRetry] = useState(0)
  const [loading, setLoading] = useState(false)
  const [followLoading, setFollowLoading] = useState(false)
  const [followActionLoading, setFollowActionLoading] = useState(false)
  const tab = searchParams.get('tab') === 'following' ? 'following' : 'followers'

  useEffect(() => { setArticlePageNum(1); setFollowPageNum(1); setFollowActionLoading(false) }, [userId])

  useEffect(() => {
    let active = true
    setLoading(true); setMessage(''); setProfile(null)
    getPublicUserProfile(userId).then((result) => { if (active) setProfile(result.data) })
      .catch((error) => { if (active) setMessage(error.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [userId, currentUser?.userId ?? currentUser?.id, profileRetry])

  useEffect(() => {
    let active = true
    setArticleLoading(true); setArticleError(''); setArticles([]); setArticlePage({ current: 1, pages: 1, total: 0 })
    pagePublicUserArticles(userId, { pageNum: articlePageNum, pageSize: 6 }).then((result) => {
      if (active) { setArticles(result.data.records || []); setArticlePage({ current: result.data.current, pages: result.data.pages, total: result.data.total }) }
    }).catch((error) => { if (active) setArticleError(error.message) })
      .finally(() => { if (active) setArticleLoading(false) })
    return () => { active = false }
  }, [userId, articlePageNum, articleRetry])

  useEffect(() => {
    let active = true
    setFollowLoading(true); setFollowError(''); setFollowUsers([]); setFollowPage({ current: 1, pages: 1, total: 0 })
    ;(tab === 'followers' ? pageUserFollowers : pageUserFollowing)(userId, { pageNum: followPageNum, pageSize: 6 }).then((result) => {
      if (active) { setFollowUsers(result.data.records || []); setFollowPage({ current: result.data.current, pages: result.data.pages, total: result.data.total }) }
    }).catch((error) => { if (active) setFollowError(error.message) })
      .finally(() => { if (active) setFollowLoading(false) })
    return () => { active = false }
  }, [userId, tab, followPageNum, followRetry])

  const activeUserRef = useRef(userId)
  activeUserRef.current = userId

  async function handleFollowToggle() {
    if (!currentUser) { navigate(`/login?redirectTo=${encodeURIComponent(`/users/${userId}`)}`); return }
    setFollowActionLoading(true)
    try {
      const result = profile.followedByCurrentUser ? await unfollowUser(profile.userId) : await followUser(profile.userId)
      if (activeUserRef.current !== userId) return
      setProfile((current) => ({ ...current, followingCount: result.data.followingCount, followerCount: result.data.followerCount, followedByCurrentUser: result.data.followedByCurrentUser }))
    } catch (error) { if (activeUserRef.current === userId) setMessage(error.message) } finally { if (activeUserRef.current === userId) setFollowActionLoading(false) }
  }

  const isSelf = Number(currentUser?.userId ?? currentUser?.id) === Number(profile?.userId)
  return <div className="content-view public-profile-view"><PageHeader title="用户主页" description="查看公开资料、关注关系和已发布内容。" actions={<button className="ghost-button icon-text-button" type="button" onClick={() => navigate(-1)}><ChevronLeft size={17} aria-hidden="true" />返回</button>} />{loading && <LoadingSpinner text="正在打开用户主页…" />}<MessageBanner message={message} type="error" />{message && <button className="ghost-button" type="button" onClick={() => setProfileRetry((v) => v + 1)}>重试主页</button>}{profile && <><section className="profile-panel public-profile-panel"><div className="profile-summary"><AuthorAvatar key={profile.userId} className="profile-avatar" avatar={profile.avatar} name={profile.nickname || profile.username || '社区用户'} alt={`${profile.nickname || profile.username || '社区用户'} 的头像`} /><div><p className="eyebrow">用户 #{profile.userId}</p><h2>{profile.nickname || profile.username}</h2><p>{profile.bio || '这个用户还没有填写个人简介。'}</p><div className="profile-stats"><span>{profile.publishedArticleCount ?? 0} 条公开内容</span><span>{profile.favoriteCount ?? 0} 个收藏</span><span>{profile.followingCount ?? 0} 个关注</span><span>{profile.followerCount ?? 0} 个粉丝</span></div></div>{!isSelf && <button className={profile.followedByCurrentUser ? 'ghost-button profile-follow-button' : 'primary-button profile-follow-button'} disabled={followActionLoading} type="button" onClick={handleFollowToggle}>{profile.followedByCurrentUser ? <><UserMinus size={17} aria-hidden="true" />取消关注</> : <><UserPlus size={17} aria-hidden="true" />关注</>}</button>}</div></section><section className="follow-panel profile-panel"><div className="sub-heading"><div><p className="eyebrow">关注关系</p><h2>{tab === 'followers' ? '粉丝' : '正在关注'}</h2></div><span>{followPage.total} 人</span></div><div className="follow-tabs"><button className={tab === 'followers' ? 'active' : ''} type="button" onClick={() => { setSearchParams({ tab: 'followers' }); setFollowPageNum(1) }}>粉丝</button><button className={tab === 'following' ? 'active' : ''} type="button" onClick={() => { setSearchParams({ tab: 'following' }); setFollowPageNum(1) }}>关注</button></div>{followLoading && <LoadingSpinner text="正在加载关注关系…" />}<MessageBanner message={followError} type="error" />{followError && <button className="ghost-button" type="button" onClick={() => setFollowRetry((v) => v + 1)}>重试关注列表</button>}<div className="follow-user-list">{followUsers.map((user) => <button className="follow-user-row" key={user.userId} type="button" onClick={() => navigate(`/users/${user.userId}`)}><span className="mini-avatar">{user.avatar ? <img alt="" src={user.avatar} /> : user.username?.slice(0, 1)}</span><span><strong>{user.nickname || user.username}</strong><small>{user.bio || '暂未填写个人简介'}</small></span></button>)}{!followLoading && !followError && followUsers.length === 0 && <EmptyState message={tab === 'followers' ? '暂无粉丝。' : '暂无关注用户。'} />}</div>{followPage.total > 0 && <Pagination pageInfo={followPage} onPageChange={setFollowPageNum} loading={followLoading} />}</section><section className="public-article-section"><div className="sub-heading"><div><p className="eyebrow">公开内容</p><h2>已发布内容</h2></div><span>{articlePage.total} 条</span></div>{articleLoading && <LoadingSpinner text="正在加载公开内容…" />}<MessageBanner message={articleError} type="error" />{articleError && <button className="ghost-button" type="button" onClick={() => setArticleRetry((v) => v + 1)}>重试公开内容</button>}<div className="article-list">{articles.map((article) => <ArticleCard key={article.id} article={article} onOpen={(id) => navigate(`/articles/${id}`)} />)}{!articleLoading && !articleError && articles.length === 0 && <EmptyState message="暂无公开内容。" />}</div>{articlePage.total > 0 && <Pagination pageInfo={articlePage} onPageChange={setArticlePageNum} loading={articleLoading} />}</section></>}</div>
}

export default PublicUserProfile
