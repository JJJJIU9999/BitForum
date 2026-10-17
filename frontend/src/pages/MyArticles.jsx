import { useEffect, useState } from 'react'
import { Save, Upload } from 'lucide-react'
import { Link, useNavigate, useOutletContext, useParams } from 'react-router'
import { pageMyArticles, pageMyFavorites } from '../api/articleApi.js'
import { pageMyReports } from '../api/reportApi.js'
import { getCurrentUserProfile, updateCurrentUserProfile } from '../api/userApi.js'
import { uploadAvatar } from '../api/uploadApi.js'
import AccountNav, { ACCOUNT_TABS } from '../components/AccountNav.jsx'
import PageHeader from '../components/PageHeader.jsx'
import ArticleCard from '../components/ArticleCard.jsx'
import StatusBadge from '../components/StatusBadge.jsx'
import Pagination from '../components/Pagination.jsx'
import EmptyState from '../components/EmptyState.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
import PrivateImagePreview from '../components/PrivateImagePreview.jsx'

const TABS = ACCOUNT_TABS
const STATUS_OPTIONS = [{ value: '', label: '全部状态' }, { value: 'DRAFT', label: '草稿' }, { value: 'PENDING', label: '待审核' }, { value: 'PUBLISHED', label: '已发布' }, { value: 'REJECTED', label: '已驳回' }, { value: 'OFFLINE', label: '已下架' }]
const REPORT_STATUS_LABELS = { PENDING: '待处理', RESOLVED: '已处理', REJECTED: '已驳回' }
const TARGET_TYPE_LABELS = { ARTICLE: '文章', COMMENT: '评论' }

function MyArticles() {
  const { tab = 'profile' } = useParams()
  const { currentUser, setCurrentUser } = useOutletContext()
  const navigate = useNavigate()
  const activeTab = TABS.some((item) => item.key === tab) ? tab : 'profile'
  const [records, setRecords] = useState([])
  const [profile, setProfile] = useState(null)
  const [profileForm, setProfileForm] = useState({ nickname: '', bio: '' })
  const [avatarFile, setAvatarFile] = useState(null)
  const [avatarInputKey, setAvatarInputKey] = useState(0)
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [pageNum, setPageNum] = useState(1)
  const [status, setStatus] = useState('')
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState('info')
  const [loading, setLoading] = useState(false)
  const [busyId, setBusyId] = useState(null)

  useEffect(() => { setPageNum(1); setMessage('') }, [activeTab])

  useEffect(() => {
    async function loadContent() {
      setLoading(true)
      setMessage('')
      try {
        if (activeTab === 'profile') {
          const result = await getCurrentUserProfile()
          setProfile(result.data)
          setProfileForm({ nickname: result.data.nickname || '', bio: result.data.bio || '' })
          return
        }
        const params = { pageNum, pageSize: 6 }
        if (activeTab === 'articles' && status) params.status = status
        const result = activeTab === 'favorites' ? await pageMyFavorites(params) : activeTab === 'reports' ? await pageMyReports(params) : await pageMyArticles(params)
        const nextRecords = result.data.records || []
        setRecords(nextRecords)
        setPageInfo({ current: result.data.current, pages: result.data.pages, total: result.data.total })
      } catch (error) {
        setRecords([])
        setMessage(error.message)
        setMessageType('error')
      } finally {
        setLoading(false)
      }
    }
    loadContent()
  }, [activeTab, pageNum, status])

  async function handleAvatarUpload() {
    if (!avatarFile) return
    setBusyId('avatar')
    try {
      const result = await uploadAvatar(avatarFile)
      setProfile((current) => current ? { ...current, pendingAvatarImageId: result.data.id } : current)
      setAvatarFile(null)
      setAvatarInputKey((value) => value + 1)
      setMessage('头像已上传，等待管理员人工审核；公开页面暂时显示原头像。')
      setMessageType('info')
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
    } finally { setBusyId(null) }
  }

  async function saveProfile(event) {
    event.preventDefault()
    setBusyId('profile')
    try {
      const result = await updateCurrentUserProfile({ nickname: profileForm.nickname, bio: profileForm.bio })
      setProfile(result.data)
      setProfileForm({ nickname: result.data.nickname || '', bio: result.data.bio || '' })
      setCurrentUser((user) => user?.userId === currentUser.userId ? { ...user, avatar: result.data.avatar || '', nickname: result.data.nickname || '' } : user)
      setMessage('个人资料已更新。')
      setMessageType('info')
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
    } finally { setBusyId(null) }
  }

  const labels = { profile: ['个人资料', '维护头像、昵称与个人简介。'], articles: ['我的文章', '继续编辑草稿或被驳回内容，查看审核状态。'], favorites: ['我的收藏', '这里保留仍处于公开状态的收藏文章。'], reports: ['我的举报', '查看你提交过的内容举报及处理状态。'] }

  return (
    <div className="account-layout">
      <AccountNav />
      <section className="account-content content-view">
        <PageHeader title={labels[activeTab][0]} description={labels[activeTab][1]} badge={activeTab === 'profile' ? `登录用户名：${profile?.username || currentUser?.username || ''}` : `${pageInfo.total} 条`} />
        {activeTab === 'articles' && <label className="filter-row">文章状态<select value={status} onChange={(event) => { setStatus(event.target.value); setPageNum(1) }}>{STATUS_OPTIONS.map((option) => <option key={option.value || 'all'} value={option.value}>{option.label}</option>)}</select></label>}
        {loading && <LoadingSpinner text="正在整理你的内容…" />}
        <MessageBanner message={message} type={messageType} />

        {activeTab === 'profile' && <section className="profile-panel">{profile?.pendingTextReview && <p role="status">资料修改待人工审核；公开页面暂时显示原有资料。</p>}<div className="profile-summary"><div className="profile-avatar">{profile?.avatar ? <img alt="当前头像" src={profile.avatar} /> : <span>{currentUser?.username?.slice(0, 1)}</span>}</div>{profile?.pendingAvatarImageId && <div className="pending-image-hint"><PrivateImagePreview imageId={profile.pendingAvatarImageId} alt="待审头像预览" className="pending-image-preview" /><span role="status">新头像待人工审核，仅你和管理员可预览。</span></div>}<div><h2>{profile?.nickname || profile?.username || currentUser?.username}</h2><p>{profile?.bio || '还没有填写个人简介。'}</p><div className="profile-stats"><span>{profile?.publishedArticleCount ?? 0} 篇已发布</span><span>{profile?.favoriteCount ?? 0} 个收藏</span></div></div></div><form className="profile-form" onSubmit={saveProfile}><div className="avatar-upload"><label className="file-picker">选择头像<input key={avatarInputKey} accept="image/jpeg,image/png,image/webp" type="file" onChange={(event) => setAvatarFile(event.target.files?.[0] || null)} /></label><button className="ghost-button" disabled={!avatarFile || busyId === 'avatar'} type="button" onClick={handleAvatarUpload}><Upload size={16} aria-hidden="true" />{busyId === 'avatar' ? '上传中…' : '上传头像'}</button></div><label>昵称<input maxLength="50" name="nickname" onChange={(event) => setProfileForm((current) => ({ ...current, nickname: event.target.value }))} placeholder="输入昵称" value={profileForm.nickname} /></label><label>个人简介<textarea maxLength="255" name="bio" onChange={(event) => setProfileForm((current) => ({ ...current, bio: event.target.value }))} placeholder="介绍一下自己" value={profileForm.bio} /></label><button className="primary-button icon-text-button" disabled={busyId === 'profile' || busyId === 'avatar'} type="submit"><Save size={17} aria-hidden="true" />{busyId === 'profile' ? '保存中…' : '保存资料'}</button></form></section>}

        {activeTab === 'reports' && <div className="report-list">{records.map((report) => <article className="report-card" key={report.id}><div><p className="article-card-kicker">{TARGET_TYPE_LABELS[report.targetType] || report.targetType} #{report.targetId}</p><h2>{report.reason}</h2><p>{report.handleResult || '管理员尚未填写处理说明。'}</p></div><StatusBadge status={report.status} label={REPORT_STATUS_LABELS[report.status]} /></article>)}{!loading && records.length === 0 && <EmptyState />}</div>}

        {(activeTab === 'articles' || activeTab === 'favorites') && <div className="article-list">{records.map((article) => <ArticleCard key={article.id} variant={activeTab === 'favorites' ? 'favorite' : 'owned'} article={article} onOpen={(id) => navigate(`/articles/${id}`)} statusLabel={activeTab === 'favorites' ? '已收藏' : undefined}>{activeTab === 'articles' && String(article.userId) === String(currentUser?.userId ?? currentUser?.id) && (article.status === 'DRAFT' || article.status === 'REJECTED') && <Link className="primary-button inline-button" to={`/articles/${article.id}/edit`}>继续编辑</Link>}</ArticleCard>)}{!loading && records.length === 0 && <EmptyState />}</div>}
        {activeTab !== 'profile' && pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={setPageNum} loading={loading} />}
      </section>
    </div>
  )
}

export default MyArticles
