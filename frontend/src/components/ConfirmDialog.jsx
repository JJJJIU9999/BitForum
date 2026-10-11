import { useEffect, useId, useRef } from 'react'

function ConfirmDialog({ title, message, inputLabel, onConfirm, onCancel, confirmText = '确认', danger = false }) {
  const dialogRef = useRef(null)
  const previousFocusRef = useRef(null)
  const titleId = useId()
  const messageId = useId()

  useEffect(() => {
    previousFocusRef.current = document.activeElement
    const firstFocusable = dialogRef.current?.querySelector('input, button')
    firstFocusable?.focus()

    function handleKeyDown(event) {
      if (event.key === 'Escape') {
        onCancel()
        return
      }
      if (event.key !== 'Tab') return

      const focusable = [...dialogRef.current.querySelectorAll('button:not([disabled]), input:not([disabled])')]
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      previousFocusRef.current?.focus?.()
    }
  }, [onCancel])

  function handleSubmit(event) {
    event.preventDefault()
    const input = event.currentTarget.elements.namedItem('dialog-input')
    const value = input?.value?.trim()
    if (inputLabel && !value) {
      input?.focus()
      return
    }
    onConfirm(value)
  }

  return (
    <div className="dialog-overlay" role="presentation" onMouseDown={onCancel}>
      <section
        ref={dialogRef}
        aria-describedby={messageId}
        aria-labelledby={titleId}
        aria-modal="true"
        className="dialog-box"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <h2 id={titleId}>{title}</h2>
        <p id={messageId}>{message}</p>
        <form onSubmit={handleSubmit}>
          {inputLabel && (
            <label className="dialog-field">
              <span>{inputLabel}</span>
              <input autoFocus name="dialog-input" placeholder={`请输入${inputLabel}`} required />
            </label>
          )}
          <div className="dialog-actions">
            <button className="ghost-button" type="button" onClick={onCancel}>取消</button>
            <button className={danger ? 'danger-button' : 'primary-button'} type="submit">{confirmText}</button>
          </div>
        </form>
      </section>
    </div>
  )
}

export default ConfirmDialog
