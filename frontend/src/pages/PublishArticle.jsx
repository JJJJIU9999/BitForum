import { useEffect, useState } from 'react'
import { ImageUp, Save, Send, Trash2 } from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router'
import { publishArticle, saveDraft } from '../api/articleApi.js'
import { listCategories } from '../api/categoryApi.js'
import { uploadArticleCover } from '../api/uploadApi.js'
import PageHeader from '../components/PageHeader.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
import PrivateImagePreview from '../components/PrivateImagePreview.jsx'

function PublishArticle() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [form, setForm] = useState({ title: '', content: '', categoryId: '', coverImageId: null, contentType: searchParams.get('type') === 'QUESTION' ? 'QUESTION' : 'ARTICLE' })
  const isQuestion = form.contentType === 'QUESTION'
  const [categories, setCategories] = useState([])
  const [coverFile, setCoverFile] = useState(null)
  const [coverInputKey, setCoverInputKey] = useState(0)
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState('info')
  const [loading, setLoading] = useState(false)
  const [draftLoading, setDraftLoading] = useState(false)
  const [coverUploading, setCoverUploading] = useState(false)

  useEffect(() => {
    async function loadCategories() {
      try {
        const result = await listCategories()
        const list = result.data || []
        setCategories(list)
        if (list.length > 0) setForm((current) => ({ ...current, categoryId: String(list[0].id) }))
      } catch (error) {
        setMessage(error.message)
        setMessageType('error')
      }
    }
    loadCategories()
  }, [])

  function updateField(event) {
    const { name, value } = event.target
    setForm((current) => ({ ...current, [name]: value }))
  }

  function payloadFromForm() {
    return { title: form.title, content: form.content, categoryId: Number(form.categoryId), coverImageId: form.coverImageId, contentType: form.contentType }
  }

  function resetCover() {
    setCoverFile(null)
    setCoverInputKey((key) => key + 1)
    setForm((current) => ({ ...current, coverImageId: null }))
  }

  async function handleCoverUpload() {
    if (!coverFile) {
      setMessage('请选择 JPG、PNG 或 WebP 图片。')
      setMessageType('error')
      return
    }
    setCoverUploading(true)
    setMessage('')
    try {
      const result = await uploadArticleCover(coverFile)
      setForm((current) => ({ ...current, coverImageId: result.data.id }))
      setMessage('封面已上传，等待管理员审核；审核通过前文章不能公开。')
      setMessageType('info')
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
    } finally {
      setCoverUploading(false)
    }
  }

  async function submitArticle(event, asDraft) {
    event?.preventDefault()
    if (!form.categoryId) {
      setMessage(isQuestion ? '请选择问题话题。' : '请选择文章板块。')
      setMessageType('error')
      return
    }
    const setBusy = asDraft ? setDraftLoading : setLoading
    setBusy(true)
    setMessage('')
    try {
      if (asDraft) await saveDraft(payloadFromForm())
      else await publishArticle(payloadFromForm())
      navigate('/me/articles', { replace: true })
    } catch (error) {
      setMessage(error.message)
      setMessageType('error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="composer-layout" onSubmit={(event) => submitArticle(event, false)}>
      <PageHeader title={isQuestion ? '提出一个问题' : '写一篇文章'} description={isQuestion ? '描述背景、已尝试的方法和期望结果。问题审核通过后开放回答。' : '先保存草稿，再在准备好后提交审核。'} />
      <div className="composer-grid">
        <section className="composer-card">
          <label>内容类型<select name="contentType" onChange={updateField} value={form.contentType}><option value="ARTICLE">文章</option><option value="QUESTION">问题</option></select></label>
          <label>{isQuestion ? '选择话题' : '选择板块'}<select name="categoryId" onChange={updateField} value={form.categoryId} required>{categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
          <label>{isQuestion ? '问题标题' : '文章标题'}<input maxLength="50" name="title" onChange={updateField} placeholder={isQuestion ? '用一句话描述你遇到的具体问题' : '用一句清楚的话概括观点'} type="text" value={form.title} required /></label>
          <label>{isQuestion ? '问题描述' : '正文内容'}<textarea name="content" onChange={updateField} placeholder={isQuestion ? '背景是什么？尝试过什么？实际结果与期望有什么差异？' : '写下背景、过程、结论，或你想讨论的问题…'} value={form.content} required /></label>
          <p className="field-hint">{form.content.length} 字 · 提交后会进入管理员审核。</p>
        </section>
        <aside className="cover-panel">
          <p className="eyebrow">{isQuestion ? '问题封面（可选）' : '文章封面'}</p><h2>{isQuestion ? '补充问题的背景' : '给文章一个入口'}</h2><p>支持 JPG、PNG、WebP，最大 5MB。</p>
          {form.coverImageId ? <PrivateImagePreview imageId={form.coverImageId} alt="待审封面预览" className="cover-preview" /> : <div className="cover-placeholder"><ImageUp size={30} aria-hidden="true" /><span>还没有选择封面</span></div>}
          <label className="file-picker">选择图片<input key={coverInputKey} accept="image/jpeg,image/png,image/webp" type="file" onChange={(event) => setCoverFile(event.target.files?.[0] || null)} /></label>
          <div className="cover-actions"><button className="ghost-button" disabled={loading || draftLoading || coverUploading || !coverFile} type="button" onClick={handleCoverUpload}>{coverUploading ? '上传中…' : '上传封面'}</button>{form.coverImageId && <button className="text-button" type="button" onClick={resetCover}><Trash2 size={15} aria-hidden="true" />移除</button>}</div>
          {form.coverImageId && <p role="status" className="field-hint">资源 #{form.coverImageId} 待人工审核；预览仅你和管理员可见。</p>}
        </aside>
      </div>
      <div className="composer-actions"><button className="primary-button icon-text-button" disabled={loading || draftLoading || coverUploading} type="submit"><Send size={17} aria-hidden="true" />{loading ? '提交中…' : '提交审核'}</button><button className="ghost-button icon-text-button" disabled={loading || draftLoading || coverUploading} type="button" onClick={() => submitArticle(null, true)}><Save size={17} aria-hidden="true" />{draftLoading ? '保存中…' : '保存草稿'}</button></div>
      <MessageBanner message={message} type={messageType} />
    </form>
  )
}

export default PublishArticle
