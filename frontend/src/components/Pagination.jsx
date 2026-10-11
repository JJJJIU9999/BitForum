function Pagination({ pageInfo, onPageChange, loading }) {
  const { current, pages } = pageInfo || {}

  return (
    <nav className="pager" aria-label="分页导航">
      <button
        className="ghost-button"
        disabled={(current ?? 1) <= 1 || loading}
        type="button"
        onClick={() => onPageChange((current ?? 2) - 1)}
      >
        上一页
      </button>
      <span>
        第 {current ?? 1} / {pages || 1} 页
      </span>
      <button
        className="ghost-button"
        disabled={(current ?? 1) >= (pages || 1) || loading}
        type="button"
        onClick={() => onPageChange((current ?? 0) + 1)}
      >
        下一页
      </button>
    </nav>
  )
}

export default Pagination
