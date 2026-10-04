export type ChatEvent =
  | { type: 'user_text'; uuid: string; text: string }
  | { type: 'assistant_text'; uuid: string; text: string }
  | { type: 'tool_use'; uuid: string; toolUseId: string; tool: string; summary: string }
  | { type: 'tool_result'; toolUseId: string; isError: boolean; preview: string }

export const MAX_TEXT = 64 * 1024
export const PREVIEW = 400
export const SUMMARY = 120
export const SNAPSHOT_LIMIT = 500

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
  agentId?: string
  message: { isMeta?: true; role?: string }
}): boolean {
  return (
    !e.agentId &&
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

export function normalizeBlocks(role: 'user' | 'assistant', content: unknown, uuid: string): ChatEvent[] {
  const blocks: Block[] =
    typeof content === 'string' ? [{ type: 'text', text: content }] : Array.isArray(content) ? content : []
  const out: ChatEvent[] = []
  const userTexts: string[] = []
  blocks.forEach((b, i) => {
    if (b.type === 'text' && typeof b.text === 'string') {
      if (role === 'user') userTexts.push(b.text)
      else if (b.text.trim()) out.push({ type: 'assistant_text', uuid: `${uuid}#${i}`, text: cap(b.text) })
    } else if (b.type === 'tool_use' && role === 'assistant') {
      const tool = String(b.name)
      const input = (b.input && typeof b.input === 'object' ? b.input : {}) as Record<string, unknown>
      out.push({ type: 'tool_use', uuid: `${uuid}#${i}`, toolUseId: String(b.id), tool, summary: summarize(tool, input) })
    } else if (b.type === 'tool_result') {
      out.push({
        type: 'tool_result',
        toolUseId: String(b.tool_use_id),
        isError: b.is_error === true,
        preview: clip(resultText(b.content), PREVIEW),
      })
    }
  })
  const text = userTexts.join('\n').trim()
  if (text) out.unshift({ type: 'user_text', uuid, text: cap(text) })
  return out
}

export function normalizeSnapshot(
  messages: readonly { role: 'user' | 'assistant'; content: unknown }[],
): ChatEvent[] {
  return messages.flatMap((m, i) => normalizeBlocks(m.role, m.content, `snap-${i}`)).slice(-SNAPSHOT_LIMIT)
}
