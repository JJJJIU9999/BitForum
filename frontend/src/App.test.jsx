import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import App from './App.jsx'
import { clearAuth, getCurrentUser, saveAuth } from './api/request.js'
import { getCurrentUserProfile, loginUser, updateCurrentUserProfile } from './api/userApi.js'
import { getUnreadNotificationCount } from './api/notificationApi.js'

vi.mock('./api/request.js', () => ({ clearAuth: vi.fn(), getCurrentUser: vi.fn(), saveAuth: vi.fn() }))
vi.mock('./api/userApi.js', () => ({ getCurrentUserProfile: vi.fn(), loginUser: vi.fn(), updateCurrentUserProfile: vi.fn() }))
vi.mock('./api/notificationApi.js', () => ({ getUnreadNotificationCount: vi.fn() }))
vi.mock('./pages/ArticleList.jsx', () => ({ default: () => <p>社区内容</p> }))
vi.mock('./components/AiAssistantPanel.jsx', () => ({ default: () => null }))

const user = { userId: 1, username: 'author', role: 'USER' }
const profile = { userId: 1, username: 'author', nickname: '林间笔记', bio: '知识交流', avatar: '' }
const response = (data = profile) => ({ data })
const mount = (path = '/') => render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)

beforeEach(() => {
  vi.resetAllMocks()
  getCurrentUser.mockReturnValue(user)
  getCurrentUserProfile.mockResolvedValue(response())
  getUnreadNotificationCount.mockResolvedValue({ data: 0 })
  saveAuth.mockImplementation(({ userId, username, role }) => getCurrentUser.mockReturnValue({ userId, username, role }))
})
afterEach(cleanup)

describe('昵称展示与登录用户名', () => {
  it('loads the nickname for the header without changing login identity', async () => {
    mount()
    const link = await screen.findByRole('link', { name: '查看林间笔记的个人资料' })
    expect(link.getAttribute('title')).toBe('林间笔记')
    expect(link.getAttribute('href')).toBe('/me/profile')
    expect(getCurrentUser().username).toBe('author')
    expect(saveAuth).not.toHaveBeenCalled()
  })

  it.each(['', null, '   '])('falls back to the login username for nickname %s', async (nickname) => {
    getCurrentUserProfile.mockResolvedValue(response({ ...profile, nickname }))
    mount()
    await waitFor(() => expect(getUnreadNotificationCount).toHaveBeenCalledTimes(2))
    expect(screen.getByRole('link', { name: '查看author的个人资料' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '退出' })).toBeTruthy()
  })

  it('keeps the community and account entry available when profile loading fails', async () => {
    getCurrentUserProfile.mockRejectedValue(new Error('资料暂不可用'))
    mount()
    await act(async () => {})
    expect(screen.getByText('社区内容')).toBeTruthy()
    expect(screen.getByRole('link', { name: '查看author的个人资料' })).toBeTruthy()
    expect(clearAuth).not.toHaveBeenCalled()
  })

  it('updates the header after saving a nickname and labels the unchanged login username', async () => {
    updateCurrentUserProfile.mockResolvedValue(response({ ...profile, nickname: '林间新笔记' }))
    mount('/me/profile')
    const nickname = await screen.findByRole('textbox', { name: '昵称' })
    await waitFor(() => expect(nickname.value).toBe('林间笔记'))
    fireEvent.change(nickname, { target: { value: '林间新笔记' } })
    fireEvent.click(screen.getByRole('button', { name: '保存资料' }))
    expect(await screen.findByRole('link', { name: '查看林间新笔记的个人资料' })).toBeTruthy()
    expect(screen.getByText('登录用户名：author')).toBeTruthy()
    expect(updateCurrentUserProfile).toHaveBeenCalledWith({ nickname: '林间新笔记', bio: '知识交流' })
    expect(getCurrentUser().username).toBe('author')
  })

  it('keeps the published nickname when a proposed profile change is held for review', async () => {
    updateCurrentUserProfile.mockResolvedValue(response({ ...profile, pendingTextReview: true }))
    mount('/me/profile')
    const nickname = await screen.findByRole('textbox', { name: '昵称' })
    await waitFor(() => expect(nickname.value).toBe('林间笔记'))
    fireEvent.change(nickname, { target: { value: '待审新昵称' } })
    fireEvent.click(screen.getByRole('button', { name: '保存资料' }))
    expect(await screen.findByText('资料修改待人工审核；公开页面暂时显示原有资料。')).toBeTruthy()
    expect(screen.getByRole('link', { name: '查看林间笔记的个人资料' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: '查看待审新昵称的个人资料' })).toBeNull()
  })

  it('does not let initial profile hydration overwrite a newly saved nickname', async () => {
    let resolveInitial
    getCurrentUserProfile.mockImplementationOnce(() => new Promise((resolve) => { resolveInitial = resolve }))
      .mockResolvedValue(response())
    updateCurrentUserProfile.mockResolvedValue(response({ ...profile, nickname: '已保存昵称' }))
    mount()
    fireEvent.click(screen.getByRole('link', { name: '查看author的个人资料' }))
    const nickname = await screen.findByRole('textbox', { name: '昵称' })
    await waitFor(() => expect(nickname.value).toBe('林间笔记'))
    fireEvent.change(nickname, { target: { value: '已保存昵称' } })
    fireEvent.click(screen.getByRole('button', { name: '保存资料' }))
    await screen.findByRole('link', { name: '查看已保存昵称的个人资料' })
    await act(async () => resolveInitial(response()))
    expect(screen.getByRole('link', { name: '查看已保存昵称的个人资料' })).toBeTruthy()
  })

  it('ignores an old profile response after logout', async () => {
    let resolveInitial
    getCurrentUserProfile.mockImplementation(() => new Promise((resolve) => { resolveInitial = resolve }))
    mount()
    fireEvent.click(screen.getByRole('button', { name: '退出' }))
    await act(async () => resolveInitial(response()))
    expect(screen.getByRole('button', { name: '登录', exact: true })).toBeTruthy()
    expect(screen.queryByRole('link', { name: '查看林间笔记的个人资料' })).toBeNull()
  })

  it('does not show the previous nickname after switching login accounts', async () => {
    let resolveFirst
    getCurrentUserProfile.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValue(response({ ...profile, userId: 2, username: 'answerer', nickname: '知行' }))
    loginUser.mockResolvedValue({ data: { userId: 2, username: 'answerer', role: 'USER', token: 'synthetic-token' } })
    mount()
    fireEvent.click(screen.getByRole('button', { name: '退出' }))
    fireEvent.change(screen.getByRole('textbox', { name: '用户名' }), { target: { value: 'answerer' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: '登录', exact: true }))
    await screen.findByRole('link', { name: '查看知行的个人资料' })
    await act(async () => resolveFirst(response()))
    expect(screen.getByRole('link', { name: '查看知行的个人资料' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: '查看林间笔记的个人资料' })).toBeNull()
    expect(loginUser).toHaveBeenCalledWith({ username: 'answerer', password: '123456' })
  })
})
