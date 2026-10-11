import { describe, expect, it } from 'vitest'
import { escapeHtml, renderInline, renderMarkdown } from './markdown.js'

describe('escapeHtml', () => {
  it('应转义全部 HTML 特殊字符', () => {
    expect(escapeHtml('<script>alert(1)</script>')).toBe(
      '&lt;script&gt;alert(1)&lt;/script&gt;',
    )
    expect(escapeHtml('a & b "c" \'d\'')).toBe('a &amp; b &quot;c&quot; &#39;d&#39;')
  })
})

describe('renderInline', () => {
  it('应渲染加粗、斜体与行内代码', () => {
    expect(renderInline('**粗体**')).toBe('<strong>粗体</strong>')
    expect(renderInline('*斜体*')).toBe('<em>斜体</em>')
    expect(renderInline('`code`')).toBe('<code>code</code>')
  })

  it('应把链接渲染为带安全属性的锚点', () => {
    const html = renderInline('[站内](/articles/1)')
    expect(html).toContain('href="/articles/1"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('应拒绝 javascript: 等危险链接协议，只保留文字', () => {
    const html = renderInline('[点我](javascript:alert(1))')
    expect(html).not.toContain('javascript:')
    expect(html).not.toContain('<a')
    expect(html).toBe('点我')
  })

  it('应拒绝 data: 协议链接', () => {
    const html = renderInline('[x](data:text/html;base64,PHNjcmlwdD4=)')
    expect(html).not.toContain('data:')
    expect(html).not.toContain('<a')
  })

  it('应正确解析 URL 中含括号的链接', () => {
    const html = renderInline('[维基](https://zh.wikipedia.org/wiki/Java_(编程语言))')
    expect(html).toContain('href="https://zh.wikipedia.org/wiki/Java_(编程语言)"')
    // 末尾不应残留多余的右括号
    expect(html).not.toMatch(/\)<\/a>\(/)
    expect(html.endsWith('</a>')).toBe(true)
  })
})

describe('renderMarkdown 安全性', () => {
  it('应转义原始 HTML 标签，防止脚本注入', () => {
    const html = renderMarkdown('<script>alert("xss")</script>')
    expect(html).not.toContain('<script')
    expect(html).toContain('&lt;script&gt;')
  })

  it('应转义带事件处理器的标签', () => {
    const html = renderMarkdown('<img src=x onerror="alert(1)">')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('onerror="alert')
  })

  it('表格单元格中的 HTML 也应被转义', () => {
    const html = renderMarkdown('| a | b |\n| --- | --- |\n| <b>x</b> | y |')
    expect(html).not.toContain('<b>x</b>')
    expect(html).toContain('&lt;b&gt;x&lt;/b&gt;')
  })

  it('代码块中的 HTML 不应被执行', () => {
    const html = renderMarkdown('```html\n<script>alert(1)</script>\n```')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
  })
})

describe('renderMarkdown 结构渲染', () => {
  it('空内容应返回空字符串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
  })

  it('应把 Markdown 表格渲染为 table', () => {
    const html = renderMarkdown([
      '| 文章 | 作者 | 浏览 |',
      '| --- | --- | --- |',
      '| Redis 热点数据同步方案 | Alice | 206 |',
    ].join('\n'))

    expect(html).toContain('<table>')
    expect(html).toContain('<thead>')
    expect(html).toContain('<th>文章</th>')
    expect(html).toContain('<td>Redis 热点数据同步方案</td>')
    expect(html).toContain('md-table-wrap')
  })

  it('表格列数不一致时应补齐，避免错位', () => {
    const html = renderMarkdown('| a | b | c |\n| --- | --- | --- |\n| 1 | 2 |')
    const bodyRow = html.split('<tbody>')[1]
    expect((bodyRow.match(/<td>/g) || []).length).toBe(3)
  })

  it('应渲染无序列表与有序列表', () => {
    expect(renderMarkdown('- 一\n- 二')).toBe('<ul><li>一</li><li>二</li></ul>')
    expect(renderMarkdown('1. 一\n2. 二')).toBe('<ol><li>一</li><li>二</li></ol>')
  })

  it('应渲染标题并从 h3 起（适配窄面板）', () => {
    expect(renderMarkdown('# 一级标题')).toBe('<h3>一级标题</h3>')
    expect(renderMarkdown('## 二级标题')).toBe('<h4>二级标题</h4>')
  })

  it('应渲染引用与分隔线', () => {
    expect(renderMarkdown('> 提示')).toBe('<blockquote>提示</blockquote>')
    expect(renderMarkdown('---')).toBe('<hr />')
  })

  it('应渲染围栏代码块并识别语言', () => {
    const html = renderMarkdown('```java\nint a = 1;\n```')
    expect(html).toContain('<pre>')
    expect(html).toContain('class="language-java"')
    expect(html).toContain('int a = 1;')
  })

  it('普通段落中的换行应保留为 br', () => {
    expect(renderMarkdown('第一行\n第二行')).toBe('<p>第一行<br />第二行</p>')
  })

  it('应能处理模型实际的回答结构', () => {
    const answer = [
      '站内目前只搜到 **1 篇**与 Redis 相关的已发布文章：',
      '',
      '| 文章 | 作者 | 板块 |',
      '| --- | --- | --- |',
      '| 《Redis 热点数据同步方案》 | Alice | Java 后端 |',
      '',
      '需要注意：',
      '- 只包含**已发布**文章',
      '- 可以换关键词再搜',
    ].join('\n')

    const html = renderMarkdown(answer)
    expect(html).toContain('<strong>1 篇</strong>')
    expect(html).toContain('<table>')
    expect(html).toContain('<ul>')
    expect(html).toContain('<li>只包含<strong>已发布</strong>文章</li>')
  })
})
