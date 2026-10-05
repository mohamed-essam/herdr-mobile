import { describe, expect, test } from 'claude-code/testing'
import {
  HOLD_EVENTS, linkFromMeta, linkJournal, linkSpawn, newAgentsState, recordWorkflow, releaseHeld, resetAgents, routeAgentEvents, setStatus,
} from '../hooks/agents'
import type { ChatEvent } from '../hooks/normalize'

const spawn = { tool_use_id: 'toolu_1', description: 'Background echo test', subagentType: 'general-purpose' }
const text = (n: number): ChatEvent => ({ type: 'assistant_text', uuid: `u${n}`, text: `t${n}` })

// The spike's Workflow result and journal.
const WF = { status: 'async_launched', taskId: 'wfu18ne1l', runId: 'wf_4ddcdf59-066', workflowName: 'tiny-two-agents', transcriptDir: '/p/subagents/workflows/wf_4ddcdf59-066' }
const JOURNAL = [
  '{"type":"launched"}',
  '{"type":"started","agentId":"a3ffd04f6c0bcfe58","label":"Say one","phase":"Reply"}',
  '{"type":"started","agentId":"a3fbe2c38f901f28e","label":"Say two","phase":"Reply"}',
  'not json {',
  '{"type":"result","agentId":"a3ffd04f6c0bcfe58","result":"one"}',
  '{"type":"result","agentId":"a3fbe2c38f901f28e","result":"two"}',
]
const withWorkflow = () => {
  const a = newAgentsState()
  recordWorkflow(a, 'toolu_wf', WF)
  return a
}

describe('linkSpawn', () => {
  test('links a subagent and returns its control', () => {
    const a = newAgentsState()
    expect(linkSpawn(a, spawn, 'aa1', 5)).toEqual({
      type: 'agent',
      agent: { agentId: 'aa1', parentToolUseId: 'toolu_1', kind: 'subagent', label: 'Background echo test', type: 'general-purpose', status: 'running', ts: 5 },
    })
    expect(a.links.get('aa1')?.parentToolUseId).toBe('toolu_1')
  })
  test('records the spawning agent as parentAgentId', () => {
    const a = newAgentsState()
    expect(linkSpawn(a, { ...spawn, parentAgentId: 'pp1' }, 'aa1', 5)?.agent.parentAgentId).toBe('pp1')
  })
  test('no agentId or an invalid one links nothing', () => {
    const a = newAgentsState()
    expect(linkSpawn(a, spawn, undefined, 5)).toBeNull()
    expect(linkSpawn(a, spawn, '../x', 5)).toBeNull()
    expect(a.links.size).toBe(0)
  })
})

describe('recordWorkflow', () => {
  test('accepts the launch result', () => {
    const a = withWorkflow()
    expect(a.workflows.get('wf_4ddcdf59-066')).toEqual({ toolUseId: 'toolu_wf', transcriptDir: WF.transcriptDir, name: 'tiny-two-agents', journalLine: 1 })
  })
  test('ignores a bad run id, a missing transcriptDir and non-objects', () => {
    const a = newAgentsState()
    recordWorkflow(a, 't', { ...WF, runId: '../evil' })
    recordWorkflow(a, 't', { ...WF, transcriptDir: undefined })
    recordWorkflow(a, 't', 'nope')
    recordWorkflow(a, 't', null)
    expect(a.workflows.size).toBe(0)
  })
})

describe('linkJournal', () => {
  test('links started agents as workflow agents, once', () => {
    const a = withWorkflow()
    const controls = linkJournal(a, 'wf_4ddcdf59-066', JOURNAL.slice(0, 4), 7)
    expect(controls).toEqual([
      { type: 'agent', agent: { agentId: 'a3ffd04f6c0bcfe58', parentToolUseId: 'toolu_wf', kind: 'workflow', label: 'Say one', phase: 'Reply', status: 'running', ts: 7 } },
      { type: 'agent', agent: { agentId: 'a3fbe2c38f901f28e', parentToolUseId: 'toolu_wf', kind: 'workflow', label: 'Say two', phase: 'Reply', status: 'running', ts: 7 } },
    ])
    expect(linkJournal(a, 'wf_4ddcdf59-066', JOURNAL.slice(0, 4), 8)).toEqual([])
  })
  test('the whole journal links both and ends them (one control each)', () => {
    const a = withWorkflow()
    const controls = linkJournal(a, 'wf_4ddcdf59-066', JOURNAL, 7)
    expect(controls.map(c => [c.agent.agentId, c.agent.status])).toEqual([['a3ffd04f6c0bcfe58', 'done'], ['a3fbe2c38f901f28e', 'done']])
    expect(linkJournal(a, 'wf_4ddcdf59-066', JOURNAL, 8)).toEqual([])
  })
  test('a result line ends a linked agent and reports the change', () => {
    const a = withWorkflow()
    linkJournal(a, 'wf_4ddcdf59-066', JOURNAL.slice(0, 3), 7)
    const controls = linkJournal(a, 'wf_4ddcdf59-066', [JOURNAL[4]!], 9)
    expect(controls).toEqual([
      { type: 'agent', agent: { agentId: 'a3ffd04f6c0bcfe58', parentToolUseId: 'toolu_wf', kind: 'workflow', label: 'Say one', phase: 'Reply', status: 'done', ts: 9 } },
    ])
    expect(linkJournal(a, 'wf_4ddcdf59-066', [JOURNAL[4]!], 10)).toEqual([])
  })
  test('a result line for an unlinked agent is ignored', () => {
    const a = withWorkflow()
    expect(linkJournal(a, 'wf_4ddcdf59-066', [JOURNAL[4]!], 9)).toEqual([])
    expect(a.links.size).toBe(0)
  })
  test('an invalid agent id or an unknown run links nothing', () => {
    const a = withWorkflow()
    expect(linkJournal(a, 'wf_4ddcdf59-066', ['{"type":"started","agentId":"../x","label":"l"}'], 1)).toEqual([])
    expect(linkJournal(a, 'wf_other', [JOURNAL[1]!], 1)).toEqual([])
    expect(a.links.size).toBe(0)
  })
})

describe('routeAgentEvents', () => {
  test('a linked agent’s events are tagged', () => {
    const a = newAgentsState()
    linkSpawn(a, spawn, 'aa1', 1)
    expect(routeAgentEvents(a, 'aa1', [text(1)])).toEqual([{ ...text(1), agentId: 'aa1' }])
  })
  test('an unknown agent’s events are held, newest HOLD_EVENTS kept', () => {
    const a = newAgentsState()
    expect(routeAgentEvents(a, 'zz9', [text(0)])).toEqual([])
    for (let i = 1; i < 250; i++) routeAgentEvents(a, 'zz9', [text(i)])
    const held = a.held.get('zz9')!.events
    expect(held.length).toBe(HOLD_EVENTS)
    expect(held[0]).toEqual(text(50))
    expect(held.at(-1)).toEqual(text(249))
  })
  test('an ignored agent’s events are dropped, none held', () => {
    const a = newAgentsState()
    a.ignored.add('zz9')
    expect(routeAgentEvents(a, 'zz9', [text(1)])).toEqual([])
    expect(a.held.size).toBe(0)
  })
})

describe('releaseHeld', () => {
  test('releases the held events of a now-linked agent, tagged, in order', () => {
    const a = newAgentsState()
    routeAgentEvents(a, 'aa1', [text(1)])
    routeAgentEvents(a, 'aa1', [text(2)])
    linkSpawn(a, spawn, 'aa1', 1)
    expect(releaseHeld(a)).toEqual([{ ...text(1), agentId: 'aa1' }, { ...text(2), agentId: 'aa1' }])
    expect(a.held.size).toBe(0)
  })
  test('an agent still unlinked after HOLD_TICKS releases is ignored and freed', () => {
    const a = newAgentsState()
    routeAgentEvents(a, 'zz9', [text(1)])
    expect(releaseHeld(a)).toEqual([])
    expect(releaseHeld(a)).toEqual([])
    expect(a.held.has('zz9')).toBe(true)
    expect(releaseHeld(a)).toEqual([])
    expect(a.held.size).toBe(0)
    expect(a.ignored.has('zz9')).toBe(true)
  })
})

describe('setStatus', () => {
  test('null when unknown or unchanged, a control on change', () => {
    const a = newAgentsState()
    expect(setStatus(a, 'aa1', 'done', 2)).toBeNull()
    linkSpawn(a, spawn, 'aa1', 1)
    expect(setStatus(a, 'aa1', 'running', 2)).toBeNull()
    expect(setStatus(a, 'aa1', 'done', 3)).toEqual({
      type: 'agent',
      agent: { agentId: 'aa1', parentToolUseId: 'toolu_1', kind: 'subagent', label: 'Background echo test', type: 'general-purpose', status: 'done', ts: 3 },
    })
    expect(setStatus(a, 'aa1', 'done', 4)).toBeNull()
  })
})

describe('resetAgents', () => {
  test('empties everything', () => {
    const a = withWorkflow()
    linkSpawn(a, spawn, 'aa1', 1)
    routeAgentEvents(a, 'zz9', [text(1)])
    a.ignored.add('qq')
    resetAgents(a)
    expect([a.links.size, a.workflows.size, a.held.size, a.ignored.size]).toEqual([0, 0, 0, 0])
  })
})

describe('linkFromMeta', () => {
  const META = '{"agentType":"general-purpose","description":"Background echo test","toolUseId":"toolu_01AHLnnYchSVz4oxU8iSiip3","spawnDepth":1,"requestShape":"background","requestNonInteractive":true,"model":"haiku"}'

  test('the spike’s meta file links a finished subagent to its call', () => {
    const a = newAgentsState()
    expect(linkFromMeta(a, 'a0ab069d9186e35de', META, 5)).toBe(true)
    expect(a.links.get('a0ab069d9186e35de')).toEqual({ agentId: 'a0ab069d9186e35de', parentToolUseId: 'toolu_01AHLnnYchSVz4oxU8iSiip3', kind: 'subagent', label: 'Background echo test', type: 'general-purpose', status: 'done', ts: 5 })
  })

  test('bad JSON, no toolUseId (a workflow agent’s meta) or a bad id link nothing', () => {
    const a = newAgentsState()
    expect(linkFromMeta(a, 'aa1', '{not json', 1)).toBe(false)
    expect(linkFromMeta(a, 'aa1', '{"agentType":"workflow-subagent","description":"x","workflowPhase":"Reply"}', 1)).toBe(false)
    expect(linkFromMeta(a, '../x', META, 1)).toBe(false)
    expect(a.links.size).toBe(0)
  })

  test('an agent already linked live keeps its link', () => {
    const a = newAgentsState()
    linkSpawn(a, spawn, 'aa1', 1)
    expect(linkFromMeta(a, 'aa1', META, 2)).toBe(true)
    expect(a.links.get('aa1')).toMatchObject({ parentToolUseId: 'toolu_1', status: 'running', ts: 1 })
  })
})
