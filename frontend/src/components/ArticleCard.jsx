import TopicLink from './TopicLink.jsx'
import StatusBadge from './StatusBadge.jsx'
import { Link } from 'react-router'
import AuthorIdentity from './AuthorIdentity.jsx'
import { displayTime } from '../utils/displayTime.js'

function ArticleCard({ article, onOpen, statusLabel, children, variant = 'public' }) {
  const question = article.contentType === 'QUESTION'
  const showStatus = article.status && (variant !== 'public' || statusLabel || article.status !== 'PUBLISHED')
  function open(event) {
    if (onOpen && event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
      event.preventDefault(); onOpen(article.id)
    }
  }
  return (
    <article className={children ? 'article-item article-item-with-footer' : 'article-item'}>
      <div className="article-main">
        {article.coverUrl && (
          <img className="article-cover-thumb" alt={`${article.title} 封面`} src={article.coverUrl} />
        )}
        <div className="card-labels"><span className="article-card-kicker"><TopicLink article={article} /></span><span className="content-type-label">{question ? '问题' : '文章'}</span>
        {question && <span className={article.acceptedAnswerId ? 'question-badge resolved' : 'question-badge'}>{article.acceptedAnswerId ? '已采纳' : article.answerCount > 0 ? '待采纳' : '待解答'}</span>}</div>
        <h3><Link to={`/articles/${article.id}`} onClick={open}>{article.title}</Link></h3>
        <p>{article.content}</p>
        <div className="article-card-meta">
          <AuthorIdentity author={article.author} userId={article.userId} compact />
          {question && article.answerCount != null ? <span>{article.answerCount} 个回答</span> : <span>浏览 {article.viewCount || 0}</span>}
          {!question && <span>点赞 {article.likeCount || 0}</span>}
          {article.createTime && <time dateTime={article.createTime}>{displayTime(article.createTime)}</time>}
        </div>
      </div>
      {showStatus && <div className="article-actions">
        {showStatus && (
          <StatusBadge status={article.status} label={statusLabel} />
        )}
        {onOpen && (
          <button
            className="ghost-button"
            type="button"
            onClick={() => onOpen(article.id)}
          >
            查看详情
          </button>
        )}
      </div>}
      {children && <div className="article-card-footer">{children}</div>}
    </article>
  )
}

export default ArticleCard
