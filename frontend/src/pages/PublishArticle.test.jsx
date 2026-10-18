import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import PublishArticle from './PublishArticle.jsx'
import { publishArticle, saveDraft } from '../api/articleApi.js'
import { listCategories } from '../api/categoryApi.js'
import { getPrivateImagePreview, uploadArticleCover } from '../api/uploadApi.js'

vi.mock('../api/articleApi.js', () => ({ publishArticle: vi.fn(), saveDraft: vi.fn() }))
vi.mock('../api/categoryApi.js', () => ({ listCategories: vi.fn() }))
vi.mock('../api/uploadApi.js', () => ({ getPrivateImagePreview: vi.fn(), uploadArticleCover: vi.fn() }))

beforeEach(() => {
  vi.resetAllMocks()
  listCategories.mockResolvedValue({ data: [{ id: 2, name: 'Java' }] })
  publishArticle.mockResolvedValue({ data: null })
  saveDraft.mockResolvedValue({ data: null })
  uploadArticleCover.mockResolvedValue({ data: { id: 9 } })
  getPrivateImagePreview.mockRejectedValue(new Error('私有预览暂不可用'))
})
afterEach(cleanup)

async function mount() {
  render(<MemoryRouter initialEntries={['/publish']}><Routes>
    <Route path="/publish" element={<PublishArticle />} />
    <Route path="/me/articles" element={<p>个人内容列表</p>} />
  </Routes></MemoryRouter>)
  await screen.findByRole('option', { name: 'Java' })
  fireEvent.change(screen.getByLabelText('文章标题'), { target: { value: '新文章' } })
  fireEvent.change(screen.getByLabelText('正文内容'), { target: { value: '正文内容' } })
}
const selectFile = () => {
  const file = new File(['synthetic'], 'cover.png', { type: 'image/png' })
  fireEvent.change(screen.getByLabelText('选择图片'), { target: { files: [file] } })
  return file
}

describe('新建投稿复用编辑字段后的封面回归', () => {
  it.each(['保存草稿', '提交审核'])('未上传的选择不能在 %s 时被静默忽略', async (button) => {
    await mount(); selectFile()
    fireEvent.click(screen.getByRole('button', { name: button }))
    await screen.findByText('请先上传已选择的封面，或取消选择后再保存。')
    expect(saveDraft).not.toHaveBeenCalled()
    expect(publishArticle).not.toHaveBeenCalled()
  })

  it('取消未上传选择后仍可保存普通文章草稿', async () => {
    await mount(); selectFile()
    fireEvent.click(screen.getByRole('button', { name: '取消选择' }))
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByText('个人内容列表')
    expect(saveDraft).toHaveBeenCalledWith({ title: '新文章', content: '正文内容', categoryId: 2, coverImageId: null, contentType: 'ARTICLE', contentFormat: 'MARKDOWN' })
  })

  it('上传封面后通过既有私有接口预览，再保存资源 ID', async () => {
    await mount()
    const file = selectFile()
    fireEvent.click(screen.getByRole('button', { name: '上传封面' }))
    await waitFor(() => expect(getPrivateImagePreview).toHaveBeenCalledWith(9))
    expect(uploadArticleCover).toHaveBeenCalledWith(file)
    expect(screen.getByText(/封面资源 #9/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByText('个人内容列表')
    expect(saveDraft).toHaveBeenCalledWith({ title: '新文章', content: '正文内容', categoryId: 2, coverImageId: 9, contentType: 'ARTICLE', contentFormat: 'MARKDOWN' })
  })

  it('移除已上传封面后以 null 提交，审核说明仍保留', async () => {
    await mount(); selectFile()
    fireEvent.click(screen.getByRole('button', { name: '上传封面' }))
    fireEvent.click(await screen.findByRole('button', { name: '移除封面' }))
    fireEvent.click(screen.getByRole('button', { name: '提交审核' }))
    await screen.findByText('个人内容列表')
    expect(publishArticle).toHaveBeenCalledWith({ title: '新文章', content: '正文内容', categoryId: 2, coverImageId: null, contentType: 'ARTICLE', contentFormat: 'MARKDOWN' })
  })
})

describe('P1-C新建预览和格式', () => {
  it('previews raw Markdown without writing and retains it after a failed save', async () => {
    await mount()
    const raw = '# 新标题\n\n`**literal**`'
    fireEvent.change(screen.getByLabelText('正文内容'), { target: { value: raw } })
    fireEvent.click(screen.getByRole('button', { name: '预览内容' }))
    expect(screen.getByRole('heading', { name: '新标题' })).toBeTruthy()
    expect(saveDraft).not.toHaveBeenCalled(); expect(publishArticle).not.toHaveBeenCalled()
    saveDraft.mockRejectedValueOnce(new Error('保存失败'))
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await screen.findByText('保存失败')
    expect(screen.getByLabelText('正文内容').value).toBe(raw)
    expect(screen.getByLabelText('正文格式').value).toBe('MARKDOWN')
  })
  it('can create plain text without interpreting markup', async () => {
    await mount()
    fireEvent.change(screen.getByLabelText('正文格式'), { target: { value: 'PLAIN_TEXT' } })
    fireEvent.change(screen.getByLabelText('正文内容'), { target: { value: '# 原文' } })
    fireEvent.click(screen.getByRole('button', { name: '预览内容' }))
    expect(document.querySelector('.composer-preview .article-body').textContent).toBe('# 原文')
    fireEvent.click(screen.getByRole('button', { name: '提交审核' }))
    await screen.findByText('个人内容列表')
    expect(publishArticle).toHaveBeenCalledWith(expect.objectContaining({ content: '# 原文', contentFormat: 'PLAIN_TEXT' }))
  })
})
