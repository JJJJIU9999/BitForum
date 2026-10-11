import { useState } from 'react'
import { Activity, BarChart3, BookOpenText, ClipboardCheck, Database, FileImage, FileText, Flag, FolderCog, ListFilter, Menu, MessageSquare, ShieldCheck, Users, X } from 'lucide-react'
import { Link, NavLink } from 'react-router'

const NAV_GROUPS = [
  {
    label: '概览',
    items: [{ to: '/admin', end: true, label: '数据看板', icon: BarChart3 }],
  },
  {
    label: '内容治理',
    items: [
      { to: '/admin/audit', label: '文章审核', icon: ClipboardCheck },
      { to: '/admin/text-moderation', label: '文字治理', icon: ListFilter },
      { to: '/admin/image-moderation', label: '图片审核', icon: FileImage },
      { to: '/admin/articles', label: '文章管理', icon: FileText },
      { to: '/admin/comments', label: '评论管理', icon: MessageSquare },
      { to: '/admin/reports', label: '举报处理', icon: Flag },
    ],
  },
  {
    label: '运营管理',
    items: [
      { to: '/admin/users', label: '用户管理', icon: Users },
      { to: '/admin/categories', label: '板块管理', icon: FolderCog },
    ],
  },
  {
    label: 'AI 能力',
    items: [
      { to: '/admin/kb', label: '知识库', icon: Database },
      { to: '/admin/moderation', label: 'AI 审核', icon: ShieldCheck },
      // M18：执行轨迹与用量（可观测性的入口）
      { to: '/admin/ai-traces', label: '执行轨迹', icon: Activity },
    ],
  },
]

function AdminLayout({ currentUser, onLogout, children }) {
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="admin-layout">
      <button
        className="admin-menu-button"
        type="button"
        aria-label="打开管理导航"
        aria-expanded={menuOpen}
        onClick={() => setMenuOpen(true)}
      >
        <Menu size={22} aria-hidden="true" />
      </button>

      {menuOpen && <button className="admin-drawer-scrim" type="button" aria-label="关闭管理导航" onClick={() => setMenuOpen(false)} />}
      <aside className={`admin-sidebar ${menuOpen ? 'is-open' : ''}`}>
        <div className="admin-sidebar-brand">
          <Link to="/admin" onClick={() => setMenuOpen(false)}>
            <ShieldCheck size={22} aria-hidden="true" />
            <span><strong>Bit Forum</strong><small>管理后台</small></span>
          </Link>
          <button className="admin-close-button" type="button" aria-label="关闭管理导航" onClick={() => setMenuOpen(false)}><X size={20} aria-hidden="true" /></button>
        </div>

        <nav className="admin-sidebar-nav" aria-label="管理后台导航">
          {NAV_GROUPS.map((group) => (
            <div className="admin-nav-group" key={group.label}>
              <p>{group.label}</p>
              {group.items.map(({ to, end, label, icon: Icon }) => (
                <NavLink key={to} end={end} to={to} onClick={() => setMenuOpen(false)}>
                  <Icon size={18} aria-hidden="true" />
                  <span>{label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="admin-sidebar-footer">
          <span>{currentUser?.username}</span>
          <button type="button" onClick={onLogout}>退出登录</button>
        </div>
      </aside>

      <div className="admin-main">
        <header className="admin-topbar">
          <div><p className="eyebrow">控制台</p><strong>社区管理</strong></div>
          <Link className="ghost-button compact-button" to="/"><BookOpenText size={16} aria-hidden="true" /> 返回社区</Link>
        </header>
        <main className="admin-body" id="main-content">
          <div className="admin-content-container">{children}</div>
        </main>
      </div>
    </div>
  )
}

export default AdminLayout
