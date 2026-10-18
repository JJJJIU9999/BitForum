import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Dashboard from './Dashboard.jsx'
import {
  checkAdminHealth,
  getAdminDashboardSummary,
  getInsightStatus,
  getLatestInsight,
} from '../../api/adminApi.js'

// 看板改造后的验收层：这里不测样式，只测"管理员看到的信息对不对"——
// 五种文章状态一个不少、时间指标没有混进状态图、百分比可核对、空数据不出现 NaN。
vi.mock('react-router', () => ({
  useOutletContext: () => ({ currentUser: { id: 1, username: 'admin' } }),
}))

vi.mock('../../api/adminApi.js', () => ({
  checkAdminHealth: vi.fn(),
  getAdminDashboardSummary: vi.fn(),
  generateInsight: vi.fn(),
  getInsightStatus: vi.fn(),
  getLatestInsight: vi.fn(),
}))

const fullSummary = () => ({
  userStats: { total: 44, normalUsers: 43, admins: 1, enabled: 43, disabled: 1 },
  articleStats: {
    total: 12, draft: 3, pending: 2, published: 5, rejected: 1, offline: 1,
    todayCreated: 1, last7DaysCreated: 4,
  },
  commentStats: { total: 30, todayCreated: 2, last7DaysCreated: 9 },
  categoryStats: { total: 5, enabled: 4, disabled: 1 },
  favoriteStats: { total: 7 },
  notificationStats: { total: 20, unread: 5 },
  reportStats: {
    total: 10, pending: 4, resolved: 4, rejected: 2, todayCreated: 1, last7DaysCreated: 6,
  },
  hotArticles: [
    { articleId: 1, title: 'Redis 缓存穿透实践', hotScore: 99, viewCount: 120, likeCount: 8 },
  ],
})

const emptySummary = () => ({
  userStats: { total: 0, normalUsers: 0, admins: 0, enabled: 0, disabled: 0 },
  articleStats: {
    total: 0, draft: 0, pending: 0, published: 0, rejected: 0, offline: 0,
    todayCreated: 0, last7DaysCreated: 0,
  },
  commentStats: { total: 0, todayCreated: 0, last7DaysCreated: 0 },
  categoryStats: { total: 0, enabled: 0, disabled: 0 },
  favoriteStats: { total: 0 },
  notificationStats: { total: 0, unread: 0 },
  reportStats: { total: 0, pending: 0, resolved: 0, rejected: 0, todayCreated: 0, last7DaysCreated: 0 },
  hotArticles: [],
})

async function renderDashboard(summary) {
  getAdminDashboardSummary.mockResolvedValue({ data: summary })
  const view = render(<Dashboard />)
  await screen.findByText('文章状态分布')
  return view
}

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    checkAdminHealth.mockResolvedValue({ code: 200 })
    // AI 洞察卡片会跟着看板一起挂载，这里给一个"没有报告"的默认状态
    getInsightStatus.mockResolvedValue({ data: null })
    getLatestInsight.mockResolvedValue({ data: null })
  })

  afterEach(() => {
    cleanup()
  })

  it('renders the four headline KPI cards with exact numbers', async () => {
    const { container } = await renderDashboard(fullSummary())

    const cards = container.querySelectorAll('.dashboard-stat-card')
    expect(cards).toHaveLength(4)
    expect([...cards].map((card) => card.querySelector('span').textContent))
      .toEqual(['用户总数', '文章总数', '评论总数', '待处理举报'])

    expect(within(cards[0]).getByText('44')).toBeTruthy()
    expect(within(cards[1]).getByText('12')).toBeTruthy()
    expect(within(cards[2]).getByText('30')).toBeTruthy()
    expect(within(cards[3]).getByText('4')).toBeTruthy()
    // 原有辅助说明保留
    expect(within(cards[0]).getByText('启用 43 / 禁用 1')).toBeTruthy()
  })

  it('shows exactly five article statuses in the bar chart', async () => {
    const { container } = await renderDashboard(fullSummary())

    const rows = container.querySelectorAll('.article-status-row')
    expect(rows).toHaveLength(5)
    expect([...rows].map((row) => row.querySelector('.article-status-label').textContent))
      .toEqual(['草稿', '待审核', '已发布', '已驳回', '已下架'])
    expect([...rows].map((row) => row.querySelector('.article-status-value').textContent))
      .toEqual(['3', '2', '5', '1', '1'])
  })

  it('keeps the 7-day figure out of the status distribution', async () => {
    const { container } = await renderDashboard(fullSummary())

    const badge = container.querySelector('.dashboard-inline-metric')
    expect(badge.textContent).toBe('近 7 天新增 4')

    // 时间指标只能作为标题区标签出现，不能变成第六个"状态"
    const statusList = container.querySelector('.article-status-list')
    expect(statusList.textContent).not.toContain('近 7 天')
    expect(statusList.querySelectorAll('.article-status-row')).toHaveLength(5)
  })

  it('computes user role and account status percentages', async () => {
    const { container } = await renderDashboard(fullSummary())
    const ratioLists = container.querySelectorAll('.ratio-bar-list')

    expect(within(ratioLists[0]).getByText('普通用户')).toBeTruthy()
    expect(within(ratioLists[0]).getByText('43 / 44 · 97.7%')).toBeTruthy()
    expect(within(ratioLists[0]).getByText('1 / 44 · 2.3%')).toBeTruthy()

    expect(within(ratioLists[1]).getByText('启用用户')).toBeTruthy()
    expect(within(ratioLists[1]).getByText('43 / 44 · 97.7%')).toBeTruthy()
    expect(within(ratioLists[1]).getByText('1 / 44 · 2.3%')).toBeTruthy()
  })

  it('computes the category enable ratio', async () => {
    const { container } = await renderDashboard(fullSummary())
    const ratioLists = container.querySelectorAll('.ratio-bar-list')

    expect(within(ratioLists[2]).getByText('启用板块')).toBeTruthy()
    expect(within(ratioLists[2]).getByText('4 / 5 · 80.0%')).toBeTruthy()
    expect(within(ratioLists[2]).getByText('1 / 5 · 20.0%')).toBeTruthy()
  })

  it('summarises reports by pending / resolved / rejected', async () => {
    const { container } = await renderDashboard(fullSummary())
    const summary = container.querySelector('.report-summary')

    expect(within(summary).getByText('待处理')).toBeTruthy()
    expect(within(summary).getByText('已处理')).toBeTruthy()
    expect(within(summary).getByText('已驳回')).toBeTruthy()
    // 待处理与已处理数量相同，因此这里用 getAllByText
    expect(within(summary).getAllByText('4 · 40.0%')).toHaveLength(2)
    expect(within(summary).getByText('2 · 20.0%')).toBeTruthy()
    expect(within(summary).getByText('举报总数 10 · 近 7 天举报 6')).toBeTruthy()
  })

  it('renders 0.0% instead of NaN when every total is zero', async () => {
    const { container } = await renderDashboard(emptySummary())

    expect(container.textContent).not.toContain('NaN')
    expect(container.querySelectorAll('.ratio-bar')).toHaveLength(7)
    expect(screen.getAllByText('0 / 0 · 0.0%')).toHaveLength(7)
    // 空数据时五种状态依然要列出名字和数字
    expect(container.querySelectorAll('.article-status-row')).toHaveLength(5)
    expect(screen.getByText('近 7 天新增 0')).toBeTruthy()
    expect(screen.getByText(/暂无热门文章/)).toBeTruthy()
  })

  it('marks the AI insight as a non-realtime data snapshot', async () => {
    getLatestInsight.mockResolvedValue({
      data: {
        id: 9,
        content: '社区目前共有 43 位用户。',
        model: 'deepseek-flash',
        latencyMs: 5004,
        createTime: '2026-09-19T15:29:47',
      },
    })

    await renderDashboard(fullSummary())

    const meta = await screen.findByText(/数据快照生成于 2026-09-19 23:29/)
    expect(meta.textContent).toContain('非实时数据')
    expect(meta.textContent).toContain('deepseek-flash')
    expect(meta.textContent).toContain('5004 ms')
    // 元信息排在正文之前，管理员先看到"这是快照"再读分析
    expect(meta.compareDocumentPosition(screen.getByText(/社区目前共有 43 位用户/))
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('still lists the hot articles', async () => {
    await renderDashboard(fullSummary())

    expect(screen.getByText('Redis 缓存穿透实践')).toBeTruthy()
    expect(screen.getByText('热度 99')).toBeTruthy()
    expect(screen.getByText('浏览 120')).toBeTruthy()
    expect(screen.getByText('点赞 8')).toBeTruthy()
  })
})
