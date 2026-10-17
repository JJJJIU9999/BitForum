import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import ArticleList from './ArticleList.jsx'
import PublishArticle from './PublishArticle.jsx'
import { getHotArticles, pageArticles, searchArticles, publishArticle, saveDraft } from '../api/articleApi.js'
import { listCategories } from '../api/categoryApi.js'

vi.mock('../api/articleApi.js', () => ({ getHotArticles: vi.fn(), pageArticles: vi.fn(), searchArticles: vi.fn(), publishArticle: vi.fn(), saveDraft: vi.fn() }))
vi.mock('../api/categoryApi.js', () => ({ listCategories: vi.fn() }))
vi.mock('../api/uploadApi.js', () => ({ uploadArticleCover: vi.fn() }))

beforeEach(() => {
  vi.clearAllMocks()
  listCategories.mockResolvedValue({ data: [{ id: 2, name: 'Java' }] })
  getHotArticles.mockResolvedValue({ data: [] })
  pageArticles.mockResolvedValue({ data: { records: [], total: 0, current: 1, pages: 1 } })
  searchArticles.mockResolvedValue({ data: { records: [], total: 0, current: 1, pages: 1 } })
  publishArticle.mockResolvedValue({})
  saveDraft.mockResolvedValue({})
})
afterEach(cleanup)

describe('问答列表', () => {
  it('keeps the content list available when hot discussions fail', async () => {
    getHotArticles.mockRejectedValue(new Error('热榜不可用'))
    pageArticles.mockResolvedValue({ data: { records: [{ id: 2, title: '正常问题', contentType: 'QUESTION', answerCount: 3 }], total: 1, current: 1, pages: 1 } })
    render(<MemoryRouter><ArticleList contentType="QUESTION" /></MemoryRouter>)
    expect(await screen.findByRole('link', { name: '正常问题' })).toBeTruthy()
    expect(screen.getByText('3 个回答')).toBeTruthy(); expect(screen.getByText('待采纳')).toBeTruthy()
    expect(await screen.findByRole('button', { name: '重试热榜' })).toBeTruthy()
  })
  it('retries only hot discussions without clearing or requesting the healthy feed', async () => {
    let resolveRetry
    getHotArticles.mockRejectedValueOnce(new Error('热榜失败'))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRetry = resolve }))
    pageArticles.mockResolvedValue({ data: { records: [{ id: 2, title: '保留的正常问题', contentType: 'QUESTION' }], total: 1, current: 1, pages: 1 } })
    render(<MemoryRouter><ArticleList contentType="QUESTION" /></MemoryRouter>)
    const title = await screen.findByRole('link', { name: '保留的正常问题' })
    fireEvent.click(await screen.findByRole('button', { name: '重试热榜' }))
    expect(screen.getByRole('link', { name: '保留的正常问题' })).toBe(title)
    expect(screen.queryByText('正在整理最新问题...')).toBeNull()
    expect(pageArticles).toHaveBeenCalledTimes(1)
    expect(listCategories).toHaveBeenCalledTimes(1)
    expect(getHotArticles).toHaveBeenCalledTimes(2)
    await act(async () => resolveRetry({ data: [{ article: { id: 3, title: '恢复的热榜' }, hotScore: 1 }] }))
    expect(await screen.findByRole('button', { name: /恢复的热榜/ })).toBeTruthy()
    expect(pageArticles).toHaveBeenCalledTimes(1)
    expect(listCategories).toHaveBeenCalledTimes(1)
  })

  it('retries only categories without reloading the feed or hot discussions', async () => {
    let resolveRetry
    listCategories.mockRejectedValueOnce(new Error('话题失败'))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRetry = resolve }))
    pageArticles.mockResolvedValue({ data: { records: [{ id: 2, title: '话题异常时仍可阅读', contentType: 'QUESTION' }], total: 1, current: 1, pages: 1 } })
    render(<MemoryRouter><ArticleList contentType="QUESTION" /></MemoryRouter>)
    await screen.findByRole('link', { name: '话题异常时仍可阅读' })
    fireEvent.click(await screen.findByRole('button', { name: '重试话题' }))
    expect(screen.getByRole('link', { name: '话题异常时仍可阅读' })).toBeTruthy()
    expect(pageArticles).toHaveBeenCalledTimes(1)
    expect(getHotArticles).toHaveBeenCalledTimes(1)
    await act(async () => resolveRetry({ data: [{ id: 2, name: '恢复话题' }] }))
    expect(await screen.findByRole('option', { name: '恢复话题' })).toBeTruthy()
    expect(listCategories).toHaveBeenCalledTimes(2)
    expect(pageArticles).toHaveBeenCalledTimes(1)
    expect(getHotArticles).toHaveBeenCalledTimes(1)
  })

  it('shows errors rather than an empty-result claim and retries only reads', async () => {
    pageArticles.mockRejectedValueOnce(new Error('列表不可用')).mockResolvedValueOnce({ data: { records: [], total: 0, current: 1, pages: 1 } })
    render(<MemoryRouter><ArticleList contentType="QUESTION" /></MemoryRouter>)
    await screen.findByText('列表不可用')
    expect(screen.queryByText(/还没有符合条件/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试列表' }))
    expect(await screen.findByText(/还没有符合条件/)).toBeTruthy()
    expect(publishArticle).not.toHaveBeenCalled()
    expect(pageArticles).toHaveBeenCalledTimes(2)
    expect(getHotArticles).toHaveBeenCalledTimes(1)
    expect(listCategories).toHaveBeenCalledTimes(1)
  })
  it('按 QUESTION 类型和话题查询，搜索保持同一筛选', async () => {
    render(<MemoryRouter initialEntries={['/questions?categoryId=2']}><ArticleList contentType="QUESTION" /></MemoryRouter>)
    await waitFor(() => expect(pageArticles).toHaveBeenCalledWith({ pageNum: 1, pageSize: 8, contentType: 'QUESTION', categoryId: '2' }))
    expect(screen.getByRole('link', { name: '提出问题' }).getAttribute('href')).toBe('/publish?type=QUESTION')
    fireEvent.change(screen.getByPlaceholderText('搜索标题或正文'), { target: { value: '线程池' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(searchArticles).toHaveBeenCalledWith({ pageNum: 1, pageSize: 8, contentType: 'QUESTION', categoryId: '2', keyword: '线程池' }))
  })

  it('从文章切换问答后，过期文章请求不能覆盖问题列表', async () => {
    let resolveLegacy
    pageArticles.mockReturnValueOnce(new Promise((resolve) => { resolveLegacy = resolve }))
      .mockResolvedValueOnce({ data: { records: [{ id: 2, title: '新问题', content: '问题描述', contentType: 'QUESTION' }], total: 1, current: 1, pages: 1 } })
    const view = render(<MemoryRouter><ArticleList /></MemoryRouter>)
    await waitFor(() => expect(pageArticles).toHaveBeenCalledTimes(1))
    view.rerender(<MemoryRouter><ArticleList contentType="QUESTION" /></MemoryRouter>)
    await screen.findByText('新问题')
    await act(async () => resolveLegacy({ data: { records: [{ id: 1, title: '过期文章', content: '正文' }], total: 1, current: 1, pages: 1 } }))
    expect(screen.queryByText('过期文章')).toBeNull()
    expect(screen.getByText('新问题')).toBeTruthy()
  })

  it('问题卡片区分待解答与已采纳', async () => {
    pageArticles.mockResolvedValue({ data: { records: [
      { id: 1, title: '已解决问题', content: '描述', userId: 1, categoryName: 'Java', contentType: 'QUESTION', acceptedAnswerId: 20 },
      { id: 2, title: '仍需帮助', content: '描述', userId: 1, categoryName: 'Java', contentType: 'QUESTION' },
    ], total: 2, current: 1, pages: 1 } })
    render(<MemoryRouter><ArticleList contentType="QUESTION" /></MemoryRouter>)
    expect(await screen.findByText('已采纳')).toBeTruthy()
    expect(screen.getByText('待解答')).toBeTruthy()
  })
})

describe('提问表单', () => {
  it('提问入口默认 QUESTION，提交复用文章接口并携带话题', async () => {
    render(<MemoryRouter initialEntries={['/publish?type=QUESTION']}><PublishArticle /></MemoryRouter>)
    await screen.findByRole('option', { name: 'Java' })
    fireEvent.change(screen.getByLabelText('问题标题'), { target: { value: '为什么线程阻塞？' } })
    fireEvent.change(screen.getByLabelText('问题描述'), { target: { value: '已检查锁与线程日志' } })
    fireEvent.click(screen.getByRole('button', { name: '提交审核' }))
    await waitFor(() => expect(publishArticle).toHaveBeenCalledWith({ title: '为什么线程阻塞？', content: '已检查锁与线程日志', categoryId: 2, coverImageId: null, contentType: 'QUESTION', contentFormat: 'MARKDOWN' }))
  })

  it('旧投稿入口默认 ARTICLE，切换问题后保存 QUESTION 草稿', async () => {
    render(<MemoryRouter initialEntries={['/publish']}><PublishArticle /></MemoryRouter>)
    await screen.findByRole('option', { name: 'Java' })
    expect(screen.getByLabelText('内容类型').value).toBe('ARTICLE')
    fireEvent.change(screen.getByLabelText('内容类型'), { target: { value: 'QUESTION' } })
    fireEvent.change(screen.getByLabelText('问题标题'), { target: { value: '待完善的问题' } })
    fireEvent.change(screen.getByLabelText('问题描述'), { target: { value: '已有背景' } })
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() => expect(saveDraft).toHaveBeenCalledWith(expect.objectContaining({ contentType: 'QUESTION' })))
  })
})
