function MessageBanner({ message, type = 'info' }) {
  if (!message) return null

  const isError = type === 'error'
  const cls = isError ? 'form-message message-error' : 'form-message'

  return <p className={cls} role={isError ? 'alert' : 'status'} aria-live={isError ? 'assertive' : 'polite'}>{message}</p>
}

export default MessageBanner
