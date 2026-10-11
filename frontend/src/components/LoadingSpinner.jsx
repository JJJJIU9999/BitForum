function LoadingSpinner({ text = '加载中...' }) {
  return <p className="loading-spinner" role="status" aria-live="polite">{text}</p>
}

export default LoadingSpinner
