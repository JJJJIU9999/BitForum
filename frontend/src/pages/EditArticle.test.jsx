import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createBrowserRouter, createMemoryRouter, RouterProvider } from 'react-router'
import { StrictMode } from 'react'
import App from '../App.jsx'
import { clearAuth, getCurrentUser } from '../api/request.js'
import { getCurrentUserProfile } from '../api/userApi.js'
import { getUnreadNotificationCount } from '../api/notificationApi.js'
import { getArticleDetail, pageMyArticles, pageMyFavorites, updateArticle, submitArticle, saveDraft } from '../api/articleApi.js'
import { listCategories } from '../api/categoryApi.js'
import { getPrivateImagePreview, uploadArticleCover } from '../api/uploadApi.js'

vi.mock('../api/request.js', () => ({ clearAuth: vi.fn(), getCurrentUser: vi.fn() }))
vi.mock('../api/userApi.js', () => ({ getCurrentUserProfile: vi.fn(), updateCurrentUserProfile: vi.fn(), loginUser: vi.fn() }))
vi.mock('../api/notificationApi.js', () => ({ getUnreadNotificationCount: vi.fn() }))
vi.mock('../api/articleApi.js', () => ({ getArticleDetail: vi.fn(), pageMyArticles: vi.fn(), pageMyFavorites: vi.fn(), updateArticle: vi.fn(), submitArticle: vi.fn(), saveDraft: vi.fn() }))
vi.mock('../api/categoryApi.js', () => ({ listCategories: vi.fn() }))
vi.mock('../api/uploadApi.js', () => ({ getPrivateImagePreview: vi.fn(), uploadArticleCover: vi.fn(), uploadAvatar: vi.fn() }))
vi.mock('../components/AiAssistantPanel.jsx', () => ({ default: () => null }))
vi.mock('./ArticleList.jsx', () => ({ default: () => <p>社区内容</p> }))

const author = { userId: 1, username: 'writer', role: 'USER' }
const draft = { id: 5, userId: 1, title: '草稿原题', content: '原有正文\n第二行', status: 'DRAFT', contentType: 'ARTICLE', categoryId: 2, categoryName: 'Java', coverImageId: null }
let router
const response = (data) => ({ data })
const page = (records) => response({ records, current: 1, pages: 1, total: records.length })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function mount(entries = ['/articles/5/edit'], strict = false) {
  router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: entries, initialIndex: entries.length - 1 })
  return render(strict ? <StrictMode><RouterProvider router={router} /></StrictMode> : <RouterProvider router={router} />)
}
async function edit(title = '新标题') {
  const input = await screen.findByLabelText('文章标题')
  fireEvent.change(input, { target: { value: title } })
  return input
}
function goHome() { fireEvent.click(screen.getAllByRole('link', { name: '社区', exact: true })[0]) }

beforeEach(() => {
  vi.resetAllMocks()
  getCurrentUser.mockReturnValue(author)
  getCurrentUserProfile.mockResolvedValue(response({ ...author, nickname: '作者', avatar: '' }))
  getUnreadNotificationCount.mockResolvedValue(response(0))
  getArticleDetail.mockResolvedValue(response(draft))
  pageMyArticles.mockResolvedValue(page([draft]))
  pageMyFavorites.mockResolvedValue(page([]))
  updateArticle.mockResolvedValue(response(null))
  submitArticle.mockResolvedValue(response(null))
  getPrivateImagePreview.mockResolvedValue(new Blob(['synthetic image'], { type: 'image/png' }))
  uploadArticleCover.mockResolvedValue(response({ id: 9 }))
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:private-preview'), revokeObjectURL: vi.fn() }))
})
afterEach(() => { cleanup(); router?.dispose(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

describe('作者草稿编辑闭环（与实际 App 路由组合）', () => {
  it.each(['DRAFT', 'REJECTED'])('直接打开 %s 内容并保存标题正文，元数据保持只读', async (status) => {
    getArticleDetail.mockResolvedValue(response({ ...draft, status }))
    mount()
    await edit()
    fireEvent.change(screen.getByLabelText('正文内容'), { target: { value: '更新正文' } })
    expect(screen.getByLabelText('内容类型').readOnly).toBe(true)
    expect(screen.getByLabelText('所属板块').value).toBe('Java')
    expect(screen.getByLabelText('所属板块').readOnly).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    expect(updateArticle).toHaveBeenCalledWith({ articleId: 5, title: '新标题', content: '更新正文', coverImageId: null, contentFormat: 'PLAIN_TEXT' })
    expect(submitArticle).not.toHaveBeenCalled()
    expect(saveDraft).not.toHaveBeenCalled()
    expect(listCategories).not.toHaveBeenCalled()
    expect(router.state.location.pathname).toBe('/articles/5/edit')
  })

  it('legacy drafts remain literal; explicitly changing only the format counts as unsaved', async () => {
    mount(); await screen.findByLabelText('正文内容')
    expect(screen.getByLabelText('正文格式').value).toBe('PLAIN_TEXT')
    fireEvent.change(screen.getByLabelText('正文格式'), { target: { value: 'MARKDOWN' } })
    goHome()
    expect(await screen.findByText('离开编辑页？')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(screen.getByLabelText('正文内容').value).toBe(draft.content)
  })

  it.each(['ARTICLE', 'QUESTION'])('saves raw Markdown and format before submitting %s, then reads it back', async (contentType) => {
    const raw = '# 标题\n\n```java\nString s = "<b>";\n```'
    getArticleDetail.mockResolvedValue(response({ ...draft, contentType, contentFormat: 'MARKDOWN', content: raw }))
    const view = mount()
    expect((await screen.findByLabelText(contentType === 'QUESTION' ? '问题描述' : '正文内容')).value).toBe(raw)
    expect(screen.getByLabelText('正文格式').value).toBe('MARKDOWN')
    fireEvent.click(screen.getByRole('button', { name: '预览内容' }))
    const preview = screen.getByRole('region', { name: '内容预览' })
    expect(within(preview).getByRole('heading', { name: '标题' })).toBeTruthy()
    expect(preview.querySelector('pre code').textContent).toBe('String s = "<b>";')
    expect(updateArticle).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    expect(updateArticle).toHaveBeenCalledWith({ articleId: 5, title: draft.title, content: raw, contentFormat: 'MARKDOWN', coverImageId: null })
    view.unmount(); router.dispose(); mount()
    expect((await screen.findByLabelText(contentType === 'QUESTION' ? '问题描述' : '正文内容')).value).toBe(raw)
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/me/articles'))
    expect(updateArticle.mock.invocationCallOrder.at(-1)).toBeLessThan(submitArticle.mock.invocationCallOrder[0])
    expect(submitArticle).toHaveBeenCalledTimes(1)
  })

  it('问题保持 QUESTION 和话题，不向更新接口发送不可修改的字段', async () => {
    getArticleDetail.mockResolvedValue(response({ ...draft, contentType: 'QUESTION' }))
    mount()
    fireEvent.change(await screen.findByLabelText('问题标题'), { target: { value: '问题新标题' } })
    fireEvent.change(screen.getByLabelText('问题描述'), { target: { value: '尝试过程' } })
    expect(screen.getByLabelText('内容类型').value).toBe('问题')
    expect(screen.getByLabelText('所属话题').value).toBe('Java')
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    expect(updateArticle).toHaveBeenCalledWith({ articleId: 5, title: '问题新标题', content: '尝试过程', coverImageId: null, contentFormat: 'PLAIN_TEXT' })
  })

  it('重挂载／刷新按文章 ID 重新读取，不依赖列表内存或私有内容缓存', async () => {
    const view = mount()
    await screen.findByDisplayValue('草稿原题')
    view.unmount(); router.dispose()
    getArticleDetail.mockResolvedValue(response({ ...draft, title: '服务器已保存标题' }))
    mount()
    expect(await screen.findByDisplayValue('服务器已保存标题')).toBeTruthy()
    expect(getArticleDetail).toHaveBeenCalledTimes(2)
    expect(getArticleDetail).toHaveBeenLastCalledWith('5')
  })

  it('匿名直达编辑链接先进入登录，不读取未公开内容', async () => {
    getCurrentUser.mockReturnValue(null)
    mount()
    await screen.findByRole('button', { name: '登录', exact: true })
    expect(router.state.location.pathname).toBe('/login')
    expect(new URLSearchParams(router.state.location.search).get('redirectTo')).toBe('/articles/5/edit')
    expect(getArticleDetail).not.toHaveBeenCalled()
  })

  it.each(['PENDING', 'PUBLISHED', 'OFFLINE'])('状态为 %s 时不可编辑或写入', async (status) => {
    getArticleDetail.mockResolvedValue(response({ ...draft, status }))
    mount()
    await screen.findByText(/当前内容不可编辑/)
    expect(screen.queryByRole('button', { name: '保存修改' })).toBeNull()
    expect(screen.queryByRole('textbox', { name: '文章标题' })).toBeNull()
    expect(updateArticle).not.toHaveBeenCalled()
  })

  it('非作者即使收到详情也不能编辑', async () => {
    getArticleDetail.mockResolvedValue(response({ ...draft, userId: 2 }))
    mount()
    await screen.findByText(/当前内容不可编辑/)
    expect(screen.queryByRole('button', { name: '保存并提交审核' })).toBeNull()
    expect(updateArticle).not.toHaveBeenCalled()
  })

  it('加载错误保留返回入口，只重试读取', async () => {
    getArticleDetail.mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce(response(draft))
    mount()
    await screen.findByText('读取失败')
    expect(screen.getByRole('link', { name: '返回我的文章' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试加载' }))
    await screen.findByDisplayValue('草稿原题')
    expect(getArticleDetail).toHaveBeenCalledTimes(2)
    expect(updateArticle).not.toHaveBeenCalled()
  })

  it('详情为空或 ID 不匹配时不可把其他内容写回目标 ID', async () => {
    getArticleDetail.mockResolvedValue(response({ ...draft, id: 6 }))
    mount()
    await screen.findByText('内容不存在或已不可访问。')
    expect(screen.queryByRole('button', { name: '保存修改' })).toBeNull()
  })

  it('预览即时显示纯文本并转义 HTML，不发出写入请求', async () => {
    mount()
    await edit('预览标题')
    fireEvent.change(screen.getByLabelText('正文内容'), { target: { value: '<img src=x onerror=alert(1)>\n# 普通文本' } })
    fireEvent.click(screen.getByRole('button', { name: '预览内容' }))
    const preview = screen.getByRole('region', { name: '内容预览' })
    expect(within(preview).getByRole('heading', { name: '预览标题' })).toBeTruthy()
    expect(preview.querySelector('.article-body').textContent).toBe('<img src=x onerror=alert(1)>\n# 普通文本')
    expect(preview.querySelector('img')).toBeNull()
    expect(updateArticle).not.toHaveBeenCalled()
  })

  it.each(['只能修改自己的文章', '只能修改草稿或被驳回文章', '网络暂不可用'])('保存失败 %s 保留输入并禁止提交', async (message) => {
    updateArticle.mockRejectedValue(new Error(message))
    mount()
    await edit()
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    await screen.findByText(`保存失败：${message}`)
    expect(screen.getByLabelText('文章标题').value).toBe('新标题')
    expect(submitArticle).not.toHaveBeenCalled()
    expect(saveDraft).not.toHaveBeenCalled()
  })

  it('先保存再提交，成功后回到刷新后的个人列表', async () => {
    const save = deferred()
    updateArticle.mockReturnValue(save.promise)
    pageMyArticles.mockResolvedValue(page([{ ...draft, status: 'PENDING', title: '新标题' }]))
    mount()
    await edit()
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    expect(updateArticle).toHaveBeenCalledTimes(1)
    expect(submitArticle).not.toHaveBeenCalled()
    await act(async () => save.resolve(response(null)))
    await screen.findByRole('heading', { name: '我的文章' })
    expect(submitArticle).toHaveBeenCalledWith(5)
    expect(router.state.location.pathname).toBe('/me/articles')
    expect(pageMyArticles).toHaveBeenCalled()
    expect(screen.queryByRole('link', { name: '继续编辑' })).toBeNull()
    expect(saveDraft).not.toHaveBeenCalled()
  })

  it('提交失败明确已保存，重试不会新建内容或丢失输入', async () => {
    submitArticle.mockRejectedValueOnce(new Error('审核队列不可用')).mockResolvedValueOnce(response(null))
    mount()
    await edit()
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    await screen.findByText('内容已保存，尚未提交审核：审核队列不可用')
    expect(screen.getByLabelText('文章标题').value).toBe('新标题')
    expect(router.state.location.pathname).toBe('/articles/5/edit')
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    await screen.findByRole('heading', { name: '我的文章' })
    expect(updateArticle).toHaveBeenCalledTimes(2)
    expect(submitArticle).toHaveBeenCalledTimes(2)
    expect(saveDraft).not.toHaveBeenCalled()
  })

  it('提交响应失败但服务器已进入 PENDING 时禁止重复提交', async () => {
    getArticleDetail.mockResolvedValueOnce(response(draft)).mockResolvedValueOnce(response({ ...draft, status: 'PENDING' }))
    submitArticle.mockRejectedValue(new Error('响应超时'))
    mount()
    await edit()
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    await screen.findByText('内容已保存并进入待审核状态，请勿重复提交。')
    expect(screen.queryByRole('button', { name: '保存并提交审核' })).toBeNull()
    expect(getArticleDetail).toHaveBeenCalledTimes(2)
    expect(submitArticle).toHaveBeenCalledTimes(1)
  })

  it('提交和状态回读都失败时先冻结写操作，核对恢复后才允许重试', async () => {
    getArticleDetail.mockResolvedValueOnce(response(draft)).mockRejectedValueOnce(new Error('状态回读失败'))
      .mockResolvedValueOnce(response(draft))
    submitArticle.mockRejectedValueOnce(new Error('响应超时'))
    mount()
    await edit()
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    await screen.findByText('内容已保存，提交结果暂无法确认；请先核对审核状态，勿重复提交。')
    expect(screen.getByRole('button', { name: '保存修改' }).disabled).toBe(true)
    expect(screen.getByRole('button', { name: '保存并提交审核' }).disabled).toBe(true)
    expect(screen.getByLabelText('文章标题').value).toBe('新标题')
    fireEvent.click(screen.getByRole('button', { name: '核对审核状态' }))
    await screen.findByText('内容已保存，尚未提交审核：请检查内容后重新提交。')
    expect(screen.getByRole('button', { name: '保存修改' }).disabled).toBe(false)
    expect(updateArticle).toHaveBeenCalledTimes(1)
    expect(submitArticle).toHaveBeenCalledTimes(1)
  })

  it('StrictMode 的重复加载清理不会造成重复写入', async () => {
    mount(['/articles/5/edit'], true)
    await edit()
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    expect(updateArticle).toHaveBeenCalledTimes(1)
    expect(submitArticle).not.toHaveBeenCalled()
  })

  it('空白标题或正文不能进入更新／提交接口', async () => {
    mount()
    await edit('   ')
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('标题和正文不能为空。')
    fireEvent.change(screen.getByLabelText('文章标题'), { target: { value: '正常标题' } })
    fireEvent.change(screen.getByLabelText('正文内容'), { target: { value: '  ' } })
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    expect(updateArticle).not.toHaveBeenCalled()
    expect(submitArticle).not.toHaveBeenCalled()
  })

  it('待处理请求禁用表单与重复操作，离开确认等待保存完成', async () => {
    const save = deferred()
    updateArticle.mockReturnValue(save.promise)
    mount()
    await edit()
    const button = screen.getByRole('button', { name: '保存修改' })
    fireEvent.click(button); fireEvent.click(button)
    expect(updateArticle).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('文章标题').closest('fieldset').disabled).toBe(true)
    goHome()
    await screen.findByRole('dialog')
    expect(screen.getByRole('button', { name: '离开', exact: true }).disabled).toBe(true)
    expect(router.state.location.pathname).toBe('/articles/5/edit')
    await act(async () => save.resolve(response(null)))
    await waitFor(() => expect(screen.getByRole('button', { name: '离开', exact: true }).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: '离开', exact: true }))
    await screen.findByText('社区内容')
  })
})

describe('封面权限与编辑内容一致性', () => {
  it('已有封面只走私有预览，替换上传结果经保存绑定', async () => {
    getArticleDetail.mockResolvedValue(response({ ...draft, coverImageId: 8, coverUrl: '/api/images/8/content' }))
    mount()
    await screen.findByDisplayValue('草稿原题')
    const image = await screen.findByRole('img', { name: '私有封面预览' })
    expect(image.getAttribute('src')).toBe('blob:private-preview')
    expect(getPrivateImagePreview).toHaveBeenCalledWith(8)
    const file = new File(['synthetic'], 'cover.png', { type: 'image/png' })
    fireEvent.change(screen.getByLabelText('选择图片'), { target: { files: [file] } })
    fireEvent.click(screen.getByRole('button', { name: '上传封面' }))
    await screen.findByText(/封面已上传，尚未保存到内容/)
    expect(uploadArticleCover).toHaveBeenCalledWith(file)
    expect(getPrivateImagePreview).toHaveBeenCalledWith(9)
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    expect(updateArticle).toHaveBeenCalledWith({ articleId: 5, title: draft.title, content: draft.content, coverImageId: 9, contentFormat: 'PLAIN_TEXT' })
  })

  it('移除既有封面发送 null，不删除服务器资源', async () => {
    getArticleDetail.mockResolvedValue(response({ ...draft, coverImageId: 8 }))
    mount()
    fireEvent.click(await screen.findByRole('button', { name: '移除封面' }))
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    expect(updateArticle).toHaveBeenCalledWith({ articleId: 5, title: draft.title, content: draft.content, coverImageId: null, contentFormat: 'PLAIN_TEXT' })
  })

  it('已选但未上传的文件阻止保存，取消选择保留原封面', async () => {
    getArticleDetail.mockResolvedValue(response({ ...draft, coverImageId: 8 }))
    mount()
    await screen.findByDisplayValue('草稿原题')
    fireEvent.change(screen.getByLabelText('选择图片'), { target: { files: [new File(['x'], 'new.png', { type: 'image/png' })] } })
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    await screen.findByText('请先上传已选择的封面，或取消选择后再保存。')
    expect(updateArticle).not.toHaveBeenCalled()
    expect(submitArticle).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '取消选择' }))
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    expect(updateArticle).toHaveBeenCalledWith({ articleId: 5, title: draft.title, content: draft.content, coverImageId: 8, contentFormat: 'PLAIN_TEXT' })
  })

  it('上传失败保留文件、标题和原封面，未公开内容不被放行', async () => {
    uploadArticleCover.mockRejectedValueOnce(new Error('上传失败'))
    getArticleDetail.mockResolvedValue(response({ ...draft, coverImageId: 8 }))
    mount()
    await edit()
    fireEvent.change(screen.getByLabelText('选择图片'), { target: { files: [new File(['x'], 'new.png', { type: 'image/png' })] } })
    fireEvent.click(screen.getByRole('button', { name: '上传封面' }))
    await screen.findByText('上传失败')
    expect(screen.getByLabelText('文章标题').value).toBe('新标题')
    expect(screen.getByText(/已选择：new.png/)).toBeTruthy()
    expect(screen.getByText(/封面资源 #8/)).toBeTruthy()
    expect(updateArticle).not.toHaveBeenCalled()
    expect(submitArticle).not.toHaveBeenCalled()
  })
})

describe('未保存离开提示及请求隔离', () => {
  it('与 main 相同的 Browser data router 能阻断并恢复真实 history 导航', async () => {
    window.history.replaceState(null, '', '/')
    router = createBrowserRouter([{ path: '*', element: <App /> }])
    render(<RouterProvider router={router} />)
    await screen.findByText('社区内容')
    await act(async () => router.navigate('/articles/5/edit'))
    await edit()
    await act(async () => router.navigate(-1))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }))
    await waitFor(() => expect(window.location.pathname).toBe('/articles/5/edit'))
    expect(screen.getByLabelText('文章标题').value).toBe('新标题')
    await act(async () => router.navigate(-1))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '离开', exact: true }))
    await screen.findByText('社区内容')
    await waitFor(() => expect(window.location.pathname).toBe('/'))
  })

  it('站内导航可取消并保留输入，确认后才离开', async () => {
    mount()
    await edit()
    goHome()
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }))
    expect(screen.getByLabelText('文章标题').value).toBe('新标题')
    expect(router.state.location.pathname).toBe('/articles/5/edit')
    fireEvent.click(screen.getByRole('link', { name: '返回我的文章' }))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '离开', exact: true }))
    await screen.findByRole('heading', { name: '我的文章' })
  })

  it('浏览器返回被阻断，取消后继续编辑，确认后返回原页面', async () => {
    mount(['/', '/articles/5/edit'])
    await edit()
    await act(async () => router.navigate(-1))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }))
    expect(router.state.location.pathname).toBe('/articles/5/edit')
    await act(async () => router.navigate(-1))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: '离开', exact: true }))
    await screen.findByText('社区内容')
  })

  it('刷新／关闭仅在有未保存修改时触发原生保护，保存后清除', async () => {
    mount()
    await edit()
    const first = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(first)
    expect(first.defaultPrevented).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    const second = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(second)
    expect(second.defaultPrevented).toBe(false)
    goHome()
    await screen.findByText('社区内容')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('取消退出不清除身份，确认退出后才进入登录页', async () => {
    mount()
    await edit()
    fireEvent.click(screen.getByRole('button', { name: '退出', exact: true }))
    await screen.findByRole('dialog')
    expect(clearAuth).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }))
    expect(clearAuth).not.toHaveBeenCalled()
    expect(screen.getByLabelText('文章标题').value).toBe('新标题')
    fireEvent.click(screen.getByRole('button', { name: '退出', exact: true }))
    fireEvent.click(await screen.findByRole('button', { name: '离开', exact: true }))
    await screen.findByRole('button', { name: '登录', exact: true })
    expect(clearAuth).toHaveBeenCalledTimes(1)
  })

  it('切换文章后过期详情不能覆盖新文章或写入旧 ID', async () => {
    const old = deferred()
    getArticleDetail.mockReturnValueOnce(old.promise).mockResolvedValueOnce(response({ ...draft, id: 6, title: '新文章' }))
    mount()
    await waitFor(() => expect(getArticleDetail).toHaveBeenCalledWith('5'))
    await act(async () => router.navigate('/articles/6/edit'))
    await screen.findByDisplayValue('新文章')
    await act(async () => old.resolve(response(draft)))
    expect(screen.getByLabelText('文章标题').value).toBe('新文章')
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await screen.findByText('内容已保存，尚未提交审核。')
    expect(updateArticle).toHaveBeenCalledWith({ articleId: 6, title: '新文章', content: draft.content, coverImageId: null, contentFormat: 'PLAIN_TEXT' })
  })

  it('加载中退出后过期详情不泄露在登录页', async () => {
    const old = deferred()
    getArticleDetail.mockReturnValueOnce(old.promise)
    mount()
    fireEvent.click(screen.getByRole('button', { name: '退出', exact: true }))
    await screen.findByRole('button', { name: '登录', exact: true })
    await act(async () => old.resolve(response(draft)))
    expect(screen.queryByDisplayValue('草稿原题')).toBeNull()
  })

  it('编辑页卸载后的更新响应不得继续触发提交', async () => {
    const save = deferred()
    updateArticle.mockReturnValue(save.promise)
    const view = mount()
    await edit()
    fireEvent.click(screen.getByRole('button', { name: '保存并提交审核' }))
    view.unmount()
    await act(async () => save.resolve(response(null)))
    expect(submitArticle).not.toHaveBeenCalled()
  })

  it('卸载后的上传响应不绑定封面或触发写入', async () => {
    const upload = deferred()
    uploadArticleCover.mockReturnValue(upload.promise)
    const view = mount()
    await screen.findByDisplayValue('草稿原题')
    fireEvent.change(screen.getByLabelText('选择图片'), { target: { files: [new File(['x'], 'new.png', { type: 'image/png' })] } })
    fireEvent.click(screen.getByRole('button', { name: '上传封面' }))
    view.unmount()
    await act(async () => upload.resolve(response({ id: 99 })))
    expect(getPrivateImagePreview).not.toHaveBeenCalledWith(99)
    expect(updateArticle).not.toHaveBeenCalled()
  })
})

describe('个人内容列表的编辑入口', () => {
  it('仅作者 DRAFT／REJECTED 出现继续编辑，入口打开真实编辑路由', async () => {
    pageMyArticles.mockResolvedValue(page([
      draft, { ...draft, id: 6, title: '被驳回内容', status: 'REJECTED' },
      ...['PENDING', 'PUBLISHED', 'OFFLINE'].map((status, i) => ({ ...draft, id: i + 7, title: status, status })),
      { ...draft, id: 10, userId: 2, title: '其他作者内容' },
    ]))
    mount(['/me/articles'])
    const links = await screen.findAllByRole('link', { name: '继续编辑' })
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/articles/5/edit', '/articles/6/edit'])
    fireEvent.click(links[0])
    await screen.findByDisplayValue('草稿原题')
    expect(router.state.location.pathname).toBe('/articles/5/edit')
  })

  it('收藏列表不提供编辑入口', async () => {
    pageMyFavorites.mockResolvedValue(page([draft]))
    mount(['/me/favorites'])
    await screen.findByRole('link', { name: '草稿原题' })
    expect(screen.queryByRole('link', { name: '继续编辑' })).toBeNull()
  })
})
