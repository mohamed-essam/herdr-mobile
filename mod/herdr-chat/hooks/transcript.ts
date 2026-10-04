import { normalizeBlocks, type ChatEvent, type Normalized } from './normalize'

type Row = {
  type?: unknown
  uuid?: unknown
  timestamp?: unknown
  isMeta?: unknown
  isSidechain?: unknown
  message?: { role?: unknown; content?: unknown }
}

// Chat history built line by line from the session's transcript file (JSON
// lines), filtered as live rows are: main-thread `user`/`assistant` rows with
// a role and a uuid, no meta rows (skill bodies, image captions, "Tool loaded.", peer-
// agent messages). Each event carries the row's real uuid and its timestamp.
// Lines that aren't JSON are skipped. Images go beside the events, by id.
export type TranscriptHistory = Normalized & { sawMessageRow: boolean }

export function newTranscriptHistory(): TranscriptHistory {
  return { events: [], images: {}, sawMessageRow: false }
}

export function addTranscriptLine(h: TranscriptHistory, line: string): void {
  if (!line.trim()) return
  let row: Row
  try {
    row = JSON.parse(line) as Row
  } catch {
    return
  }
  if (!row || typeof row !== 'object' || (row.type !== 'user' && row.type !== 'assistant')) return
  h.sawMessageRow = true
  if (row.isMeta === true || row.isSidechain === true) return
  const role = row.message?.role
  if (role !== 'user' && role !== 'assistant') return
  if (typeof row.uuid !== 'string') return
  const ms = typeof row.timestamp === 'string' ? Date.parse(row.timestamp) : NaN
  const n = normalizeBlocks(role, row.message?.content, row.uuid, Number.isNaN(ms) ? undefined : ms)
  for (const ev of n.events) h.events.push(ev)
  Object.assign(h.images, n.images)
}

// Null when the file had no user/assistant row at all, so the caller can fall
// back to the api-form history.
export function finishTranscriptHistory(h: TranscriptHistory): Normalized | null {
  return h.sawMessageRow ? { events: h.events, images: h.images } : null
}

// The events of a whole transcript (null as above).
export function eventsFromTranscript(jsonl: string): ChatEvent[] | null {
  const h = newTranscriptHistory()
  for (const line of jsonl.split('\n')) addTranscriptLine(h, line)
  return finishTranscriptHistory(h)?.events ?? null
}

// One piece of the file read from a line onward (`tail -n +<line>`), whose
// output may have been cut at the read limit. A whole piece yields all its
// lines. A cut piece yields only its complete lines, as the cut may fall
// inside the last line or inside one of its characters; `advance` is how many
// lines the next read starts after. A cut piece without any newline is a
// single line longer than the limit: it is skipped (advance 1).
export function splitPiece(text: string, cut: boolean): { lines: string[]; advance: number } {
  if (!cut) return { lines: text.split('\n'), advance: 0 }
  const end = text.lastIndexOf('\n')
  if (end < 0) return { lines: [], advance: 1 }
  const lines = text.slice(0, end).split('\n')
  return { lines, advance: lines.length }
}
