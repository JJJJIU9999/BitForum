import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ImageModeration from './ImageModeration.jsx'
import { approveImage, pagePendingImages, rejectImage } from '../../api/adminApi.js'

vi.mock('../../api/adminApi.js', () => ({
  approveImage: vi.fn(), pagePendingImages: vi.fn(), rejectImage: vi.fn(),
}))
vi.mock('../../components/PrivateImagePreview.jsx', () => ({
  default: ({ alt }) => <span>{alt}</span>,
}))

beforeEach(() => {
  vi.clearAllMocks()
  pagePendingImages.mockResolvedValue({ data: { records: [
    { id: 7, ownerId: 3, kind: 'AVATAR', status: 'PENDING', originalFilename: 'new.png' },
    { id: 8, ownerId: 4, kind: 'COVER', status: 'LEGACY_REVIEW', legacyUrl: 'https://example.com/old.jpg', originalFilename: 'old.jpg' },
  ], current: 1, pages: 1, total: 2 } })
  approveImage.mockResolvedValue({ data: null })
  rejectImage.mockResolvedValue({ data: null })
})
afterEach(cleanup)

it('allows manual approval while requiring historical external images to be rejected', async () => {
  render(<ImageModeration />)
  expect(await screen.findByText('头像 #7 待审预览')).toBeTruthy()
  expect(screen.getByText('外链不可预览')).toBeTruthy()
  const approveButtons = screen.getAllByRole('button', { name: '通过' })
  expect(approveButtons[1].disabled).toBe(true)

  fireEvent.click(approveButtons[0])
  fireEvent.click(screen.getByRole('button', { name: '确认' }))
  await waitFor(() => expect(approveImage).toHaveBeenCalledWith(7))

  fireEvent.click(screen.getAllByRole('button', { name: '驳回' })[1])
  fireEvent.change(screen.getByPlaceholderText('请输入驳回原因'), { target: { value: '请重新上传站内图片' } })
  fireEvent.click(screen.getByRole('button', { name: '确认' }))
  await waitFor(() => expect(rejectImage).toHaveBeenCalledWith(8, '请重新上传站内图片'))
})
