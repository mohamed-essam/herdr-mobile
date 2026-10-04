import { normalizeBlocks, type ChatEvent } from './normalize'

type Row = {
  type?: unknown
  uuid?: unknown
  timestamp?: unknown
  isMeta?: unknown
  isSidechain?: unknown
  message?: { role?: unknown; content?: unknown }
}

// Chat history from the session's transcript file (JSON lines), filtered as
// live rows are: main-thread `user`/`assistant` rows with a role, no meta rows
// (skill bodies, image captions, "Tool loaded.", peer-agent messages). Each
// event carries the row's real uuid and its timestamp. Lines that aren't JSON
// are skipped. Null when the file has no user/assistant row at all, so the
// caller can fall back to the api-form history.
export function eventsFromTranscript(jsonl: string): ChatEvent[] | null {
  const out: ChatEvent[] = []
  let sawMessageRow = false
  for (const line of jsonl.split('\n')) {
    if (!line.trim()) continue
    let row: Row
    try {
      row = JSON.parse(line) as Row
    } catch {
      continue
    }
    if (!row || typeof row !== 'object' || (row.type !== 'user' && row.type !== 'assistant')) continue
    sawMessageRow = true
    if (row.isMeta === true || row.isSidechain === true) continue
    const role = row.message?.role
    if (role !== 'user' && role !== 'assistant') continue
    const ms = typeof row.timestamp === 'string' ? Date.parse(row.timestamp) : NaN
    out.push(...normalizeBlocks(role, row.message?.content, String(row.uuid), Number.isNaN(ms) ? undefined : ms))
  }
  return sawMessageRow ? out : null
}
