import { useEffect, useState } from 'react'
import { Eye, Save, Send } from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router'
import { publishArticle, saveDraft } from '../api/articleApi.js'
import { listCategories } from '../api/categoryApi.js'
import { uploadArticleCover } from '../api/uploadApi.js'
import PageHeader from '../components/PageHeader.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
import ContentEditorFields from '../components/ContentEditorFields.jsx'
import ContentPreview from '../components/ContentPreview.jsx'

function PublishArticle() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [form, setForm] = useState({ title: '', content: '', contentFormat: 'MARKDOWN', categoryId: '', coverImageId: null, contentType: searchParams.get('type') === 'QUESTION' ? 'QUESTION' : 'ARTICLE' })
  const isQuestion = form.contentType === 'QUESTION'
  const [preview, setPreview] = useState(false)
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
    return { title: form.title, content: form.content, categoryId: Number(form.categoryId), coverImageId: form.coverImageId, contentType: form.contentType, contentFormat: form.contentFormat }
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
      setCoverFile(null)
      setCoverInputKey((key) => key + 1)
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
    if (coverFile) {
      setMessage('请先上传已选择的封面，或取消选择后再保存。')
      setMessageType('error')
      return
    }
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
      <ContentEditorFields form={form} categories={categories} onChange={updateField}
        disabled={loading || draftLoading || coverUploading} coverFile={coverFile} coverInputKey={coverInputKey}
        onSelectCover={setCoverFile} onUploadCover={handleCoverUpload} onRemoveCover={resetCover}
        onClearCoverFile={() => { setCoverFile(null); setCoverInputKey((key) => key + 1) }} coverUploading={coverUploading} />
      <div className="composer-actions"><button className="primary-button icon-text-button" disabled={loading || draftLoading || coverUploading} type="submit"><Send size={17} aria-hidden="true" />{loading ? '提交中…' : '提交审核'}</button><button className="ghost-button icon-text-button" disabled={loading || draftLoading || coverUploading} type="button" onClick={() => submitArticle(null, true)}><Save size={17} aria-hidden="true" />{draftLoading ? '保存中…' : '保存草稿'}</button></div>
      <button className="ghost-button icon-text-button" type="button" onClick={() => setPreview((value) => !value)} aria-expanded={preview}><Eye size={17} aria-hidden="true" />{preview ? '收起预览' : '预览内容'}</button>
      {preview && <ContentPreview form={form} />}
      <MessageBanner message={message} type={messageType} />
    </form>
  )
}

export default PublishArticle
