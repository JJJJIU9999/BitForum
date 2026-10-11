import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import ConfirmDialog from './ConfirmDialog.jsx'

describe('ConfirmDialog', () => {
  it('closes with Escape and exposes an accessible dialog', () => {
    const onCancel = vi.fn()
    render(<ConfirmDialog title="Confirm action" message="This action needs confirmation." onCancel={onCancel} onConfirm={vi.fn()} confirmText="Confirm" />)

    expect(screen.getByRole('dialog', { name: 'Confirm action' })).toBeTruthy()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)
  })
})
