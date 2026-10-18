/**
 * Shared basic Markdown subset for AI messages and explicitly opted-in user bodies.
 * Escape raw text/attributes, keep code literal, allow HTTP(S) and same-origin root links.
 * No raw HTML, inline images, embedded content, syntax highlighter or full CommonMark claim.
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

// Parse raw tokens before generating HTML: code stays literal and URLs are never formatted.
export function renderInline(text, depth = 0) {
  const source = String(text)
  if (depth > 8) return escapeHtml(source)
  const tokens = /`([^`\n]+)`|(!?\[([^\]\n]+)\]\(([^()\n]*(?:\([^()\n]*\)[^()\n]*)*)\))|\*\*([^*\n]+)\*\*|\*([^*\n]+)\*/g
  let result = ''
  let end = 0
  for (const match of source.matchAll(tokens)) {
    result += escapeHtml(source.slice(end, match.index))
    const [, code, link, label, url, strong, emphasis] = match
    if (code !== undefined) result += `<code>${escapeHtml(code)}</code>`
    else if (link !== undefined) {
      // Inline images stay literal: public images still use the existing reviewed cover workflow.
      if (link.startsWith('!')) result += escapeHtml(link)
      else {
        const safe = /^(?:https?:\/\/[^\s<>"'\\]+|\/(?!\/)[^\s<>"'\\]*)$/i.test(url)
        const visible = renderInline(label, depth + 1)
        result += safe ? `<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">${visible}</a>` : visible
      }
    } else if (strong !== undefined) result += `<strong>${renderInline(strong, depth + 1)}</strong>`
    else result += `<em>${renderInline(emphasis, depth + 1)}</em>`
    end = match.index + match[0].length
  }
  return result + escapeHtml(source.slice(end))
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
export function renderMarkdown(markdown, { headingOffset = 2 } = {}) {
  if (!markdown) {
    return ''
  }

  const lines = String(markdown).replace(/\r\n?/g, '\n').split('\n')
  const blocks = []
  let index = 0

  while (index < lines.length) {
    const line = lines[index]

    // Fence lengths must match; an unclosed block remains escaped code until the end.
    const fence = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/)
    if (fence) {
      const language = /^[a-zA-Z0-9_+-]{1,32}$/.test(fence[2].trim()) ? fence[2].trim() : ''
      const closing = new RegExp(`^ {0,3}${fence[1][0]}{${fence[1].length},}\\s*$`)
      const codeLines = []
      index += 1
      while (index < lines.length && !closing.test(lines[index])) {
        codeLines.push(lines[index])
        index += 1
      }
      if (index < lines.length) index += 1
      const langAttr = language ? ` class="language-${language}"` : ''
      blocks.push(`<pre tabindex="0" aria-label="代码块${language ? ` ${language}` : ''}"><code${langAttr}>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
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
      const level = Math.min(heading[1].length + headingOffset, 6) // AI从h3、用户正文从h2起
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
      while (index < lines.length && /^\s*([-*+]|\d+\.)\s+/.test(lines[index]) && /^\s*\d+\.\s+/.test(lines[index]) === ordered) {
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
      !/^ {0,3}(`{3,}|~{3,})/.test(lines[index]) &&
      !/^(#{1,6})\s+/.test(lines[index]) &&
      !/^\s*([-*+]|\d+\.)\s+/.test(lines[index]) &&
      !/^\s*>\s?/.test(lines[index])
    ) {
      // 段落中间若出现表格头，停止合并交给下一轮处理
      if (paragraph.length > 0 && looksLikeTableRow(lines[index]) && index + 1 < lines.length && isSeparatorRow(parseRow(lines[index + 1]))) {
        break
      }
      paragraph.push(lines[index])
      index += 1
    }
    blocks.push(`<p>${paragraph.map(renderInline).join('<br />')}</p>`)
  }

  return blocks.join('')
}
