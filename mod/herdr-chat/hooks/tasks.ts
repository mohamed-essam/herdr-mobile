// Pure state and logic for background tasks (no `$`: the hooks in register.ts
// do the I/O): shells, subagents, workflows and monitors launched in the
// background, from their launch results; ended by their task notifications.
export type TaskKind = 'shell' | 'subagent' | 'workflow' | 'monitor'
export type TaskStatus = 'running' | 'done' | 'failed'
// detail: the whole command (a shell's, a monitor's) when the label doesn't
// already show all of it; description: what the launch said it does.
export type Task = { id: string; kind: TaskKind; label: string; toolUseId: string; status: TaskStatus; startedAt: number; endedAt?: number; detail?: string; description?: string }
export type TasksState = Map<string, Task> // by task id
export type TasksControl = { type: 'tasks'; tasks: Task[] }

// A finished task stays listed this long.
export const TASK_TTL_MS = 600_000
export const LABEL_MAX = 120
export const DETAIL_MAX = 4000

// One line, at most LABEL_MAX characters.
function label(s: string): string {
  const one = s.replace(/\s+/g, ' ').trim()
  return one.length > LABEL_MAX ? one.slice(0, LABEL_MAX - 1) + '…' : one
}

// As written (newlines kept), at most DETAIL_MAX characters.
function detail(s: string): string {
  const t = s.trim()
  return t.length > DETAIL_MAX ? t.slice(0, DETAIL_MAX - 1) + '…' : t
}

// A notice's status word: `completed` is done, anything else failed.
export function noticeStatus(status: string): TaskStatus {
  return status === 'completed' ? 'done' : 'failed'
}

const str = (v: unknown): string => (typeof v === 'string' ? v : '')

// A background launch's result (the shapes the tools return); anything else,
// a foreground run included, changes nothing.
export function taskFromLaunch(t: TasksState, tool: string, toolUseId: string, input: Record<string, unknown>, result: unknown, now: number): boolean {
  if (!result || typeof result !== 'object') return false
  const r = result as Record<string, unknown>
  let id = ''
  let kind: TaskKind
  let text = ''
  let command = ''
  let description = ''
  if (tool === 'Bash') {
    id = str(r.backgroundTaskId)
    kind = 'shell'
    text = command = str(input.command)
    description = str(input.description).trim()
  } else if (tool === 'Agent') {
    if (r.status !== 'async_launched') return false
    id = str(r.agentId)
    kind = 'subagent'
    text = str(input.description)
  } else if (tool === 'Workflow') {
    id = str(r.taskId)
    kind = 'workflow'
    text = str(r.workflowName) || str(r.summary)
  } else if (tool === 'Monitor') {
    id = str(r.taskId)
    kind = 'monitor'
    text = str(input.description) || str(input.command)
    command = str(input.command)
  } else return false
  if (!id) return false
  const task: Task = { id, kind, label: label(text), toolUseId, status: 'running', startedAt: now }
  if (command.trim() && detail(command) !== task.label) task.detail = detail(command)
  if (description) task.description = description
  t.set(id, task)
  return true
}

// Ends the task the notice names (by task id, else by tool-use id), or lists
// an unknown one as already finished. A repeat updates in place. A notice
// without a status is a running monitor's event, not an end: it changes nothing.
export function taskFromNotice(t: TasksState, n: { taskId?: string; toolUseId?: string; status: string; summary: string }, now: number): boolean {
  if (!n.status) return false
  const status = noticeStatus(n.status)
  const known = (n.taskId ? t.get(n.taskId) : undefined) ?? (n.toolUseId ? [...t.values()].find(k => k.toolUseId === n.toolUseId) : undefined)
  if (known) {
    known.status = status
    known.endedAt = now
    return true
  }
  const id = n.taskId ?? n.toolUseId ?? `notice-${now}`
  const kind: TaskKind = n.summary.startsWith('Agent "') ? 'subagent' : n.summary.startsWith('Workflow') ? 'workflow' : 'shell'
  t.set(id, { id, kind, label: label(n.summary), toolUseId: n.toolUseId ?? '', status, startedAt: now, endedAt: now })
  return true
}

// A TaskStop result ends the running task it names: a stopped task often gets
// no notice at all.
export function taskFromStop(t: TasksState, result: unknown, now: number): boolean {
  if (!result || typeof result !== 'object') return false
  const k = t.get(str((result as Record<string, unknown>).task_id))
  if (!k || k.status !== 'running') return false
  k.status = 'failed'
  k.endedAt = now
  return true
}

export function expireTasks(t: TasksState, now: number): boolean {
  let dropped = false
  for (const [id, k] of [...t]) {
    if (k.endedAt !== undefined && now - k.endedAt >= TASK_TTL_MS) {
      t.delete(id)
      dropped = true
    }
  }
  return dropped
}

// Running tasks first (oldest start first), then finished ones, newest end
// first. The control carries copies: the tasks go on changing after it is queued.
export function tasksControl(t: TasksState): TasksControl {
  const all = [...t.values()].map(k => ({ ...k }))
  const running = all.filter(k => k.status === 'running').sort((a, b) => a.startedAt - b.startedAt)
  const done = all.filter(k => k.status !== 'running').sort((a, b) => (b.endedAt ?? 0) - (a.endedAt ?? 0))
  return { type: 'tasks', tasks: [...running, ...done] }
}
