function EmptyState({ message = '暂无数据。' }) {
  return <p className="form-message empty-state">{message}</p>
}

export default EmptyState
