import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import AiAssistantPanel from './AiAssistantPanel.jsx'
import { createConversation, listConversations, listMessages, recommendForQuery, sendMessage } from '../api/aiApi.js'

vi.mock('../api/aiApi.js', () => ({ createConversation: vi.fn(), listConversations: vi.fn(), listMessages: vi.fn(), recommendForQuery: vi.fn(), sendMessage: vi.fn() }))
const user = (id = 1, content = '问题') => ({ id, role: 'user', content })
const answer = (id = 2, content = '服务端回答') => ({ id, role: 'assistant', content, citations: [{ articleId: 42, title: '公开来源' }] })
const pending = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const envelope = (data) => ({ data })
function mount(id = 7) { return render(<MemoryRouter><AiAssistantPanel userId={id} /></MemoryRouter>) }
async function open() { fireEvent.click(screen.getByRole('button', { name: '打开 AI 助手' })); await screen.findByRole('button', { name: '会话一' }) }
async function select(name = '会话一') { fireEvent.click(screen.getByRole('button', { name })); await waitFor(() => expect(screen.queryByText('正在核对历史…')).toBeNull()) }
function submit(text = '问题') { fireEvent.change(screen.getByRole('textbox', { name: '消息内容' }), { target: { value: text } }); fireEvent.submit(screen.getByRole('button', { name: '发送' }).closest('form')) }

beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear(); localStorage.clear()
  Element.prototype.scrollIntoView = vi.fn()
  listConversations.mockResolvedValue(envelope([{ id: 11, title: '会话一' }, { id: 12, title: '会话二' }]))
  createConversation.mockResolvedValue(envelope({ id: 13, title: '新对话' }))
  listMessages.mockResolvedValue(envelope([]))
  sendMessage.mockResolvedValue(envelope(answer(999, 'POST临时响应不可作历史')))
  recommendForQuery.mockResolvedValue(envelope([]))
})
afterEach(cleanup)

it('describes public retrieval accurately and uses server history with citations after sending', async () => {
  mount(); await open(); expect(screen.getByText(/检索公开文章/)).toBeTruthy(); await select()
  listMessages.mockResolvedValue(envelope([user(), answer()]))
  submit(); expect(await screen.findByText('服务端回答')).toBeTruthy()
  expect(screen.queryByText('POST临时响应不可作历史')).toBeNull()
  expect(screen.getByRole('link', { name: '公开来源' }).getAttribute('href')).toBe('/articles/42')
  expect(screen.getByRole('textbox').value).toBe(''); expect(sendMessage).toHaveBeenCalledTimes(1)
})

it('shares the synchronous lock between double submit and new conversation', async () => {
  const request = pending(); sendMessage.mockReturnValue(request.promise)
  mount(); await open(); await select(); submit(); submit()
  fireEvent.click(screen.getByRole('button', { name: '新建对话' }))
  expect(sendMessage).toHaveBeenCalledTimes(1); expect(createConversation).not.toHaveBeenCalled()
  listMessages.mockResolvedValue(envelope([user(), answer()])); await act(async () => request.resolve(envelope(answer())))
})

it('locks automatic conversation creation before sending', async () => {
  const request = pending(); createConversation.mockReturnValue(request.promise)
  mount(); await open(); submit(); submit(); expect(createConversation).toHaveBeenCalledTimes(1)
  listMessages.mockResolvedValue(envelope([user(), answer()])); await act(async () => request.resolve(envelope({ id: 13, title: '新对话' })))
  await waitFor(() => expect(sendMessage).toHaveBeenCalledTimes(1))
})

it.each(['timeout', 'network', '401', '429', 'quota'])('retains input and freezes an uncertain %s request without retry', async (reason) => {
  sendMessage.mockRejectedValue(new Error(reason))
  mount(); await open(); await select(); listMessages.mockResolvedValue(envelope([user()]))
  submit(); await screen.findByText(/发送状态待确认/)
  expect(screen.getByRole('textbox').value).toBe('问题'); expect(screen.getByRole('button', { name: '发送' }).disabled).toBe(true)
  submit(); expect(sendMessage).toHaveBeenCalledTimes(1)
  listMessages.mockResolvedValue(envelope([user(), answer()]))
  fireEvent.click(screen.getByRole('button', { name: '核对历史' })); await screen.findByText('服务端回答')
  expect(screen.getByRole('button', { name: '发送' }).disabled).toBe(false)
})

it('recovers a lost POST response by reading history and preserves input for comparison', async () => {
  sendMessage.mockRejectedValue(new Error('响应丢失'))
  mount(); await open(); await select(); listMessages.mockResolvedValue(envelope([user(), answer()]))
  submit(); await screen.findByText(/服务端已保存回答/)
  expect(screen.getByRole('textbox').value).toBe('问题'); expect(sendMessage).toHaveBeenCalledTimes(1)
})

it('keeps input when post succeeds but history read fails and only retries the read', async () => {
  mount(); await open(); await select(); listMessages.mockRejectedValue(new Error('读取失败'))
  submit(); await screen.findByText(/历史读取失败/); expect(screen.getByRole('textbox').value).toBe('问题')
  expect(screen.getByRole('button', { name: '发送' }).disabled).toBe(true)
  listMessages.mockResolvedValue(envelope([user(), answer()])); fireEvent.click(screen.getByRole('button', { name: '核对历史' }))
  await screen.findByText('服务端回答'); expect(sendMessage).toHaveBeenCalledTimes(1)
})

it('on refresh a pending marker cannot be cleared by unchanged old history', async () => {
  sessionStorage.setItem('bitforum-ai-pending:7:11', JSON.stringify({ afterId: 2 }))
  listMessages.mockResolvedValue(envelope([user(), answer()]))
  mount(); await open(); await select(); await screen.findByText(/发送状态待确认/)
  submit(); expect(sendMessage).not.toHaveBeenCalled()
  listMessages.mockResolvedValue(envelope([user(), answer(), user(3), answer(4, '后来完成')]))
  fireEvent.click(screen.getByRole('button', { name: '核对历史' })); await screen.findByText('后来完成')
  expect(sessionStorage.getItem('bitforum-ai-pending:7:11')).toBeNull()
})

it('never resumes a terminal unanswered user message after refresh', async () => {
  listMessages.mockResolvedValue(envelope([user()]))
  mount(); await open(); await select(); await screen.findByText(/发送状态待确认/)
  expect(sendMessage).not.toHaveBeenCalled()
})

it('late send response cannot overwrite another conversation or its draft', async () => {
  const request = pending(); sendMessage.mockReturnValue(request.promise)
  mount(); await open(); await select(); submit()
  listMessages.mockResolvedValue(envelope([answer(5, '会话二历史')]))
  await select('会话二'); await screen.findByText('会话二历史')
  await act(async () => request.resolve(envelope(answer())))
  expect(screen.queryByText('服务端回答')).toBeNull(); expect(screen.getByRole('textbox').value).toBe('')
  expect(recommendForQuery).not.toHaveBeenCalled()
})

it('close cancels stale UI updates and reopen reconciles persisted history', async () => {
  const request = pending(); sendMessage.mockReturnValue(request.promise)
  mount(); await open(); await select(); submit(); fireEvent.click(screen.getByRole('button', { name: '收起 AI 助手' }))
  await act(async () => request.resolve(envelope(answer())))
  listMessages.mockResolvedValue(envelope([user(), answer()])); await open(); await screen.findByText('服务端回答')
  expect(sendMessage).toHaveBeenCalledTimes(1)
})

it('logout and another login cannot receive the old send response', async () => {
  const request = pending(); sendMessage.mockReturnValue(request.promise)
  const first = mount(); await open(); await select(); submit(); first.unmount()
  mount(8); await open(); await select(); await act(async () => request.resolve(envelope(answer())))
  expect(screen.queryByText('服务端回答')).toBeNull(); expect(screen.getByRole('textbox').value).toBe('')
})

it('late history or related posts never overwrite the selected conversation', async () => {
  const history = pending(); listMessages.mockReturnValueOnce(history.promise).mockResolvedValue(envelope([answer(6, '第二份历史')]))
  mount(); await open(); fireEvent.click(screen.getByRole('button', { name: '会话一' })); await select('会话二')
  await screen.findByText('第二份历史'); await act(async () => history.resolve(envelope([answer()])))
  expect(screen.queryByText('服务端回答')).toBeNull()
})

it('same-user reauthentication isolates old requests without requiring logout', async () => {
  localStorage.setItem('bit_forum_token', 'old-test-session')
  const request = pending(); sendMessage.mockReturnValue(request.promise)
  const view = mount(); await open(); await select(); submit('旧登录会话的问题')
  localStorage.setItem('bit_forum_token', 'new-test-session')
  view.rerender(<MemoryRouter><AiAssistantPanel userId={7} /></MemoryRouter>)
  await open()
  await act(async () => request.resolve(envelope(answer())))
  expect(screen.queryByText('服务端回答')).toBeNull()
  expect(screen.getByRole('textbox').value).toBe('')
  expect(listMessages).toHaveBeenCalledTimes(1)
  expect(sendMessage).toHaveBeenCalledTimes(1)
  expect(recommendForQuery).not.toHaveBeenCalled()
  expect(sessionStorage.getItem('bitforum-ai-pending:7:11')).not.toBeNull()
})

it('token change rejects a late response before a component rerender', async () => {
  localStorage.setItem('bit_forum_token', 'old-test-session')
  const request = pending(); sendMessage.mockReturnValue(request.promise)
  mount(); await open(); await select(); submit()
  localStorage.setItem('bit_forum_token', 'new-test-session')
  await act(async () => request.resolve(envelope(answer())))
  expect(screen.queryByText('服务端回答')).toBeNull()
  expect(listMessages).toHaveBeenCalledTimes(1)
  expect(recommendForQuery).not.toHaveBeenCalled()
})
