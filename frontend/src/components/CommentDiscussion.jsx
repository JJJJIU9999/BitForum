import { useState } from 'react'
import { Flag, Heart } from 'lucide-react'
import { publishComment, likeAnswer, unlikeAnswer } from '../api/commentApi.js'
import AuthorIdentity from './AuthorIdentity.jsx'
import { displayTime } from '../utils/displayTime.js'

const parentId = (item) => Number(item.parentCommentId || 0)
const chronological = (a, b) => String(a.createTime || '').localeCompare(String(b.createTime || '')) || Number(a.id) - Number(b.id)

export default function CommentDiscussion({ comments, currentUser, isQuestion, isQuestionOwner,
  acceptedAnswerId, articleId, actionLoading, onAction, onReport, onAdopt, loading = false }) {
  const [expanded, setExpanded] = useState(new Set())
  const [replyTarget, setReplyTarget] = useState(null)
  const [replyContent, setReplyContent] = useState('')
  const [replyError, setReplyError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const roots = comments.filter((c) => parentId(c) === 0 && c.status === 'PUBLISHED')
  const privateRoots = comments.filter((c) => parentId(c) === 0 && c.status !== 'PUBLISHED').sort((a, b) => chronological(b, a))

  function toggleReplies(id) {
    setExpanded((value) => {
      const next = new Set(value)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function submitReply(event, root) {
    event.preventDefault()
    setSubmitting(true)
    setReplyError('')
    try {
      await publishComment({ articleId, content: replyContent, parentCommentId: root.id })
      await onAction(() => Promise.resolve({}), '回复已提交；命中文字规则时等待人工审核。')
      setExpanded((value) => new Set([...value, root.id]))
      setReplyContent('')
      setReplyTarget(null)
    } catch (error) {
      setReplyError(error.message)
    } finally {
      setSubmitting(false)
    }
  }

  function content(item, privateItem = false) {
    return <>
      <header><AuthorIdentity author={item.author} userId={item.userId} compact /><time dateTime={item.createTime}>{displayTime(item.createTime)}</time>
        {privateItem && <span>{item.status === 'PENDING' ? '待人工审核，仅自己可见' : '未通过审核，仅自己可见'}</span>}
      </header>
      <p>{item.content}</p>
    </>
  }

  return <div className="comment-list">
    {roots.map((root) => {
      const replies = comments.filter((c) => parentId(c) === Number(root.id) && c.status === 'PUBLISHED').sort(chronological)
      const privateReplies = comments.filter((c) => parentId(c) === Number(root.id) && c.status !== 'PUBLISHED').sort(chronological)
      const open = expanded.has(root.id)
      return <article className={isQuestion && Number(acceptedAnswerId) === Number(root.id) ? 'comment-item accepted-answer' : 'comment-item'} key={root.id}>
        {content(root)}
        {isQuestion && Number(acceptedAnswerId) === Number(root.id) && <span className="question-badge resolved">提问者已采纳</span>}
        <div className="comment-actions">
          {isQuestion && <button className="ghost-button icon-text-button" disabled={actionLoading} type="button"
            aria-label={`${root.likedByCurrentUser ? '取消赞同' : '赞同'}回答 #${root.id}`} aria-pressed={Boolean(root.likedByCurrentUser)}
            onClick={() => onAction(() => root.likedByCurrentUser ? unlikeAnswer(root.id) : likeAnswer(root.id), '回答点赞状态已更新。')}>
            <Heart size={15} aria-hidden="true" />{root.likedByCurrentUser ? '已赞同' : '赞同'} {root.likeCount || 0}
          </button>}
          {isQuestionOwner && <button className="ghost-button" disabled={actionLoading} type="button"
            aria-label={Number(acceptedAnswerId) === Number(root.id) ? `取消采纳回答 #${root.id}` : `采纳回答 #${root.id}`}
            onClick={() => onAdopt(root.id)}>{Number(acceptedAnswerId) === Number(root.id) ? '取消采纳' : acceptedAnswerId ? '改为采纳此回答' : '采纳回答'}</button>}
          <button className="text-button" type="button" disabled={actionLoading} onClick={() => {
            if (!currentUser) { onAction(() => Promise.resolve({}), ''); return }
            setReplyTarget(root.id); setReplyContent(''); setReplyError('')
          }} aria-label={`回复${isQuestion ? '回答' : '评论'} #${root.id}`}>回复</button>
          <button className="text-button" type="button" aria-expanded={open} onClick={() => toggleReplies(root.id)}>
            {open ? '收起回复' : '展开回复'}（{replies.length}）
          </button>
          {currentUser && <button className="text-button" disabled={actionLoading} type="button" onClick={() => onReport(root.id)}><Flag size={15} aria-hidden="true" />{isQuestion ? '举报回答' : '举报评论'}</button>}
        </div>
        {replyTarget === root.id && <form className="comment-form reply-form" onSubmit={(event) => submitReply(event, root)}>
          <label><span>回复{isQuestion ? '回答' : '评论'} #{root.id}</span>
            <textarea value={replyContent} onChange={(event) => setReplyContent(event.target.value)} disabled={submitting} required />
          </label>
          {replyError && <p role="alert">{replyError}</p>}
          <div className="comment-actions"><button className="primary-button" type="submit" disabled={submitting}>提交回复</button>
            <button className="ghost-button" type="button" disabled={submitting} onClick={() => setReplyTarget(null)}>取消回复</button></div>
        </form>}
        {(open || privateReplies.length > 0) && <div className="reply-list">
          {open && replies.map((reply) => <article className="reply-item" key={reply.id}>
            {content(reply)}
            {currentUser && <button className="text-button" disabled={actionLoading} type="button" onClick={() => onReport(reply.id)}>举报回复</button>}
          </article>)}
          {privateReplies.length > 0 && <section aria-label={`回答 #${root.id} 的本人未公开回复`}>
            <h3>我的未公开回复（{privateReplies.length}）</h3>
            {privateReplies.map((reply) => <article className="reply-item" key={reply.id}>{content(reply, true)}</article>)}
          </section>}
        </div>}
      </article>
    })}
    {roots.length === 0 && !loading && <p className="empty-inline">{isQuestion ? '还没有回答，分享你的解法吧。' : '暂无评论，来留下第一条观点吧。'}</p>}
    {privateRoots.length > 0 && <section className="private-comments" aria-label="本人未公开内容">
      <h3>我的未公开{isQuestion ? '回答' : '评论'}（{privateRoots.length}）</h3>
      {privateRoots.map((item) => <article className="comment-item" key={item.id}>{content(item, true)}</article>)}
    </section>}
  </div>
}
