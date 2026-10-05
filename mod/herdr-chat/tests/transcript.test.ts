import { describe, expect, test } from 'claude-code/testing'
import { addTranscriptLine, eventsFromTranscript, finishTranscriptHistory, HISTORY_IMAGES, newTranscriptHistory, splitPiece } from '../hooks/transcript'

const row = (o: object) => JSON.stringify(o)
const base = { isSidechain: false, sessionId: 's1' }

const FIXTURE = [
  row({ type: 'queue-operation', operation: 'enqueue', timestamp: '2026-10-04T15:10:14.000Z' }),
  row({ ...base, type: 'user', uuid: 'u1', timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: 'run the tests' } }),
  row({ ...base, type: 'user', uuid: 'm1', isMeta: true, timestamp: '2026-10-04T15:10:15.000Z', message: { role: 'user', content: [{ type: 'text', text: 'Base directory for this skill: /x\n\nskill body' }] } }),
  row({ ...base, type: 'assistant', uuid: 'sc1', isSidechain: true, timestamp: '2026-10-04T15:10:16.000Z', message: { role: 'assistant', content: [{ type: 'text', text: 'subagent chatter' }] } }),
  row({ ...base, type: 'assistant', uuid: 'a1', timestamp: '2026-10-04T15:10:17.000Z', message: { role: 'assistant', content: [{ type: 'text', text: 'Running them' }, { type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'npm test' } }] } }),
  row({ ...base, type: 'user', uuid: 'r1', timestamp: '2026-10-04T15:10:18.000Z', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't1', content: 'all green', is_error: false }] } }),
  row({ ...base, type: 'user', uuid: 'i1', isMeta: true, timestamp: '2026-10-04T15:10:19.000Z', message: { role: 'user', content: [{ type: 'text', text: '[Image: original 1280x2856, displayed at 896x2000]' }] } }),
  row({ ...base, type: 'user', uuid: 'p1', isMeta: true, timestamp: '2026-10-04T15:10:20.000Z', message: { role: 'user', content: [{ type: 'text', text: '<agent-message from="a0f">report</agent-message>' }] } }),
  '{not json',
].join('\n')

describe('eventsFromTranscript', () => {
  test('keeps only main-thread non-meta message rows, in order, with real uuids and timestamps', () => {
    expect(eventsFromTranscript(FIXTURE)).toEqual([
      { type: 'user_text', uuid: 'u1', text: 'run the tests', ts: 1791126614835 },
      { type: 'assistant_text', uuid: 'a1#0', text: 'Running them', ts: Date.parse('2026-10-04T15:10:17.000Z') },
      { type: 'tool_use', uuid: 'a1#1', toolUseId: 't1', tool: 'Bash', summary: 'Bash: npm test', ts: Date.parse('2026-10-04T15:10:17.000Z') },
      { type: 'tool_result', toolUseId: 't1', isError: false, preview: 'all green', ts: Date.parse('2026-10-04T15:10:18.000Z') },
    ])
  })

  test('an unparseable timestamp leaves ts off', () => {
    const out = eventsFromTranscript(row({ type: 'user', uuid: 'u9', timestamp: 'nope', message: { role: 'user', content: 'hi' } }))
    expect(out).toEqual([{ type: 'user_text', uuid: 'u9', text: 'hi' }])
  })

  test('rows without a string uuid are dropped', () => {
    const out = eventsFromTranscript([
      row({ type: 'user', timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: 'no uuid' } }),
      row({ type: 'user', uuid: 7, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: 'numeric uuid' } }),
      row({ type: 'user', uuid: 'u1', timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: 'kept' } }),
    ].join('\n'))
    expect(out).toEqual([{ type: 'user_text', uuid: 'u1', text: 'kept', ts: 1791126614835 }])
  })

  test('rows whose message has no role are dropped', () => {
    const out = eventsFromTranscript([
      row({ type: 'user', uuid: 'x1', timestamp: '2026-10-04T15:10:14.835Z', message: { content: 'no role' } }),
      row({ type: 'user', uuid: 'u1', timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: 'kept' } }),
    ].join('\n'))
    expect(out).toEqual([{ type: 'user_text', uuid: 'u1', text: 'kept', ts: 1791126614835 }])
  })

  test('an empty file yields null', () => {
    expect(eventsFromTranscript('')).toBe(null)
  })

  test('a file of only non-message rows yields null', () => {
    const jsonl = [row({ type: 'queue-operation' }), row({ type: 'mode', mode: 'default' }), '{bad'].join('\n')
    expect(eventsFromTranscript(jsonl)).toBe(null)
  })
})

describe('splitPiece', () => {
  test('a whole piece yields every line', () => {
    expect(splitPiece('a\nb\nc', false)).toEqual({ lines: ['a', 'b', 'c'], advance: 0 })
  })
  test('a cut piece yields its complete lines and advances past them', () => {
    expect(splitPiece('a\nb\npart', true)).toEqual({ lines: ['a', 'b'], advance: 2 })
    expect(splitPiece('a\n', true)).toEqual({ lines: ['a'], advance: 1 })
  })
  test('a cut piece without a newline flags that one over-long line', () => {
    expect(splitPiece('xxxxxxxx', true)).toEqual({ lines: [], advance: 1, overlong: true })
  })
})

describe('transcript images', () => {
  const imageRow = (uuid: string, data: string) =>
    row({ type: 'user', uuid, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: [{ type: 'image', source: { type: 'base64', media_type: 'image/png', data } }] } })

  test('only the newest HISTORY_IMAGES images are held while reading, in history order', () => {
    const h = newTranscriptHistory()
    for (let i = 0; i < 35; i++) {
      addTranscriptLine(h, imageRow(`i${i}`, 'QUJD'))
      expect(Object.keys(h.images).length).toBeLessThanOrEqual(HISTORY_IMAGES)
    }
    expect(Object.keys(h.images)).toEqual(Array.from({ length: 30 }, (_, i) => `i${i + 5}#0`))
    expect(h.events.length).toBe(35) // every reference stays
  })

  test('an image with empty data is not held (it resolves missing)', () => {
    const h = newTranscriptHistory()
    addTranscriptLine(h, imageRow('e1', ''))
    expect(h.images).toEqual({})
    expect(h.events).toEqual([{ type: 'user_text', uuid: 'e1', text: '', images: ['e1#0'], ts: 1791126614835 }])
  })
})

describe('agent transcripts', () => {
  const side = row({ type: 'assistant', uuid: 's1', isSidechain: true, agentId: 'aa1', timestamp: '2026-10-05T09:24:37.482Z', message: { role: 'assistant', content: [{ type: 'text', text: 'from the subagent' }] } })

  test('a sidechain row is dropped by default and kept with { sidechain: true }', () => {
    const main = newTranscriptHistory()
    addTranscriptLine(main, side)
    expect(main.events).toEqual([])
    const thread = newTranscriptHistory()
    addTranscriptLine(thread, side, { sidechain: true })
    expect(thread.events).toEqual([{ type: 'assistant_text', uuid: 's1#0', text: 'from the subagent', ts: Date.parse('2026-10-05T09:24:37.482Z') }])
  })

  // The Workflow tool_result row as the spike's transcript holds it.
  const workflowResult = (runId: string) => row({
    type: 'user', uuid: 'r9', isSidechain: false, timestamp: '2026-10-05T09:30:00.000Z',
    message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_W', content: 'launched' }] },
    toolUseResult: { status: 'async_launched', taskId: 'wfu18ne1l', taskType: 'local_workflow', workflowName: 'tiny-two-agents', runId, summary: 's', transcriptDir: '/x/subagents/workflows/' + runId },
  })

  test('a Workflow tool result names its run, by the call it answers', () => {
    const h = newTranscriptHistory()
    addTranscriptLine(h, workflowResult('wf_4ddcdf59-066'))
    expect(h.workflowRuns.get('wf_4ddcdf59-066')).toBe('toolu_W')
    expect(finishTranscriptHistory(h)?.workflowRuns.get('wf_4ddcdf59-066')).toBe('toolu_W')
  })

  test('a run id that is not a plain one is ignored', () => {
    const h = newTranscriptHistory()
    addTranscriptLine(h, workflowResult('../x'))
    expect([...h.workflowRuns]).toEqual([])
  })
})
