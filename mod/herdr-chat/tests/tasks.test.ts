import { describe, expect, test } from 'claude-code/testing'
import { expireTasks, LABEL_MAX, taskFromLaunch, taskFromNotice, taskFromStop, tasksControl, TASK_TTL_MS, type TasksState } from '../hooks/tasks'

// The spike's launch results.
const BASH = { backgroundTaskId: 'b4prbe90d', stdout: '', stderr: '' }
const AGENT = { status: 'async_launched', agentId: 'a0ab069d9186e35de', description: 'Background echo test', prompt: 'p' }
const WF = { status: 'async_launched', taskId: 'wfu18ne1l', runId: 'wf_4ddcdf59-066', workflowName: 'tiny-two-agents', summary: 'Launched tiny-two-agents', transcriptDir: '/p/wf' }
const MON = { taskId: 'm1', timeoutMs: 300000 }

const launched = () => {
  const t: TasksState = new Map()
  taskFromLaunch(t, 'Bash', 'toolu_b', { command: 'sleep 25 && echo hi' }, BASH, 100)
  taskFromLaunch(t, 'Agent', 'toolu_a', { description: 'Background echo test' }, AGENT, 200)
  taskFromLaunch(t, 'Workflow', 'toolu_w', {}, WF, 300)
  taskFromLaunch(t, 'Monitor', 'toolu_m', { description: 'watch logs', command: 'tail -f x' }, MON, 400)
  return t
}

describe('taskFromLaunch', () => {
  test('the four spike launches become running tasks of the right kind and label', () => {
    const t = launched()
    expect([...t.values()]).toEqual([
      { id: 'b4prbe90d', kind: 'shell', label: 'sleep 25 && echo hi', toolUseId: 'toolu_b', status: 'running', startedAt: 100 },
      { id: 'a0ab069d9186e35de', kind: 'subagent', label: 'Background echo test', toolUseId: 'toolu_a', status: 'running', startedAt: 200 },
      { id: 'wfu18ne1l', kind: 'workflow', label: 'tiny-two-agents', toolUseId: 'toolu_w', status: 'running', startedAt: 300 },
      { id: 'm1', kind: 'monitor', label: 'watch logs', toolUseId: 'toolu_m', status: 'running', startedAt: 400 },
    ])
  })
  test('returns true when the list changed, false otherwise', () => {
    const t: TasksState = new Map()
    expect(taskFromLaunch(t, 'Bash', 'x', { command: 'c' }, BASH, 1)).toBe(true)
    expect(taskFromLaunch(t, 'Bash', 'x', { command: 'c' }, { stdout: 'foreground' }, 1)).toBe(false)
  })
  test('a foreground Agent result adds nothing', () => {
    const t: TasksState = new Map()
    expect(taskFromLaunch(t, 'Agent', 'toolu_a', { description: 'd' }, { status: 'completed', agentId: 'a1' }, 1)).toBe(false)
    expect(t.size).toBe(0)
  })
  test('an unrelated tool or a junk result adds nothing', () => {
    const t: TasksState = new Map()
    expect(taskFromLaunch(t, 'Read', 't', {}, BASH, 1)).toBe(false)
    expect(taskFromLaunch(t, 'Bash', 't', {}, null, 1)).toBe(false)
    expect(taskFromLaunch(t, 'Monitor', 't', {}, 'str', 1)).toBe(false)
    expect(t.size).toBe(0)
  })
  test('the workflow label falls back to the summary; the monitor label to its command', () => {
    const t: TasksState = new Map()
    taskFromLaunch(t, 'Workflow', 'w', {}, { taskId: 'w1', summary: 'Launched it' }, 1)
    taskFromLaunch(t, 'Monitor', 'm', { command: 'tail -f x' }, { taskId: 'm2' }, 1)
    expect(t.get('w1')!.label).toBe('Launched it')
    expect(t.get('m2')!.label).toBe('tail -f x')
  })
  test('a 300-character command is clipped to 120 with an ellipsis, on one line', () => {
    const t: TasksState = new Map()
    taskFromLaunch(t, 'Bash', 'b', { command: 'a'.repeat(300) }, BASH, 1)
    expect(t.get('b4prbe90d')!.label).toBe('a'.repeat(LABEL_MAX - 1) + '…')
    expect(t.get('b4prbe90d')!.label.length).toBe(LABEL_MAX)
    const u: TasksState = new Map()
    taskFromLaunch(u, 'Bash', 'b', { command: 'echo a\n  echo b' }, BASH, 1)
    expect(u.get('b4prbe90d')!.label).toBe('echo a echo b')
  })
})

describe('taskFromNotice', () => {
  test('matches by task id and sets done with endedAt', () => {
    const t = launched()
    expect(taskFromNotice(t, { taskId: 'a0ab069d9186e35de', status: 'completed', summary: 'Agent "x" finished' }, 900)).toBe(true)
    expect(t.get('a0ab069d9186e35de')).toMatchObject({ status: 'done', endedAt: 900, label: 'Background echo test' })
  })
  test('matches by tool-use id alone', () => {
    const t = launched()
    taskFromNotice(t, { toolUseId: 'toolu_b', status: 'completed', summary: 's' }, 900)
    expect(t.get('b4prbe90d')).toMatchObject({ status: 'done', endedAt: 900 })
    expect(t.size).toBe(4)
  })
  test('failed, killed, stopped and anything else set failed', () => {
    for (const status of ['failed', 'killed', 'stopped', 'weird']) {
      const t = launched()
      taskFromNotice(t, { taskId: 'm1', status, summary: 's' }, 5)
      expect(t.get('m1')!.status).toBe('failed')
    }
  })
  test('a notice without a status (a monitor event) ends nothing and adds nothing', () => {
    const t = launched()
    expect(taskFromNotice(t, { taskId: 'm1', status: '', summary: 'Monitor event: "x"' }, 5)).toBe(false)
    expect(t.get('m1')!.status).toBe('running')
    expect(taskFromNotice(t, { taskId: 'zz', status: '', summary: 'Monitor event: "y"' }, 5)).toBe(false)
    expect(t.has('zz')).toBe(false)
  })
  test('a repeat notice updates in place', () => {
    const t: TasksState = new Map()
    taskFromNotice(t, { taskId: 'z1', status: 'completed', summary: 'Background command "x" completed' }, 10)
    taskFromNotice(t, { taskId: 'z1', status: 'failed', summary: 'Background command "x" failed' }, 20)
    expect(t.size).toBe(1)
    expect(t.get('z1')).toMatchObject({ status: 'failed', endedAt: 20 })
  })
  test('an unknown notice adds a finished entry, its kind from the summary', () => {
    const t: TasksState = new Map()
    taskFromNotice(t, { taskId: 'q1', status: 'completed', summary: 'Agent "x" finished' }, 50)
    taskFromNotice(t, { toolUseId: 'toolu_q', status: 'completed', summary: 'Workflow "y" finished' }, 50)
    taskFromNotice(t, { status: 'killed', summary: 'Background command "z" was stopped' }, 50)
    expect(t.get('q1')).toEqual({ id: 'q1', kind: 'subagent', label: 'Agent "x" finished', toolUseId: '', status: 'done', startedAt: 50, endedAt: 50 })
    expect(t.get('toolu_q')).toMatchObject({ kind: 'workflow', toolUseId: 'toolu_q' })
    expect(t.get('notice-50')).toMatchObject({ kind: 'shell', status: 'failed' })
  })
})

describe('expireTasks', () => {
  test('drops finished tasks at endedAt + TTL, keeps them a ms earlier, never running ones', () => {
    const t = launched()
    taskFromNotice(t, { taskId: 'm1', status: 'completed', summary: 's' }, 1000)
    expect(expireTasks(t, 1000 + TASK_TTL_MS - 1)).toBe(false)
    expect(t.has('m1')).toBe(true)
    expect(expireTasks(t, 1000 + TASK_TTL_MS)).toBe(true)
    expect(t.has('m1')).toBe(false)
    expect(t.size).toBe(3)
  })
})

describe('tasksControl', () => {
  test('running first by startedAt, then finished by endedAt descending', () => {
    const t = launched()
    taskFromNotice(t, { taskId: 'b4prbe90d', status: 'completed', summary: 's' }, 700)
    taskFromNotice(t, { taskId: 'wfu18ne1l', status: 'completed', summary: 's' }, 800)
    const c = tasksControl(t)
    expect(c.type).toBe('tasks')
    expect(c.tasks.map(x => x.id)).toEqual(['a0ab069d9186e35de', 'm1', 'wfu18ne1l', 'b4prbe90d'])
  })
  test('the control carries copies', () => {
    const t = launched()
    const c = tasksControl(t)
    taskFromNotice(t, { taskId: 'm1', status: 'completed', summary: 's' }, 5)
    expect(c.tasks.find(x => x.id === 'm1')!.status).toBe('running')
  })
})

describe('taskFromStop', () => {
  test('a TaskStop result ends the task it names as failed', () => {
    const t = launched()
    expect(taskFromStop(t, { message: 'Successfully stopped task: m1 (tail -f x)', task_id: 'm1' }, 70)).toBe(true)
    expect(t.get('m1')).toMatchObject({ status: 'failed', endedAt: 70 })
  })
  test('an unknown, already ended or missing task id changes nothing', () => {
    const t = launched()
    expect(taskFromStop(t, { task_id: 'nope' }, 70)).toBe(false)
    expect(taskFromStop(t, { message: 'x' }, 70)).toBe(false)
    expect(taskFromStop(t, 'Error', 70)).toBe(false)
    taskFromNotice(t, { taskId: 'm1', status: 'completed', summary: 's' }, 60)
    expect(taskFromStop(t, { task_id: 'm1' }, 70)).toBe(false)
    expect(t.get('m1')).toMatchObject({ status: 'done', endedAt: 60 })
  })
})
