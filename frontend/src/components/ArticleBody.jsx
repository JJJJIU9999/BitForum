import { useMemo } from 'react'
import { renderMarkdown } from '../utils/markdown.js'

// Missing/unknown formats stay literal. Preview and reading always use the same renderer.
function ArticleBody({ content = '', contentFormat }) {
  const markdown = contentFormat === 'MARKDOWN'
  const html = useMemo(() => markdown ? renderMarkdown(content, { headingOffset: 1 }) : '', [content, markdown])
  if (!markdown) return <p className="article-body">{content}</p>
  return <div className="article-body article-markdown" dangerouslySetInnerHTML={{ __html: html }} />
}

export default ArticleBody
