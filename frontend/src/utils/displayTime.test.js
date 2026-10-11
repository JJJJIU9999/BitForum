import { describe, expect, it } from 'vitest'
import { displayTime } from './displayTime.js'
describe('服务端时间展示', () => {
  it('preserves the supplied local date and minute without timezone conversion', () => {
    expect(displayTime('2026-10-09T01:25:04.123')).toBe('2026-10-09 01:25')
  })
  it('rejects missing and impossible dates', () => {
    for (const value of [null, 'invalid', '2026-02-30T02:00:00', '2026-10-09T25:00:00']) expect(displayTime(value)).toBe('时间未知')
  })
})
