import { useEffect, useState } from 'react'
import { Sparkles } from 'lucide-react'
import { getRecommendations } from '../api/articleApi.js'

/**
 * 文章详情页的「相关推荐」（M17）。
 *
 * 设计要点：
 * 1. **推荐是增强能力，不是页面的必要部分**：加载失败或没有候选时整块隐藏，
 *    绝不让读者看到报错或空白区块（与后端的降级约定对应）。
 * 2. **理由可能为空**：AI 不可用时后端只返回列表、不返回理由，
 *    这里如实显示"暂无推荐理由"，而不是把区块藏掉 —— 推荐文章本身仍然有价值。
 * 3. **排序由服务端决定**：前端只按返回顺序展示，不做二次排序或筛选。
 */
function RelatedRecommendations({ articleId, onOpen }) {
  const [items, setItems] = useState([])

  useEffect(() => {
    let cancelled = false

    async function load() {
      setItems([])
      try {
        const result = await getRecommendations(articleId, 5)
        if (!cancelled) {
          setItems(result.data || [])
        }
      } catch {
        // 静默失败：推荐不可用不影响阅读，页面不显示这一块
        if (!cancelled) {
          setItems([])
        }
      }
    }

    if (articleId) {
      load()
    }
    return () => {
      cancelled = true
    }
  }, [articleId])

  if (items.length === 0) {
    return null
  }

  return (
    <section className="related-section" aria-labelledby="related-title">
      <div className="sub-heading">
        <div>
          <p className="eyebrow">AI 推荐</p>
          <h2 id="related-title">
            <Sparkles size={19} aria-hidden="true" />
            相关推荐
          </h2>
        </div>
        <span>{items.length} 篇</span>
      </div>
      <ol className="related-list">
        {items.map((item) => (
          <li className="related-item" key={item.articleId}>
            <button
              className="related-title"
              type="button"
              onClick={() => onOpen?.(item.articleId)}
            >
              <span className="related-rank">{item.rank}</span>
              <span>{item.title}</span>
            </button>
            {item.categoryName && <span className="related-category">{item.categoryName}</span>}
            {item.reason
              ? <p className="related-reason">{item.reason}</p>
              : <p className="related-reason related-reason-muted">暂无推荐理由</p>}
          </li>
        ))}
      </ol>
    </section>
  )
}

export default RelatedRecommendations
