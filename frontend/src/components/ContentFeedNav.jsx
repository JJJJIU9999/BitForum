import { Link } from 'react-router'

export default function ContentFeedNav({ active, currentUser }) {
  return (
    <div className="content-feed-navigation">
      <nav className="content-feed-nav" aria-label="内容来源">
        <Link to="/" aria-current={active === 'latest' ? 'page' : undefined}>最新</Link>
        <Link to="/?feed=hot" aria-current={active === 'hot' ? 'page' : undefined}>热门</Link>
        <Link to="/following" aria-current={active === 'following' ? 'page' : undefined}>关注</Link>
      </nav>
      {!currentUser && active !== 'following' && (
        <p className="feed-signin-hint">登录后可查看你关注的话题与作者动态。<Link to="/login?redirectTo=%2Ffollowing">登录查看关注</Link></p>
      )}
    </div>
  )
}
