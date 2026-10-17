import { Bell, BookOpenText, MessageCircle, LogIn, PenLine, ShieldCheck, UserRound } from 'lucide-react'
import { Link, NavLink } from 'react-router'
import AiAssistantPanel from '../components/AiAssistantPanel.jsx'

function MainLayout({ currentUser, unreadCount, onLogout, children }) {
  const isLoggedIn = Boolean(currentUser)
  const isAdmin = currentUser?.role === 'ADMIN'
  const displayName = currentUser?.nickname?.trim() || currentUser?.username || '社区用户'

  return (
    <div className="site-layout">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      <header className="site-header">
        <div className="header-inner">
          <Link className="header-brand" to="/">
            <span className="header-brand-mark">BF</span>
            <span>Bit Forum</span>
          </Link>

          <nav className="header-nav" aria-label="社区导航">
            <NavLink end to="/">社区</NavLink>
            <NavLink to="/questions">问答</NavLink>
            <NavLink to="/topics">话题</NavLink>
            {isLoggedIn && <NavLink to="/following">关注</NavLink>}
            {isLoggedIn && <NavLink to="/publish">投稿</NavLink>}
            {isLoggedIn && (
              <NavLink className="nav-with-badge" to="/notifications">
                通知
                {unreadCount > 0 && <span className="nav-badge" aria-label={`${unreadCount} 条未读通知`}>{unreadCount}</span>}
              </NavLink>
            )}
          </nav>

          <div className="header-actions">
            {isLoggedIn ? (
              <>
                {isAdmin && (
                  <Link className="header-icon-link" title="管理后台" to="/admin" aria-label="进入管理后台">
                    <ShieldCheck size={18} aria-hidden="true" />
                  </Link>
                )}
                <Link className="header-user-link" to="/me/profile" aria-label={`查看${displayName}的个人资料`} title={displayName}>
                  <span className="header-user-avatar" aria-hidden="true">
                    {currentUser.avatar ? <img alt="" src={currentUser.avatar} /> : displayName.slice(0, 1)}
                  </span>
                  <span className="header-user-name">{displayName}</span>
                </Link>
                <button className="text-button" type="button" onClick={onLogout}>退出</button>
              </>
            ) : (
              <Link className="primary-button compact-button" to="/login">登录</Link>
            )}
          </div>
        </div>
      </header>

      <main className="site-main" id="main-content">
        <div className="content-container">{children}</div>
      </main>

      <nav className="mobile-nav" aria-label="移动端主导航">
        <NavLink end to="/"><BookOpenText size={19} aria-hidden="true" /><span>社区</span></NavLink>
        <NavLink to="/questions"><MessageCircle size={19} aria-hidden="true" /><span>问答</span></NavLink>
        {isLoggedIn ? (
          <>
            <NavLink to="/publish"><PenLine size={19} aria-hidden="true" /><span>投稿</span></NavLink>
            <NavLink to="/notifications"><Bell size={19} aria-hidden="true" /><span>通知</span>{unreadCount > 0 && <b>{unreadCount}</b>}</NavLink>
            <NavLink to="/me/profile"><UserRound size={19} aria-hidden="true" /><span>我的</span></NavLink>
          </>
        ) : (
          <NavLink to="/login"><LogIn size={19} aria-hidden="true" /><span>登录</span></NavLink>
        )}
      </nav>

      <footer className="site-footer">
        <p>Bit Forum · 在观点与讨论之间，留下值得回看的内容。</p>
      </footer>

      {/* M13 AI 助手：仅登录用户可见，接口本身也要求登录 */}
      {isLoggedIn && <AiAssistantPanel key={currentUser.userId} userId={currentUser.userId} />}
    </div>
  )
}

export default MainLayout
