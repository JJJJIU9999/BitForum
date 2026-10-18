import { useCallback, useEffect, useRef, useState } from 'react'
import { Eye, Save, Send } from 'lucide-react'
import { Link, useBeforeUnload, useBlocker, useNavigate, useOutletContext, useParams } from 'react-router'
import { getArticleDetail, submitArticle, updateArticle } from '../api/articleApi.js'
import { uploadArticleCover } from '../api/uploadApi.js'
import ContentEditorFields from '../components/ContentEditorFields.jsx'
import ContentPreview from '../components/ContentPreview.jsx'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import MessageBanner from '../components/MessageBanner.jsx'
import PageHeader from '../components/PageHeader.jsx'

const editable = (article, identity) => String(article.userId) === String(identity)
  && ['DRAFT', 'REJECTED'].includes(article.status)
const values = (article) => ({ title: article.title || '', content: article.content || '', contentFormat: article.contentFormat || 'PLAIN_TEXT', coverImageId: article.coverImageId || null })
const fingerprint = (form) => JSON.stringify(values(form))

function EditArticle() {
  const { articleId } = useParams()
  const { currentUser, registerLogoutGuard } = useOutletContext()
  const identity = currentUser?.userId ?? currentUser?.id
  const scope = `${articleId}:${identity}`
  const scopeRef = useRef(scope)
  scopeRef.current = scope
  const versionRef = useRef(0)
  const busyRef = useRef(false)
  const allowLeaveRef = useRef(false)
  const formRef = useRef(null)
  const navigate = useNavigate()
  const [loaded, setLoaded] = useState(null)
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [retry, setRetry] = useState(0)
  const [busy, setBusy] = useState('')
  const [coverFile, setCoverFile] = useState(null)
  const [coverInputKey, setCoverInputKey] = useState(0)
  const [preview, setPreview] = useState(false)
  const [message, setMessage] = useState('')
  const [messageType, setMessageType] = useState('info')
  const [pendingLogout, setPendingLogout] = useState(null)
  const [submissionUnknown, setSubmissionUnknown] = useState(false)
  const article = loaded?.scope === scope ? loaded.article : null
  const canEdit = article && editable(article, identity)
  const dirty = Boolean(canEdit && form && (fingerprint(form) !== saved || coverFile))

  const blocker = useBlocker(useCallback(() => !allowLeaveRef.current && (dirty || Boolean(busy)), [dirty, busy]))
  useBeforeUnload(useCallback((event) => {
    if (!allowLeaveRef.current && (dirty || busy)) {
      event.preventDefault()
      event.returnValue = ''
    }
  }, [dirty, busy]))

  useEffect(() => {
    const version = ++versionRef.current
    busyRef.current = false
    allowLeaveRef.current = false
    setLoaded(null); setForm(null); setSaved(''); setLoading(true); setLoadError('')
    setBusy(''); setCoverFile(null); setPreview(false); setMessage(''); setPendingLogout(null); setSubmissionUnknown(false)
    setCoverInputKey((key) => key + 1)
    getArticleDetail(articleId).then(({ data }) => {
      if (version !== versionRef.current || scope !== scopeRef.current) return
      if (!data || String(data.id) !== String(articleId)) throw new Error('内容不存在或已不可访问。')
      setLoaded({ scope, article: data })
      setForm({ ...values(data), contentType: data.contentType || 'ARTICLE', categoryId: data.categoryId, categoryName: data.categoryName })
      setSaved(fingerprint(data))
    }).catch((error) => {
      if (version === versionRef.current && scope === scopeRef.current) setLoadError(error.message)
    }).finally(() => {
      if (version === versionRef.current && scope === scopeRef.current) setLoading(false)
    })
    return () => { ++versionRef.current }
  }, [articleId, identity, retry])

  useEffect(() => registerLogoutGuard?.((logout) => {
    if (dirty || busy) setPendingLogout({ logout })
    else logout()
  }), [registerLogoutGuard, dirty, busy])

  function notify(text, type = 'error') {
    setMessage(text); setMessageType(type)
  }

  function clearCoverSelection() {
    setCoverFile(null); setCoverInputKey((key) => key + 1)
  }

  async function uploadCover() {
    if (!canEdit || !coverFile || busyRef.current) return
    busyRef.current = true; setBusy('upload'); setMessage('')
    const version = versionRef.current
    const active = () => version === versionRef.current && scope === scopeRef.current
    try {
      const result = await uploadArticleCover(coverFile)
      if (!active()) return
      if (!result.data?.id) throw new Error('上传结果不完整，请重试。')
      setForm((current) => ({ ...current, coverImageId: result.data.id }))
      clearCoverSelection()
      notify('封面已上传，尚未保存到内容；审核通过后才会公开。', 'info')
    } catch (error) {
      if (active()) notify(error.message)
    } finally {
      if (active()) { busyRef.current = false; setBusy('') }
    }
  }

  async function persist(asSubmit) {
    if (!canEdit || busyRef.current || submissionUnknown) return
    if (!formRef.current.reportValidity()) return
    if (!form.title.trim() || !form.content.trim()) { notify('标题和正文不能为空。'); return }
    if (coverFile) { notify('请先上传已选择的封面，或取消选择后再保存。'); return }
    busyRef.current = true; setBusy(asSubmit ? 'submit' : 'save'); setMessage('')
    const version = versionRef.current
    const active = () => version === versionRef.current && scope === scopeRef.current
    const payload = { articleId: Number(articleId), ...values(form) }
    let updated = false
    try {
      await updateArticle(payload)
      updated = true
      if (!active()) return
      setSaved(fingerprint(payload))
      if (asSubmit) {
        await submitArticle(Number(articleId))
        if (!active()) return
        allowLeaveRef.current = true
        navigate('/me/articles', { replace: true })
      } else notify('内容已保存，尚未提交审核。', 'info')
    } catch (error) {
      if (active()) {
        if (updated) await readSubmissionStatus(active, error.message)
        else notify(`保存失败：${error.message}`)
      }
    } finally {
      if (active()) { busyRef.current = false; setBusy('') }
    }
  }

  // A failed response can follow a committed submit. Read status before allowing a retry.
  async function readSubmissionStatus(active, failureMessage = '请检查内容后重新提交。') {
    try {
      const { data } = await getArticleDetail(articleId)
      if (!active()) return
      if (!data || String(data.id) !== String(articleId)) throw new Error('内容状态暂不可确认。')
      setLoaded({ scope, article: data }); setSubmissionUnknown(false)
      if (data.status === 'PENDING') notify('内容已保存并进入待审核状态，请勿重复提交。', 'info')
      else if (editable(data, identity)) notify(`内容已保存，尚未提交审核：${failureMessage}`)
      else notify('内容已保存，当前状态已变化，请返回我的文章查看。', 'info')
    } catch {
      if (active()) {
        setSubmissionUnknown(true)
        notify('内容已保存，提交结果暂无法确认；请先核对审核状态，勿重复提交。')
      }
    }
  }

  async function checkSubmission() {
    if (busyRef.current) return
    const version = versionRef.current
    const active = () => version === versionRef.current && scope === scopeRef.current
    busyRef.current = true; setBusy('check')
    await readSubmissionStatus(active)
    if (active()) { busyRef.current = false; setBusy('') }
  }

  function cancelLeave() {
    setPendingLogout(null)
    if (blocker.state === 'blocked') blocker.reset()
  }

  function confirmLeave() {
    if (busyRef.current) return
    allowLeaveRef.current = true
    if (pendingLogout) {
      const logout = pendingLogout.logout
      setPendingLogout(null)
      logout()
    } else if (blocker.state === 'blocked') blocker.proceed()
  }

  return (
    <div className="composer-layout">
      <PageHeader title={article?.contentType === 'QUESTION' ? '继续编辑问题' : '继续编辑文章'} description="修改标题、正文和封面，保存后可继续编辑，准备好再提交审核。" />
      {loading && <LoadingSpinner text="正在加载你的内容…" />}
      {loadError && <div className="route-state"><MessageBanner message={loadError} type="error" /><button className="ghost-button" onClick={() => setRetry((value) => value + 1)}>重试加载</button></div>}
      {article && !canEdit && <p role="alert">只能编辑自己的草稿或被驳回内容。当前内容不可编辑。</p>}
      {canEdit && form && <>
        <form ref={formRef} className="composer-layout" onSubmit={(event) => { event.preventDefault(); persist(true) }}>
          <ContentEditorFields form={form} readOnlyMetadata disabled={Boolean(busy) || submissionUnknown}
            onChange={(event) => { const { name, value } = event.target; setForm((current) => ({ ...current, [name]: value })) }}
            coverFile={coverFile} coverInputKey={coverInputKey} onSelectCover={setCoverFile}
            coverUploading={busy === 'upload'} onUploadCover={uploadCover} onClearCoverFile={clearCoverSelection}
            onRemoveCover={() => { clearCoverSelection(); setForm((current) => ({ ...current, coverImageId: null })) }} />
          <div className="composer-actions">
            <button className="ghost-button icon-text-button" type="button" disabled={Boolean(busy) || submissionUnknown} onClick={() => persist(false)}><Save size={17} aria-hidden="true" />{busy === 'save' ? '保存中…' : '保存修改'}</button>
            <button className="ghost-button icon-text-button" type="button" onClick={() => setPreview((value) => !value)} aria-expanded={preview}><Eye size={17} aria-hidden="true" />{preview ? '收起预览' : '预览内容'}</button>
            <button className="primary-button icon-text-button" type="submit" disabled={Boolean(busy) || submissionUnknown}><Send size={17} aria-hidden="true" />{busy === 'submit' ? '提交中…' : '保存并提交审核'}</button>
          </div>
        </form>
        {preview && <ContentPreview form={form} />}
      </>}
      <MessageBanner message={message} type={messageType} />
      {submissionUnknown && <button className="ghost-button" type="button" disabled={Boolean(busy)} onClick={checkSubmission}>{busy === 'check' ? '核对中…' : '核对审核状态'}</button>}
      <Link className="text-button" to="/me/articles">返回我的文章</Link>
      {(pendingLogout || blocker.state === 'blocked') && <ConfirmDialog title={busy ? '操作正在进行' : '离开编辑页？'}
        message={busy ? '正在上传、保存或提交，请等待操作结束后再离开。' : dirty ? '还有未保存的修改，离开后这些修改将丢失。' : '确认离开当前编辑页？'}
        confirmText="离开" confirmDisabled={Boolean(busy)} onCancel={cancelLeave} onConfirm={confirmLeave} />}
    </div>
  )
}

export default EditArticle
