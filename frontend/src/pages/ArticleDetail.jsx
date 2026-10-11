import { useEffect, useRef, useState } from 'react'
import { Bookmark, ChevronLeft, Flag, Heart, MessageCircle } from 'lucide-react'
import { useNavigate, useOutletContext, useParams } from 'react-router'
import { acceptAnswer, cancelAcceptedAnswer, favoriteArticle, getArticleDetail, likeArticle, unfavoriteArticle, unlikeArticle, viewArticle } from '../api/articleApi.js'
import { listComments, listMyComments, publishComment } from '../api/commentApi.js'
import { reportArticle, reportComment } from '../api/reportApi.js'
import TopicLink from '../components/TopicLink.jsx'
import PageHeader from '../components/PageHeader.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import RelatedRecommendations from '../components/RelatedRecommendations.jsx'
import CommentDiscussion from '../components/CommentDiscussion.jsx'
import AuthorIdentity from '../components/AuthorIdentity.jsx'
import { displayTime } from '../utils/displayTime.js'

function ArticleDetail() {
  const { articleId } = useParams()
  const { currentUser } = useOutletContext()
  const navigate = useNavigate()
  const [article, setArticle] = useState(null)
  const [comments, setComments] = useState([])
  const [answerSort, setAnswerSort] = useState('hot')
  const [commentContent, setCommentContent] = useState('')
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState('info')
  const [loading, setLoading] = useState(false)
  const [detailError, setDetailError] = useState('')
  const [commentsError, setCommentsError] = useState('')
  const [mineError, setMineError] = useState('')
  const [commentsLoading, setCommentsLoading] = useState(false)
  const [detailRetry, setDetailRetry] = useState(0)
  const [actionLoading, setActionLoading] = useState(false)
  const [reportTarget, setReportTarget] = useState(null)
  const viewedArticleRef = useRef(null)
  const loadVersionRef = useRef(0)

  const commentVersionRef = useRef(0)
  const identity = currentUser?.userId ?? currentUser?.id
  const scope = `${articleId}:${identity}`
  const activeScopeRef = useRef(scope)
  activeScopeRef.current = scope

  async function refreshDetail(version = loadVersionRef.current) {
    const result = await getArticleDetail(articleId)
    if (scope === activeScopeRef.current && version === loadVersionRef.current) setArticle(result.data)
    return result.data
  }

  async function refreshComments(currentArticle = article, sort = answerSort) {
    if (scope !== activeScopeRef.current) return false
    const version = ++commentVersionRef.current
    setComments([]); setCommentsError(''); setMineError(''); setCommentsLoading(true)
    const results = await Promise.allSettled([
      listComments(articleId, currentArticle?.contentType === 'QUESTION' ? sort : undefined),
      currentUser ? listMyComments(articleId) : Promise.resolve({ data: [] }),
    ])
    if (version !== commentVersionRef.current) return false
    setCommentsLoading(false)
    if (results[0].status === 'rejected') {
      setCommentsError(results[0].reason.message); return false
    }
    const mine = results[1].status === 'fulfilled' ? results[1].value.data || [] : []
    if (results[1].status === 'rejected') setMineError(`我的未公开内容加载失败：${results[1].reason.message}`)
    setComments([...(results[0].value.data || []), ...mine.filter((item) => item.status !== 'PUBLISHED')])
    return results[1].status === 'fulfilled'
  }

  useEffect(() => {
    const version = ++loadVersionRef.current
    ++commentVersionRef.current
    setArticle(null); setComments([]); setCommentContent(''); setReportTarget(null)
    setCommentsError(''); setMineError(''); setCommentsLoading(false); setActionLoading(false)
    setLoading(true); setDetailError(''); setMessage('')
    getArticleDetail(articleId).then((result) => {
      if (version !== loadVersionRef.current) return
      setArticle(result.data)
      if (result.data?.status === 'PUBLISHED' && viewedArticleRef.current !== articleId) {
        viewedArticleRef.current = articleId
        // 浏览统计是非关键副作用，失败不阻止阅读，也不随读取重试再次投递。
        viewArticle(articleId).catch(() => {})
      }
    }).catch((error) => {
      if (version === loadVersionRef.current) setDetailError(error.message)
    }).finally(() => { if (version === loadVersionRef.current) setLoading(false) })
    return () => { ++loadVersionRef.current; ++commentVersionRef.current }
  }, [articleId, identity, detailRetry])

  useEffect(() => {
    if (Number(article?.id) === Number(articleId) && article?.status === 'PUBLISHED') refreshComments(article, answerSort)
    return () => { ++commentVersionRef.current }
  }, [articleId, article?.id, article?.status, answerSort, identity])

  async function handleAction(action, successMessage) {
    if (scope !== activeScopeRef.current) return false
    if (!currentUser) {
      navigate(`/login?redirectTo=${encodeURIComponent(`/articles/${articleId}`)}`)
      return false
    }
    const version = loadVersionRef.current
    setActionLoading(true); setMessage('')
    let committed = false
    try {
      const result = await action(); committed = true
      if (version !== loadVersionRef.current) return true
      const updated = await refreshDetail(version)
      if (version !== loadVersionRef.current) return true
      const refreshed = await refreshComments(updated)
      if (version === loadVersionRef.current) {
        setMessage(refreshed ? result.message || successMessage : '操作已完成，内容刷新失败；请重试加载，勿重复提交。')
        setMessageType(refreshed ? 'info' : 'error')
      }
    } catch (error) {
      if (version === loadVersionRef.current) {
        setMessage(committed ? '操作已完成，内容刷新失败；请重试加载，勿重复提交。' : error.message)
        setMessageType('error')
      }
    } finally { if (version === loadVersionRef.current) setActionLoading(false) }
    return committed
  }

  async function handleReport(reason) {
    const version = loadVersionRef.current
    setActionLoading(true)
    setMessage('')
    try {
      if (reportTarget?.type === 'article') await reportArticle({ articleId, reason })
      else await reportComment({ commentId: reportTarget.id, reason })
      if (version !== loadVersionRef.current) return
      setMessage(reportTarget?.type === 'article' ? '举报已提交，等待管理员处理。' : `评论 #${reportTarget.id} 举报已提交。`)
      setMessageType('info')
      setReportTarget(null)
    } catch (error) {
      if (version === loadVersionRef.current) { setMessage(error.message); setMessageType('error') }
    } finally {
      if (version === loadVersionRef.current) setActionLoading(false)
    }
  }

  async function handleCommentSubmit(event) {
    event.preventDefault()
    if (!currentUser) {
      navigate(`/login?redirectTo=${encodeURIComponent(`/articles/${articleId}`)}`)
      return
    }
    const version = loadVersionRef.current
    setActionLoading(true); setMessage('')
    try {
      await publishComment({ articleId, content: commentContent })
      if (version !== loadVersionRef.current) return
      setCommentContent('')
      const refreshed = await refreshComments()
      if (version !== loadVersionRef.current) return
      setMessage(refreshed ? (article?.contentType === 'QUESTION' ? '回答已提交；命中文字规则的回答会在人工审核通过后公开。' : '评论已提交；命中文字规则的评论会在人工审核通过后公开。') : '内容已提交，列表刷新失败；请重试加载，勿重复提交。')
      setMessageType(refreshed ? 'info' : 'error')
    } catch (error) {
      if (version === loadVersionRef.current) { setMessage(error.message); setMessageType('error') }
    } finally { if (version === loadVersionRef.current) setActionLoading(false) }
  }

  // 点赞/收藏状态由服务端在详情接口给出（匿名时稳定为 false），前端只负责展示与切换，
  // 不再自己维护第二套状态，也不根据 null 去猜。
  const liked = Boolean(article?.likedByCurrentUser)
  const favorited = Boolean(article?.favoritedByCurrentUser)

  // 只有已发布文章才对外可见、才允许点赞/收藏/评论；
  // 草稿等未公开状态仅作者本人能打开，页面按"预览"呈现
  const isPublished = article?.status === 'PUBLISHED'
  const isQuestion = article?.contentType === 'QUESTION'
  const isQuestionOwner = isQuestion && Number(currentUser?.userId ?? currentUser?.id) === Number(article?.userId)
  const publicCount = comments.filter((c) => Number(c.parentCommentId || 0) === 0 && c.status === 'PUBLISHED').length
  const unpublishedLabel = {
    DRAFT: '草稿',
    PENDING: isQuestion ? '待审核问题' : '待审核文章',
    REJECTED: isQuestion ? '被驳回的问题' : '被驳回的文章',
    OFFLINE: isQuestion ? '已下架的问题' : '已下架的文章',
  }[article?.status] || '未公开内容'

  return (
    <div className="content-view article-detail-view">
      <PageHeader
        title={isQuestion ? '问题详情' : '文章详情'}
        description={isQuestion ? '分享解法，让提问者找到有帮助的回答。' : '阅读全文，参与观点交换。'}
        actions={<button className="ghost-button icon-text-button" type="button" onClick={() => navigate(-1)}><ChevronLeft size={17} aria-hidden="true" />返回</button>}
      />
      {loading && <LoadingSpinner text={isQuestion ? '正在打开问题...' : '正在打开文章...'} />}
      <MessageBanner message={detailError} type="error" />
      {detailError && <button className="ghost-button" type="button" onClick={() => setDetailRetry((v) => v + 1)}>重试内容</button>}
      <MessageBanner message={message} type={messageType} />

      {article && (
        <article className="detail-card editorial-article">
          <header>
            <p className="article-card-kicker"><TopicLink article={article} /></p>
            <h2>{article.title}</h2>
            <div className="article-byline"><span>{isQuestion ? '问题' : '文章'} #{article.id}</span>
              {isQuestion && <span className={article.acceptedAnswerId ? 'question-badge resolved' : 'question-badge'}>{article.acceptedAnswerId ? '已采纳回答' : '等待解答'}</span>}<AuthorIdentity author={article.author} userId={article.userId} /><time dateTime={article.createTime}>{displayTime(article.createTime)}</time></div>
          </header>
          {!isPublished && (
            <p className="unpublished-banner">
              这是你的{unpublishedLabel}，尚未公开，仅你可见。可在「我的文章」中继续编辑或提交审核。
            </p>
          )}
          {article.coverUrl && <img className="detail-cover" alt={`${article.title} 封面`} src={article.coverUrl} />}
          <p className="article-body">{article.content}</p>
          {isPublished && (
            <footer className="article-detail-footer">
              <div className="metric-row" aria-label="文章互动数据">
                <span><strong>{article.viewCount}</strong> 浏览</span>
                <span><strong>{article.likeCount}</strong> 点赞</span>
                <span><strong>{article.favoriteCount ?? 0}</strong> 收藏</span>
              </div>
              <div className="action-row">
                {/* 点赞和取消点赞是同一个按钮的两个方向；已点赞用深绿填充，未点赞用描边 */}
                <button
                  className={`${liked ? 'primary-button' : 'ghost-button'} icon-text-button`}
                  aria-pressed={liked}
                  disabled={actionLoading}
                  type="button"
                  onClick={() => handleAction(
                    () => (liked ? unlikeArticle(articleId) : likeArticle(articleId)),
                    liked ? '已取消点赞。' : '点赞成功。',
                  )}
                >
                  <Heart size={17} aria-hidden="true" />{liked ? '已点赞' : '点赞'}
                </button>
                <button
                  className={`${favorited ? 'primary-button' : 'ghost-button'} icon-text-button`}
                  aria-pressed={favorited}
                  disabled={actionLoading}
                  type="button"
                  onClick={() => handleAction(
                    () => (favorited ? unfavoriteArticle(articleId) : favoriteArticle(articleId)),
                    favorited ? '已取消收藏。' : '收藏成功。',
                  )}
                >
                  <Bookmark size={17} aria-hidden="true" />{favorited ? '已收藏' : '收藏'}
                </button>
                {/* 举报只对已登录用户显示，与「举报评论」保持一致 */}
                {currentUser && (
                  <button className="ghost-button icon-text-button danger-ghost-button" disabled={actionLoading} type="button" onClick={() => setReportTarget({ type: 'article' })}><Flag size={17} aria-hidden="true" />{isQuestion ? '举报问题' : '举报文章'}</button>
                )}
              </div>
            </footer>
          )}
        </article>
      )}

      {/* 相关推荐（M17）：与评论同一可见性规则 —— 只有公开内容才展示。
          该组件在推荐不可用时自行隐藏，不会给阅读带来干扰 */}
      {isPublished && (
        <RelatedRecommendations
          articleId={articleId}
          onOpen={(id) => navigate(`/articles/${id}`)}
        />
      )}

      {/* 评论只属于已发布文章；未公开内容不展示公开讨论 */}
      {isPublished && (
      <section className="comment-section" aria-labelledby="comment-title">
        <div className="sub-heading"><div><p className="eyebrow">{isQuestion ? '分享你的解法' : '继续讨论'}</p><h2 id="comment-title"><MessageCircle size={19} aria-hidden="true" />{isQuestion ? '回答' : '评论'}</h2></div><span>{commentsLoading || commentsError ? '讨论加载中或暂不可用' : `${publicCount} 条公开${isQuestion ? '回答' : '评论'}`}</span></div>
        {isQuestion && <label className="answer-sort">回答排序
          <select aria-label="回答排序" value={answerSort} onChange={(event) => { setComments([]); setAnswerSort(event.target.value) }}>
            <option value="hot">热门</option><option value="latest">最新</option>
          </select>
        </label>}
        <form className="comment-form" onSubmit={handleCommentSubmit}>
          <label>
            <span className="sr-only">{isQuestion ? '回答内容' : '评论内容'}</span>
            <textarea disabled={actionLoading} onChange={(event) => setCommentContent(event.target.value)} placeholder={currentUser ? (isQuestion ? '描述你的解法、步骤和依据…' : '写下你的看法…') : '登录后可以参与讨论'} value={commentContent} required />
          </label>
          <button className="primary-button" disabled={actionLoading} type="submit">{isQuestion ? '提交回答' : '发表评论'}</button>
        </form>
        {commentsLoading && <LoadingSpinner text="正在加载讨论…" />}
        <MessageBanner message={commentsError || mineError} type="error" />
        {(commentsError || mineError) && <button className="ghost-button" type="button" onClick={() => refreshComments()}>重试讨论</button>}
        {!commentsError && <CommentDiscussion loading={commentsLoading} key={`${articleId}:${identity}`} comments={comments} currentUser={currentUser} isQuestion={isQuestion}
          isQuestionOwner={isQuestionOwner} acceptedAnswerId={article.acceptedAnswerId} articleId={articleId}
          actionLoading={actionLoading} onAction={handleAction} onReport={(id) => setReportTarget({ type: 'comment', id })}
          onAdopt={(id) => handleAction(
            () => Number(article.acceptedAnswerId) === Number(id) ? cancelAcceptedAnswer(articleId) : acceptAnswer(articleId, id),
            Number(article.acceptedAnswerId) === Number(id) ? '已取消采纳' : '回答已采纳',
          )} />}
      </section>
      )}

      {reportTarget && <ConfirmDialog title={reportTarget.type === 'article' ? (isQuestion ? '举报问题' : '举报文章') : (isQuestion ? '举报回答' : '举报评论')} message="请说明具体原因，管理员会据此处理。" inputLabel="举报原因" onConfirm={handleReport} onCancel={() => setReportTarget(null)} confirmText="提交举报" danger />}
    </div>
  )
}

export default ArticleDetail
