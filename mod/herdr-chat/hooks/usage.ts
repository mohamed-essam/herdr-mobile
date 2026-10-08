// The status line's figures as the companion takes them (see the /sync
// `usage` field): the live context window's fill and the account's 5-hour
// and 7-day rate-limit windows. A gateway's spend_limit is not carried.
export type UsageWindow = { kind: 'five_hour' | 'seven_day'; percentUsed: number; resetsAt?: string }
export type Usage = {
  context?: { percent: number; tokens?: number; window: number }
  limits: UsageWindow[]
}

type Measured = {
  context?: { tokens?: number; window: number; percent?: number }
  rateLimits?: readonly { kind: string; percentUsed: number; resetsAt?: string }[]
} | null | undefined

// `$.session.usage()`'s answer or a `session.measure` event, as sent. Null
// when there is nothing to read (an engine without the op).
export function toUsage(u: Measured): Usage | null {
  if (!u) return null
  const out: Usage = { limits: [] }
  const c = u.context
  if (c && typeof c.percent === 'number') {
    out.context = { percent: c.percent, ...(typeof c.tokens === 'number' ? { tokens: c.tokens } : {}), window: c.window }
  }
  for (const r of u.rateLimits ?? []) {
    if (r.kind !== 'five_hour' && r.kind !== 'seven_day') continue
    out.limits.push({ kind: r.kind, percentUsed: r.percentUsed, ...(r.resetsAt ? { resetsAt: r.resetsAt } : {}) })
  }
  return out
}
