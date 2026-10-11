import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import TextModeration from './TextModeration.jsx'
import {
  executeTextRescan, listTextRules, pageTextOperations, pageTextPending, previewTextRescan,
} from '../../api/adminApi.js'

vi.mock('../../api/adminApi.js', () => ({
  createTextRule: vi.fn(), decideTextPending: vi.fn(), deleteTextRule: vi.fn(),
  executeTextRescan: vi.fn(), listTextRules: vi.fn(), pageTextOperations: vi.fn(),
  pageTextPending: vi.fn(), previewTextRescan: vi.fn(), updateTextRule: vi.fn(),
}))

beforeEach(() => {
  vi.clearAllMocks()
  listTextRules.mockResolvedValue({ data: [] })
  pageTextOperations.mockResolvedValue({ data: { records: [] } })
  pageTextPending.mockResolvedValue({ data: { records: [], current: 1, pages: 1, total: 0 } })
})

afterEach(cleanup)

it('shows preview before confirmed batched rescan', async () => {
  previewTextRescan.mockResolvedValue({ data: {
    ruleVersion: 7, scanned: 12, matched: 1,
    samples: [{ targetType: 'COMMENT', targetId: 42, matchedRules: '示例词', excerpt: '示例评论' }],
  } })
  executeTextRescan.mockResolvedValue({ data: {
    ruleVersion: 7, nextCursor: null, scanned: 12, held: 1, complete: true,
  } })

  render(<MemoryRouter><TextModeration /></MemoryRouter>)
  await screen.findByText('暂无规则。')
  fireEvent.click(screen.getByRole('button', { name: '生成预览' }))
  expect(await screen.findByText(/示例评论/)).toBeTruthy()
  expect(executeTextRescan).not.toHaveBeenCalled()

  fireEvent.click(screen.getByRole('button', { name: '确认执行复扫' }))
  fireEvent.click(screen.getByRole('button', { name: '确认' }))
  await waitFor(() => expect(executeTextRescan).toHaveBeenCalledWith({ ruleVersion: 7, cursor: null }))
  expect(await screen.findByText(/复扫完成：检查 12 条，转待审 1 条/)).toBeTruthy()
})

it('lets the reviewer inspect pending text before deciding', async () => {
  pageTextPending.mockResolvedValue({ data: {
    records: [{ id: 9, targetType: 'COMMENT', targetId: 42, targetText: '待审评论完整内容', matchedRules: '示例词', source: 'NEW' }],
    current: 1, pages: 1, total: 1,
  } })
  render(<MemoryRouter><TextModeration /></MemoryRouter>)
  const summary = await screen.findByText('查看待审原文')
  fireEvent.click(summary)
  expect(screen.getByText('待审评论完整内容')).toBeTruthy()
})
