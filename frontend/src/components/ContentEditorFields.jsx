import { ImageUp, Trash2 } from 'lucide-react'
import PrivateImagePreview from './PrivateImagePreview.jsx'

// New posts and existing drafts share the same fields and private cover workflow.
function ContentEditorFields({ form, onChange, categories = [], readOnlyMetadata = false, disabled = false,
  coverFile, coverInputKey, onSelectCover, onUploadCover, onRemoveCover, onClearCoverFile, coverUploading }) {
  const isQuestion = form.contentType === 'QUESTION'
  return (
    <fieldset className="composer-grid composer-fields" disabled={disabled}>
      <section className="composer-card">
        {readOnlyMetadata ? <>
          <label>内容类型<input readOnly value={isQuestion ? '问题' : '文章'} /></label>
          <label>{isQuestion ? '所属话题' : '所属板块'}<input readOnly value={form.categoryName || `板块 #${form.categoryId}`} /></label>
          <p className="field-hint">已有内容的类型和所属板块保持不变。</p>
        </> : <>
          <label>内容类型<select name="contentType" onChange={onChange} value={form.contentType}><option value="ARTICLE">文章</option><option value="QUESTION">问题</option></select></label>
          <label>{isQuestion ? '选择话题' : '选择板块'}<select name="categoryId" onChange={onChange} value={form.categoryId} required>{categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
        </>}
        <label>{isQuestion ? '问题标题' : '文章标题'}<input maxLength="50" name="title" onChange={onChange} placeholder={isQuestion ? '用一句话描述你遇到的具体问题' : '用一句清楚的话概括观点'} type="text" value={form.title} required /></label>
        <label>正文格式<select name="contentFormat" onChange={onChange} value={form.contentFormat || 'PLAIN_TEXT'}><option value="MARKDOWN">Markdown</option><option value="PLAIN_TEXT">纯文本</option></select></label>
        <p className="field-hint">{form.contentFormat === 'MARKDOWN' ? '支持标题、列表、引用、行内代码和围栏代码块；不支持内嵌图片或 HTML。' : '按原文保留换行和符号。切换格式只影响排版，不改写正文。'}</p>
        <label>{isQuestion ? '问题描述' : '正文内容'}<textarea name="content" onChange={onChange} placeholder={isQuestion ? '背景是什么？尝试过什么？实际结果与期望有什么差异？' : '写下背景、过程、结论，或你想讨论的问题…'} value={form.content} required /></label>
        {form.contentFormat === 'MARKDOWN' && <details className="markdown-help"><summary>Markdown 写法示例</summary><pre>{'# 小标题\n\n- 一个要点\n\n> 一句引用\n\n`行内代码`\n\n```java\nint count = 1;\n```'}</pre></details>}
        <p className="field-hint">{form.content.length} 字 · 提交后会进入管理员审核。</p>
      </section>
      <aside className="cover-panel">
        <p className="eyebrow">{isQuestion ? '问题封面（可选）' : '文章封面'}</p>
        <h2>{isQuestion ? '补充问题的背景' : '给文章一个入口'}</h2><p>支持 JPG、PNG、WebP，最大 5MB。</p>
        {form.coverImageId ? <PrivateImagePreview imageId={form.coverImageId} alt="私有封面预览" className="cover-preview" /> : <div className="cover-placeholder"><ImageUp size={30} aria-hidden="true" /><span>还没有选择封面</span></div>}
        <label className="file-picker">选择图片<input key={coverInputKey} accept="image/jpeg,image/png,image/webp" type="file" onChange={(event) => onSelectCover(event.target.files?.[0] || null)} /></label>
        {coverFile && <p className="field-hint">已选择：{coverFile.name}，请上传后再保存。</p>}
        <div className="cover-actions">
          <button className="ghost-button" disabled={!coverFile || disabled} type="button" onClick={onUploadCover}>{coverUploading ? '上传中…' : '上传封面'}</button>
          {coverFile && <button className="text-button" type="button" onClick={onClearCoverFile}>取消选择</button>}
          {form.coverImageId && <button className="text-button" type="button" onClick={onRemoveCover}><Trash2 size={15} aria-hidden="true" />移除封面</button>}
        </div>
        {form.coverImageId && <p role="status" className="field-hint">封面资源 #{form.coverImageId}；审核通过后才会公开，私有预览仅你和管理员可见。</p>}
      </aside>
    </fieldset>
  )
}

export default ContentEditorFields
