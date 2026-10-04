import { describe, expect, test } from 'claude-code/testing'
import { eventsFromTranscript, splitPiece } from '../hooks/transcript'

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
  test('a cut piece without a newline skips that one over-long line', () => {
    expect(splitPiece('xxxxxxxx', true)).toEqual({ lines: [], advance: 1 })
  })
})
