import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import ArticleCard from './ArticleCard.jsx'
afterEach(cleanup)

describe('ArticleCard', () => {
  it('keeps title activation, author navigation and owned status separate', () => {
    const open = vi.fn()
    render(<MemoryRouter><ArticleCard variant="owned" onOpen={open} article={{ id: 8, title: '标题', userId: 7, status: 'DRAFT', author: { userId: 7, displayName: '作者', profileAvailable: true } }} /></MemoryRouter>)
    fireEvent.click(screen.getByRole('link', { name: '标题' }), { button: 0 })
    expect(open).toHaveBeenCalledWith(8)
    expect(screen.getByRole('link', { name: '查看作者的主页' }).getAttribute('href')).toBe('/users/7')
    expect(screen.getByText('草稿')).toBeTruthy()
  })
  it('omits redundant published status and never invents a missing answer count', () => {
    render(<MemoryRouter><ArticleCard article={{ id: 8, title: '问题', status: 'PUBLISHED', contentType: 'QUESTION' }} /></MemoryRouter>)
    expect(screen.queryByText('已发布')).toBeNull(); expect(screen.queryByText('0 个回答')).toBeNull()
  })
  it('renders supplied footer content exactly once', () => {
    render(
      <MemoryRouter><ArticleCard article={{ id: 1, title: 'Readable title', content: 'Summary', userId: 2 }}>
        <button type="button">footer action</button>
      </ArticleCard></MemoryRouter>,
    )

    expect(screen.getAllByRole('button', { name: 'footer action' })).toHaveLength(1)
  })
})
