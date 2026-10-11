import { useEffect, useState } from 'react'
import { Search, TrendingUp, X } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { getHotArticles, pageArticles, searchArticles } from '../api/articleApi.js'
import { listCategories } from '../api/categoryApi.js'
import PageHeader from '../components/PageHeader.jsx'
import ArticleCard from '../components/ArticleCard.jsx'
import Pagination from '../components/Pagination.jsx'
import EmptyState from '../components/EmptyState.jsx'
import LoadingSpinner from '../components/LoadingSpinner.jsx'
import MessageBanner from '../components/MessageBanner.jsx'

function ArticleList({ contentType }) {
  const isQuestionList = contentType === 'QUESTION'
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [articles, setArticles] = useState([])
  const [hotArticles, setHotArticles] = useState([])
  const [categories, setCategories] = useState([])
  const [keyword, setKeyword] = useState(searchParams.get('q') || '')
  const [pageInfo, setPageInfo] = useState({ current: 1, pages: 1, total: 0 })
  const [message, setMessage] = useState('')
  const [categoryError, setCategoryError] = useState('')
  const [hotError, setHotError] = useState('')
  const [hotLoading, setHotLoading] = useState(false)
  const [categoryRetry, setCategoryRetry] = useState(0)
  const [hotRetry, setHotRetry] = useState(0)
  const [listRetry, setListRetry] = useState(0)
  const [loading, setLoading] = useState(false)

  const selectedCategoryId = searchParams.get('categoryId') || ''
  const submittedKeyword = searchParams.get('q') || ''
  const pageNum = Math.max(1, Number(searchParams.get('page')) || 1)

  useEffect(() => {
    let active = true
    setCategoryError('')
    listCategories().then((result) => { if (active) setCategories(result.data || []) })
      .catch((error) => { if (active) { setCategoryError(error.message); setCategories([]) } })
    return () => { active = false }
  }, [categoryRetry])

  useEffect(() => {
    let active = true
    setHotLoading(true); setHotError(''); setHotArticles([])
    getHotArticles().then((result) => { if (active) setHotArticles(result.data || []) })
      .catch((error) => { if (active) setHotError(error.message) })
      .finally(() => { if (active) setHotLoading(false) })
    return () => { active = false }
  }, [hotRetry])

  useEffect(() => {
    setKeyword(submittedKeyword)
  }, [submittedKeyword])

  useEffect(() => {
    let active = true
    async function loadArticles() {
      setLoading(true)
      setMessage(''); setArticles([]); setPageInfo({ current: pageNum, pages: 1, total: 0 })
      try {
        const params = { pageNum, pageSize: 8 }
        if (contentType) params.contentType = contentType
        if (selectedCategoryId) params.categoryId = selectedCategoryId
        if (submittedKeyword) params.keyword = submittedKeyword

        const pageResult = await (submittedKeyword ? searchArticles(params) : pageArticles(params))
        if (!active) return
        setArticles(pageResult.data.records || [])
        setPageInfo({
          current: pageResult.data.current,
          pages: pageResult.data.pages,
          total: pageResult.data.total,
        })
      } catch (error) {
        if (active) setMessage(error.message)
      } finally {
        if (active) setLoading(false)
      }
    }

    loadArticles()
    return () => { active = false }
  }, [pageNum, selectedCategoryId, submittedKeyword, contentType, listRetry])

  function updateQuery(changes) {
    const next = new URLSearchParams(searchParams)
    Object.entries(changes).forEach(([key, value]) => {
      if (value) next.set(key, String(value))
      else next.delete(key)
    })
    setSearchParams(next)
  }

  function handleSearch(event) {
    event.preventDefault()
    updateQuery({ q: keyword.trim(), page: 1 })
  }

  function clearSearch() {
    setKeyword('')
    updateQuery({ q: '', categoryId: '', page: '' })
  }

  return (
    <div className="community-home">
      <section className="community-masthead">
        <p className="eyebrow">{isQuestionList ? '问答与话题' : '社区精选'}</p>
        <h1>{isQuestionList ? '把问题说清楚，让经验成为答案。' : '让每一次讨论，都留下可回看的观点。'}</h1>
        <p>{isQuestionList ? '按话题寻找问题，分享你的解法；提问者可采纳有帮助的回答。' : '从技术实践到日常思考，在 Bit Forum 找到值得继续聊下去的内容。'}</p>
        {isQuestionList && <Link className="primary-button inline-button" to="/publish?type=QUESTION">提出问题</Link>}
      </section>

      <div className="topic-shortcuts"><Link to="/topics">浏览话题</Link><Link to="/following">关注动态</Link></div>
      <div className="community-grid">
        <section className="article-feed" aria-label={isQuestionList ? '问题列表' : '文章列表'}>
          <PageHeader level={2} title={isQuestionList ? '最新问题' : '最新内容'} description={isQuestionList ? '选择话题，找到你能回答的问题。' : '按板块筛选，或直接搜索标题和正文。'} badge={`${pageInfo.total} ${isQuestionList ? '个' : '条'}`} />
          <form className="search-filter editorial-toolbar" onSubmit={handleSearch}>
            <label>
              <span>搜索内容</span>
              <div className="input-with-icon"><Search size={17} aria-hidden="true" /><input onChange={(event) => setKeyword(event.target.value)} placeholder="搜索标题或正文" value={keyword} /></div>
            </label>
            <label>
              <span>{isQuestionList ? '浏览话题' : '浏览板块'}</span>
              <select value={selectedCategoryId} onChange={(event) => updateQuery({ categoryId: event.target.value, page: 1 })}>
                <option value="">{isQuestionList ? '全部话题' : '全部板块'}</option>
                {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
              </select>
            </label>
            <button className="primary-button" type="submit">搜索</button>
            {(submittedKeyword || selectedCategoryId) && <button className="ghost-button icon-text-button" type="button" onClick={clearSearch}><X size={16} aria-hidden="true" />清空</button>}
          </form>

          <MessageBanner message={categoryError ? `话题加载失败：${categoryError}` : ''} type="error" />
          {categoryError && <button className="ghost-button" type="button" onClick={() => setCategoryRetry((v) => v + 1)}>重试话题</button>}
          {loading && <LoadingSpinner text={isQuestionList ? '正在整理最新问题...' : '正在整理最新文章...'} />}
          <MessageBanner message={message} type="error" />
          {message && <button className="ghost-button" type="button" onClick={() => setListRetry((v) => v + 1)}>重试列表</button>}
          <div className="article-list">
            {articles.map((article) => <ArticleCard key={article.id} article={article} onOpen={(id) => navigate(`/articles/${id}`)} />)}
            {!loading && !message && articles.length === 0 && <EmptyState message={isQuestionList ? '还没有符合条件的问题。试试其他话题，或提出你的问题。' : '还没有符合条件的文章，换个关键词试试。'} />}
          </div>
          {pageInfo.total > 0 && <Pagination pageInfo={pageInfo} onPageChange={(page) => updateQuery({ page })} loading={loading} />}
        </section>

        <aside className="community-sidebar">
          <section className="hot-section">
            <div className="sub-heading"><div><p className="eyebrow">正在发生</p><h2><TrendingUp size={18} aria-hidden="true" />热门讨论</h2></div></div>
            <div className="hot-list">
              {hotLoading && <LoadingSpinner text="正在整理热门讨论…" />}
              <MessageBanner message={hotError} type="error" />
              {hotError && <button className="ghost-button" type="button" onClick={() => setHotRetry((v) => v + 1)}>重试热榜</button>}
              {!hotLoading && !hotError && hotArticles.length === 0 ? <p>暂时还没有热榜数据。</p> : hotArticles.map((item, index) => (
                <button className="hot-item" key={item.article.id} type="button" onClick={() => navigate(`/articles/${item.article.id}`)}>
                  <span>{String(index + 1).padStart(2, '0')}</span><strong>{item.article.title}</strong><small>热度 {item.hotScore}</small>
                </button>
              ))}
            </div>
          </section>
          <section className="category-rail">
            <p className="eyebrow">{isQuestionList ? '找到同路人' : '按兴趣阅读'}</p><h2>{isQuestionList ? '问题话题' : '热门板块'}</h2>
            <div>{categories.map((category) => <button key={category.id} className={String(category.id) === selectedCategoryId ? 'active' : ''} type="button" onClick={() => updateQuery({ categoryId: category.id, page: 1 })}>{category.name}</button>)}</div>
            <Link className="topic-link" to="/topics">查看话题详情</Link>
          </section>
        </aside>
      </div>
    </div>
  )
}

export default ArticleList
