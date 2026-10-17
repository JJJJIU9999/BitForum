import ArticleBody from './ArticleBody.jsx'

function ContentPreview({ form }) {
  return <section className="composer-preview" aria-label="内容预览">
    <h2>{form.title || '未填写标题'}</h2>
    <ArticleBody content={form.content || '未填写正文'} contentFormat={form.contentFormat} />
    <p className="field-hint">当前为{form.contentFormat === 'MARKDOWN' ? ' Markdown ' : '纯文本'}预览；内容与封面审核通过后才会公开。</p>
  </section>
}

export default ContentPreview
