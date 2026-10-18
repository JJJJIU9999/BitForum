import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate, useOutletContext } from 'react-router'
import MainLayout from './layouts/MainLayout.jsx'
import AdminLayout from './layouts/AdminLayout.jsx'
import Login from './pages/Login.jsx'
import Register from './pages/Register.jsx'
import ArticleList from './pages/ArticleList.jsx'
import ArticleDetail from './pages/ArticleDetail.jsx'
import PublishArticle from './pages/PublishArticle.jsx'
import EditArticle from './pages/EditArticle.jsx'
import MyArticles from './pages/MyArticles.jsx'
import PublicUserProfile from './pages/PublicUserProfile.jsx'
import NotificationCenter from './pages/NotificationCenter.jsx'
import Topics from './pages/Topics.jsx'
import TopicDetail from './pages/TopicDetail.jsx'
import FollowFeed from './pages/FollowFeed.jsx'
import MyTopics from './pages/MyTopics.jsx'
import Dashboard from './pages/admin/Dashboard.jsx'
import AuditArticles from './pages/admin/AuditArticles.jsx'
import ManageArticles from './pages/admin/ManageArticles.jsx'
import ManageComments from './pages/admin/ManageComments.jsx'
import ManageReports from './pages/admin/ManageReports.jsx'
import ManageUsers from './pages/admin/ManageUsers.jsx'
import ManageCategories from './pages/admin/ManageCategories.jsx'
import KnowledgeBase from './pages/admin/KnowledgeBase.jsx'
import ModerationRecords from './pages/admin/ModerationRecords.jsx'
import AiTraces from './pages/admin/AiTraces.jsx'
import TextModeration from './pages/admin/TextModeration.jsx'
import ImageModeration from './pages/admin/ImageModeration.jsx'
import { clearAuth, getCurrentUser } from './api/request.js'
import { getUnreadNotificationCount } from './api/notificationApi.js'
import { getCurrentUserProfile } from './api/userApi.js'

const ADMIN_ROLE = 'ADMIN'

function App() {
  const [currentUser, setCurrentUser] = useState(getCurrentUser())
  const [unreadCount, setUnreadCount] = useState(0)

  async function refreshUnreadCount() {
    if (!currentUser) {
      setUnreadCount(0)
      return
    }

    try {
      const result = await getUnreadNotificationCount()
      setUnreadCount(result.data || 0)
    } catch {
      setUnreadCount(0)
    }
  }

  useEffect(() => {
    refreshUnreadCount()
  }, [currentUser])

  useEffect(() => {
    if (!currentUser) return undefined

    let active = true
    getCurrentUserProfile().then((result) => {
      if (active) {
        setCurrentUser((user) => user?.userId === currentUser.userId ? {
          ...user,
          avatar: user.avatar ?? result.data.avatar ?? '',
          nickname: user.nickname ?? result.data.nickname ?? '',
        } : user)
      }
    }).catch(() => {})

    return () => { active = false }
  }, [currentUser?.userId])

  const sharedProps = { currentUser, setCurrentUser, unreadCount, refreshUnreadCount }

  return (
    <Routes>
      <Route element={<PublicShell {...sharedProps} />}>
        <Route index element={<ArticleList />} />
        <Route path="topics" element={<Topics />} />
        <Route path="topics/:categoryId" element={<TopicDetail />} />
        <Route path="questions" element={<ArticleList contentType="QUESTION" />} />
        <Route path="articles/:articleId" element={<ArticleDetail />} />
        <Route path="users/:userId" element={<PublicUserProfile />} />
        <Route path="login" element={<Login />} />
        <Route path="register" element={<Register />} />
        <Route element={<ProtectedRoute />}>
          <Route path="following" element={<FollowFeed />} />
          <Route path="me/topics" element={<MyTopics />} />
          <Route path="publish" element={<PublishArticle />} />
          <Route path="articles/:articleId/edit" element={<EditArticle />} />
          <Route path="notifications" element={<NotificationCenter />} />
          <Route path="me/:tab" element={<MyArticles />} />
          <Route path="me" element={<Navigate to="/me/profile" replace />} />
        </Route>
      </Route>

      <Route element={<AdminRoute {...sharedProps} />}>
        <Route path="admin" element={<AdminShell {...sharedProps} />}>
          <Route index element={<Dashboard />} />
          <Route path="audit" element={<AuditArticles />} />
          <Route path="text-moderation" element={<TextModeration />} />
          <Route path="image-moderation" element={<ImageModeration />} />
          <Route path="articles" element={<ManageArticles />} />
          <Route path="comments" element={<ManageComments />} />
          <Route path="reports" element={<ManageReports />} />
          <Route path="users" element={<ManageUsers />} />
          <Route path="categories" element={<ManageCategories />} />
          <Route path="kb" element={<KnowledgeBase />} />
          <Route path="moderation" element={<ModerationRecords />} />
          {/* M18：AI 执行轨迹与用量 —— 答辩演示的关键画面 */}
          <Route path="ai-traces" element={<AiTraces />} />
        </Route>
      </Route>

      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}

function PublicShell({ currentUser, setCurrentUser, unreadCount, refreshUnreadCount }) {
  const navigate = useNavigate()
  const logoutGuardRef = useRef(null)
  const registerLogoutGuard = useCallback((guard) => {
    logoutGuardRef.current = guard
    return () => { if (logoutGuardRef.current === guard) logoutGuardRef.current = null }
  }, [])

  function handleLogout() {
    const logout = () => {
      clearAuth()
      setCurrentUser(null)
      navigate('/login')
    }
    if (logoutGuardRef.current) logoutGuardRef.current(logout)
    else logout()
  }

  return (
    <MainLayout
      currentUser={currentUser}
      unreadCount={unreadCount}
      onLogout={handleLogout}
    >
      <Outlet context={{ currentUser, setCurrentUser, refreshUnreadCount, registerLogoutGuard }} />
    </MainLayout>
  )
}

function ProtectedRoute() {
  const context = useOutletContext()
  const location = useLocation()

  if (!context.currentUser) {
    const redirectTo = `${location.pathname}${location.search}`
    return <Navigate replace to={`/login?redirectTo=${encodeURIComponent(redirectTo)}`} />
  }

  return <Outlet context={context} />
}

function AdminRoute({ currentUser }) {
  const location = useLocation()

  if (!currentUser) {
    const redirectTo = `${location.pathname}${location.search}`
    return <Navigate replace to={`/login?redirectTo=${encodeURIComponent(redirectTo)}`} />
  }

  if (currentUser.role !== ADMIN_ROLE) {
    return (
      <main className="route-state content-container" id="main-content">
        <p className="eyebrow">访问受限</p>
        <h1>这里需要管理员权限</h1>
        <p>你的账号没有管理后台访问权限。系统仍会由后端再次校验权限。</p>
        <Link className="primary-button inline-button" to="/">返回社区首页</Link>
      </main>
    )
  }

  return <Outlet />
}

function AdminShell({ currentUser, setCurrentUser }) {
  const navigate = useNavigate()

  function handleLogout() {
    clearAuth()
    setCurrentUser(null)
    navigate('/login')
  }

  return (
    <AdminLayout currentUser={currentUser} onLogout={handleLogout}>
      <Outlet context={{ currentUser }} />
    </AdminLayout>
  )
}

function NotFound() {
  return (
    <main className="route-state content-container" id="main-content">
      <p className="eyebrow">404</p>
      <h1>这个页面没有找到</h1>
      <p>链接可能已失效，或页面尚未开放。</p>
      <Link className="primary-button inline-button" to="/">返回社区首页</Link>
    </main>
  )
}

export default App
