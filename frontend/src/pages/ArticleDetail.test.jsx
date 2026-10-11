import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ArticleDetail from './ArticleDetail.jsx'
import {
  acceptAnswer,
  cancelAcceptedAnswer,
  favoriteArticle,
  getArticleDetail,
  getRecommendations,
  likeArticle,
  unfavoriteArticle,
  unlikeArticle,
  viewArticle,
} from '../api/articleApi.js'
import { listComments, listMyComments, publishComment } from '../api/commentApi.js'

// 文章详情操作区整改的回归测试。
//
// 整改前的缺陷：点赞和"取消点赞"是两个入口，收藏和"取消收藏"也是，
// 后者还藏在一个"更多操作"下拉里，展开后是三个与页面风格不一致的方框。
//
// 这里锁定整改后的契约：一个功能只有一个状态切换按钮，文案与 aria-pressed
// 都由服务端返回的 likedByCurrentUser / favoritedByCurrentUser 决定。
const mocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  articleId: { value: '88' },
  currentUser: { value: { id: 1, username: 'reader', role: 'USER' } },
}))

vi.mock('react-router', () => ({
  Link: ({ to, children, ...props }) => <a href={to} {...props}>{children}</a>,
  useParams: () => ({ articleId: mocks.articleId.value }),
  useNavigate: () => mocks.navigate,
  useOutletContext: () => ({ currentUser: mocks.currentUser.value }),
}))

vi.mock('../api/articleApi.js', () => ({
  getArticleDetail: vi.fn(),
  acceptAnswer: vi.fn(),
  cancelAcceptedAnswer: vi.fn(),
  viewArticle: vi.fn(),
  likeArticle: vi.fn(),
  unlikeArticle: vi.fn(),
  favoriteArticle: vi.fn(),
  unfavoriteArticle: vi.fn(),
  getRecommendations: vi.fn(),
}))

vi.mock('../api/commentApi.js', () => ({
  listComments: vi.fn(),
  listMyComments: vi.fn(),
  publishComment: vi.fn(),
  likeAnswer: vi.fn(),
  unlikeAnswer: vi.fn(),
}))

vi.mock('../api/reportApi.js', () => ({
  reportArticle: vi.fn(),
  reportComment: vi.fn(),
}))

const baseArticle = (overrides = {}) => ({
  id: 88,
  title: 'Redis 缓存穿透实践',
  content: '正文内容',
  status: 'PUBLISHED',
  userId: 5,
  categoryName: '后端',
  viewCount: 10,
  likeCount: 3,
  favoriteCount: 2,
  likedByCurrentUser: false,
  favoritedByCurrentUser: false,
  ...overrides,
})

async function renderDetail() {
  render(<ArticleDetail />)
  await screen.findByText('Redis 缓存穿透实践')
}

describe('ArticleDetail 操作区', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.articleId.value = '88'
    mocks.currentUser.value = { id: 1, username: 'reader', role: 'USER' }
    getArticleDetail.mockResolvedValue({ data: baseArticle() })
    viewArticle.mockResolvedValue({})
    listComments.mockResolvedValue({ data: [] })
    listMyComments.mockResolvedValue({ data: [] })
    getRecommendations.mockResolvedValue({ data: [] })
    likeArticle.mockResolvedValue({ message: '点赞成功。' })
    unlikeArticle.mockResolvedValue({ message: '已取消点赞。' })
    favoriteArticle.mockResolvedValue({ message: '收藏成功。' })
    unfavoriteArticle.mockResolvedValue({ message: '已取消收藏。' })
  })

  afterEach(() => {
    cleanup()
  })

  it('likes the article when it is not liked yet', async () => {
    await renderDetail()

    const button = screen.getByRole('button', { name: '点赞' })
    expect(button.getAttribute('aria-pressed')).toBe('false')

    button.click()

    await waitFor(() => expect(likeArticle).toHaveBeenCalledWith('88'))
    expect(unlikeArticle).not.toHaveBeenCalled()
  })

  it('keeps reading available when view tracking or discussion fails', async () => {
    viewArticle.mockRejectedValue(new Error('统计不可用'))
    listComments.mockRejectedValueOnce(new Error('讨论不可用')).mockResolvedValueOnce({ data: [] })
    await renderDetail()
    expect(await screen.findByRole('button', { name: '重试讨论' })).toBeTruthy()
    expect(screen.getByText('正文内容')).toBeTruthy()
    expect(screen.queryByText('暂无评论，来留下第一条观点吧。')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试讨论' }))
    expect(await screen.findByText('暂无评论，来留下第一条观点吧。')).toBeTruthy()
    expect(viewArticle).toHaveBeenCalledTimes(1)
  })

  it('does not hide public comments when the private mine request fails', async () => {
    listComments.mockResolvedValue({ data: [{ id: 3, userId: 2, parentCommentId: 0, status: 'PUBLISHED', content: '公开讨论仍可读' }] })
    listMyComments.mockRejectedValueOnce(new Error('本人查询失败'))
    await renderDetail()
    expect(await screen.findByText('公开讨论仍可读')).toBeTruthy()
    expect(screen.getByText(/我的未公开内容加载失败/)).toBeTruthy()
  })

  it('clears old content when navigating to a failing detail request', async () => {
    const view = render(<ArticleDetail />); await screen.findByText('Redis 缓存穿透实践')
    getArticleDetail.mockRejectedValueOnce(new Error('新内容不存在'))
    mocks.articleId.value = '99'; view.rerender(<ArticleDetail />)
    await screen.findByText('新内容不存在')
    expect(screen.queryByText('正文内容')).toBeNull()
    expect(screen.queryByText('Redis 缓存穿透实践')).toBeNull()
  })

  it('retains failed write input and never repeats a successful publication on read retry', async () => {
    publishComment.mockRejectedValueOnce(new Error('发布失败')).mockResolvedValueOnce({})
    await renderDetail(); await screen.findByText('暂无评论，来留下第一条观点吧。')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '提交内容' } })
    fireEvent.click(screen.getByRole('button', { name: '发表评论' }))
    await screen.findByText('发布失败'); expect(screen.getByRole('textbox').value).toBe('提交内容')
    listComments.mockRejectedValueOnce(new Error('刷新失败')).mockResolvedValueOnce({ data: [] })
    fireEvent.click(screen.getByRole('button', { name: '发表评论' }))
    await screen.findByText('内容已提交，列表刷新失败；请重试加载，勿重复提交。')
    expect(screen.getByRole('textbox').value).toBe('')
    fireEvent.click(screen.getByRole('button', { name: '重试讨论' }))
    await screen.findByText('暂无评论，来留下第一条观点吧。')
    expect(publishComment).toHaveBeenCalledTimes(2)
  })

  it('keeps the reply component mounted and automatically expands a saved reply', async () => {
    const root = { id: 3, userId: 2, parentCommentId: 0, status: 'PUBLISHED', content: '顶层回答' }
    listComments.mockResolvedValueOnce({ data: [root] }).mockResolvedValue({ data: [root, { id: 4, parentCommentId: 3, userId: 1, status: 'PUBLISHED', content: '刚提交的回复' }] })
    publishComment.mockResolvedValue({})
    await renderDetail(); await screen.findByText('顶层回答')
    fireEvent.click(screen.getByRole('button', { name: '回复评论 #3' }))
    fireEvent.change(screen.getByRole('textbox', { name: '回复评论 #3' }), { target: { value: '刚提交的回复' } })
    fireEvent.click(screen.getByRole('button', { name: '提交回复' }))
    expect(await screen.findByText('刚提交的回复')).toBeTruthy()
    expect(screen.getByRole('button', { name: '收起回复（1）' })).toBeTruthy()
  })

  it('unlikes the article when it is already liked', async () => {
    getArticleDetail.mockResolvedValue({ data: baseArticle({ likedByCurrentUser: true }) })
    await renderDetail()

    const button = screen.getByRole('button', { name: '已点赞' })
    expect(button.getAttribute('aria-pressed')).toBe('true')

    button.click()

    await waitFor(() => expect(unlikeArticle).toHaveBeenCalledWith('88'))
    expect(likeArticle).not.toHaveBeenCalled()
  })

  it('favorites the article when it is not favorited yet', async () => {
    await renderDetail()

    const button = screen.getByRole('button', { name: '收藏' })
    expect(button.getAttribute('aria-pressed')).toBe('false')

    button.click()

    await waitFor(() => expect(favoriteArticle).toHaveBeenCalledWith('88'))
    expect(unfavoriteArticle).not.toHaveBeenCalled()
  })

  it('unfavorites the article when it is already favorited', async () => {
    getArticleDetail.mockResolvedValue({ data: baseArticle({ favoritedByCurrentUser: true }) })
    await renderDetail()

    const button = screen.getByRole('button', { name: '已收藏' })
    expect(button.getAttribute('aria-pressed')).toBe('true')

    button.click()

    await waitFor(() => expect(unfavoriteArticle).toHaveBeenCalledWith('88'))
    expect(favoriteArticle).not.toHaveBeenCalled()
  })

  it('reloads the detail after a successful toggle and shows the new state', async () => {
    getArticleDetail
      .mockResolvedValueOnce({ data: baseArticle() })
      .mockResolvedValueOnce({ data: baseArticle({ likedByCurrentUser: true, likeCount: 4 }) })

    const { container } = render(<ArticleDetail />)
    await screen.findByText('Redis 缓存穿透实践')
    screen.getByRole('button', { name: '点赞' }).click()

    await waitFor(() => expect(likeArticle).toHaveBeenCalledWith('88'))
    // 重新拉取详情后，同一个按钮变成"已点赞"，点赞数同步更新
    expect(await screen.findByRole('button', { name: '已点赞' })).toBeTruthy()
    expect(container.querySelector('.metric-row').textContent).toMatch(/4\s*点赞/)
  })

  it('keeps exactly one entry per interaction and drops the more-actions menu', async () => {
    const { container } = render(<ArticleDetail />)
    await screen.findByText('Redis 缓存穿透实践')

    expect(screen.queryByText('更多操作')).toBeNull()
    expect(screen.queryByText('取消点赞')).toBeNull()
    expect(screen.queryByText('取消收藏')).toBeNull()
    expect(container.querySelector('.article-more-actions')).toBeNull()
    expect(container.querySelector('details')).toBeNull()

    // 点赞、收藏各只有一个按钮
    expect(screen.getAllByRole('button', { name: '点赞' })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: '收藏' })).toHaveLength(1)
  })

  it('opens the report dialog for a logged-in reader', async () => {
    await renderDetail()

    const reportButton = screen.getByRole('button', { name: '举报文章' })
    reportButton.click()

    expect(await screen.findByLabelText('举报原因')).toBeTruthy()
  })

  it('hides the report button for anonymous visitors', async () => {
    mocks.currentUser.value = null

    await renderDetail()

    expect(screen.queryByRole('button', { name: '举报文章' })).toBeNull()
    // 作者主页与点赞/收藏对匿名访客仍然可见（点击后再引导登录）
    expect(screen.getByRole('link', { name: '查看社区用户的主页' }).getAttribute('href')).toBe('/users/5')
    expect(screen.getByRole('button', { name: '点赞' })).toBeTruthy()
  })

  it('reflects both interaction states in aria-pressed', async () => {
    getArticleDetail.mockResolvedValue({
      data: baseArticle({ likedByCurrentUser: true, favoritedByCurrentUser: false }),
    })

    await renderDetail()

    expect(screen.getByRole('button', { name: '已点赞' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: '收藏' }).getAttribute('aria-pressed')).toBe('false')
  })
})


describe('ArticleDetail 问题与采纳', () => {
  const publicAnswer = { id: 101, userId: 7, articleId: 88, parentCommentId: 0, status: 'PUBLISHED', content: '使用索引解决查询问题' }
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.articleId.value = '88'
    mocks.currentUser.value = { userId: 5, username: 'author', role: 'USER' }
    getArticleDetail.mockResolvedValue({ data: baseArticle({ contentType: 'QUESTION', acceptedAnswerId: null }) })
    listComments.mockResolvedValue({ data: [publicAnswer] })
    listMyComments.mockResolvedValue({ data: [] })
    viewArticle.mockResolvedValue({})
    getRecommendations.mockResolvedValue({ data: [] })
    acceptAnswer.mockResolvedValue({ message: '回答已采纳' })
    cancelAcceptedAnswer.mockResolvedValue({ message: '已取消采纳' })
  })
  afterEach(cleanup)

  it('作者采纳后从详情回读状态并可取消', async () => {
    await renderDetail()
    const accept = await screen.findByRole('button', { name: '采纳回答 #101' })
    getArticleDetail.mockResolvedValue({ data: baseArticle({ contentType: 'QUESTION', acceptedAnswerId: 101 }) })
    accept.click()
    await waitFor(() => expect(acceptAnswer).toHaveBeenCalledWith('88', 101))
    expect(await screen.findByText('提问者已采纳')).toBeTruthy()
    const cancel = await screen.findByRole('button', { name: '取消采纳回答 #101' })
    getArticleDetail.mockResolvedValue({ data: baseArticle({ contentType: 'QUESTION', acceptedAnswerId: null }) })
    cancel.click()
    await waitFor(() => expect(cancelAcceptedAnswer).toHaveBeenCalledWith('88'))
    await waitFor(() => expect(screen.queryByText('提问者已采纳')).toBeNull())
  })

  it('其他用户和访客看不到采纳入口', async () => {
    mocks.currentUser.value = { userId: 7 }
    await renderDetail()
    await screen.findByText(publicAnswer.content)
    expect(screen.queryByRole('button', { name: /采纳回答/ })).toBeNull()
    cleanup()
    mocks.currentUser.value = null
    await renderDetail()
    await screen.findByText(publicAnswer.content)
    expect(screen.queryByRole('button', { name: /采纳回答/ })).toBeNull()
  })

  it('待审回答和非顶层回答没有采纳按钮', async () => {
    listComments.mockResolvedValue({ data: [{ ...publicAnswer, parentCommentId: 50 }] })
    listMyComments.mockResolvedValue({ data: [{ ...publicAnswer, id: 102, userId: 5, status: 'PENDING', content: '我的待审回答' }] })
    await renderDetail()
    await screen.findByText('我的待审回答')
    expect(screen.queryByRole('button', { name: /采纳回答/ })).toBeNull()
    expect(screen.getByText('待人工审核，仅自己可见')).toBeTruthy()
    expect(screen.getByRole('button', { name: '提交回答' })).toBeTruthy()
  })

  it('采纳被拒绝时显示错误并保留当前状态', async () => {
    acceptAnswer.mockRejectedValue(new Error('只能采纳本问题已公开的顶层回答'))
    await renderDetail()
    ;(await screen.findByRole('button', { name: '采纳回答 #101' })).click()
    expect(await screen.findByText('只能采纳本问题已公开的顶层回答')).toBeTruthy()
    expect(screen.queryByText('提问者已采纳')).toBeNull()
  })
  it('requests server sorting and counts only public top-level answers', async () => {
    getArticleDetail.mockResolvedValue({ data: baseArticle({ contentType: 'QUESTION' }) })
    listComments.mockResolvedValue({ data: [
      { id: 1, parentCommentId: 0, userId: 2, status: 'PUBLISHED', content: 'public root' },
      { id: 2, parentCommentId: 1, userId: 3, status: 'PUBLISHED', content: 'public child' },
    ] })
    listMyComments.mockResolvedValue({ data: [{ id: 3, parentCommentId: 0, status: 'PENDING', content: 'own pending' }] })
    await renderDetail()
    expect(await screen.findByText('1 条公开回答')).toBeTruthy()
    expect(await screen.findByText('我的未公开回答（1）')).toBeTruthy()
    expect(listComments).toHaveBeenCalledWith('88', 'hot')
    fireEvent.change(screen.getByLabelText('回答排序'), { target: { value: 'latest' } })
    await waitFor(() => expect(listComments).toHaveBeenCalledWith('88', 'latest'))
  })

  it('ignores an older hot response that arrives after latest sorting', async () => {
    getArticleDetail.mockResolvedValue({ data: baseArticle({ contentType: 'QUESTION' }) })
    let resolveHot
    listComments.mockImplementation((id, sort) => sort === 'hot'
      ? new Promise((resolve) => { resolveHot = resolve })
      : Promise.resolve({ data: [{ id: 9, parentCommentId: 0, userId: 2, status: 'PUBLISHED', content: '最新有效回答' }] }))
    render(<ArticleDetail />)
    const select = await screen.findByLabelText('回答排序')
    await waitFor(() => expect(resolveHot).toBeTypeOf('function'))
    fireEvent.change(select, { target: { value: 'latest' } })
    await screen.findByText('最新有效回答')
    resolveHot({ data: [{ id: 8, parentCommentId: 0, status: 'PUBLISHED', content: '过期排序回答' }] })
    await waitFor(() => expect(screen.queryByText('过期排序回答')).toBeNull())
    expect(screen.getByText('最新有效回答')).toBeTruthy()
  })

})
