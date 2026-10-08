// The session's slash commands as the phone's picker lists them, and telling
// a phone message that names one apart from a prompt.

export type SlashCommand = { name: string; description: string; source: string }

// A picker row shows one line of description.
export const DESCRIPTION_MAX = 160

// `$.command.list()` rows in typeahead order: first of each name, one line
// of description each.
export function toCommands(list: readonly { name: string; description: string; source: string }[]): SlashCommand[] {
  const seen = new Set<string>()
  const out: SlashCommand[] = []
  for (const c of list) {
    if (!c.name || seen.has(c.name)) continue
    seen.add(c.name)
    let description = (c.description ?? '').replace(/\s+/g, ' ').trim()
    if (description.length > DESCRIPTION_MAX) description = description.slice(0, DESCRIPTION_MAX - 1) + '…'
    out.push({ name: c.name, description, source: c.source })
  }
  return out
}

// `/name args` naming one of `names`: run as that command. Anything else
// (an unknown name included) goes to the model as typed.
export function parseSlash(text: string, names: ReadonlySet<string>): { command: string; args: string } | null {
  const m = /^\/(\S+)(?:\s+([\s\S]*))?$/.exec(text.trim())
  if (!m || !names.has(m[1]!)) return null
  return { command: m[1]!, args: (m[2] ?? '').trim() }
}
