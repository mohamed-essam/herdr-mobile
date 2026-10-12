import { describe, expect, test } from 'claude-code/testing'
import { toUsage } from '../hooks/usage'

describe('toUsage', () => {
  test('keeps context with a percent and only the 5h/7d windows', () => {
    expect(toUsage({
      context: { tokens: 122000, window: 200000, percent: 61 },
      rateLimits: [
        { kind: 'five_hour', percentUsed: 42.5, resetsAt: '2026-10-08T19:40:00Z' },
        { kind: 'seven_day', percentUsed: 18, resetsAt: '2026-10-15T14:00:00Z' },
        { kind: 'spend_limit', percentUsed: 3 },
      ],
    })).toEqual({
      context: { percent: 61, tokens: 122000, window: 200000 },
      limits: [
        { kind: 'five_hour', percentUsed: 42.5, resetsAt: '2026-10-08T19:40:00Z' },
        { kind: 'seven_day', percentUsed: 18, resetsAt: '2026-10-15T14:00:00Z' },
      ],
    })
  })

  test('no percent yet: no context', () => {
    expect(toUsage({ context: { window: 200000 }, rateLimits: [] })).toEqual({ limits: [] })
  })

  test('rounds the context figures (the companion takes integers)', () => {
    expect(toUsage({ context: { tokens: 122000.4, window: 200000, percent: 61.6 }, rateLimits: [] })).toEqual({
      context: { percent: 62, tokens: 122000, window: 200000 },
      limits: [],
    })
  })

  test('nothing to read: null', () => {
    expect(toUsage(undefined)).toBeNull()
    expect(toUsage(null)).toBeNull()
  })
})
