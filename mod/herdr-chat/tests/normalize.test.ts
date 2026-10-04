import { describe, expect, test } from 'claude-code/testing'
import { cap, normalizeBlocks, normalizeSnapshot, shouldForward, summarize, MAX_TEXT } from '../hooks/normalize'

describe('shouldForward', () => {
  const row = (over: object) => ({ door: 'prompt', message: { role: 'user' }, ...over })
  test('main-conversation prompt/response/tool-result/delivery rows pass', () => {
    for (const door of ['prompt', 'response', 'tool-result', 'delivery']) {
      expect(shouldForward(row({ door }) as never)).toBe(true)
    }
  })
  test('subagent rows, meta rows and injected doors are dropped', () => {
    expect(shouldForward(row({ agentId: 'a1' }) as never)).toBe(false)
    expect(shouldForward(row({ message: { role: 'user', isMeta: true } }) as never)).toBe(false)
    for (const door of ['attachment', 'hook-context', 'note', 'compaction', 'notice', 'command', 'tool-message']) {
      expect(shouldForward(row({ door }) as never)).toBe(false)
    }
  })
  test('rows without a role are dropped', () => {
    expect(shouldForward({ door: 'response', message: {} } as never)).toBe(false)
  })
})

describe('normalizeBlocks', () => {
  test('user text blocks join into one user_text with the row uuid', () => {
    const out = normalizeBlocks('user', [{ type: 'text', text: 'hello' }, { type: 'text', text: 'world' }], 'u1')
    expect(out).toEqual([{ type: 'user_text', uuid: 'u1', text: 'hello\nworld' }])
  })
  test('a user row with only tool results yields only tool_result events', () => {
    const out = normalizeBlocks('user', [{ type: 'tool_result', tool_use_id: 't1', content: [{ type: 'text', text: 'ok' }], is_error: false }], 'u2')
    expect(out).toEqual([{ type: 'tool_result', toolUseId: 't1', isError: false, preview: 'ok' }])
  })
  test('string content is treated as one text block', () => {
    expect(normalizeBlocks('user', 'hi', 'u3')).toEqual([{ type: 'user_text', uuid: 'u3', text: 'hi' }])
  })
  test('assistant text and tool_use blocks each yield an event; thinking is dropped', () => {
    const out = normalizeBlocks('assistant', [
      { type: 'thinking', thinking: 'hmm' },
      { type: 'text', text: 'Running tests' },
      { type: 'tool_use', id: 't9', name: 'Bash', input: { command: 'npm   test' } },
    ], 'a1')
    expect(out).toEqual([
      { type: 'assistant_text', uuid: 'a1#1', text: 'Running tests' },
      { type: 'tool_use', uuid: 'a1#2', toolUseId: 't9', tool: 'Bash', summary: 'Bash: npm test' },
    ])
  })
  test('blank assistant text is skipped', () => {
    expect(normalizeBlocks('assistant', [{ type: 'text', text: '  \n' }], 'a2')).toEqual([])
  })
  test('tool_result preview is cut to 400 chars and keeps is_error', () => {
    const long = 'x'.repeat(1000)
    const [ev] = normalizeBlocks('user', [{ type: 'tool_result', tool_use_id: 't2', content: long, is_error: true }], 'u4')
    expect(ev).toEqual({ type: 'tool_result', toolUseId: 't2', isError: true, preview: 'x'.repeat(399) + '…' })
  })
})

describe('summarize and cap', () => {
  test('known tools show their key argument', () => {
    expect(summarize('Edit', { file_path: 'src/a.kt', old_string: 'x' })).toBe('Edit: src/a.kt')
    expect(summarize('Read', { file_path: '/x/y' })).toBe('Read: /x/y')
  })
  test('other tools show their first string argument, or just the name', () => {
    expect(summarize('Grep', { pattern: 'TODO', path: '.' })).toBe('Grep: TODO')
    expect(summarize('TodoWrite', { todos: [] })).toBe('TodoWrite')
  })
  test('summaries are cut to 120 chars', () => {
    expect(summarize('Bash', { command: 'a'.repeat(300) }).length).toBe(120)
  })
  test('cap truncates past 64 KB with a marker', () => {
    expect(cap('a'.repeat(MAX_TEXT + 5))).toBe('a'.repeat(MAX_TEXT) + '…[truncated]')
    expect(cap('short')).toBe('short')
  })
})

describe('normalizeSnapshot', () => {
  test('uses snap-<index> uuids and the same block rules', () => {
    const out = normalizeSnapshot([
      { role: 'user', content: [{ type: 'text', text: 'fix it' }] },
      { role: 'assistant', content: [{ type: 'text', text: 'done' }] },
    ])
    expect(out).toEqual([
      { type: 'user_text', uuid: 'snap-0', text: 'fix it' },
      { type: 'assistant_text', uuid: 'snap-1#0', text: 'done' },
    ])
  })
  test('keeps only the last 500 events', () => {
    const msgs = Array.from({ length: 600 }, (_, i) => ({ role: 'user' as const, content: `m${i}` }))
    const out = normalizeSnapshot(msgs)
    expect(out.length).toBe(500)
    expect(out[0]).toEqual({ type: 'user_text', uuid: 'snap-100', text: 'm100' })
  })
})
