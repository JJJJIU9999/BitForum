import { afterEach, describe, expect, it } from 'vitest'
import { clearAuth, getCurrentUser, getToken, saveAuth } from './request.js'

describe('authentication storage', () => {
  afterEach(() => clearAuth())

  it('stores and restores the role returned from login', () => {
    saveAuth({ token: 'test-token', userId: 7, username: 'moderator', role: 'ADMIN' })

    expect(getToken()).toBe('test-token')
    expect(getCurrentUser()).toEqual({ userId: 7, username: 'moderator', role: 'ADMIN' })
  })

  it('clears malformed saved user data instead of throwing', () => {
    localStorage.setItem('bit_forum_token', 'test-token')
    localStorage.setItem('bit_forum_user', '{not-json')

    expect(getCurrentUser()).toBeNull()
    expect(getToken()).toBeNull()
  })
})
