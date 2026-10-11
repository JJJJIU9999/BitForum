import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AiTraces from './AiTraces.jsx'
import { getAiTraceDetail, getAiUsageOverview, pageAiTraces } from '../../api/adminApi.js'

vi.mock('../../api/adminApi.js', () => ({
  pageAiTraces: vi.fn(),
  getAiTraceDetail: vi.fn(),
  getAiUsageOverview: vi.fn(),
}))

// AI 执行轨迹页（M18）。
//
// 这里验证的是"能不能看懂"这一层：
// 用量概览要出数字，列表要显示场景/状态，**降级必须能看到原因**，
// 展开后要能看到每一步（含工具调用）—— 这正是 M18 验收第 1、4 条的可视化形式。
describe('AiTraces', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAiUsageOverview.mockResolvedValue({
      data: {
        days: 7,
        totals: { calls: 12, totalTokens: 3456, cost: 0.0231, abnormalCalls: 2 },
        daily: [{ statDate: '2026-09-19', calls: 5, totalTokens: 1200, cost: 0.01 }],
        byAgent: [{ agentType: 'QA', calls: 8, totalTokens: 3000, cost: 0.02 }],
        topUsers: [],
        inputPricePerMillion: 2.0,
        outputPricePerMillion: 8.0,
        priceNote: '成本为估算值。',
      },
    })
    pageAiTraces.mockResolvedValue({
      data: {
        records: [
          {
            traceId: 'trace-1',
            scene: 'CHAT',
            agentType: 'QA',
            status: 'DEGRADED',
            route: '按会话 agent_type 路由到「问答助手」',
            stepCount: 3,
            totalTokens: 120,
            latencyMs: 1500,
            degradeReason: 'LLM_TIMEOUT',
            message: 'AI 响应超时，已使用降级结果，请稍后重试。',
            createTime: '2026-09-19T10:00:00',
          },
        ],
        current: 1,
        pages: 1,
        total: 1,
      },
    })
  })

  afterEach(() => {
    cleanup()
  })

  it('renders usage totals and the trace list', async () => {
    render(<AiTraces />)

    // 用量：调用次数与 token 合计
    expect(await screen.findByText('12')).toBeTruthy()
    expect(screen.getByText('3,456')).toBeTruthy()
    // 轨迹：场景、状态、以及"为什么降级"
    // 场景名在"按 Agent"表格里也会出现，因此这里用 getAllByText
    expect(screen.getAllByText('问答助手').length).toBeGreaterThan(0)
    // "降级"在状态筛选下拉里也有一个选项，因此同样用 getAllByText
    expect(screen.getAllByText('降级').length).toBeGreaterThan(0)
    expect(screen.getByText(/LLM_TIMEOUT/)).toBeTruthy()
    expect(screen.getByText(/AI 响应超时/)).toBeTruthy()
  })

  it('loads and renders step timeline after expanding a trace', async () => {
    getAiTraceDetail.mockResolvedValue({
      data: {
        traceId: 'trace-1',
        model: 'deepseek-chat',
        steps: [
          { seq: 1, type: 'ROUTE', name: 'QA', detail: '路由到问答助手', latencyMs: 1, totalTokens: null },
          {
            seq: 2, type: 'TOOL_CALL', name: 'searchArticles',
            detail: '入参={"keyword":"Redis"} → 返回=["Redis 缓存穿透实践"]', latencyMs: 3, totalTokens: null,
          },
        ],
      },
    })

    const { container } = render(<AiTraces />)
    await waitFor(() => expect(pageAiTraces).toHaveBeenCalled())

    fireEvent.click(container.querySelector('.trace-summary'))

    // 展开才请求详情：列表接口不带 steps（后端同一条约定）
    await waitFor(() => expect(getAiTraceDetail).toHaveBeenCalledWith('trace-1'))
    expect(await screen.findByText('工具调用')).toBeTruthy()
    expect(screen.getByText(/Redis 缓存穿透实践/)).toBeTruthy()
  })

  it('shows an empty hint when there is no trace yet', async () => {
    pageAiTraces.mockResolvedValue({ data: { records: [], current: 1, pages: 0, total: 0 } })

    render(<AiTraces />)

    expect(await screen.findByText(/还没有执行轨迹/)).toBeTruthy()
  })
})
