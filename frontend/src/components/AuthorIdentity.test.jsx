import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import AuthorIdentity from './AuthorIdentity.jsx'
afterEach(cleanup)
const author = { userId: 7, displayName: '公开昵称', avatar: '/api/images/3/content', profileAvailable: true }
describe('公开作者身份', () => {
  it('uses a native link and Enter navigates to the matching profile', async () => {
    render(<MemoryRouter><Routes><Route path="/" element={<AuthorIdentity author={author} />} /><Route path="/users/7" element={<h1>匹配用户主页</h1>} /></Routes></MemoryRouter>)
    const user = userEvent.setup(); await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('link', { name: '查看公开昵称的主页' }))
    await user.keyboard('{Enter}'); expect(await screen.findByText('匹配用户主页')).toBeTruthy()
  })
  it('falls back on image failure and does not load external avatar URLs', () => {
    const view = render(<MemoryRouter><AuthorIdentity author={author} /></MemoryRouter>)
    fireEvent.error(view.container.querySelector('img')); expect(view.container.querySelector('img')).toBeNull()
    expect(screen.getByRole('link').textContent).toContain('公开昵称')
    view.rerender(<MemoryRouter><AuthorIdentity author={{ ...author, avatar: 'https://untrusted.invalid/a' }} /></MemoryRouter>)
    expect(view.container.querySelector('img')).toBeNull()
  })
  it('tries a changed avatar after the previous image failed', () => {
    const view = render(<MemoryRouter><AuthorIdentity author={author} /></MemoryRouter>)
    fireEvent.error(view.container.querySelector('img'))
    expect(view.container.querySelector('img')).toBeNull()
    view.rerender(<MemoryRouter><AuthorIdentity author={{ ...author, avatar: '/api/images/4/content' }} /></MemoryRouter>)
    expect(view.container.querySelector('img').getAttribute('src')).toBe('/api/images/4/content')
    expect(screen.getByRole('link').getAttribute('href')).toBe('/users/7')
  })
  it('does not create a profile link for unavailable users', () => {
    render(<MemoryRouter><AuthorIdentity author={{ userId: 7, displayName: '用户不可用', profileAvailable: false }} /></MemoryRouter>)
    expect(screen.queryByRole('link')).toBeNull(); expect(screen.getByText('用户不可用')).toBeTruthy()
  })
  it('supports legacy responses without extra author network requests', () => {
    render(<MemoryRouter><AuthorIdentity userId={9} /></MemoryRouter>)
    expect(screen.getByRole('link').getAttribute('href')).toBe('/users/9')
  })
})
