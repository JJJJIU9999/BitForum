import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RelatedRecommendations from './RelatedRecommendations.jsx'
import { getRecommendations } from '../api/articleApi.js'

vi.mock('../api/articleApi.js', () => ({
  getRecommendations: vi.fn(),
}))

describe('RelatedRecommendations', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // 本项目没有配置 testing-library 的自动 cleanup，
  // 不显式清理会让上一个用例的 DOM 残留、导致"找到多个元素"
  afterEach(() => {
    cleanup()
  })

  it('renders each recommendation with its AI reason', async () => {
    getRecommendations.mockResolvedValue({
      data: [
        { articleId: 85, title: 'Spring Boot 论坛项目实践', categoryName: '技术', rank: 1, reason: '与你正在看的这篇主题相近' },
        { articleId: 87, title: 'Redis 热点数据同步方案', categoryName: '技术', rank: 2, reason: '近期社区热度较高' },
      ],
    })

    render(<RelatedRecommendations articleId="86" onOpen={() => {}} />)

    expect(await screen.findByText('Spring Boot 论坛项目实践')).toBeTruthy()
    expect(screen.getByText('与你正在看的这篇主题相近')).toBeTruthy()
    expect(screen.getByText('近期社区热度较高')).toBeTruthy()
    expect(getRecommendations).toHaveBeenCalledWith('86', 5)
  })

  it('hides the whole section when the API fails', async () => {
    getRecommendations.mockRejectedValue(new Error('服务不可用'))

    const { container } = render(<RelatedRecommendations articleId="86" onOpen={() => {}} />)

    await waitFor(() => expect(getRecommendations).toHaveBeenCalled())
    // 推荐不可用时应整块消失，而不是留下报错或空标题
    expect(container.querySelector('.related-section')).toBeNull()
  })

  it('hides the section when there is nothing to recommend', async () => {
    getRecommendations.mockResolvedValue({ data: [] })

    const { container } = render(<RelatedRecommendations articleId="86" onOpen={() => {}} />)

    await waitFor(() => expect(getRecommendations).toHaveBeenCalled())
    expect(container.querySelector('.related-section')).toBeNull()
  })

  it('still shows the article when the AI reason is missing', async () => {
    // AI 理由生成降级时后端只返回列表：文章仍要展示，理由处如实说明
    getRecommendations.mockResolvedValue({
      data: [{ articleId: 85, title: 'Spring Boot 论坛项目实践', categoryName: '技术', rank: 1, reason: null }],
    })

    render(<RelatedRecommendations articleId="86" onOpen={() => {}} />)

    expect(await screen.findByText('Spring Boot 论坛项目实践')).toBeTruthy()
    expect(screen.getByText('暂无推荐理由')).toBeTruthy()
  })

  it('opens the clicked recommendation', async () => {
    const onOpen = vi.fn()
    getRecommendations.mockResolvedValue({
      data: [{ articleId: 85, title: 'Spring Boot 论坛项目实践', categoryName: '技术', rank: 1, reason: '理由' }],
    })

    render(<RelatedRecommendations articleId="86" onOpen={onOpen} />)

    const button = await screen.findByRole('button', { name: /Spring Boot 论坛项目实践/ })
    button.click()
    expect(onOpen).toHaveBeenCalledWith(85)
  })
})
