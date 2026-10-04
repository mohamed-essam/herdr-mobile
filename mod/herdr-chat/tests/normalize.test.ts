import { describe, expect, test } from 'claude-code/testing'
import { cap, normalizeBlocks as normalizeAll, normalizeSnapshot as snapshotAll, shouldForward, summarize, MAX_TEXT, SNAPSHOT_BYTES } from '../hooks/normalize'

// Most tests look at the events only; the image side channel has its own.
const normalizeBlocks = (...a: Parameters<typeof normalizeAll>) => normalizeAll(...a).events
const normalizeSnapshot = (...a: Parameters<typeof snapshotAll>) => snapshotAll(...a).events

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

describe('normalizeBlocks: injected context (L1)', () => {
  const reminders =
    '<system-reminder>\nSessionStart:clear hook success: ok\n</system-reminder>\n' +
    '<system-reminder>\nAs you answer the user\'s questions, use this context.\n</system-reminder>\n\n'
  test('a reminder-only user message yields no user_text', () => {
    expect(normalizeBlocks('user', [{ type: 'text', text: reminders }], 'u1')).toEqual([])
  })
  test('reminders + real text yields just the real text', () => {
    const out = normalizeBlocks('user', [{ type: 'text', text: reminders + 'Reply with exactly: before-restart' }], 'u2')
    expect(out).toEqual([{ type: 'user_text', uuid: 'u2', text: 'Reply with exactly: before-restart' }])
  })
  test('reminders are stripped from every text block, string content too', () => {
    const out = normalizeBlocks('user', 'a <system-reminder>x</system-reminder>b', 'u3')
    expect(out).toEqual([{ type: 'user_text', uuid: 'u3', text: 'a b' }])
  })
  test('text without reminders is unchanged', () => {
    expect(normalizeBlocks('user', [{ type: 'text', text: '  keep <b>me</b>\n' }], 'u4')).toEqual([
      { type: 'user_text', uuid: 'u4', text: 'keep <b>me</b>' },
    ])
  })
  test('assistant text is not touched', () => {
    const t = '<system-reminder>x</system-reminder> hi'
    expect(normalizeBlocks('assistant', [{ type: 'text', text: t }], 'a1')).toEqual([
      { type: 'assistant_text', uuid: 'a1#0', text: t },
    ])
  })
})

describe('normalizeBlocks: task notifications (L2)', () => {
  const notice = (status: string, summary?: string) =>
    '<task-notification>\n<task-id>bf3npqvu3</task-id>\n<tool-use-id>toolu_1</tool-use-id>\n' +
    '<output-file>/x/tasks/bf3npqvu3.output</output-file>\n' +
    (status ? `<status>${status}</status>\n` : '') +
    (summary !== undefined ? `<summary>${summary}</summary>\n` : '') +
    '</task-notification>'
  test('a notification-only user message yields one task_notice and no user_text', () => {
    const s = 'Background command "Sleep for 25 seconds" completed (exit code 0)'
    expect(normalizeBlocks('user', [{ type: 'text', text: notice('completed', s) }], 'd1')).toEqual([
      { type: 'task_notice', uuid: 'd1#0', status: 'completed', summary: s },
    ])
  })
  test('missing status/summary become empty strings', () => {
    expect(normalizeBlocks('user', notice(''), 'd2')).toEqual([{ type: 'task_notice', uuid: 'd2#0', status: '', summary: '' }])
  })
  test('text outside the notification still becomes user_text', () => {
    const out = normalizeBlocks('user', [{ type: 'text', text: notice('failed', 'boom') + '\n\nnow fix it' }], 'd3')
    expect(out).toEqual([
      { type: 'user_text', uuid: 'd3', text: 'now fix it' },
      { type: 'task_notice', uuid: 'd3#0', status: 'failed', summary: 'boom' },
    ])
  })
  test('reminders around a notification are stripped first', () => {
    const out = normalizeBlocks('user', '<system-reminder>r</system-reminder>\n' + notice('killed', 'k'), 'd4')
    expect(out).toEqual([{ type: 'task_notice', uuid: 'd4#0', status: 'killed', summary: 'k' }])
  })
  test('two notifications in one block get distinct uuids', () => {
    const out = normalizeBlocks('user', notice('completed', 'a') + '\n' + notice('completed', 'b'), 'd5')
    expect(out.map((e) => (e as { uuid: string }).uuid)).toEqual(['d5#0', 'd5#0.1'])
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
  test('caps the serialized events at 4 MB, keeping the newest', () => {
    const big = 'x'.repeat(MAX_TEXT)
    const msgs = Array.from({ length: 500 }, (_, i) => ({ role: 'user' as const, content: `${i} ${big}` }))
    const out = normalizeSnapshot(msgs)
    expect(SNAPSHOT_BYTES).toBe(4 * 1024 * 1024)
    expect(JSON.stringify(out).length).toBeLessThanOrEqual(SNAPSHOT_BYTES)
    expect(out.length).toBeGreaterThan(50)
    expect(out[out.length - 1]!.type === 'user_text' && (out[out.length - 1] as any).uuid).toBe('snap-499')
    const uuids = out.map(e => (e as any).uuid)
    expect(uuids[0]).toBe(`snap-${500 - out.length}`)
  })
  test('counts multi-byte characters by their UTF-8 size', () => {
    const big = 'é'.repeat(MAX_TEXT) // 2 bytes each in UTF-8
    const msgs = Array.from({ length: 100 }, (_, i) => ({ role: 'user' as const, content: big }))
    const out = normalizeSnapshot(msgs)
    const bytes = out.reduce((n, e) => n + JSON.stringify(e).length + JSON.stringify(e).replace(/[^é]/g, '').length + 1, 1)
    expect(bytes).toBeLessThanOrEqual(SNAPSHOT_BYTES)
    expect(out.length).toBeLessThan(33)
  })
})

describe('normalizeBlocks: local-command envelopes (M1)', () => {
  const caveat =
    '<local-command-caveat>The command below was run directly in Claude Code, not sent to you as a request.</local-command-caveat>'
  test('a caveat-only user message yields nothing', () => {
    expect(normalizeBlocks('user', [{ type: 'text', text: caveat }], 'c1')).toEqual([])
  })
  test('caveat + command-name + stdout yields nothing', () => {
    const t = `${caveat}\n<command-name>/reload-plugins</command-name>\n<command-message>reload-plugins</command-message>\n<command-args></command-args>\n<local-command-stdout>Reloaded\nok</local-command-stdout>\n<local-command-stderr></local-command-stderr>`
    expect(normalizeBlocks('user', t, 'c2')).toEqual([])
  })
  test('real text mixed with a caveat yields only the real text', () => {
    expect(normalizeBlocks('user', `${caveat}\nhello there`, 'c3')).toEqual([
      { type: 'user_text', uuid: 'c3', text: 'hello there' },
    ])
  })
  test('an unclosed tag is left as-is', () => {
    expect(normalizeBlocks('user', '<command-name>/x', 'c4')).toEqual([
      { type: 'user_text', uuid: 'c4', text: '<command-name>/x' },
    ])
  })
})

describe('normalizeBlocks: timestamps', () => {
  test('ts is set on every event when given', () => {
    const out = normalizeBlocks('assistant', [
      { type: 'text', text: 'hi' },
      { type: 'tool_use', id: 't1', name: 'Read', input: { file_path: '/a' } },
    ], 'a1', 42)
    expect(out).toEqual([
      { type: 'assistant_text', uuid: 'a1#0', text: 'hi', ts: 42 },
      { type: 'tool_use', uuid: 'a1#1', toolUseId: 't1', tool: 'Read', summary: 'Read: /a', ts: 42 },
    ])
    const user = normalizeBlocks('user', [
      { type: 'text', text: 'q<task-notification><status>done</status><summary>s</summary></task-notification>' },
      { type: 'tool_result', tool_use_id: 't1', content: 'ok' },
    ], 'u1', 7)
    expect(user.map(e => e.type)).toEqual(['user_text', 'task_notice', 'tool_result'])
    expect(user.every(e => e.ts === 7)).toBe(true)
  })
  test('ts is absent when not given', () => {
    expect('ts' in normalizeBlocks('user', 'hi', 'u1')[0]!).toBe(false)
  })
})

describe('normalizeBlocks: images', () => {
  const img = (data: string, mediaType = 'image/png') => ({ type: 'image', source: { type: 'base64', media_type: mediaType, data } })
  test('tool_result images move to the side channel with <uuid>#<block>.<n> ids', () => {
    const out = normalizeAll('user', [
      { type: 'text', text: 'context' },
      { type: 'tool_result', tool_use_id: 't1', content: [img('AAA'), { type: 'text', text: 'see the screenshot' }, img('BBB', 'image/jpeg')] },
    ], 'u1')
    expect(out.images).toEqual({
      'u1#1.0': { mediaType: 'image/png', data: 'AAA' },
      'u1#1.1': { mediaType: 'image/jpeg', data: 'BBB' },
    })
    const result = out.events.find(e => e.type === 'tool_result')
    expect(result).toEqual({ type: 'tool_result', toolUseId: 't1', isError: false, preview: 'see the screenshot', images: ['u1#1.0', 'u1#1.1'] })
  })
  test('a user message of one image yields an empty user_text referencing it', () => {
    const out = normalizeAll('user', [img('CCC')], 'u2', 5)
    expect(out.events).toEqual([{ type: 'user_text', uuid: 'u2', text: '', images: ['u2#0'], ts: 5 }])
    expect(out.images).toEqual({ 'u2#0': { mediaType: 'image/png', data: 'CCC' } })
  })
  test('user text and images share one user_text', () => {
    const out = normalizeAll('user', [{ type: 'text', text: 'look' }, img('DDD')], 'u3')
    expect(out.events).toEqual([{ type: 'user_text', uuid: 'u3', text: 'look', images: ['u3#1'] }])
  })
  test('no images: no images key and an empty map', () => {
    const out = normalizeAll('user', [{ type: 'tool_result', tool_use_id: 't2', content: 'ok' }], 'u4')
    expect(out.images).toEqual({})
    expect('images' in out.events[0]!).toBe(false)
  })
  test('non-base64 image sources are ignored', () => {
    const out = normalizeAll('user', [{ type: 'image', source: { type: 'url', url: 'https://x' } }], 'u5')
    expect(out).toEqual({ events: [], images: {} })
  })
  test('the api-form snapshot carries the images its kept events reference', () => {
    const out = snapshotAll([
      { role: 'user', content: [img('EEE')] },
      { role: 'assistant', content: [{ type: 'text', text: 'nice' }] },
    ])
    expect(out.events[0]).toEqual({ type: 'user_text', uuid: 'snap-0', text: '', images: ['snap-0#0'] })
    expect(out.images).toEqual({ 'snap-0#0': { mediaType: 'image/png', data: 'EEE' } })
  })
  test('the api-form snapshot drops images of events cut by its limit', () => {
    const msgs = [
      { role: 'user' as const, content: [img('OLD')] as unknown },
      ...Array.from({ length: 500 }, (_, i) => ({ role: 'user' as const, content: `m${i}` as unknown })),
    ]
    expect(snapshotAll(msgs).images).toEqual({})
  })
})
