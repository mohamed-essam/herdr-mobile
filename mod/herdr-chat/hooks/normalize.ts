import { imageSize } from './imagesize'

// `ts`: epoch milliseconds, when known (optional on the wire). `images`: ids
// of images carried beside the events (the /sync body's `images` map);
// `imageSizes`: [width, height] in pixels of those whose header parsed, so the
// phone can reserve their space before the bytes arrive.
export type ImageSizes = Record<string, [number, number]>
// `agentId`: set on a subagent's or workflow agent's rows (see agents.ts).
export type ChatEvent =
  | { type: 'user_text'; uuid: string; text: string; images?: string[]; imageSizes?: ImageSizes; ts?: number; agentId?: string }
  | { type: 'assistant_text'; uuid: string; text: string; ts?: number; agentId?: string }
  | { type: 'tool_use'; uuid: string; toolUseId: string; tool: string; summary: string; ts?: number; agentId?: string }
  | { type: 'tool_result'; toolUseId: string; isError: boolean; preview: string; images?: string[]; imageSizes?: ImageSizes; ts?: number; agentId?: string }
  | { type: 'task_notice'; uuid: string; status: string; summary: string; ts?: number; agentId?: string }

export type ChatImage = { mediaType: string; data: string }
// Events plus the images they reference, by id.
export type Normalized = { events: ChatEvent[]; images: Record<string, ChatImage> }

export const MAX_TEXT = 64 * 1024
export const PREVIEW = 400
export const SUMMARY = 120
export const SNAPSHOT_LIMIT = 500
export const SNAPSHOT_BYTES = 4 * 1024 * 1024

const FORWARDED_DOORS = new Set(['prompt', 'response', 'tool-result', 'delivery'])

type Block = { type?: unknown; [k: string]: unknown }

export function cap(s: string): string {
  return s.length > MAX_TEXT ? s.slice(0, MAX_TEXT) + '…[truncated]' : s
}

function clip(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + '…' : s
}

export function summarize(tool: string, input: Record<string, unknown>): string {
  const str = (k: string) => (typeof input[k] === 'string' ? (input[k] as string) : undefined)
  let detail: string | undefined
  if (tool === 'Bash') detail = str('command')
  else if (tool === 'Edit' || tool === 'Write' || tool === 'Read') detail = str('file_path')
  else if (tool === 'NotebookEdit') detail = str('notebook_path')
  else detail = Object.values(input).find((v): v is string => typeof v === 'string')
  const one = (detail ?? '').replace(/\s+/g, ' ').trim()
  return clip(one ? `${tool}: ${one}` : tool, SUMMARY)
}

export function shouldForward(e: {
  door: string
  message: { isMeta?: true; role?: string }
}): boolean {
  return (
    !e.message.isMeta &&
    FORWARDED_DOORS.has(e.door) &&
    (e.message.role === 'user' || e.message.role === 'assistant')
  )
}

function resultText(content: unknown): string {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .filter((b: Block) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b: Block) => b.text as string)
    .join('\n')
}

// Claude Code merges injected context into the api-form history's user text.
const SYSTEM_REMINDER = /<system-reminder>[\s\S]*?<\/system-reminder>/g
// Slash-command envelopes recorded as user messages (local-command output).
const LOCAL_COMMAND =
  /<(local-command-caveat|command-name|command-message|command-args|local-command-stdout|local-command-stderr)>[\s\S]*?<\/\1>/g
// A background task finishing is delivered as a user-role row of this shape.
const TASK_NOTIFICATION = /<task-notification>([\s\S]*?)<\/task-notification>/g

function tag(doc: string, name: string): string {
  const m = new RegExp(`<${name}>([\\s\\S]*?)</${name}>`).exec(doc)
  return m?.[1]?.trim() ?? ''
}

// A base64 image block's payload (as stored); undefined for anything else.
function imageOf(b: Block): ChatImage | undefined {
  if (!b || b.type !== 'image' || !b.source || typeof b.source !== 'object') return undefined
  const src = b.source as { type?: unknown; media_type?: unknown; data?: unknown }
  if (src.type !== 'base64' || typeof src.media_type !== 'string' || typeof src.data !== 'string') return undefined
  return { mediaType: src.media_type, data: src.data }
}

// The `images` (and `imageSizes`, when any parsed) fields for an event.
function imageFields(ids: string[], images: Record<string, ChatImage>): { images?: string[]; imageSizes?: ImageSizes } {
  if (!ids.length) return {}
  const sizes: ImageSizes = {}
  for (const id of ids) {
    const size = imageSize(images[id]!.data)
    if (size) sizes[id] = size
  }
  return Object.keys(sizes).length ? { images: ids, imageSizes: sizes } : { images: ids }
}

// A message's blocks as chat events. Image blocks (a user's own, or inside a
// tool_result) move to `images`, keyed `<uuid>#<block>` (`#<block>.<n>` for
// the n-th image of a tool_result), and the events list their ids.
export function normalizeBlocks(role: 'user' | 'assistant', content: unknown, uuid: string, ts?: number): Normalized {
  const blocks: Block[] =
    typeof content === 'string' ? [{ type: 'text', text: content }] : Array.isArray(content) ? content : []
  const out: ChatEvent[] = []
  const images: Record<string, ChatImage> = {}
  const userTexts: string[] = []
  const userImages: string[] = []
  blocks.forEach((b, i) => {
    if (!b || typeof b !== 'object') return
    const image = role === 'user' ? imageOf(b) : undefined
    if (image) {
      images[`${uuid}#${i}`] = image
      userImages.push(`${uuid}#${i}`)
      return
    }
    if (b.type === 'text' && typeof b.text === 'string') {
      if (role === 'user') {
        let k = 0
        const rest = b.text.replace(SYSTEM_REMINDER, '').replace(LOCAL_COMMAND, '').replace(TASK_NOTIFICATION, (_, doc: string) => {
          out.push({
            type: 'task_notice',
            uuid: k === 0 ? `${uuid}#${i}` : `${uuid}#${i}.${k}`,
            status: tag(doc, 'status'),
            summary: cap(tag(doc, 'summary')),
          })
          k++
          return ''
        })
        if (rest.trim()) userTexts.push(rest)
      }
      else if (b.text.trim()) out.push({ type: 'assistant_text', uuid: `${uuid}#${i}`, text: cap(b.text) })
    } else if (b.type === 'tool_use' && role === 'assistant') {
      const tool = String(b.name)
      const input = (b.input && typeof b.input === 'object' ? b.input : {}) as Record<string, unknown>
      out.push({ type: 'tool_use', uuid: `${uuid}#${i}`, toolUseId: String(b.id), tool, summary: summarize(tool, input) })
    } else if (b.type === 'tool_result') {
      const ids: string[] = []
      if (Array.isArray(b.content)) {
        for (const c of b.content as Block[]) {
          const img = imageOf(c)
          if (!img) continue
          const id = `${uuid}#${i}.${ids.length}`
          images[id] = img
          ids.push(id)
        }
      }
      out.push({
        type: 'tool_result',
        toolUseId: String(b.tool_use_id),
        isError: b.is_error === true,
        preview: clip(resultText(b.content), PREVIEW),
        ...imageFields(ids, images),
      })
    }
  })
  const text = userTexts.join('\n').trim()
  if (text || userImages.length) {
    out.unshift({ type: 'user_text', uuid, text: cap(text), ...imageFields(userImages, images) })
  }
  if (ts !== undefined) for (const ev of out) ev.ts = ts
  return { events: out, images }
}

// UTF-8 size of a string (surrogate pairs count 2 + 2 = 4 bytes).
export function utf8Bytes(s: string): number {
  let n = 0
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    n += c < 0x80 ? 1 : c < 0x800 ? 2 : c >= 0xd800 && c <= 0xdfff ? 2 : 3
  }
  return n
}

// The last SNAPSHOT_LIMIT events, then the newest of those whose serialized
// array fits in SNAPSHOT_BYTES (well under the companion's 8 MB body limit;
// an oversized snapshot would be rejected and re-sent forever), with the
// images those events reference.
export function normalizeSnapshot(
  messages: readonly { role: 'user' | 'assistant'; content: unknown }[],
): Normalized {
  const images: Record<string, ChatImage> = {}
  const all = messages
    .flatMap((m, i) => {
      const n = normalizeBlocks(m.role, m.content, `snap-${i}`)
      Object.assign(images, n.images)
      return n.events
    })
    .slice(-SNAPSHOT_LIMIT)
  let bytes = 2 // [ ]
  let start = all.length
  while (start > 0) {
    const size = utf8Bytes(JSON.stringify(all[start - 1])) + (start < all.length ? 1 : 0)
    if (bytes + size > SNAPSHOT_BYTES) break
    bytes += size
    start--
  }
  const events = all.slice(start)
  const kept: Record<string, ChatImage> = {}
  for (const ev of events) {
    if (ev.type !== 'user_text' && ev.type !== 'tool_result') continue
    for (const id of ev.images ?? []) if (images[id]) kept[id] = images[id]
  }
  return { events, images: kept }
}
