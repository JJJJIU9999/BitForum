import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import ArticleBody from './ArticleBody.jsx'
import ContentPreview from './ContentPreview.jsx'

afterEach(cleanup)
const raw = '# 标题\n\n- 条目\n\n> 引用\n\n```java\nString s = "<script>";\n```'
describe('用户正文兼容与安全', () => {
  it.each([undefined, null, 'PLAIN_TEXT', 'UNKNOWN'])('treats %s as literal text', (contentFormat) => {
    const { container } = render(<ArticleBody content={'# 原文\n**代码** <img src=x onerror=alert(1)>'} contentFormat={contentFormat} />)
    expect(container.textContent).toBe('# 原文\n**代码** <img src=x onerror=alert(1)>')
    expect(container.querySelector('img, strong, h2')).toBeNull()
  })
  it('shares exact rendered body between preview and final reading', () => {
    const view = render(<><ContentPreview form={{ title: '预览', content: raw, contentFormat: 'MARKDOWN' }} /><ArticleBody content={raw} contentFormat="MARKDOWN" /></>)
    const bodies = view.container.querySelectorAll('.article-markdown')
    expect(bodies[0].innerHTML).toBe(bodies[1].innerHTML)
    expect(bodies[0].querySelector('h2').textContent).toBe('标题')
    expect(bodies[0].querySelector('pre').tabIndex).toBe(0)
    expect(bodies[0].querySelector('script')).toBeNull()
  })
  it('never creates executable elements, event attributes, or unsafe URLs', () => {
    const content = '<img src=x onerror=alert(1)>\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1)) [x](data:text/html,evil) [x](//evil.test)\n\n![x](https://evil.test/x.png)'
    const { container } = render(<ArticleBody content={content} contentFormat="MARKDOWN" />)
    expect(container.querySelector('script,img,iframe,svg,a,[onerror]')).toBeNull()
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>')
  })
})
