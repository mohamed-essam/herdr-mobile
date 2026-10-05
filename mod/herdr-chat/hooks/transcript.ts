import { RUN_ID } from './agents'
import { normalizeBlocks, type ChatEvent, type Normalized } from './normalize'

type Row = {
  type?: unknown
  uuid?: unknown
  timestamp?: unknown
  isMeta?: unknown
  isSidechain?: unknown
  message?: { role?: unknown; content?: unknown }
  toolUseResult?: unknown
}

// Chat history built line by line from the session's transcript file (JSON
// lines), filtered as live rows are: main-thread `user`/`assistant` rows with
// a role and a uuid, no meta rows (skill bodies, image captions, "Tool loaded.", peer-
// agent messages). Each event carries the row's real uuid and its timestamp.
// Lines that aren't JSON are skipped. Images go beside the events, by id:
// only the newest HISTORY_IMAGES (the companion keeps 30 per pane), held as
// a rolling window while reading, in history order; an image with empty data
// (stripped from an over-long line) is never held. Every reference stays in
// its event (an image not held is answered `missing`).
//
// An agent's own transcript (`sidechain: true`) is all sidechain rows: the
// filter is skipped for it. Workflow launches are collected as they pass, in
// `workflowRuns` (runId → the Workflow call's tool use id): a tool_result row
// whose `toolUseResult` is a `local_workflow` with a plain run id.
export const HISTORY_IMAGES = 30

export type TranscriptHistory = Normalized & { sawMessageRow: boolean; workflowRuns: Map<string, string> }
export type TranscriptOpts = { sidechain?: boolean }

export function newTranscriptHistory(): TranscriptHistory {
  return { events: [], images: {}, sawMessageRow: false, workflowRuns: new Map() }
}

// The run a Workflow tool result launched, and the call it answers.
function workflowRun(row: Row): [string, string] | undefined {
  const r = row.toolUseResult as { taskType?: unknown; runId?: unknown } | undefined
  if (!r || typeof r !== 'object' || r.taskType !== 'local_workflow') return undefined
  if (typeof r.runId !== 'string' || !RUN_ID.test(r.runId)) return undefined
  const content = row.message?.content
  if (!Array.isArray(content)) return undefined
  for (const b of content as { type?: unknown; tool_use_id?: unknown }[]) {
    if (b && b.type === 'tool_result' && typeof b.tool_use_id === 'string' && b.tool_use_id) return [r.runId, b.tool_use_id]
  }
  return undefined
}

export function addTranscriptLine(h: TranscriptHistory, line: string, opts?: TranscriptOpts): void {
  if (!line.trim()) return
  let row: Row
  try {
    row = JSON.parse(line) as Row
  } catch {
    return
  }
  if (!row || typeof row !== 'object' || (row.type !== 'user' && row.type !== 'assistant')) return
  h.sawMessageRow = true
  if (row.isMeta === true || (row.isSidechain === true && !opts?.sidechain)) return
  const role = row.message?.role
  if (role !== 'user' && role !== 'assistant') return
  if (typeof row.uuid !== 'string') return
  const run = workflowRun(row)
  if (run) h.workflowRuns.set(run[0], run[1])
  const ms = typeof row.timestamp === 'string' ? Date.parse(row.timestamp) : NaN
  const n = normalizeBlocks(role, row.message?.content, row.uuid, Number.isNaN(ms) ? undefined : ms)
  for (const ev of n.events) h.events.push(ev)
  for (const [id, img] of Object.entries(n.images)) {
    if (!img.data) continue
    delete h.images[id] // a repeated id moves to the newest end
    h.images[id] = img
  }
  const ids = Object.keys(h.images) // insertion order (ids are never integer-like)
  for (let i = 0; i < ids.length - HISTORY_IMAGES; i++) delete h.images[ids[i]!]
}

// Null when the file had no user/assistant row at all, so the caller can fall
// back to the api-form history.
export type FinishedHistory = Normalized & { workflowRuns: Map<string, string> }
export function finishTranscriptHistory(h: TranscriptHistory): FinishedHistory | null {
  return h.sawMessageRow ? { events: h.events, images: h.images, workflowRuns: h.workflowRuns } : null
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
// single line longer than the limit (`overlong`): the caller re-reads it
// another way or skips it (advance 1).
export function splitPiece(text: string, cut: boolean): { lines: string[]; advance: number; overlong?: true } {
  if (!cut) return { lines: text.split('\n'), advance: 0 }
  const end = text.lastIndexOf('\n')
  if (end < 0) return { lines: [], advance: 1, overlong: true }
  const lines = text.slice(0, end).split('\n')
  return { lines, advance: lines.length }
}
