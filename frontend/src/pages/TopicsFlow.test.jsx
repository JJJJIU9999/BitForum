import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { Link, MemoryRouter, Outlet, Route, Routes } from 'react-router'
import FollowFeed from './FollowFeed.jsx'
import Topics from './Topics.jsx'
import TopicDetail from './TopicDetail.jsx'
import MyTopics from './MyTopics.jsx'
import TopicLink from '../components/TopicLink.jsx'
import { getFollowFeed, getTopic, followTopic, unfollowTopic, pageFollowedTopics, pageTopicContents } from '../api/topicApi.js'
import { listCategories } from '../api/categoryApi.js'
vi.mock('../api/topicApi.js', () => ({ getFollowFeed: vi.fn(), getTopic: vi.fn(), followTopic: vi.fn(), unfollowTopic: vi.fn(), pageFollowedTopics: vi.fn(), pageTopicContents: vi.fn() }))
vi.mock('../api/categoryApi.js', () => ({ listCategories: vi.fn() }))
const row = (id, title = `内容${id}`, extra = {}) => ({ id, title, contentType: 'ARTICLE', status: 'PUBLISHED', categoryId: 2, categoryName: 'Java', categoryAvailable: true, ...extra })
const feed = (rows, cursor = null, more = false) => ({ data: { records: rows, nextCursor: cursor, hasMore: more } })
const page = (rows) => ({ data: { records: rows, total: rows.length, current: 1, pages: 1 } })
function Shell({ initialUser }) {
  const [currentUser, setUser] = useState(initialUser)
  return <><Link to="/following">进入动态</Link><Link to="/else">离开</Link><button onClick={() => setUser({ userId: 9 })}>换用户</button><Outlet context={{ currentUser }} /></>
}
function mount(path = '/following', user = { userId: 7 }) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route element={<Shell initialUser={user} />}><Route path="following" element={<FollowFeed />} /><Route path="topics" element={<Topics />} /><Route path="topics/:categoryId" element={<TopicDetail />} /><Route path="me/topics" element={<MyTopics />} /><Route path="else" element={<p>另一页</p>} /><Route path="login" element={<p>登录页</p>} /></Route></Routes></MemoryRouter>)
}
beforeEach(() => {
  vi.clearAllMocks()
  getFollowFeed.mockResolvedValue(feed([]))
  listCategories.mockResolvedValue({ data: [{ id: 2, name: 'Java', description: '经验分享' }] })
  getTopic.mockResolvedValue({ data: { categoryId: 2, name: 'Java', description: '经验分享', status: 1, followedByCurrentUser: false } })
  pageTopicContents.mockResolvedValue(page([]))
  followTopic.mockResolvedValue({ data: { categoryId: 2, followedByCurrentUser: true } })
  unfollowTopic.mockResolvedValue({ data: { categoryId: 2, followedByCurrentUser: false } })
  pageFollowedTopics.mockResolvedValue(page([]))
})
afterEach(cleanup)
describe('关注动态', () => {
  it('renders mixed cards and deduplicates overlapping next batches', async () => {
    getFollowFeed.mockResolvedValueOnce(feed([row(1)], 'first', true)).mockResolvedValueOnce(feed([row(1), row(2, '新问题', { contentType: 'QUESTION', answerCount: 2 })]))
    mount(); fireEvent.click(await screen.findByRole('button', { name: '加载下一批' }))
    expect(await screen.findByRole('link', { name: '新问题' })).toBeTruthy()
    expect(screen.getAllByRole('link', { name: '内容1' })).toHaveLength(1)
    expect(getFollowFeed).toHaveBeenLastCalledWith({ pageSize: 8, cursor: 'first' })
  })
  it('retains cards after next-batch failure and retries the same cursor only', async () => {
    getFollowFeed.mockResolvedValueOnce(feed([row(1)], 'first', true)).mockRejectedValueOnce(new Error('暂不可用')).mockResolvedValueOnce(feed([row(2)]))
    mount(); fireEvent.click(await screen.findByRole('button', { name: '加载下一批' }))
    await screen.findByText('暂不可用'); expect(screen.getByRole('link', { name: '内容1' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试动态' })); await screen.findByRole('link', { name: '内容2' })
    expect(getFollowFeed.mock.calls.slice(1).map(([p]) => p.cursor)).toEqual(['first', 'first'])
    expect(followTopic).not.toHaveBeenCalled()
  })
  it('refreshes from the first batch and ignores a late request from the discarded list', async () => {
    let resolveLate
    getFollowFeed.mockResolvedValueOnce(feed([row(1)], 'first', true)).mockImplementationOnce(() => new Promise((r) => { resolveLate = r })).mockResolvedValueOnce(feed([row(3)]))
    mount(); fireEvent.click(await screen.findByRole('button', { name: '加载下一批' })); fireEvent.click(screen.getByRole('button', { name: '刷新动态' }))
    await screen.findByRole('link', { name: '内容3' }); await act(async () => resolveLate(feed([row(2)])))
    expect(screen.queryByRole('link', { name: '内容1' })).toBeNull(); expect(screen.queryByRole('link', { name: '内容2' })).toBeNull()
    expect(getFollowFeed).toHaveBeenLastCalledWith({ pageSize: 8 })
  })
  it('filter changes reset the cursor and clear the old type', async () => {
    getFollowFeed.mockResolvedValueOnce(feed([row(1)], 'first', true)).mockResolvedValueOnce(feed([row(2, '问题筛选', { contentType: 'QUESTION' })]))
    mount(); await screen.findByRole('link', { name: '内容1' }); fireEvent.change(screen.getByRole('combobox', { name: '内容类型' }), { target: { value: 'QUESTION' } })
    await screen.findByRole('link', { name: '问题筛选' }); expect(screen.queryByRole('link', { name: '内容1' })).toBeNull()
    expect(getFollowFeed).toHaveBeenLastCalledWith({ pageSize: 8, contentType: 'QUESTION' })
  })
  it('reentering the page always fetches first batch again', async () => {
    getFollowFeed.mockResolvedValueOnce(feed([row(1)], 'first', true)).mockResolvedValueOnce(feed([row(2)]))
    mount(); await screen.findByRole('button', { name: '加载下一批' }); fireEvent.click(screen.getByRole('link', { name: '离开' })); fireEvent.click(screen.getByRole('link', { name: '进入动态' }))
    await screen.findByRole('link', { name: '内容2' }); expect(getFollowFeed.mock.calls.map(([p]) => p)).toEqual([{ pageSize: 8 }, { pageSize: 8 }])
  })
  it('switching user discards late content and requests no old cursor', async () => {
    let resolveOld
    getFollowFeed.mockImplementationOnce(() => new Promise((r) => { resolveOld = r })).mockResolvedValueOnce(feed([row(9)]))
    mount(); fireEvent.click(screen.getByRole('button', { name: '换用户' })); await screen.findByRole('link', { name: '内容9' }); await act(async () => resolveOld(feed([row(7)])))
    expect(screen.queryByRole('link', { name: '内容7' })).toBeNull(); expect(getFollowFeed).toHaveBeenLastCalledWith({ pageSize: 8 })
  })
  it('distinguishes first-page failure from empty state', async () => {
    getFollowFeed.mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce(feed([]))
    mount(); await screen.findByText('读取失败'); expect(screen.queryByText(/暂无关注动态/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试动态' })); await screen.findByText(/暂无关注动态/)
  })
})
describe('话题入口和关注操作', () => {
  it.each([false, undefined])('disabled or unknown availability renders plain text (%s)', (available) => {
    render(<MemoryRouter><TopicLink article={row(1, '', { categoryAvailable: available })} /></MemoryRouter>)
    expect(screen.getByText('Java')).toBeTruthy(); expect(screen.queryByRole('link')).toBeNull()
  })
  it('enabled topic is a real semantic detail link', () => {
    render(<MemoryRouter><TopicLink article={row(1)} /></MemoryRouter>); expect(screen.getByRole('link').getAttribute('href')).toBe('/topics/2')
  })
  it('directory retries read errors and renders descriptions and links', async () => {
    listCategories.mockRejectedValueOnce(new Error('目录失败')).mockResolvedValueOnce({ data: [{ id: 2, name: 'Java', description: '经验分享' }] })
    mount('/topics'); fireEvent.click(await screen.findByRole('button', { name: '重试话题' })); expect(await screen.findByText('经验分享')).toBeTruthy(); expect(screen.getByRole('link', { name: 'Java' }).getAttribute('href')).toBe('/topics/2')
  })
  it('anonymous follow returns to login without sending a write', async () => {
    mount('/topics/2', null); fireEvent.click(await screen.findByRole('button', { name: '关注话题' })); await screen.findByText('登录页'); expect(followTopic).not.toHaveBeenCalled()
  })
  it('disables repeated writes and follows server state', async () => {
    let resolveWrite
    followTopic.mockImplementationOnce(() => new Promise((r) => { resolveWrite = r }))
    mount('/topics/2'); fireEvent.click(await screen.findByRole('button', { name: '关注话题' })); const pending = screen.getByRole('button', { name: '处理中…' }); expect(pending.disabled).toBe(true); fireEvent.click(pending); expect(followTopic).toHaveBeenCalledTimes(1)
    await act(async () => resolveWrite({ data: { followedByCurrentUser: true } })); fireEvent.click(screen.getByRole('button', { name: '取消关注话题' })); await screen.findByRole('button', { name: '关注话题' }); expect(unfollowTopic).toHaveBeenCalledTimes(1)
  })
  it('write failure preserves known state and requires a user action to retry', async () => {
    followTopic.mockRejectedValueOnce(new Error('写入失败'))
    mount('/topics/2'); fireEvent.click(await screen.findByRole('button', { name: '关注话题' })); await screen.findByText('写入失败'); expect(screen.getByRole('button', { name: '关注话题' }).disabled).toBe(false); expect(followTopic).toHaveBeenCalledTimes(1)
  })
  it('topic search and type changes retain the category and reset pagination', async () => {
    mount('/topics/2?page=3'); await waitFor(() => expect(pageTopicContents).toHaveBeenCalled())
    fireEvent.change(screen.getByRole('textbox', { name: '搜索内容' }), { target: { value: ' 并发 ' } }); fireEvent.click(screen.getByRole('button', { name: '搜索' }))
    await waitFor(() => expect(pageTopicContents).toHaveBeenLastCalledWith('2', { pageNum: 1, pageSize: 8, keyword: '并发' }))
    fireEvent.change(screen.getByRole('combobox', { name: '内容类型' }), { target: { value: 'QUESTION' } }); await waitFor(() => expect(pageTopicContents).toHaveBeenLastCalledWith('2', { pageNum: 1, pageSize: 8, keyword: '并发', contentType: 'QUESTION' }))
  })
  it('private disabled topic has no detail link and can still be unfollowed', async () => {
    pageFollowedTopics.mockResolvedValueOnce(page([{ categoryId: 2, name: '停用话题', status: 0, description: '说明' }])).mockResolvedValueOnce(page([]))
    mount('/me/topics'); await screen.findByText('已停用'); expect(screen.queryByRole('link', { name: '停用话题' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '取消关注停用话题' })); await screen.findByText(/还没有关注话题/); expect(unfollowTopic).toHaveBeenCalledWith(2)
  })
})
