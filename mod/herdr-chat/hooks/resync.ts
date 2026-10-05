import { AGENT_ID, type AgentsState, type AgentStatus } from './agents'
import type { TasksState } from './tasks'

// Pure parts of the resync's agent rebuild (no `$`: register.ts lists and
// reads the session's files). Of the linked agents, the threads of the
// THREADS_MAX whose transcripts changed last are replayed, each its newest
// THREAD_EVENTS events (the companion keeps no more per thread).
export const THREADS_MAX = 20
export const THREAD_EVENTS = 1000

export type AgentFile = { id: string; path: string; mtime: number }

// A `find <dir> -maxdepth 1 -name 'agent-*<suffix>'` listing (`timed`: with
// `-printf '%T@ %p\n'`). Only `<dir>/agent-<id><suffix>` with a plain agent
// id counts, its path rebuilt from `dir` and the id; any other line is ignored.
export function parseListing(stdout: string, dir: string, suffix: string, timed: boolean): AgentFile[] {
  const out: AgentFile[] = []
  const prefix = `${dir}/agent-`
  for (const raw of stdout.split('\n')) {
    let line = raw
    let mtime = 0
    if (timed) {
      const m = /^(\d+(?:\.\d+)?) (.*)$/.exec(raw)
      if (!m) continue
      mtime = Number(m[1])
      line = m[2]!
    }
    if (!line.startsWith(prefix) || !line.endsWith(suffix)) continue
    const id = line.slice(prefix.length, line.length - suffix.length)
    if (AGENT_ID.test(id)) out.push({ id, path: `${prefix}${id}${suffix}`, mtime })
  }
  return out
}

// The linked agents' files to replay: the newest THREADS_MAX, oldest first
// (an id listed twice counts by its newest file).
export function newestAgents(files: readonly AgentFile[], a: AgentsState): AgentFile[] {
  const byId = new Map<string, AgentFile>()
  for (const f of files) {
    if (!a.links.has(f.id)) continue
    const known = byId.get(f.id)
    if (!known || f.mtime > known.mtime) byId.set(f.id, f)
  }
  return [...byId.values()].sort((x, y) => y.mtime - x.mtime).slice(0, THREADS_MAX).reverse()
}

// `$.agent.list()`'s status word as an agent status; undefined (keep the
// file-derived one) for `idle` and anything unknown.
export function listStatus(status: string): AgentStatus | undefined {
  if (status === 'running' || status === 'waiting' || status === 'pending') return 'running'
  if (status === 'completed') return 'done'
  if (status === 'failed' || status === 'killed') return 'failed'
  return undefined
}

// Lists each linked subagent `$.agent.list()` says is running, and the tasks
// do not, as a running task (a background one launched before the mod
// loaded is known no other way). True when the list changed.
export function runningSubagentTasks(t: TasksState, list: readonly { id: string; status: string; description: string }[], a: AgentsState, now: number): boolean {
  let changed = false
  for (const info of list) {
    const l = a.links.get(info.id)
    if (!l || l.kind !== 'subagent' || listStatus(info.status) !== 'running' || t.has(info.id)) continue
    t.set(info.id, { id: info.id, kind: 'subagent', label: info.description || l.label, toolUseId: l.parentToolUseId, status: 'running', startedAt: now })
    changed = true
  }
  return changed
}
