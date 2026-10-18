import { describe, expect, it } from 'vitest'
import { displayTime } from './displayTime.js'
import { formatDate } from '../pages/admin/adminHelpers.js'

describe('UTC API timestamps displayed in Asia/Shanghai', () => {
  it.each([
    ['2026-10-13T12:51:17Z', '2026-10-13 20:51'],
    ['2026-10-13T12:51:17', '2026-10-13 20:51'],
    ['2026-10-13 12:51:17.123456', '2026-10-13 20:51'],
    ['2026-10-13T20:51:17+08:00', '2026-10-13 20:51'],
    ['2026-10-13T15:59:59Z', '2026-10-13 23:59'],
    ['2026-10-13T16:00:00Z', '2026-10-14 00:00'],
    ['2026-01-31T16:00:00Z', '2026-02-01 00:00'],
    ['2026-12-31T16:00:00Z', '2027-01-01 00:00'],
    ['2024-02-29T16:00:00Z', '2024-03-01 00:00'],
  ])('formats %s without relying on the browser timezone', (value, expected) => {
    expect(displayTime(value)).toBe(expected)
    expect(formatDate(value)).toBe(expected)
  })
  it.each([null, '', 'invalid', '2026-02-30T02:00:00Z', '2026-10-09T25:00:00',
    '2026-10-09T01:00:60Z', '2026-10-09T01:00:00+08:99'])('rejects invalid %s', (value) => {
    expect(displayTime(value)).toBe('时间未知')
  })
  it('retains the admin missing-value placeholder', () => expect(formatDate(null)).toBe('-'))
})
