import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Link, MemoryRouter, Outlet, Route, Routes } from 'react-router'
import PublicUserProfile from './PublicUserProfile.jsx'
import { getPublicUserProfile, pagePublicUserArticles, pageUserFollowers, pageUserFollowing } from '../api/userApi.js'
vi.mock('../api/userApi.js', () => ({ getPublicUserProfile: vi.fn(), pagePublicUserArticles: vi.fn(), pageUserFollowers: vi.fn(), pageUserFollowing: vi.fn(), followUser: vi.fn(), unfollowUser: vi.fn() }))
const page = (records = []) => ({ data: { records, current: 1, pages: 1, total: records.length } })
function mount() {
  return render(<MemoryRouter initialEntries={['/users/1']}><Link to="/users/2">另一个作者</Link><Routes><Route element={<Outlet context={{ currentUser: null }} />}><Route path="/users/:userId" element={<PublicUserProfile />} /></Route></Routes></MemoryRouter>)
}
beforeEach(() => {
  vi.clearAllMocks()
  getPublicUserProfile.mockImplementation((id) => Promise.resolve({ data: { userId: Number(id), username: `账号${id}`, nickname: `作者${id}` } }))
  pagePublicUserArticles.mockResolvedValue(page()); pageUserFollowers.mockResolvedValue(page()); pageUserFollowing.mockResolvedValue(page())
})
afterEach(cleanup)
describe('公开主页的分区状态', () => {
  it('keeps the author profile visible when public articles fail', async () => {
    pagePublicUserArticles.mockRejectedValue(new Error('内容列表失败'))
    mount(); expect(await screen.findByRole('heading', { name: '作者1' })).toBeTruthy()
    expect(await screen.findByRole('button', { name: '重试公开内容' })).toBeTruthy()
    expect(screen.queryByText('暂无公开内容。')).toBeNull()
  })
  it('retries only public content while retaining the profile and followers', async () => {
    let resolveRetry
    pagePublicUserArticles.mockRejectedValueOnce(new Error('公开内容失败'))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRetry = resolve }))
    pageUserFollowers.mockResolvedValue(page([{ userId: 3, nickname: '已有粉丝' }]))
    mount()
    const heading = await screen.findByRole('heading', { name: '作者1' })
    const follower = await screen.findByText('已有粉丝')
    fireEvent.click(await screen.findByRole('button', { name: '重试公开内容' }))
    expect(screen.getByRole('heading', { name: '作者1' })).toBe(heading)
    expect(screen.getByText('已有粉丝')).toBe(follower)
    expect(screen.queryByText('正在打开用户主页…')).toBeNull()
    expect(screen.queryByText('正在加载关注关系…')).toBeNull()
    expect(getPublicUserProfile).toHaveBeenCalledTimes(1)
    expect(pageUserFollowers).toHaveBeenCalledTimes(1)
    expect(pageUserFollowing).not.toHaveBeenCalled()
    await act(async () => resolveRetry(page([{ id: 8, title: '恢复的公开内容', content: '正文', userId: 1 }])))
    expect(await screen.findByRole('link', { name: '恢复的公开内容' })).toBeTruthy()
    expect(pagePublicUserArticles).toHaveBeenCalledTimes(2)
    expect(getPublicUserProfile).toHaveBeenCalledTimes(1)
    expect(pageUserFollowers).toHaveBeenCalledTimes(1)
  })

  it('retries only the public profile without re-requesting completed content or followers', async () => {
    let resolveRetry
    getPublicUserProfile.mockRejectedValueOnce(new Error('资料失败'))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRetry = resolve }))
    pagePublicUserArticles.mockResolvedValue(page([{ id: 8, title: '已加载的公开内容', userId: 1 }]))
    pageUserFollowers.mockResolvedValue(page([{ userId: 3, nickname: '已加载的粉丝' }]))
    mount()
    fireEvent.click(await screen.findByRole('button', { name: '重试主页' }))
    expect(pagePublicUserArticles).toHaveBeenCalledTimes(1)
    expect(pageUserFollowers).toHaveBeenCalledTimes(1)
    await act(async () => resolveRetry({ data: { userId: 1, nickname: '恢复的作者' } }))
    expect(await screen.findByRole('heading', { name: '恢复的作者' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '已加载的公开内容' })).toBeTruthy()
    expect(screen.getByText('已加载的粉丝')).toBeTruthy()
    expect(getPublicUserProfile).toHaveBeenCalledTimes(2)
    expect(pagePublicUserArticles).toHaveBeenCalledTimes(1)
    expect(pageUserFollowers).toHaveBeenCalledTimes(1)
  })

  it('retries only followers while retaining the profile and content', async () => {
    let resolveRetry
    pageUserFollowers.mockRejectedValueOnce(new Error('粉丝失败'))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRetry = resolve }))
    pagePublicUserArticles.mockResolvedValue(page([{ id: 8, title: '保留的公开内容', userId: 1 }]))
    mount()
    await screen.findByRole('heading', { name: '作者1' })
    const title = await screen.findByRole('link', { name: '保留的公开内容' })
    fireEvent.click(await screen.findByRole('button', { name: '重试关注列表' }))
    expect(screen.getByRole('heading', { name: '作者1' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '保留的公开内容' })).toBe(title)
    expect(getPublicUserProfile).toHaveBeenCalledTimes(1)
    expect(pagePublicUserArticles).toHaveBeenCalledTimes(1)
    await act(async () => resolveRetry(page([{ userId: 3, nickname: '恢复的粉丝' }])))
    expect(await screen.findByText('恢复的粉丝')).toBeTruthy()
    expect(pageUserFollowers).toHaveBeenCalledTimes(2)
    expect(getPublicUserProfile).toHaveBeenCalledTimes(1)
    expect(pagePublicUserArticles).toHaveBeenCalledTimes(1)
  })

  it('falls back after a profile avatar fails and loads the next author avatar', async () => {
    getPublicUserProfile.mockImplementation((id) => Promise.resolve({ data: { userId: Number(id), nickname: `作者${id}`, avatar: `/api/images/${id}/content` } }))
    const view = mount()
    const image = await screen.findByRole('img', { name: '作者1 的头像' })
    fireEvent.error(image)
    expect(screen.queryByRole('img', { name: '作者1 的头像' })).toBeNull()
    expect(view.container.querySelector('.profile-avatar').textContent).toBe('作')
    expect(screen.getByRole('heading', { name: '作者1' })).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: '另一个作者' }))
    expect((await screen.findByRole('img', { name: '作者2 的头像' })).getAttribute('src')).toBe('/api/images/2/content')
    expect(screen.queryByRole('heading', { name: '作者1' })).toBeNull()
  })

  it('ignores a content retry that arrives after switching authors', async () => {
    let resolveOldRetry
    pagePublicUserArticles.mockImplementation((id) => id === '2'
      ? Promise.resolve(page([{ id: 8, title: '新作者的内容', userId: 2 }]))
      : Promise.reject(new Error('旧作者内容失败')))
    mount(); await screen.findByRole('heading', { name: '作者1' })
    pagePublicUserArticles.mockImplementationOnce(() => new Promise((resolve) => { resolveOldRetry = resolve }))
    fireEvent.click(await screen.findByRole('button', { name: '重试公开内容' }))
    fireEvent.click(screen.getByRole('link', { name: '另一个作者' }))
    await screen.findByRole('heading', { name: '作者2' })
    await screen.findByRole('link', { name: '新作者的内容' })
    await act(async () => resolveOldRetry(page([{ id: 9, title: '过期重试内容', userId: 1 }])))
    expect(screen.queryByRole('link', { name: '过期重试内容' })).toBeNull()
    expect(screen.getByRole('link', { name: '新作者的内容' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: '作者2' })).toBeTruthy()
  })

  it('ignores a previous author profile arriving after navigation', async () => {
    let resolveOld
    getPublicUserProfile.mockImplementation((id) => id === '1' ? new Promise((resolve) => { resolveOld = resolve }) : Promise.resolve({ data: { userId: 2, nickname: '作者2' } }))
    mount(); fireEvent.click(screen.getByRole('link', { name: '另一个作者' }))
    await screen.findByRole('heading', { name: '作者2' })
    await act(async () => resolveOld({ data: { userId: 1, nickname: '旧作者不应出现' } }))
    expect(screen.queryByText('旧作者不应出现')).toBeNull()
  })
  it('ignores an older followers result when switching to following', async () => {
    let resolveOld
    pageUserFollowers.mockReturnValue(new Promise((resolve) => { resolveOld = resolve }))
    pageUserFollowing.mockResolvedValue(page([{ userId: 3, nickname: '正在关注的用户' }]))
    const view=mount(); await screen.findByRole('heading', { name: '作者1' })
    fireEvent.click(within(view.container.querySelector('.follow-tabs')).getByRole('button', { name: '关注', exact: true }))
    await screen.findByText('正在关注的用户')
    await act(async () => resolveOld(page([{ userId: 4, nickname: '旧粉丝结果' }])))
    expect(screen.queryByText('旧粉丝结果')).toBeNull()
  })
})
