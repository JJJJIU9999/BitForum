/**
 * 轻量 Markdown 渲染（M14 补强）。
 *
 * 为什么自己实现而不引入 react-markdown / marked：
 * 项目约束明确要求不引入复杂 UI 组件库；这里是可控的纯函数逻辑，
 * 可测试、零依赖，也不会因上游版本变动影响构建。
 *
 * 安全要求（重要）：渲染结果会通过 dangerouslySetInnerHTML 注入，因此
 * 1. 先做 HTML 转义，任何 < > & 都变成实体，模型输出的标签不会被执行；
 * 2. 链接只允许 http/https 协议，阻断 javascript: 等危险 scheme；
 * 3. 表格单元格与列表项在拼入 HTML 前也经过转义。
 *
 * 支持范围（覆盖大模型回答里的常见写法）：
 * 围栏代码块、表格、标题、有序/无序列表、引用、分隔线，
 * 以及行内的粗体、斜体、行内代码、链接。
 */

/** 转义 HTML 特殊字符，这是所有后续拼接的安全前提 */
export function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 行内语法：粗体、斜体、行内代码、链接 */
export function renderInline(text) {
  let result = escapeHtml(text)

  // 行内代码优先处理，避免其中的符号被当成强调语法
  result = result.replace(/`([^`]+)`/g, '<code>$1</code>')
  result = result.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  result = result.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')

  // 链接：([文字](目标))。目标内部可含成对括号，例如 wiki 链接或 javascript:alert(1)。
  // 若只用 [^)]+ 匹配，遇到含括号的 URL 会在第一个 ) 处提前截断并残留字符，这里做完整配对。
  result = result.replace(/\[([^\]]+)\]\(([^()]*(?:\([^()]*\)[^()]*)*)\)/g, (match, label, url) => {
    const safe = /^(https?:\/\/|\/)/i.test(url) ? url : null
    if (!safe) {
      // 非白名单协议：丢弃链接，只保留可见文字，避免 javascript: 之类的可点击危险链接
      return label
    }
    return `<a href="${safe}" target="_blank" rel="noopener noreferrer">${label}</a>`
  })

  return result
}

/** 判断表格的分隔行，如 |---|---| */
function isSeparatorRow(cells) {
  return cells.length > 0 && cells.every((cell) => /^:?-{2,}:?$/.test(cell.replace(/\s/g, '')))
}

function looksLikeTableRow(line) {
  return line.includes('|') && line.trim().length > 0
}

function parseRow(line) {
  return line
    .replace(/^\s*\|/, '')
    .replace(/\|\s*$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

function renderTable(headerCells, bodyRows) {
  const head = headerCells.map((cell) => `<th>${renderInline(cell)}</th>`).join('')
  const body = bodyRows
    .map((row) => `<tr>${row.map((cell) => `<td>${renderInline(cell)}</td>`).join('')}</tr>`)
    .join('')
  // 窄容器（聊天面板只有 400px）里表格可能超出，用容器横向滚动而不是压缩到不可读
  return `<div class="md-table-wrap"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`
}

/**
 * 把 Markdown 文本渲染为安全 HTML 字符串。
 *
 * @param {string} markdown 模型返回的原始文本
 * @returns {string} 可直接注入的 HTML
 */
export function renderMarkdown(markdown) {
  if (!markdown) {
    return ''
  }

  const lines = String(markdown).replace(/\r\n/g, '\n').split('\n')
  const blocks = []
  let index = 0

  while (index < lines.length) {
    const line = lines[index]

    // 围栏代码块
    if (/^\s*```/.test(line)) {
      const language = line.replace(/^\s*```/, '').trim()
      const codeLines = []
      index += 1
      while (index < lines.length && !/^\s*```/.test(lines[index])) {
        codeLines.push(lines[index])
        index += 1
      }
      index += 1 // 跳过结束围栏
      const langAttr = language ? ` class="language-${escapeHtml(language)}"` : ''
      blocks.push(`<pre><code${langAttr}>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
      continue
    }

    // 空行
    if (!line.trim()) {
      index += 1
      continue
    }

    // 表格：当前行与下一行构成"表头 + 分隔行"
    if (looksLikeTableRow(line) && index + 1 < lines.length) {
      const headerCells = parseRow(line)
      const separatorCells = parseRow(lines[index + 1])
      if (isSeparatorRow(separatorCells)) {
        const columnCount = Math.max(headerCells.length, separatorCells.length)
        const bodyRows = []
        index += 2
        while (index < lines.length && looksLikeTableRow(lines[index])) {
          const cells = parseRow(lines[index])
          // 补齐或截断到表头列数，避免模型给出的列数不一致导致表格错位
          while (cells.length < columnCount) {
            cells.push('')
          }
          bodyRows.push(cells.slice(0, columnCount))
          index += 1
        }
        while (headerCells.length < columnCount) {
          headerCells.push('')
        }
        blocks.push(renderTable(headerCells, bodyRows))
        continue
      }
    }

    // 标题
    const heading = line.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      const level = Math.min(heading[1].length + 2, 6) // 面板内从 h3 起，避免标题过大
      blocks.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      index += 1
      continue
    }

    // 分隔线
    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) {
      blocks.push('<hr />')
      index += 1
      continue
    }

    // 引用
    if (/^\s*>\s?/.test(line)) {
      const quoted = []
      while (index < lines.length && /^\s*>\s?/.test(lines[index])) {
        quoted.push(lines[index].replace(/^\s*>\s?/, ''))
        index += 1
      }
      blocks.push(`<blockquote>${quoted.map(renderInline).join('<br />')}</blockquote>`)
      continue
    }

    // 列表
    if (/^\s*([-*+]|\d+\.)\s+/.test(line)) {
      const ordered = /^\s*\d+\.\s+/.test(line)
      const items = []
      while (index < lines.length && /^\s*([-*+]|\d+\.)\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*([-*+]|\d+\.)\s+/, ''))
        index += 1
      }
      const tag = ordered ? 'ol' : 'ul'
      blocks.push(`<${tag}>${items.map((item) => `<li>${renderInline(item)}</li>`).join('')}</${tag}>`)
      continue
    }

    // 普通段落：连续非空行合并
    const paragraph = []
    while (
      index < lines.length &&
      lines[index].trim() &&
      !/^\s*```/.test(lines[index]) &&
      !/^(#{1,6})\s+/.test(lines[index]) &&
      !/^\s*([-*+]|\d+\.)\s+/.test(lines[index]) &&
      !/^\s*>\s?/.test(lines[index])
    ) {
      // 段落中间若出现表格头，停止合并交给下一轮处理
      if (paragraph.length > 0 && looksLikeTableRow(lines[index])) {
        break
      }
      paragraph.push(lines[index])
      index += 1
    }
    blocks.push(`<p>${paragraph.map(renderInline).join('<br />')}</p>`)
  }

  return blocks.join('')
}
