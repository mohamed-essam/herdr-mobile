import type { ChatEvent } from './normalize'

// Pure state and logic for linking subagents and workflow agents to the tool
// call that started them (no `$`: the hooks in register.ts do the I/O). A
// subagent is linked by its `agent.spawn`; a workflow agent by its workflow's
// journal, read by the sync tick. Rows from an agent not yet linked wait here.
export type AgentKind = 'subagent' | 'workflow'
export type AgentStatus = 'running' | 'done' | 'failed'
export type AgentLink = {
  agentId: string
  parentToolUseId: string
  parentAgentId?: string
  kind: AgentKind
  label: string
  type?: string
  phase?: string
  status: AgentStatus
  ts: number
}
export type AgentControl = { type: 'agent'; agent: AgentLink }
export type Workflow = { toolUseId: string; transcriptDir: string; name: string; journalLine: number }
export type AgentsState = {
  links: Map<string, AgentLink>
  workflows: Map<string, Workflow> // by runId
  // Unlinked agentId → its held events, and the ticks it has been waiting.
  held: Map<string, { events: ChatEvent[]; ticks: number }>
  ignored: Set<string> // gave up linking
}

export const HOLD_EVENTS = 200
export const HOLD_TICKS = 3
// Ids reach file paths and the wire: anything else is ignored, never used.
export const AGENT_ID = /^[a-z0-9]{1,64}$/
export const RUN_ID = /^wf_[a-z0-9-]{1,64}$/

export function newAgentsState(): AgentsState {
  return { links: new Map(), workflows: new Map(), held: new Map(), ignored: new Set() }
}

export function resetAgents(a: AgentsState): void {
  a.links.clear()
  a.workflows.clear()
  a.held.clear()
  a.ignored.clear()
}

// The control carries a copy: the link goes on changing after it is queued.
function control(link: AgentLink): AgentControl {
  return { type: 'agent', agent: { ...link } }
}

function link(a: AgentsState, l: AgentLink): AgentControl {
  a.links.set(l.agentId, l)
  a.ignored.delete(l.agentId)
  return control(l)
}

export function linkSpawn(
  a: AgentsState,
  e: { tool_use_id: string; description: string; subagentType: string; parentAgentId?: string },
  agentId: string | undefined,
  now: number,
): AgentControl | null {
  if (!agentId || !AGENT_ID.test(agentId)) return null
  return link(a, {
    agentId,
    parentToolUseId: e.tool_use_id,
    ...(e.parentAgentId ? { parentAgentId: e.parentAgentId } : {}),
    kind: 'subagent',
    label: e.description,
    type: e.subagentType,
    status: 'running',
    ts: now,
  })
}

// A subagent's `agent-<id>.meta.json` (read at a resync): links it to its
// Agent call as finished (a live status comes after, from `$.agent.list()`).
// One already linked keeps its link. False on bad JSON, no toolUseId (a
// workflow agent's meta has none) or an id that is not a plain one.
export function linkFromMeta(a: AgentsState, agentId: string, metaJson: string, now: number): boolean {
  if (!AGENT_ID.test(agentId)) return false
  let m: { toolUseId?: unknown; description?: unknown; agentType?: unknown }
  try {
    m = JSON.parse(metaJson)
  } catch {
    return false
  }
  if (!m || typeof m !== 'object' || typeof m.toolUseId !== 'string' || !m.toolUseId) return false
  if (a.links.has(agentId)) return true
  link(a, {
    agentId,
    parentToolUseId: m.toolUseId,
    kind: 'subagent',
    label: typeof m.description === 'string' ? m.description : '',
    ...(typeof m.agentType === 'string' ? { type: m.agentType } : {}),
    status: 'done',
    ts: now,
  })
  return true
}

// A control per linked agent, as each stands now.
export function agentControls(a: AgentsState): AgentControl[] {
  return [...a.links.values()].map(control)
}

// The Workflow tool's launch result: needs a valid runId and a transcriptDir.
export function recordWorkflow(a: AgentsState, toolUseId: string, result: unknown): void {
  if (!result || typeof result !== 'object') return
  const r = result as { runId?: unknown; transcriptDir?: unknown; workflowName?: unknown }
  if (typeof r.runId !== 'string' || !RUN_ID.test(r.runId)) return
  if (typeof r.transcriptDir !== 'string' || !r.transcriptDir) return
  if (a.workflows.has(r.runId)) return
  a.workflows.set(r.runId, { toolUseId, transcriptDir: r.transcriptDir, name: typeof r.workflowName === 'string' ? r.workflowName : '', journalLine: 1 })
}

// `started` lines link a workflow agent; `result` lines end an already linked
// one. One control per agent, as it stands after all the lines.
export function linkJournal(a: AgentsState, runId: string, lines: string[], now: number): AgentControl[] {
  const wf = a.workflows.get(runId)
  if (!wf) return []
  const touched = new Set<string>()
  for (const line of lines) {
    let row: { type?: unknown; agentId?: unknown; label?: unknown; phase?: unknown }
    try {
      row = JSON.parse(line)
    } catch {
      continue
    }
    if (!row || typeof row !== 'object' || typeof row.agentId !== 'string' || !AGENT_ID.test(row.agentId)) continue
    const known = a.links.get(row.agentId)
    if (row.type === 'started' && !known) {
      link(a, {
        agentId: row.agentId,
        parentToolUseId: wf.toolUseId,
        kind: 'workflow',
        label: typeof row.label === 'string' ? row.label : '',
        ...(typeof row.phase === 'string' ? { phase: row.phase } : {}),
        status: 'running',
        ts: now,
      })
      touched.add(row.agentId)
    } else if (row.type === 'result' && known?.parentToolUseId === wf.toolUseId) {
      if (setStatus(a, row.agentId, 'done', now)) touched.add(row.agentId)
    }
  }
  return [...touched].map(id => control(a.links.get(id)!))
}

function tagged(events: ChatEvent[], agentId: string): ChatEvent[] {
  return events.map(e => ({ ...e, agentId }))
}

export function routeAgentEvents(a: AgentsState, agentId: string, events: ChatEvent[]): ChatEvent[] {
  if (a.ignored.has(agentId)) return []
  if (a.links.has(agentId)) return tagged(events, agentId)
  const h = a.held.get(agentId) ?? { events: [], ticks: 0 }
  h.events.push(...events)
  if (h.events.length > HOLD_EVENTS) h.events.splice(0, h.events.length - HOLD_EVENTS)
  a.held.set(agentId, h)
  return []
}

// Once per tick, after the journal reads.
export function releaseHeld(a: AgentsState): ChatEvent[] {
  const out: ChatEvent[] = []
  for (const [id, h] of [...a.held]) {
    if (a.links.has(id)) {
      out.push(...tagged(h.events, id))
      a.held.delete(id)
    } else if (++h.ticks >= HOLD_TICKS) {
      a.held.delete(id)
      a.ignored.add(id)
    }
  }
  return out
}

export function setStatus(a: AgentsState, agentId: string, status: AgentStatus, now: number): AgentControl | null {
  const l = a.links.get(agentId)
  if (!l || l.status === status) return null
  l.status = status
  l.ts = now
  return control(l)
}
