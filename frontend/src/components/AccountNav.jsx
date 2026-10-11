import { Camera, FileText, Flag, Heart, Rss, Tags } from 'lucide-react'
import { NavLink } from 'react-router'
export const ACCOUNT_TABS = [
  { key: 'profile', label: '个人资料', icon: Camera },
  { key: 'articles', label: '我的文章', icon: FileText },
  { key: 'favorites', label: '我的收藏', icon: Heart },
  { key: 'reports', label: '我的举报', icon: Flag },
]
export default function AccountNav() {
  return <aside className="account-nav" aria-label="个人中心导航"><p className="eyebrow">个人中心</p>
    {[...ACCOUNT_TABS, { key: 'topics', label: '我的话题', icon: Tags }].map(({ key, label, icon: Icon }) => <NavLink key={key} to={`/me/${key}`}><Icon size={17} aria-hidden="true" />{label}</NavLink>)}
    <NavLink to="/following"><Rss size={17} aria-hidden="true" />关注动态</NavLink>
  </aside>
}
