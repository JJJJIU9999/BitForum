import { useMemo } from 'react'
import { renderMarkdown } from '../utils/markdown.js'

/**
 * 渲染模型返回的 Markdown 内容。
 *
 * 模型会输出表格、列表、加粗等 Markdown 语法，直接当纯文本显示会变成
 * 带 `|` 和 `**` 的源码，可读性差。这里统一渲染为 HTML。
 *
 * 安全性：转换函数内部先做 HTML 转义、并限制链接协议，
 * 因此这里的 dangerouslySetInnerHTML 注入的是受控内容。
 */
function MarkdownText({ content }) {
  // 同一条消息内容不变时不重复解析，避免流式刷新时反复渲染
  const html = useMemo(() => renderMarkdown(content), [content])

  return (
    <div
      className="md-content"
      // eslint-disable-next-line react/no-danger
      dangerouslySetInnerHTML={{ __html: html }}
    />
  )
}

export default MarkdownText
