import { cleanup, fireEvent, render, screen, within, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import CommentDiscussion from './CommentDiscussion.jsx'
import { publishComment, likeAnswer, unlikeAnswer } from '../api/commentApi.js'
vi.mock('../api/commentApi.js', () => ({ publishComment: vi.fn(), likeAnswer: vi.fn(), unlikeAnswer: vi.fn() }))
const item = (id, parentCommentId = 0, status = 'PUBLISHED', extra = {}) => ({ id, parentCommentId, status, content: `内容${id}`, userId: 2, createTime: `2026-10-08T12:${String(id).padStart(2, '0')}:00`, likeCount: 0, ...extra })
let props
beforeEach(() => {
  vi.clearAllMocks()
  props = { comments: [], currentUser: { userId: 2 }, isQuestion: true, isQuestionOwner: true, articleId: '88', actionLoading: false,
    onAction: vi.fn(async (action) => { await action() }), onReport: vi.fn(), onAdopt: vi.fn() }
})
afterEach(cleanup)
describe('回答互动与两层回复', () => {
  it('keeps server root order and isolates own unpublished roots newest first', () => {
    props.comments = [item(2), item(1), item(4, 0, 'PENDING'), item(3, 0, 'REJECTED')]
    render(<MemoryRouter><CommentDiscussion {...props} /></MemoryRouter>)
    const articles = document.querySelectorAll('.comment-list > article')
    expect(articles[0].textContent).toContain('内容2')
    expect(articles[1].textContent).toContain('内容1')
    const own = screen.getByRole('region', { name: '本人未公开内容' })
    expect(own.textContent.indexOf('内容4')).toBeLessThan(own.textContent.indexOf('内容3'))
    expect(within(own).queryByRole('button')).toBeNull()
  })
  it('uses chronological children and never gives children like or adoption controls', () => {
    props.comments = [item(1), item(3, 1), item(2, 1)]
    render(<MemoryRouter><CommentDiscussion {...props} /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: '展开回复（2）' }))
    const replies = document.querySelector('.reply-list')
    expect(replies.textContent.indexOf('内容2')).toBeLessThan(replies.textContent.indexOf('内容3'))
    expect(within(replies).queryByRole('button', { name: /赞同|采纳|^回复/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '收起回复（2）' }))
    expect(screen.queryByText('内容2')).toBeNull()
  })
  it('shows own pending child separately and does not count it as public reply', () => {
    props.comments = [item(1), item(2, 1, 'PENDING')]
    render(<MemoryRouter><CommentDiscussion {...props} /></MemoryRouter>)
    expect(screen.getByRole('button', { name: '展开回复（0）' })).toBeTruthy()
    expect(screen.getByText('我的未公开回复（1）')).toBeTruthy()
    expect(screen.getByText('待人工审核，仅自己可见')).toBeTruthy()
  })
  it('never shows a child whose parent is hidden or absent', () => {
    props.comments = [item(1, 0, 'PENDING'), item(2, 1, 'PENDING'), item(3, 1)]
    render(<MemoryRouter><CommentDiscussion {...props} /></MemoryRouter>)
    expect(screen.queryByText('内容2')).toBeNull()
    expect(screen.queryByText('内容3')).toBeNull()
  })
  it('permits self answer likes and toggles based on server state', async () => {
    props.comments = [item(1, 0, 'PUBLISHED', { likedByCurrentUser: false })]
    const view = render(<MemoryRouter><CommentDiscussion {...props} /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: '赞同回答 #1' }))
    await waitFor(() => expect(likeAnswer).toHaveBeenCalledWith(1))
    props.comments = [item(1, 0, 'PUBLISHED', { likedByCurrentUser: true, likeCount: 1 })]
    view.rerender(<MemoryRouter><CommentDiscussion {...props} /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: '取消赞同回答 #1' }))
    await waitFor(() => expect(unlikeAnswer).toHaveBeenCalledWith(1))
  })
  it('routes anonymous interaction through login action and does not open reply form', () => {
    props.currentUser = null; props.isQuestionOwner = false; props.comments = [item(1)]
    render(<MemoryRouter><CommentDiscussion {...props} /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: '回复回答 #1' }))
    expect(props.onAction).toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '提交回复' })).toBeNull()
  })
  it('retains input on submission failure then submits correct parent on retry', async () => {
    props.comments = [item(1)]
    publishComment.mockRejectedValueOnce(new Error('父对象未公开')).mockResolvedValueOnce({})
    render(<MemoryRouter><CommentDiscussion {...props} /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: '回复回答 #1' }))
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '回复内容' } })
    fireEvent.click(screen.getByRole('button', { name: '提交回复' }))
    await screen.findByRole('alert')
    expect(screen.getByRole('textbox').value).toBe('回复内容')
    fireEvent.click(screen.getByRole('button', { name: '提交回复' }))
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(publishComment).toHaveBeenLastCalledWith({ articleId: '88', content: '回复内容', parentCommentId: 1 })
  })
})
