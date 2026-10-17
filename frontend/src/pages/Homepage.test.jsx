import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { createMemoryRouter, Link, Outlet, RouterProvider, useLocation, useNavigate } from 'react-router'
import ArticleList from './ArticleList.jsx'
import FollowFeed from './FollowFeed.jsx'
import App from '../App.jsx'
import { getHotArticles, pageArticles, searchArticles } from '../api/articleApi.js'
import { listCategories } from '../api/categoryApi.js'
import { getFollowFeed } from '../api/topicApi.js'
import { getCurrentUser, saveAuth } from '../api/request.js'
import { getCurrentUserProfile, loginUser } from '../api/userApi.js'
import { getUnreadNotificationCount } from '../api/notificationApi.js'
vi.mock('../api/articleApi.js', () => ({ getHotArticles: vi.fn(), pageArticles: vi.fn(), searchArticles: vi.fn() }))
vi.mock('../api/categoryApi.js', () => ({ listCategories: vi.fn() }))
vi.mock('../api/topicApi.js', () => ({ getFollowFeed: vi.fn() }))
vi.mock('../api/request.js', () => ({ getCurrentUser: vi.fn(), saveAuth: vi.fn(), clearAuth: vi.fn() }))
vi.mock('../api/userApi.js', () => ({ getCurrentUserProfile: vi.fn(), loginUser: vi.fn() }))
vi.mock('../api/notificationApi.js', () => ({ getUnreadNotificationCount: vi.fn() }))
vi.mock('../components/AiAssistantPanel.jsx', () => ({ default: () => null }))
const user = { userId: 7, username: 'reader', role: 'USER' }
const row = (id, title = `公开内容${id}`, extra = {}) => ({ id, title, content: '正文', status: 'PUBLISHED', contentType: 'ARTICLE', userId: 9, categoryId: 2, categoryName: 'Java', categoryAvailable: true, ...extra })
const page = (rows = [], current = 1, pages = 1, total = rows.length) => ({ data: { records: rows, current, pages, total } })
const hot = (rows = []) => ({ data: rows.map((article, i) => ({ article, hotScore: 100 - i * 10 })) })
const feed = (rows = [], nextCursor = null, hasMore = false) => ({ data: { records: rows, nextCursor, hasMore } })
function deferred() { let resolve; const promise = new Promise((yes) => { resolve = yes }); return { resolve, promise } }
let router
function Shell({ initialUser }) {
  const [currentUser, setUser] = useState(initialUser), location = useLocation(), navigate = useNavigate()
  return <><output aria-label="当前位置">{location.pathname}{location.search}</output><button onClick={() => navigate(-1)}>后退</button><button onClick={() => setUser(null)}>退出测试账号</button><Link to="/else">离开列表</Link><Outlet context={{ currentUser }} /></>
}
function mount(path = '/', currentUser = null) {
  router = createMemoryRouter([{ element: <Shell initialUser={currentUser} />, children: [
    { path: '/', element: <ArticleList /> }, { path: '/questions', element: <ArticleList contentType="QUESTION" /> },
    { path: '/following', element: <FollowFeed /> }, { path: '/else', element: <p>另一页面</p> },
  ] }], { initialEntries: [path] })
  return render(<RouterProvider router={router} />)
}
const navigation = () => within(screen.getByRole('navigation', { name: '内容来源' }))
beforeEach(() => {
  vi.resetAllMocks()
  listCategories.mockResolvedValue({ data: [{ id: 2, name: 'Java' }] })
  getHotArticles.mockResolvedValue(hot()); pageArticles.mockResolvedValue(page()); searchArticles.mockResolvedValue(page()); getFollowFeed.mockResolvedValue(feed())
  getCurrentUser.mockReturnValue(null); getCurrentUserProfile.mockResolvedValue({ data: user }); getUnreadNotificationCount.mockResolvedValue({ data: 0 })
})
afterEach(() => { cleanup(); router?.dispose() })
describe('P1-B 内容入口与 URL', () => {
  it('defaults to latest with semantic links and anonymous guidance', async () => {
    pageArticles.mockResolvedValue(page([row(1)])); mount(); await screen.findByRole('link', { name: '公开内容1' })
    expect(navigation().getByRole('link', { name: '最新' }).getAttribute('aria-current')).toBe('page')
    expect(navigation().getByRole('link', { name: '热门' }).getAttribute('href')).toBe('/?feed=hot')
    expect(navigation().getByRole('link', { name: '关注' }).getAttribute('href')).toBe('/following')
    expect(screen.getByRole('link', { name: '登录查看关注' }).getAttribute('href')).toBe('/login?redirectTo=%2Ffollowing')
    expect(screen.getByRole('link', { name: '分享内容' }).getAttribute('href')).toBe('/publish')
    expect(pageArticles).toHaveBeenCalledWith({ pageNum: 1, pageSize: 8 }); expect(getFollowFeed).not.toHaveBeenCalled()
  })
  it('direct hot links display ranking/type labels without latest filters or pagination', async () => {
    getHotArticles.mockResolvedValue(hot([row(8, '第一名问题', { contentType: 'QUESTION' }), row(3, '第二名文章')]))
    mount('/?feed=hot&q=不支持的搜索&categoryId=2&page=9')
    const region = screen.getByRole('region', { name: '热门内容列表' }); await within(region).findByRole('link', { name: '第一名问题' })
    expect(within(region).getAllByRole('heading', { level: 3 }).map((e) => e.textContent)).toEqual(['第一名问题', '第二名文章'])
    expect(within(region).getByText('热度 100')).toBeTruthy(); expect(within(region).getByText('问题')).toBeTruthy()
    expect(navigation().getByRole('link', { name: '热门' }).getAttribute('aria-current')).toBe('page')
    expect(screen.queryByRole('textbox')).toBeNull(); expect(screen.queryByRole('combobox')).toBeNull(); expect(screen.queryByRole('navigation', { name: '分页导航' })).toBeNull()
    expect(pageArticles).not.toHaveBeenCalled(); expect(searchArticles).not.toHaveBeenCalled(); expect(getFollowFeed).not.toHaveBeenCalled()
    expect(screen.getByRole('link', { name: 'Java最新内容' }).getAttribute('href')).toBe('/?categoryId=2')
    expect(screen.getByText('已显示当前热榜全部内容。')).toBeTruthy()
  })
  it('browser back restores latest keyword/category/page from hot', async () => {
    searchArticles.mockResolvedValue(page([row(4)], 3, 4, 30)); mount('/?q=Java&categoryId=2&page=3'); await screen.findByRole('link', { name: '公开内容4' })
    fireEvent.click(navigation().getByRole('link', { name: '热门' })); await screen.findByRole('heading', { name: '热门内容', exact: true })
    expect(screen.getByLabelText('当前位置').textContent).toBe('/?feed=hot'); fireEvent.click(screen.getByRole('button', { name: '后退' })); await screen.findByRole('link', { name: '公开内容4' })
    expect(screen.getByRole('textbox', { name: '搜索内容' }).value).toBe('Java'); expect(screen.getByRole('combobox', { name: '浏览板块' }).value).toBe('2'); expect(screen.getByText('第 3 / 4 页')).toBeTruthy()
    expect(searchArticles).toHaveBeenLastCalledWith({ pageNum: 3, pageSize: 8, categoryId: '2', keyword: 'Java' })
  })
  it('paging retains search/category and a new search resets page', async () => {
    searchArticles.mockResolvedValue(page([row(1)], 2, 3, 20)); mount('/?q=Java&categoryId=2&page=2'); await screen.findByRole('link', { name: '公开内容1' })
    fireEvent.click(screen.getByRole('button', { name: '下一页' })); await waitFor(() => expect(searchArticles).toHaveBeenLastCalledWith({ pageNum: 3, pageSize: 8, categoryId: '2', keyword: 'Java' }))
    fireEvent.change(screen.getByRole('textbox', { name: '搜索内容' }), { target: { value: ' 并发 ' } }); fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(searchArticles).toHaveBeenLastCalledWith({ pageNum: 1, pageSize: 8, categoryId: '2', keyword: '并发' }))
  })
  it('switching sources ignores a late latest response', async () => {
    const late = deferred(); pageArticles.mockReturnValueOnce(late.promise).mockResolvedValueOnce(page([row(2, '新的最新内容')]))
    getHotArticles.mockResolvedValue(hot([row(8, '当前热榜')])); mount(); fireEvent.click(navigation().getByRole('link', { name: '热门' })); await screen.findByRole('link', { name: '当前热榜' })
    fireEvent.click(navigation().getByRole('link', { name: '最新' })); await screen.findByRole('link', { name: '新的最新内容' }); await act(async () => late.resolve(page([row(1, '旧请求内容')])))
    expect(screen.queryByRole('link', { name: '旧请求内容' })).toBeNull(); expect(pageArticles).toHaveBeenCalledTimes(2)
  })
  it.each(['-1', '1.5', 'Infinity', 'NaN'])('normalizes invalid page %s', async (value) => {
    mount(`/?page=${value}`); await waitFor(() => expect(pageArticles).toHaveBeenCalledWith({ pageNum: 1, pageSize: 8 }))
  })
  it('leaving the hot page ignores a late rank response', async () => {
    const late = deferred(); getHotArticles.mockReturnValueOnce(late.promise); mount('/?feed=hot'); fireEvent.click(screen.getByRole('link', { name: '离开列表' })); await screen.findByText('另一页面')
    await act(async () => late.resolve(hot([row(8, '过期热榜')]))); expect(screen.queryByText('过期热榜')).toBeNull()
  })
  it('unknown source values fall back to latest', async () => {
    mount('/?feed=recommended'); await waitFor(() => expect(pageArticles).toHaveBeenCalledTimes(1)); expect(navigation().getByRole('link', { name: '最新' }).getAttribute('aria-current')).toBe('page')
  })
  it('question lists retain their own filter semantics', async () => {
    mount('/questions?feed=hot&categoryId=2'); await waitFor(() => expect(pageArticles).toHaveBeenCalledWith({ pageNum: 1, pageSize: 8, contentType: 'QUESTION', categoryId: '2' }))
    expect(screen.queryByRole('navigation', { name: '内容来源' })).toBeNull(); expect(screen.getByRole('link', { name: '提出问题' }).getAttribute('href')).toBe('/publish?type=QUESTION')
  })
})
describe('P1-B 错误隔离与关注', () => {
  it('hot failure offers retry instead of an empty ranking', async () => {
    getHotArticles.mockRejectedValueOnce(new Error('热榜暂不可用')).mockResolvedValueOnce(hot([row(6, '恢复热榜')]))
    mount('/?feed=hot'); await screen.findByText('热榜暂不可用'); expect(screen.queryByText(/暂时还没有热门内容/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试热榜' })); await screen.findByRole('link', { name: '恢复热榜' }); expect(pageArticles).not.toHaveBeenCalled(); expect(listCategories).toHaveBeenCalledTimes(1)
  })
  it('empty hot state retains discovery and posting routes', async () => {
    mount('/?feed=hot'); await screen.findByText(/暂时还没有热门内容/); expect(screen.getByRole('link', { name: '前往最新内容' }).getAttribute('href')).toBe('/')
    expect(screen.getByRole('link', { name: '浏览话题' })).toBeTruthy(); expect(screen.getByRole('link', { name: '分享内容' })).toBeTruthy()
  })
  it('hot refresh disables repeat requests and replaces the ranking', async () => {
    const next = deferred(); getHotArticles.mockResolvedValueOnce(hot([row(3, '之前的热榜')])).mockReturnValueOnce(next.promise)
    mount('/?feed=hot'); await screen.findByRole('link', { name: '之前的热榜' }); fireEvent.click(screen.getByRole('button', { name: '刷新热榜' }))
    const refresh = screen.getByRole('button', { name: '刷新热榜' }); expect(refresh.disabled).toBe(true); fireEvent.click(refresh); expect(getHotArticles).toHaveBeenCalledTimes(2); expect(screen.queryByRole('link', { name: '之前的热榜' })).toBeNull()
    await act(async () => next.resolve(hot([row(4, '刷新后的热榜')]))); await screen.findByRole('link', { name: '刷新后的热榜' })
  })
  it('category retry does not reload a healthy ranking', async () => {
    listCategories.mockRejectedValueOnce(new Error('目录失败')).mockResolvedValueOnce({ data: [{ id: 2, name: 'Java' }] }); getHotArticles.mockResolvedValue(hot([row(1, '不受影响的热榜')]))
    mount('/?feed=hot'); await screen.findByRole('link', { name: '不受影响的热榜' }); fireEvent.click(await screen.findByRole('button', { name: '重试话题' })); await screen.findByRole('link', { name: 'Java最新内容' }); expect(getHotArticles).toHaveBeenCalledTimes(1)
  })
  it('latest retry does not reload other sections', async () => {
    pageArticles.mockRejectedValueOnce(new Error('最新读取失败')).mockResolvedValueOnce(page()); mount(); await screen.findByText('最新读取失败'); expect(screen.queryByText(/还没有符合条件/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试列表' })); await screen.findByText(/还没有符合条件/); expect(getHotArticles).toHaveBeenCalledTimes(1); expect(listCategories).toHaveBeenCalledTimes(1)
  })
  it('anonymous FollowFeed sends no private request', async () => {
    mount('/following'); await screen.findByRole('link', { name: '登录查看关注' }); expect(navigation().getByRole('link', { name: '关注' }).getAttribute('aria-current')).toBe('page')
    expect(getFollowFeed).not.toHaveBeenCalled(); expect(screen.queryByRole('button', { name: '刷新动态' })).toBeNull()
  })
  it('empty authenticated feed links to topics and author discovery', async () => {
    mount('/following', user); await screen.findByText(/暂无关注动态/); expect(screen.getByRole('link', { name: '发现感兴趣的话题' }).getAttribute('href')).toBe('/topics'); expect(screen.getByRole('link', { name: '从最新内容发现作者' }).getAttribute('href')).toBe('/')
  })
  it('logout removes private feed cards without further requests', async () => {
    getFollowFeed.mockResolvedValue(feed([row(1, '当前用户的动态')])); mount('/following', user); await screen.findByRole('link', { name: '当前用户的动态' }); fireEvent.click(screen.getByRole('button', { name: '退出测试账号' }))
    await screen.findByRole('link', { name: '登录查看关注' }); expect(screen.queryByRole('link', { name: '当前用户的动态' })).toBeNull(); expect(getFollowFeed).toHaveBeenCalledTimes(1)
  })
  it('invalid follow types fall back to all content', async () => {
    mount('/following?type=OTHER', user); await waitFor(() => expect(getFollowFeed).toHaveBeenCalledWith({ pageSize: 8 })); expect(screen.getByRole('combobox', { name: '内容类型' }).value).toBe('')
  })
  it('real App login gate returns anonymous readers to following after login', async () => {
    loginUser.mockResolvedValue({ data: { ...user, token: 'synthetic-token' } }); saveAuth.mockImplementation(() => getCurrentUser.mockReturnValue(user)); getFollowFeed.mockResolvedValue(feed([row(9, '登录后的关注动态')]))
    router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: ['/?feed=hot'] }); render(<RouterProvider router={router} />)
    fireEvent.click(navigation().getByRole('link', { name: '关注' })); await screen.findByRole('heading', { name: '登录 Bit Forum' }); expect(router.state.location.search).toBe('?redirectTo=%2Ffollowing'); expect(getFollowFeed).not.toHaveBeenCalled()
    fireEvent.change(screen.getByRole('textbox', { name: '用户名' }), { target: { value: 'reader' } }); fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'synthetic' } }); fireEvent.click(screen.getByRole('button', { name: '登录', exact: true }))
    await screen.findByRole('link', { name: '登录后的关注动态' }); expect(router.state.location.pathname).toBe('/following')
  })
})
