# herdr-mobile Chat View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Panes running Claude Code open in a chat view on the phone (messages, tool cards, working/idle state, a send box) instead of the raw terminal, with the terminal one tap away.

**Architecture:** A Claude Code mod (`mod/herdr-chat`) runs in every Claude session inside a herdr pane. It queues normalized conversation events in memory and, once a second, POSTs them to the companion over a Unix socket (`/sync`). The response carries the phone messages waiting for that pane, which the mod submits with `$.prompt.submit`. The companion's new `chatbridge` package keeps a per-pane ring buffer, outbox and liveness flag, and relays over the existing WebSocket with new `chat_*` frames (companionProtocol 8). The app adds a pure reducer, a `ChatRepository`, a `ChatScreen`, and a `PaneScreen` that picks chat or terminal.

**Tech Stack:** Claude Code 2.1.289 hooks-module plugin (TypeScript, `claude-code` / `claude-code/testing`); Go 1.23 (`net/http` over a Unix socket, `coder/websocket`); Kotlin + Jetpack Compose (Material3, kotlinx.serialization), JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-04-herdr-mobile-chat-view-design.md`

## Global Constraints

- The mod must never delay, change or refuse a conversation row: every `session.append` hook awaits `next(e)` and returns its result unchanged.
- Hooks never do I/O. They only push onto the in-memory `pending` list; all I/O happens in the 1s sync timer.
- The mod is a no-op when `HERDR_PANE_ID` is unset.
- The sync timer never awaits `$.prompt.submit` (it resolves only when Claude goes idle); submits go through an unawaited promise chain.
- Socket path: `HERDR_MOBILE_CHAT_SOCK` if set, else `$XDG_RUNTIME_DIR/herdr-mobile/chat.sock`, else chat is disabled (no `/tmp` fallback — security ruling). Same rule in the mod and the companion. Parent dir 0700, socket 0600.
- Event types on the wire: `user_text {uuid,text}`, `assistant_text {uuid,text}`, `tool_use {uuid,toolUseId,tool,summary}`, `tool_result {toolUseId,isError,preview}`; control types `hello {sessionId,cwd}`, `snapshot {events}`, `state {state}`.
- Limits: text fields cut at 64 KB with `…[truncated]`; `preview` 400 chars; `summary` 120 chars; ring buffer 500 events; snapshot sends the last 500; outbox 20; `/sync` body 8 MB; liveness window 5 s; pending-message timeout 2 min.
- Protocol changes are additive (`companionProtocol` 7 → 8). Pane JSON gains `chat` (omitempty).
- WS error codes for `chat_send_result.error`: `no_mod`, `outbox_full`, `empty`.
- No Markdown library in the app: only fenced code blocks are split out and drawn monospace.
- Go tests run with `-race` in CI (`cd companion && go test -race ./...`). App CI runs `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` from `app/`.

## Review Focus

1. **Claude busy for minutes while the phone sends messages**: the mod must keep syncing (heartbeat) while submits wait; the pane must stay `chat: true`. Pinned by the mod test "sync keeps running while a submit is pending" (Task 2).
2. **A slow or stalled WS client**: chat updates must not block `Hub.Sync` (and so the mod's heartbeat). Pinned by the hub test "a full subscriber does not block Sync" (Task 3).
3. **The same text sent twice ("yes", "yes")**: each `user_text` confirms only one pending bubble. Pinned by the reducer test "duplicate texts confirm one pending each" (Task 8).
4. **Companion restarted mid-session**: the mod resyncs (hello + snapshot) after the outage and the app shows the conversation again under a new epoch. Pinned by the mod test "offline then recovered re-sends hello and snapshot" (Task 2) and the reducer test "snapshot replaces entries" (Task 8).
5. **Pane closed while the phone has its chat open**: the subscription channel is closed and the forwarding goroutine exits; no panic on a later cancel. Pinned by the hub test "Drop closes subscribers and cancel after Drop is safe" (Task 3).

---

## File Structure

**Mod (new)**
- `mod/.claude-plugin/marketplace.json`: local marketplace listing `herdr-chat`, so it installs from this folder.
- `mod/herdr-chat/.claude-plugin/plugin.json`: plugin manifest.
- `mod/herdr-chat/hooks/hooks.json`: points at `register.ts`.
- `mod/herdr-chat/hooks/normalize.ts`: pure functions: row filter, block normalizer, summaries, truncation, snapshot builder.
- `mod/herdr-chat/hooks/register.ts`: hooks and the sync loop.
- `mod/herdr-chat/tests/normalize.test.ts`, `mod/herdr-chat/tests/register.test.ts`: tests.
- `mod/herdr-chat/tsconfig.json`: type-check config.

**Companion**
- Create `companion/internal/chatbridge/hub.go` (per-pane state, Sync/Send/Subscribe/Tick/Drop) + `hub_test.go`.
- Create `companion/internal/chatbridge/server.go` (SocketPath, Listen, HTTP handler) + `server_test.go`.
- Modify `companion/internal/proto/proto.go` (+ test): chat frames, protocol 8.
- Modify `companion/internal/wsserver/server.go` (+ test): `ChatHub`, `chat_open/close/send`.
- Modify `companion/internal/state/store.go` (+ test): `Pane.Chat`, `SetChat`.
- Modify `companion/internal/engine/engine.go`, `companion/cmd/herdr-mobiled/main.go`: wiring, `--chat-socket`.

**App** (`app/app/src/main/java/dev/herdr/mobile/…`, tests in `app/app/src/test/java/dev/herdr/mobile/`)
- Modify `net/Protocol.kt`: `Pane.chat`, chat event types, chat frames, `ClientMsg.chat*`.
- Modify `net/CompanionClient.kt`: `sendChat`.
- Create `data/ChatModel.kt`: `ChatView`, `PendingMsg`, `ChatReducer`, `pendingLabel`.
- Create `data/ChatRepository.kt`.
- Create `ui/ChatText.kt`: `splitFences`.
- Create `ui/ChatScreen.kt`, `ui/PaneScreen.kt`.
- Modify `ui/DashboardViewModel.kt`, `ui/DashboardScreen.kt`, `ui/TerminalScreen.kt`.

**Docs**
- Modify `README.md`: installing the mod; `CHANGELOG.md`.

---

### Task 1: Mod scaffold + pure normalizer

**Files:**
- Create: `mod/.claude-plugin/marketplace.json`
- Create: `mod/herdr-chat/.claude-plugin/plugin.json`
- Create: `mod/herdr-chat/hooks/hooks.json`
- Create: `mod/herdr-chat/hooks/normalize.ts`
- Create: `mod/herdr-chat/hooks/register.ts` (empty register for now)
- Create: `mod/herdr-chat/tsconfig.json`
- Test: `mod/herdr-chat/tests/normalize.test.ts`

**Interfaces:**
- Produces (from `hooks/normalize.ts`):
  - `type ChatEvent = { type: 'user_text'; uuid: string; text: string } | { type: 'assistant_text'; uuid: string; text: string } | { type: 'tool_use'; uuid: string; toolUseId: string; tool: string; summary: string } | { type: 'tool_result'; toolUseId: string; isError: boolean; preview: string }`
  - `const MAX_TEXT = 65536, PREVIEW = 400, SUMMARY = 120, SNAPSHOT_LIMIT = 500`
  - `cap(s: string): string`, `summarize(tool: string, input: Record<string, unknown>): string`
  - `shouldForward(e: { door: string; agentId?: string; message: { isMeta?: true; role?: string } }): boolean`
  - `normalizeBlocks(role: 'user' | 'assistant', content: unknown, uuid: string): ChatEvent[]`
  - `normalizeSnapshot(messages: readonly { role: 'user' | 'assistant'; content: unknown }[]): ChatEvent[]`

- [ ] **Step 1: Write the manifests**

`mod/.claude-plugin/marketplace.json`:
```json
{
  "name": "herdr-mobile",
  "owner": { "name": "herdr-mobile" },
  "plugins": [
    { "name": "herdr-chat", "source": "./herdr-chat", "description": "Streams Claude Code conversations in herdr panes to herdr-mobile's chat view" }
  ]
}
```

`mod/herdr-chat/.claude-plugin/plugin.json`:
```json
{ "name": "herdr-chat", "version": "0.1.0", "description": "Streams Claude Code conversations in herdr panes to herdr-mobile's chat view" }
```

`mod/herdr-chat/hooks/hooks.json`:
```json
{ "modules": ["./register.ts"] }
```

`mod/herdr-chat/hooks/register.ts`:
```ts
import type { Register } from 'claude-code'

export const register: Register = () => {}
```

`mod/herdr-chat/tsconfig.json` (the header of the engine's `claude-code.d.ts` documents this shape; `.claude-plugin/types` is laid by the engine once the mod has loaded in a session):
```json
{
  "compilerOptions": {
    "target": "es2023", "lib": ["es2023"], "types": [],
    "module": "esnext", "moduleResolution": "bundler",
    "strict": true, "noUncheckedIndexedAccess": true,
    "noEmit": true, "skipLibCheck": true
  },
  "include": [".claude-plugin/types", "hooks", "tests"]
}
```

Add `mod/herdr-chat/.claude-plugin/types/` to the repo's `.gitignore` (it is engine-generated).

- [ ] **Step 2: Write the failing tests**

`mod/herdr-chat/tests/normalize.test.ts`:
```ts
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
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `claude plugin test mod/herdr-chat`
Expected: FAIL, `../hooks/normalize` cannot be resolved.

- [ ] **Step 4: Implement `hooks/normalize.ts`**

```ts
export type ChatEvent =
  | { type: 'user_text'; uuid: string; text: string }
  | { type: 'assistant_text'; uuid: string; text: string }
  | { type: 'tool_use'; uuid: string; toolUseId: string; tool: string; summary: string }
  | { type: 'tool_result'; toolUseId: string; isError: boolean; preview: string }

export const MAX_TEXT = 64 * 1024
export const PREVIEW = 400
export const SUMMARY = 120
export const SNAPSHOT_LIMIT = 500

const FORWARDED_DOORS = new Set(['prompt', 'response', 'tool-result', 'delivery'])

type Block = { type?: unknown; [k: string]: unknown }

export function cap(s: string): string {
  return s.length > MAX_TEXT ? s.slice(0, MAX_TEXT) + '…[truncated]' : s
}

function clip(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + '…' : s
}

export function summarize(tool: string, input: Record<string, unknown>): string {
  const str = (k: string) => (typeof input[k] === 'string' ? (input[k] as string) : undefined)
  let detail: string | undefined
  if (tool === 'Bash') detail = str('command')
  else if (tool === 'Edit' || tool === 'Write' || tool === 'Read') detail = str('file_path')
  else if (tool === 'NotebookEdit') detail = str('notebook_path')
  else detail = Object.values(input).find((v): v is string => typeof v === 'string')
  const one = (detail ?? '').replace(/\s+/g, ' ').trim()
  return clip(one ? `${tool}: ${one}` : tool, SUMMARY)
}

export function shouldForward(e: {
  door: string
  agentId?: string
  message: { isMeta?: true; role?: string }
}): boolean {
  return (
    !e.agentId &&
    !e.message.isMeta &&
    FORWARDED_DOORS.has(e.door) &&
    (e.message.role === 'user' || e.message.role === 'assistant')
  )
}

function resultText(content: unknown): string {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .filter((b: Block) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b: Block) => b.text as string)
    .join('\n')
}

export function normalizeBlocks(role: 'user' | 'assistant', content: unknown, uuid: string): ChatEvent[] {
  const blocks: Block[] =
    typeof content === 'string' ? [{ type: 'text', text: content }] : Array.isArray(content) ? content : []
  const out: ChatEvent[] = []
  const userTexts: string[] = []
  blocks.forEach((b, i) => {
    if (b.type === 'text' && typeof b.text === 'string') {
      if (role === 'user') userTexts.push(b.text)
      else if (b.text.trim()) out.push({ type: 'assistant_text', uuid: `${uuid}#${i}`, text: cap(b.text) })
    } else if (b.type === 'tool_use' && role === 'assistant') {
      const tool = String(b.name)
      const input = (b.input && typeof b.input === 'object' ? b.input : {}) as Record<string, unknown>
      out.push({ type: 'tool_use', uuid: `${uuid}#${i}`, toolUseId: String(b.id), tool, summary: summarize(tool, input) })
    } else if (b.type === 'tool_result') {
      out.push({
        type: 'tool_result',
        toolUseId: String(b.tool_use_id),
        isError: b.is_error === true,
        preview: clip(resultText(b.content), PREVIEW),
      })
    }
  })
  const text = userTexts.join('\n').trim()
  if (text) out.unshift({ type: 'user_text', uuid, text: cap(text) })
  return out
}

export function normalizeSnapshot(
  messages: readonly { role: 'user' | 'assistant'; content: unknown }[],
): ChatEvent[] {
  return messages.flatMap((m, i) => normalizeBlocks(m.role, m.content, `snap-${i}`)).slice(-SNAPSHOT_LIMIT)
}
```

- [ ] **Step 5: Run tests and validation**

Run: `claude plugin test mod/herdr-chat && claude plugin validate mod/herdr-chat`
Expected: all normalize tests PASS; validate reports no refusals.

- [ ] **Step 6: Commit**

```bash
git add mod .gitignore
git commit -m "feat(mod): herdr-chat plugin scaffold and conversation normalizer"
```

---

### Task 2: Mod hooks and sync loop

**Files:**
- Modify: `mod/herdr-chat/hooks/register.ts`
- Test: `mod/herdr-chat/tests/register.test.ts`

**Interfaces:**
- Consumes: `normalizeBlocks`, `normalizeSnapshot`, `shouldForward`, `ChatEvent` from Task 1.
- Produces: the wire contract the companion (Task 4) implements: `POST http://chat/sync` over the socket, body `{ paneId: string, sessionId: string, events: Array<ChatEvent | { type: 'hello'; sessionId: string; cwd: string } | { type: 'snapshot'; events: ChatEvent[] } | { type: 'state'; state: 'working' | 'idle' }> }`, response `{ messages: { id: string; text: string }[] }`.

**Test-kit notes.** `claude plugin test` runs each test with the engine's `$` and an `on` whose hooks sit *beneath* the plugin and stand for the engine. Beneath them the bottom hook throws, so every event the mod calls `next` on (`session.start`, `session.append`, `turn.start`, `turn.complete`, `session.end`) needs a test hook answering it. The test answers the mod's `$` calls by hooking the events those calls raise. Calls on `$` (`http.fetch` with `e.url`/`e.init`, `session.id`, `session.messages`, `process.run`) are *op* events whose hooks answer `{ value }` (`OpEventResult` in the d.ts). `prompt.submit` is an ordinary event answering `PromptSubmitResult` (`{ text }`). `mock.env(on, {...})` answers `$.env.get`, and `mock.clock(on)` holds the timers (`clock.advance(1000)` fires one sync). Raise engine events with `$.session.start({...})`, `$.session.append({...})`, `$.turn.start({...})`, `$.turn.complete({...})`, `$.session.end({...})`. These names and shapes are from `claude-code.d.ts` 2.1.289 (`declare module 'claude-code/testing'`, `SessionStartInput`, `SessionAppendInput`, `TurnStartInput`, `TurnCompleteFields`, `SessionEndInput`). If a call's exact argument shape differs in the installed build, read the declaration and adjust the test, not the mod's behaviour.

- [ ] **Step 1: Write the failing tests**

`mod/herdr-chat/tests/register.test.ts`:
```ts
import { describe, expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'

type Sync = { paneId: string; sessionId: string; events: { type: string; [k: string]: unknown }[] }

// Wires the world beneath the plugin: env, clock, session reads, and a fake
// companion that records each /sync body and answers with queued messages.
function world(on: On, opts: { pane?: string; down?: () => boolean } = {}) {
  mock.env(on, opts.pane === undefined ? { HERDR_PANE_ID: 'w1:p1', HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : opts.pane ? { HERDR_PANE_ID: opts.pane, HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : {})
  const clock = mock.clock(on)
  const syncs: Sync[] = []
  const outbox: { id: string; text: string }[] = []
  const submitted: string[] = []
  let submitGate: Promise<void> = Promise.resolve()
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  on('session.id', () => ({ value: 'sess-1' }))
  on('session.messages', () => ({
    value: [
      { role: 'user', content: [{ type: 'text', text: 'earlier question' }] },
      { role: 'assistant', content: [{ type: 'text', text: 'earlier answer' }] },
    ],
  }))
  on('http.fetch', ($, e) => {
    if (opts.down?.()) throw new Error('ECONNREFUSED')
    expect(e.url).toBe('http://chat/sync')
    expect(e.init?.socketPath).toBe('/s/chat.sock')
    syncs.push(JSON.parse(String(e.init?.body)))
    const messages = outbox.splice(0)
    return { value: { status: 200, ok: true, headers: {}, text: JSON.stringify({ messages }) } }
  })
  on('prompt.submit', async ($, e) => {
    await submitGate
    submitted.push(e.text)
    return { text: e.text }
  })
  return {
    clock, syncs, outbox, submitted,
    hold() { let release!: () => void; submitGate = new Promise(r => (release = r)); return release },
    all: () => syncs.flatMap(s => s.events),
  }
}

const start = ($: any) => $.session.start({ cwd: '/repo', surface: 'terminal', isInteractive: true })

describe('herdr-chat', () => {
  test('first sync carries hello then a snapshot of the history', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(1000)
    expect(w.syncs[0]?.paneId).toBe('w1:p1')
    expect(w.syncs[0]?.sessionId).toBe('sess-1')
    const [hello, snap] = w.syncs[0]!.events
    expect(hello).toEqual({ type: 'hello', sessionId: 'sess-1', cwd: '/repo' })
    expect(snap?.type).toBe('snapshot')
    expect((snap as any).events).toEqual([
      { type: 'user_text', uuid: 'snap-0', text: 'earlier question' },
      { type: 'assistant_text', uuid: 'snap-1#0', text: 'earlier answer' },
    ])
  })

  test('does nothing without HERDR_PANE_ID', async ($, on) => {
    const w = world(on, { pane: '' })
    await start($)
    await w.clock.advance(3000)
    expect(w.syncs.length).toBe(0)
  })

  test('forwarded rows are queued in order; the stored row is returned unchanged', async ($, on) => {
    const w = world(on)
    on('session.append', ($, e) => ({ message: e.message, uuid: e.uuid }))
    await start($)
    await w.clock.advance(1000)
    const r = await $.session.append({ door: 'prompt', uuid: 'u1', origin: { kind: 'user' } as never, message: { type: 'user', role: 'user', content: [{ type: 'text', text: 'run tests' }] } })
    expect(r.uuid).toBe('u1')
    await $.session.append({ door: 'response', uuid: 'a1', origin: { kind: 'model', model: 'm' }, message: { type: 'assistant', role: 'assistant', content: [{ type: 'text', text: 'ok' }] } })
    await $.session.append({ door: 'attachment', uuid: 'x1', origin: { kind: 'model', model: 'm' }, message: { type: 'attachment', content: [] } })
    await $.session.append({ door: 'response', uuid: 's1', agentId: 'sub', origin: { kind: 'model', model: 'm' }, message: { type: 'assistant', role: 'assistant', content: [{ type: 'text', text: 'subagent' }] } })
    await w.clock.advance(1000)
    expect(w.syncs[1]?.events).toEqual([
      { type: 'user_text', uuid: 'u1', text: 'run tests' },
      { type: 'assistant_text', uuid: 'a1#0', text: 'ok' },
    ])
  })

  test('turn start and main-thread completion queue working then idle', async ($, on) => {
    const w = world(on)
    on('turn.start', ($, e) => ({ turnId: e.turnId }))
    on('turn.complete', () => ({ text: 'answer' }))
    await start($)
    await $.turn.start({ text: 'hi', turnId: 't1' })
    await $.turn.complete({ answer: 'a', durationMs: 1, isAborted: false, turnId: 't1', agentId: 'sub', reason: 'completed' } as never)
    await $.turn.complete({ answer: 'a', durationMs: 1, isAborted: false, turnId: 't1', reason: 'completed' } as never)
    await w.clock.advance(1000)
    const states = w.all().filter(e => e.type === 'state')
    expect(states).toEqual([{ type: 'state', state: 'working' }, { type: 'state', state: 'idle' }])
  })

  test('outbox messages are submitted as the user, in order', async ($, on) => {
    const w = world(on)
    await start($)
    w.outbox.push({ id: 'm1', text: 'first' }, { id: 'm2', text: 'second' })
    await w.clock.advance(1000)
    await w.clock.settle()
    expect(w.submitted).toEqual(['first', 'second'])
  })

  test('sync keeps running while a submit is pending', async ($, on) => {
    const w = world(on)
    await start($)
    const release = w.hold()
    w.outbox.push({ id: 'm1', text: 'while busy' })
    await w.clock.advance(1000)
    await w.clock.advance(1000)
    await w.clock.advance(1000)
    expect(w.syncs.length).toBe(3)
    expect(w.submitted).toEqual([])
    release()
    await w.clock.settle()
    expect(w.submitted).toEqual(['while busy'])
  })

  test('offline then recovered re-sends hello and snapshot', async ($, on) => {
    let down = false
    const w = world(on, { down: () => down })
    await start($)
    await w.clock.advance(1000) // initial hello+snapshot
    down = true
    await w.clock.advance(1000) // fails, batch dropped
    down = false
    await w.clock.advance(1000) // empty probe succeeds -> queue resync
    await w.clock.advance(1000) // resync goes out
    const last = w.syncs[w.syncs.length - 1]!.events.map(e => e.type)
    expect(last).toEqual(['hello', 'snapshot'])
  })

  test('/clear re-sends hello and snapshot', async ($, on) => {
    const w = world(on)
    on('session.end', ($, e) => ({ sessionId: e.sessionId }))
    await start($)
    await w.clock.advance(1000)
    await $.session.end({ reason: 'clear', sessionId: 'sess-1', resume: undefined as never })
    await w.clock.advance(1000)
    expect(w.syncs[1]?.events.map(e => e.type)).toEqual(['hello', 'snapshot'])
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `claude plugin test mod/herdr-chat`
Expected: the `herdr-chat` tests FAIL (no syncs recorded); the normalize tests still PASS.

- [ ] **Step 3: Implement `hooks/register.ts`**

```ts
import type { EngineInterface, Register } from 'claude-code'
import { normalizeBlocks, normalizeSnapshot, shouldForward, type ChatEvent } from './normalize'

type Control =
  | { type: 'hello'; sessionId: string; cwd: string }
  | { type: 'snapshot'; events: ChatEvent[] }
  | { type: 'state'; state: 'working' | 'idle' }
type Outgoing = ChatEvent | Control

const SYNC_MS = 1000

export const register: Register = on => {
  let paneId: string | undefined
  let sessionId = ''
  let cwd = ''
  let socketPath = ''
  let pending: Outgoing[] = []
  let offline = false
  let inFlight = false
  let submitChain: Promise<unknown> = Promise.resolve()

  async function resolveSocket($: EngineInterface): Promise<string> {
    const explicit = await $.env.get('HERDR_MOBILE_CHAT_SOCK')
    if (explicit) return explicit
    const runtime = await $.env.get('XDG_RUNTIME_DIR')
    if (runtime) return `${runtime}/herdr-mobile/chat.sock`
    const id = await $.process.run(['id', '-u'])
    return `/tmp/herdr-mobile-${id.stdout.trim()}/chat.sock`
  }

  async function queueResync($: EngineInterface) {
    sessionId = await $.session.id()
    const history = await $.session.messages({ as: 'api' })
    const events = Array.isArray(history) ? normalizeSnapshot(history) : []
    pending.push({ type: 'hello', sessionId, cwd }, { type: 'snapshot', events })
  }

  async function tick($: EngineInterface) {
    if (inFlight || !paneId) return
    inFlight = true
    const batch = offline ? [] : pending
    if (!offline) pending = []
    try {
      const res = await $.http.fetch('http://chat/sync', {
        method: 'POST',
        socketPath,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ paneId, sessionId, events: batch }),
      })
      if (!res.ok) throw new Error(`sync ${res.status}`)
      if (offline) {
        offline = false
        pending = []
        await queueResync($)
      }
      const { messages = [] } = JSON.parse(res.text) as { messages?: { id: string; text: string }[] }
      for (const m of messages) {
        // Not awaited: submit resolves only when Claude goes idle, and this
        // loop is the heartbeat that keeps the pane chat-capable meanwhile.
        submitChain = submitChain.then(() => $.prompt.submit({ text: m.text, asUser: true })).catch(() => {})
      }
    } catch {
      offline = true
      pending = []
    } finally {
      inFlight = false
    }
  }

  on('session.start', async ($, e, next) => {
    const r = await next(e)
    paneId = await $.env.get('HERDR_PANE_ID')
    if (!paneId) return r
    cwd = e.cwd
    socketPath = await resolveSocket($)
    await queueResync($)
    $.clock.every(SYNC_MS, () => void tick($))
    return r
  })

  on('session.end', async ($, e, next) => {
    const r = await next(e)
    if (paneId && e.reason === 'clear') {
      pending = []
      await queueResync($)
    }
    return r
  })

  on('session.append', async ($, e, next) => {
    const r = await next(e)
    if (paneId && shouldForward(e) && (e.message.role === 'user' || e.message.role === 'assistant')) {
      pending.push(...normalizeBlocks(e.message.role, r.message?.content ?? e.message.content, e.uuid))
    }
    return r
  })

  on('turn.start', async ($, e, next) => {
    const r = await next(e)
    if (paneId) pending.push({ type: 'state', state: 'working' })
    return r
  })

  on('turn.complete', async ($, e, next) => {
    const r = await next(e)
    if (paneId && !e.agentId) pending.push({ type: 'state', state: 'idle' })
    return r
  })
}
```

- [ ] **Step 4: Run tests, validate, type-check**

Run: `claude plugin test mod/herdr-chat && claude plugin validate mod/herdr-chat`
Expected: all tests PASS; validate lists hooks on `session.start`, `session.end`, `session.append`, `turn.start`, `turn.complete` and env reads `HERDR_PANE_ID`, `HERDR_MOBILE_CHAT_SOCK`, `XDG_RUNTIME_DIR`, with no refusals.

If `mod/herdr-chat/.claude-plugin/types/` exists (the mod has been loaded in a session), also run `tsc -p mod/herdr-chat` and expect no errors. Otherwise, point a temporary tsconfig in the scratchpad at the engine's `claude-code.d.ts` (path printed by the plugin-authoring skill) plus `mod/herdr-chat/hooks` and `tests`, and run `tsc -p` on it.

- [ ] **Step 5: Commit**

```bash
git add mod/herdr-chat
git commit -m "feat(mod): queue conversation events and sync them to the companion each second"
```

---

### Task 3: Companion chatbridge hub

**Files:**
- Create: `companion/internal/chatbridge/hub.go`
- Test: `companion/internal/chatbridge/hub_test.go`

**Interfaces:**
- Produces:
  - `const RingCap = 500; OutboxCap = 20; LiveWindow = 5 * time.Second`
  - `var ErrNoMod, ErrOutboxFull, ErrEmpty error` with messages `"no_mod"`, `"outbox_full"`, `"empty"`
  - `type Entry struct { Seq int \`json:"seq"\`; Event json.RawMessage \`json:"event"\` }`
  - `type Snapshot struct { PaneID string; Epoch int; State string; Events []Entry }`
  - `type Update struct { Kind string /* "event"|"state"|"snapshot" */; Epoch int; Entry Entry; State string; Snapshot Snapshot }`
  - `type OutMsg struct { ID string \`json:"id"\`; Text string \`json:"text"\` }`
  - `func NewHub(now func() time.Time) *Hub` (nil → `time.Now`)
  - `func (h *Hub) SetOnLiveness(fn func(paneID string, live bool))`
  - `func (h *Hub) Sync(paneID, sessionID string, events []json.RawMessage) []OutMsg` (never nil)
  - `func (h *Hub) Send(paneID, text string) error`
  - `func (h *Hub) Live(paneID string) bool`
  - `func (h *Hub) Subscribe(paneID string) (Snapshot, <-chan Update, func())`
  - `func (h *Hub) Tick()`
  - `func (h *Hub) Drop(paneID string)`

- [ ] **Step 1: Write the failing tests**

`companion/internal/chatbridge/hub_test.go`:
```go
package chatbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"
)

func raw(s string) json.RawMessage { return json.RawMessage(s) }

func userText(n int) json.RawMessage {
	return raw(fmt.Sprintf(`{"type":"user_text","uuid":"u%d","text":"m%d"}`, n, n))
}

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) now() time.Time      { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *fakeClock) add(d time.Duration) { c.mu.Lock(); c.t = c.t.Add(d); c.mu.Unlock() }

func TestSnapshotResetsEpochAndSeq(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s1", []json.RawMessage{raw(`{"type":"hello","sessionId":"s1","cwd":"/x"}`), raw(`{"type":"snapshot","events":[` + string(userText(1)) + `,{"type":"bogus"}]}`)})
	snap, _, cancel := h.Subscribe("p")
	defer cancel()
	if snap.Epoch != 2 || len(snap.Events) != 1 || snap.Events[0].Seq != 1 {
		t.Fatalf("after first snapshot: %+v", snap)
	}
	h.Sync("p", "s1", []json.RawMessage{userText(2)})
	h.Sync("p", "s2", []json.RawMessage{raw(`{"type":"snapshot","events":[]}`)})
	snap2, _, cancel2 := h.Subscribe("p")
	defer cancel2()
	if snap2.Epoch != 3 || len(snap2.Events) != 0 {
		t.Fatalf("after second snapshot: %+v", snap2)
	}
}

func TestEventsGetMonotonicSeqAndFanOut(t *testing.T) {
	h := NewHub(nil)
	_, ch, cancel := h.Subscribe("p")
	defer cancel()
	h.Sync("p", "s", []json.RawMessage{userText(1), userText(2), raw(`{"type":"state","state":"working"}`)})
	u1, u2, u3 := <-ch, <-ch, <-ch
	if u1.Kind != "event" || u1.Entry.Seq != 1 || u2.Entry.Seq != 2 {
		t.Fatalf("bad event updates: %+v %+v", u1, u2)
	}
	if u3.Kind != "state" || u3.State != "working" {
		t.Fatalf("bad state update: %+v", u3)
	}
}

func TestInvalidStateIgnored(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"state","state":"exploded"}`), raw(`not json`)})
	snap, _, cancel := h.Subscribe("p")
	defer cancel()
	if snap.State != "idle" {
		t.Fatalf("state = %q, want idle", snap.State)
	}
}

func TestRingCap(t *testing.T) {
	h := NewHub(nil)
	evs := make([]json.RawMessage, 0, RingCap+10)
	for i := 1; i <= RingCap+10; i++ {
		evs = append(evs, userText(i))
	}
	h.Sync("p", "s", evs)
	snap, _, cancel := h.Subscribe("p")
	defer cancel()
	if len(snap.Events) != RingCap || snap.Events[0].Seq != 11 {
		t.Fatalf("ring: len=%d first=%d", len(snap.Events), snap.Events[0].Seq)
	}
}

func TestSendRequiresLivePaneAndDrains(t *testing.T) {
	h := NewHub(nil)
	if err := h.Send("p", "hi"); !errors.Is(err, ErrNoMod) {
		t.Fatalf("send to unknown pane: %v", err)
	}
	h.Sync("p", "s", nil)
	if err := h.Send("p", "  "); !errors.Is(err, ErrEmpty) {
		t.Fatalf("blank send: %v", err)
	}
	if err := h.Send("p", "hi"); err != nil {
		t.Fatal(err)
	}
	out := h.Sync("p", "s", nil)
	if len(out) != 1 || out[0].Text != "hi" || out[0].ID == "" {
		t.Fatalf("drain: %+v", out)
	}
	if again := h.Sync("p", "s", nil); len(again) != 0 || again == nil {
		t.Fatalf("second drain should be empty non-nil, got %#v", again)
	}
}

func TestOutboxCap(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", nil)
	for i := 0; i < OutboxCap; i++ {
		if err := h.Send("p", "x"); err != nil {
			t.Fatal(err)
		}
	}
	if err := h.Send("p", "x"); !errors.Is(err, ErrOutboxFull) {
		t.Fatalf("over cap: %v", err)
	}
}

func TestLivenessFlips(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	var mu sync.Mutex
	var flips []string
	h.SetOnLiveness(func(id string, live bool) { mu.Lock(); flips = append(flips, fmt.Sprintf("%s=%v", id, live)); mu.Unlock() })
	h.Sync("p", "s", nil)
	h.Sync("p", "s", nil) // already live: no second flip
	c.add(4 * time.Second)
	h.Tick()
	if !h.Live("p") {
		t.Fatal("should still be live at 4s")
	}
	c.add(time.Second)
	h.Tick()
	if h.Live("p") {
		t.Fatal("should be dead at 5s")
	}
	mu.Lock()
	defer mu.Unlock()
	if fmt.Sprint(flips) != "[p=true p=false]" {
		t.Fatalf("flips = %v", flips)
	}
}

func TestFullSubscriberDoesNotBlockSync(t *testing.T) {
	h := NewHub(nil)
	_, _, cancel := h.Subscribe("p") // never read
	defer cancel()
	done := make(chan struct{})
	go func() {
		for i := 0; i < subBuffer*3; i++ {
			h.Sync("p", "s", []json.RawMessage{userText(i)})
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Sync blocked on a full subscriber")
	}
}

func TestDropClosesSubscribersAndCancelAfterDropIsSafe(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", nil)
	_, ch, cancel := h.Subscribe("p")
	h.Drop("p")
	if _, ok := <-ch; ok {
		t.Fatal("channel should be closed after Drop")
	}
	cancel() // must not panic (double close)
	if h.Live("p") {
		t.Fatal("dropped pane should not be live")
	}
}

func TestSnapshotUpdateFansOut(t *testing.T) {
	h := NewHub(nil)
	_, ch, cancel := h.Subscribe("p")
	defer cancel()
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot","events":[` + string(userText(1)) + `]}`)})
	u := <-ch
	if u.Kind != "snapshot" || u.Snapshot.PaneID != "p" || len(u.Snapshot.Events) != 1 {
		t.Fatalf("snapshot update: %+v", u)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd companion && go test ./internal/chatbridge/`
Expected: FAIL, undefined `NewHub` etc.

- [ ] **Step 3: Implement `hub.go`**

```go
// Package chatbridge relays Claude Code conversations from the herdr-chat mod
// (one per Claude session in a herdr pane) to the app's chat view, and carries
// messages typed on the phone back to the mod.
package chatbridge

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	RingCap    = 500
	OutboxCap  = 20
	LiveWindow = 5 * time.Second
	subBuffer  = 256
)

var (
	ErrNoMod      = errors.New("no_mod")
	ErrOutboxFull = errors.New("outbox_full")
	ErrEmpty      = errors.New("empty")
)

type Entry struct {
	Seq   int             `json:"seq"`
	Event json.RawMessage `json:"event"`
}

type Snapshot struct {
	PaneID string
	Epoch  int
	State  string
	Events []Entry
}

// Update is one change fanned out to a pane's subscribers.
type Update struct {
	Kind     string // "event" | "state" | "snapshot"
	Epoch    int
	Entry    Entry
	State    string
	Snapshot Snapshot
}

type OutMsg struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

type pane struct {
	epoch, seq int
	sessionID  string
	state      string
	lastSeen   time.Time
	live       bool
	events     []Entry
	outbox     []OutMsg
	subs       map[int]chan Update
}

type Hub struct {
	mu         sync.Mutex
	panes      map[string]*pane
	now        func() time.Time
	onLiveness func(paneID string, live bool)
	nextSub    int
	nextMsg    int
}

func NewHub(now func() time.Time) *Hub {
	if now == nil {
		now = time.Now
	}
	return &Hub{panes: map[string]*pane{}, now: now, onLiveness: func(string, bool) {}}
}

// SetOnLiveness registers the callback for chat-capable flips. It is called
// without the hub's lock held. Set it before the hub is used.
func (h *Hub) SetOnLiveness(fn func(paneID string, live bool)) { h.onLiveness = fn }

func (h *Hub) get(id string) *pane {
	p := h.panes[id]
	if p == nil {
		p = &pane{epoch: 1, state: "idle", subs: map[int]chan Update{}}
		h.panes[id] = p
	}
	return p
}

// Sync applies the mod's queued events in order, records the heartbeat and
// drains the pane's outbox. The result is never nil.
func (h *Hub) Sync(paneID, sessionID string, events []json.RawMessage) []OutMsg {
	h.mu.Lock()
	p := h.get(paneID)
	p.lastSeen = h.now()
	flipped := !p.live
	p.live = true
	for _, ev := range events {
		p.apply(paneID, sessionID, ev)
	}
	out := p.outbox
	p.outbox = nil
	h.mu.Unlock()
	if flipped {
		h.onLiveness(paneID, true)
	}
	if out == nil {
		out = []OutMsg{}
	}
	return out
}

func isChatEvent(t string) bool {
	switch t {
	case "user_text", "assistant_text", "tool_use", "tool_result":
		return true
	}
	return false
}

func (p *pane) apply(paneID, sessionID string, raw json.RawMessage) {
	var head struct {
		Type   string            `json:"type"`
		State  string            `json:"state"`
		Events []json.RawMessage `json:"events"`
	}
	if json.Unmarshal(raw, &head) != nil {
		return
	}
	switch {
	case head.Type == "hello":
		p.sessionID = sessionID
	case head.Type == "snapshot":
		p.epoch++
		p.seq = 0
		p.events = nil
		for _, ev := range head.Events {
			var inner struct {
				Type string `json:"type"`
			}
			if json.Unmarshal(ev, &inner) == nil && isChatEvent(inner.Type) {
				p.push(ev)
			}
		}
		p.fan(Update{Kind: "snapshot", Epoch: p.epoch, Snapshot: p.snapshot(paneID)})
	case head.Type == "state":
		if head.State != "working" && head.State != "idle" {
			return
		}
		p.state = head.State
		p.fan(Update{Kind: "state", Epoch: p.epoch, State: p.state})
	case isChatEvent(head.Type):
		e := p.push(raw)
		p.fan(Update{Kind: "event", Epoch: p.epoch, Entry: e})
	}
}

func (p *pane) push(raw json.RawMessage) Entry {
	p.seq++
	e := Entry{Seq: p.seq, Event: append(json.RawMessage(nil), raw...)}
	p.events = append(p.events, e)
	if len(p.events) > RingCap {
		p.events = append([]Entry(nil), p.events[len(p.events)-RingCap:]...)
	}
	return e
}

func (p *pane) snapshot(paneID string) Snapshot {
	ev := make([]Entry, len(p.events))
	copy(ev, p.events)
	return Snapshot{PaneID: paneID, Epoch: p.epoch, State: p.state, Events: ev}
}

// fan delivers u to every subscriber without blocking: a subscriber whose
// buffer is full misses the update rather than stalling the mod's heartbeat.
func (p *pane) fan(u Update) {
	for _, ch := range p.subs {
		select {
		case ch <- u:
		default:
		}
	}
}

// Send queues text for the pane's mod. The pane must be chat-capable.
func (h *Hub) Send(paneID, text string) error {
	if strings.TrimSpace(text) == "" {
		return ErrEmpty
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	p := h.panes[paneID]
	if p == nil || !p.live {
		return ErrNoMod
	}
	if len(p.outbox) >= OutboxCap {
		return ErrOutboxFull
	}
	h.nextMsg++
	p.outbox = append(p.outbox, OutMsg{ID: "m" + strconv.Itoa(h.nextMsg), Text: text})
	return nil
}

func (h *Hub) Live(paneID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	p := h.panes[paneID]
	return p != nil && p.live
}

// Subscribe returns the pane's current snapshot and a channel of later
// updates. cancel closes the channel; it is safe to call more than once and
// after Drop.
func (h *Hub) Subscribe(paneID string) (Snapshot, <-chan Update, func()) {
	h.mu.Lock()
	p := h.get(paneID)
	h.nextSub++
	id := h.nextSub
	ch := make(chan Update, subBuffer)
	p.subs[id] = ch
	snap := p.snapshot(paneID)
	h.mu.Unlock()
	var once sync.Once
	cancel := func() {
		once.Do(func() {
			h.mu.Lock()
			defer h.mu.Unlock()
			if q := h.panes[paneID]; q != nil {
				if c, ok := q.subs[id]; ok {
					delete(q.subs, id)
					close(c)
				}
			}
		})
	}
	return snap, ch, cancel
}

// Tick expires panes whose mod has not synced within LiveWindow.
func (h *Hub) Tick() {
	now := h.now()
	var dead []string
	h.mu.Lock()
	for id, p := range h.panes {
		if p.live && now.Sub(p.lastSeen) >= LiveWindow {
			p.live = false
			dead = append(dead, id)
		}
	}
	h.mu.Unlock()
	for _, id := range dead {
		h.onLiveness(id, false)
	}
}

// Drop forgets a pane that herdr no longer reports, closing its subscribers.
func (h *Hub) Drop(paneID string) {
	h.mu.Lock()
	p := h.panes[paneID]
	delete(h.panes, paneID)
	h.mu.Unlock()
	if p == nil {
		return
	}
	for id, ch := range p.subs {
		delete(p.subs, id)
		close(ch)
	}
}
```

Note on `Drop`: the subscriber's `cancel` looks the pane up in `h.panes`. After `Drop` the pane is gone (or replaced by a fresh one without that sub id), so `cancel` finds nothing and never closes twice.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd companion && go test -race ./internal/chatbridge/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add companion/internal/chatbridge
git commit -m "feat(companion): chatbridge hub with per-pane buffer, outbox and liveness"
```

---

### Task 4: Companion chatbridge Unix-socket server

**Files:**
- Create: `companion/internal/chatbridge/server.go`
- Test: `companion/internal/chatbridge/server_test.go`

**Interfaces:**
- Consumes: `Hub.Sync` from Task 3.
- Produces:
  - `func SocketPath() string`
  - `func Listen(path string) (net.Listener, error)`
  - `func (h *Hub) Handler() http.Handler` serving `POST /sync`

- [ ] **Step 1: Write the failing tests**

`companion/internal/chatbridge/server_test.go`:
```go
package chatbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func unixClient(path string) *http.Client {
	return &http.Client{Transport: &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", path)
		},
	}}
}

func serve(t *testing.T, h *Hub) (string, *http.Client) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "sub", "chat.sock")
	l, err := Listen(path)
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: h.Handler()}
	go srv.Serve(l)
	t.Cleanup(func() { srv.Close() })
	return path, unixClient(path)
}

func TestSocketModesAndStaleRemoval(t *testing.T) {
	path := filepath.Join(t.TempDir(), "d", "chat.sock")
	os.MkdirAll(filepath.Dir(path), 0o700)
	os.WriteFile(path, []byte("stale"), 0o600)
	l, err := Listen(path)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	fi, _ := os.Stat(path)
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("socket mode %v", fi.Mode().Perm())
	}
	di, _ := os.Stat(filepath.Dir(path))
	if di.Mode().Perm() != 0o700 {
		t.Fatalf("dir mode %v", di.Mode().Perm())
	}
}

func TestSyncRoundTrip(t *testing.T) {
	h := NewHub(nil)
	_, c := serve(t, h)
	body := `{"paneId":"w1:p1","sessionId":"s","events":[{"type":"user_text","uuid":"u","text":"hi"}]}`
	res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 200 {
		t.Fatalf("status %d", res.StatusCode)
	}
	if !h.Live("w1:p1") {
		t.Fatal("sync should mark the pane live")
	}
	h.Send("w1:p1", "from phone")
	res, _ = c.Post("http://chat/sync", "application/json", strings.NewReader(`{"paneId":"w1:p1","sessionId":"s","events":[]}`))
	var got struct{ Messages []OutMsg }
	json.NewDecoder(res.Body).Decode(&got)
	if len(got.Messages) != 1 || got.Messages[0].Text != "from phone" {
		t.Fatalf("messages = %+v", got.Messages)
	}
}

func TestSyncRejectsBadRequests(t *testing.T) {
	_, c := serve(t, NewHub(nil))
	for _, body := range []string{`not json`, `{"sessionId":"s","events":[]}`} {
		res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != 400 {
			t.Fatalf("body %q: status %d", body, res.StatusCode)
		}
	}
	res, _ := c.Get("http://chat/sync")
	if res.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("GET status %d", res.StatusCode)
	}
}

func TestSyncBodyLimit(t *testing.T) {
	_, c := serve(t, NewHub(nil))
	big := bytes.Repeat([]byte("a"), maxBody+1)
	body := `{"paneId":"p","sessionId":"s","events":[{"type":"user_text","uuid":"u","text":"` + string(big) + `"}]}`
	res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 400 {
		t.Fatalf("oversized body: status %d", res.StatusCode)
	}
}

func TestSocketPathRules(t *testing.T) {
	t.Setenv("HERDR_MOBILE_CHAT_SOCK", "/explicit.sock")
	if SocketPath() != "/explicit.sock" {
		t.Fatal(SocketPath())
	}
	t.Setenv("HERDR_MOBILE_CHAT_SOCK", "")
	t.Setenv("XDG_RUNTIME_DIR", "/run/user/7")
	if SocketPath() != "/run/user/7/herdr-mobile/chat.sock" {
		t.Fatal(SocketPath())
	}
	t.Setenv("XDG_RUNTIME_DIR", "")
	if !strings.HasPrefix(SocketPath(), "/tmp/herdr-mobile-") {
		t.Fatal(SocketPath())
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd companion && go test ./internal/chatbridge/`
Expected: FAIL, undefined `Listen`, `SocketPath`, `maxBody`.

- [ ] **Step 3: Implement `server.go`**

```go
package chatbridge

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
)

const maxBody = 8 << 20

// SocketPath is where the companion listens and the mod connects:
// HERDR_MOBILE_CHAT_SOCK, else $XDG_RUNTIME_DIR/herdr-mobile/chat.sock,
// else /tmp/herdr-mobile-<uid>/chat.sock. The mod uses the same rule.
func SocketPath() string {
	if p := os.Getenv("HERDR_MOBILE_CHAT_SOCK"); p != "" {
		return p
	}
	if d := os.Getenv("XDG_RUNTIME_DIR"); d != "" {
		return filepath.Join(d, "herdr-mobile", "chat.sock")
	}
	return filepath.Join("/tmp", fmt.Sprintf("herdr-mobile-%d", os.Getuid()), "chat.sock")
}

// Listen creates the socket's directory (0700), removes a stale socket and
// listens with the socket restricted to the user (0600).
func Listen(path string) (net.Listener, error) {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return nil, err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	l, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o600); err != nil {
		l.Close()
		return nil, err
	}
	return l, nil
}

type syncReq struct {
	PaneID    string            `json:"paneId"`
	SessionID string            `json:"sessionId"`
	Events    []json.RawMessage `json:"events"`
}

// Handler serves the mod's one endpoint, POST /sync.
func (h *Hub) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /sync", func(w http.ResponseWriter, r *http.Request) {
		var req syncReq
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody)).Decode(&req); err != nil || req.PaneID == "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		msgs := h.Sync(req.PaneID, req.SessionID, req.Events)
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"messages": msgs})
	})
	return mux
}
```

Note: the `/tmp` fallback dir may already exist with other permissions, so `Listen` re-applies 0700 explicitly. Only the dir's owner can chmod it, so if another user created it first, `Listen` fails rather than serving inside a dir they control.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd companion && go test -race ./internal/chatbridge/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add companion/internal/chatbridge
git commit -m "feat(companion): serve the mod's /sync endpoint on a private Unix socket"
```

---

### Task 5: Protocol frames + WebSocket chat handling

**Files:**
- Modify: `companion/internal/proto/proto.go` (Welcome → 8; new chat frame builders)
- Modify: `companion/internal/proto/proto_test.go` (rename the protocol test to 8; add frame tests)
- Modify: `companion/internal/wsserver/server.go`
- Test: `companion/internal/wsserver/server_test.go`

**Interfaces:**
- Consumes: `chatbridge.Snapshot`, `chatbridge.Update`, `chatbridge.Entry`, `chatbridge.ErrNoMod`, `*chatbridge.Hub` (Tasks 3–4).
- Produces:
  - `proto.ChatSnapshot(s chatbridge.Snapshot) []byte` → `{"t":"chat_snapshot","paneId","epoch","state","events":[{"seq","event"}]}` (events never null)
  - `proto.ChatEvent(paneID string, epoch int, e chatbridge.Entry) []byte` → `{"t":"chat_event","paneId","epoch","seq","event"}`
  - `proto.ChatState(paneID, state string) []byte` → `{"t":"chat_state","paneId","state"}`
  - `proto.ChatSendResult(reqID string, ok bool, errCode string) []byte` → `{"t":"chat_send_result","reqId","ok","error"?}`
  - `wsserver.ChatHub` interface `{ Subscribe(paneID string) (chatbridge.Snapshot, <-chan chatbridge.Update, func()); Send(paneID, text string) error }`
  - `(*wsserver.Server).SetChat(h ChatHub)`
  - Client frames handled: `chat_open {paneId}`, `chat_close {paneId}`, `chat_send {reqId,paneId,text}` (all use existing `ClientMsg` fields).

- [ ] **Step 1: Write the failing proto tests**

In `companion/internal/proto/proto_test.go`, rename `TestWelcomeAdvertisesProtocol7` to `TestWelcomeAdvertisesProtocol8` and change its `7` checks to `8`. Then add (add `chatbridge` to the imports):
```go
func TestChatFrames(t *testing.T) {
	var m map[string]any
	json.Unmarshal(ChatSnapshot(chatbridge.Snapshot{PaneID: "w1:p1", Epoch: 3, State: "idle"}), &m)
	if m["t"] != "chat_snapshot" || m["epoch"].(float64) != 3 || m["events"] == nil {
		t.Fatalf("snapshot: %v", m)
	}
	json.Unmarshal(ChatEvent("w1:p1", 3, chatbridge.Entry{Seq: 7, Event: json.RawMessage(`{"type":"user_text","uuid":"u","text":"hi"}`)}), &m)
	ev := m["event"].(map[string]any)
	if m["t"] != "chat_event" || m["seq"].(float64) != 7 || ev["text"] != "hi" {
		t.Fatalf("event: %v", m)
	}
	m = nil
	json.Unmarshal(ChatSendResult("c1", false, "no_mod"), &m)
	if m["ok"] != false || m["error"] != "no_mod" {
		t.Fatalf("send result: %v", m)
	}
	m = nil
	json.Unmarshal(ChatSendResult("c2", true, ""), &m)
	if _, has := m["error"]; has {
		t.Fatalf("ok result must omit error: %v", m)
	}
}
```

- [ ] **Step 2: Write the failing wsserver tests**

Append to `companion/internal/wsserver/server_test.go` (add `"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"` to the imports):
```go
func dialChat(t *testing.T, hub *chatbridge.Hub) (*websocket.Conn, context.Context) {
	t.Helper()
	s := NewServer(AllowAll{}, &stubRPC{})
	s.SetChat(hub)
	srv := httptest.NewServer(s.Handler())
	t.Cleanup(srv.Close)
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close(websocket.StatusNormalClosure, "") })
	return c, ctx
}

func TestChatOpenSnapshotThenLiveEvents(t *testing.T) {
	hub := chatbridge.NewHub(nil)
	hub.Sync("w1:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"snapshot","events":[{"type":"user_text","uuid":"u1","text":"hi"}]}`)})
	c, ctx := dialChat(t, hub)

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	snap := readUntil(t, ctx, c, "chat_snapshot")
	if evs := snap["events"].([]any); len(evs) != 1 {
		t.Fatalf("snapshot events: %v", snap["events"])
	}
	hub.Sync("w1:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"assistant_text","uuid":"a1","text":"hello"}`), json.RawMessage(`{"type":"state","state":"working"}`)})
	ev := readUntil(t, ctx, c, "chat_event")
	if ev["seq"].(float64) != 2 || ev["paneId"] != "w1:p1" {
		t.Fatalf("chat_event: %v", ev)
	}
	st := readUntil(t, ctx, c, "chat_state")
	if st["state"] != "working" {
		t.Fatalf("chat_state: %v", st)
	}
}

func TestChatCloseStopsUpdates(t *testing.T) {
	hub := chatbridge.NewHub(nil)
	hub.Sync("w1:p1", "s", nil)
	c, ctx := dialChat(t, hub)
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	readUntil(t, ctx, c, "chat_snapshot")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_close","paneId":"w1:p1"}`))
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"ping"}`))
	readUntil(t, ctx, c, "pong") // close processed before the next sync
	hub.Sync("w1:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"user_text","uuid":"u","text":"x"}`)})
	readNoneUntil(t, ctx, c, "chat_event", 500*time.Millisecond)
}

func TestChatSendQueuesOrReportsNoMod(t *testing.T) {
	hub := chatbridge.NewHub(nil)
	hub.Sync("w1:p1", "s", nil)
	c, ctx := dialChat(t, hub)

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_send","reqId":"c1","paneId":"w1:p1","text":"hi"}`))
	ok := readUntil(t, ctx, c, "chat_send_result")
	if ok["ok"] != true || ok["reqId"] != "c1" {
		t.Fatalf("send ok: %v", ok)
	}
	if out := hub.Sync("w1:p1", "s", nil); len(out) != 1 || out[0].Text != "hi" {
		t.Fatalf("outbox: %+v", out)
	}
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_send","reqId":"c2","paneId":"w9:p9","text":"hi"}`))
	bad := readUntil(t, ctx, c, "chat_send_result")
	if bad["ok"] != false || bad["error"] != "no_mod" {
		t.Fatalf("send no_mod: %v", bad)
	}
}

func TestChatSendWithoutHubIsNoMod(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_send","reqId":"c1","paneId":"p","text":"hi"}`))
	if r := readUntil(t, ctx, c, "chat_send_result"); r["error"] != "no_mod" {
		t.Fatalf("%v", r)
	}
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd companion && go test ./internal/proto/ ./internal/wsserver/`
Expected: FAIL (undefined `ChatSnapshot`, `SetChat`, protocol 7 ≠ 8).

- [ ] **Step 4: Implement proto changes**

In `proto.go`: add the import `"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"`, change `"companionProtocol": 7` to `8`, and append:
```go
func ChatSnapshot(s chatbridge.Snapshot) []byte {
	events := s.Events
	if events == nil {
		events = []chatbridge.Entry{}
	}
	return must(map[string]any{"t": "chat_snapshot", "paneId": s.PaneID, "epoch": s.Epoch, "state": s.State, "events": events})
}

func ChatEvent(paneID string, epoch int, e chatbridge.Entry) []byte {
	return must(map[string]any{"t": "chat_event", "paneId": paneID, "epoch": epoch, "seq": e.Seq, "event": e.Event})
}

func ChatState(paneID, st string) []byte {
	return must(map[string]any{"t": "chat_state", "paneId": paneID, "state": st})
}

func ChatSendResult(reqID string, ok bool, errCode string) []byte {
	m := map[string]any{"t": "chat_send_result", "reqId": reqID, "ok": ok}
	if errCode != "" {
		m["error"] = errCode
	}
	return must(m)
}
```

- [ ] **Step 5: Implement wsserver changes**

In `server.go`:

1. Import `"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"`.
2. After the `HerdrRPC` interface add:
```go
// ChatHub is the chatbridge surface the WS server needs (a *chatbridge.Hub).
type ChatHub interface {
	Subscribe(paneID string) (chatbridge.Snapshot, <-chan chatbridge.Update, func())
	Send(paneID, text string) error
}
```
3. Add the field `chat ChatHub` to `Server` and the setter beside the others:
```go
func (s *Server) SetChat(h ChatHub) { s.chat = h }
```
4. Add `chats map[string]func()` to `client` (guarded by `smu`), and initialise it in `Handler`: `c := &client{conn: conn, send: make(chan []byte, 64), sessions: map[string]*termSession{}, chats: map[string]func(){}}`.
5. In `readLoop`'s switch add:
```go
		case "chat_open":
			s.openChat(ctx, c, m.PaneID)
		case "chat_close":
			c.closeChat(m.PaneID)
		case "chat_send":
			s.sendChat(c, m)
```
6. Add the handlers:
```go
// openChat subscribes this client to a pane's chat: the snapshot first, then
// live updates in order. Re-opening a pane replaces the previous subscription.
func (s *Server) openChat(ctx context.Context, c *client, paneID string) {
	if s.chat == nil || paneID == "" {
		return
	}
	c.closeChat(paneID)
	snap, ch, cancel := s.chat.Subscribe(paneID)
	c.smu.Lock()
	c.chats[paneID] = cancel
	c.smu.Unlock()
	sendBlocking(ctx, c, proto.ChatSnapshot(snap))
	go func() {
		for u := range ch {
			var f []byte
			switch u.Kind {
			case "event":
				f = proto.ChatEvent(paneID, u.Epoch, u.Entry)
			case "state":
				f = proto.ChatState(paneID, u.State)
			case "snapshot":
				f = proto.ChatSnapshot(u.Snapshot)
			default:
				continue
			}
			sendBlocking(ctx, c, f)
		}
	}()
}

func (s *Server) sendChat(c *client, m proto.ClientMsg) {
	err := chatbridge.ErrNoMod
	if s.chat != nil {
		err = s.chat.Send(m.PaneID, m.Text)
	}
	if err != nil {
		c.send <- proto.ChatSendResult(m.ReqID, false, err.Error())
		return
	}
	c.send <- proto.ChatSendResult(m.ReqID, true, "")
}

func (c *client) closeChat(paneID string) {
	c.smu.Lock()
	cancel := c.chats[paneID]
	delete(c.chats, paneID)
	c.smu.Unlock()
	if cancel != nil {
		cancel()
	}
}
```
7. In `closeAll`, also cancel every chat subscription:
```go
func (c *client) closeAll() {
	c.smu.Lock()
	all := c.sessions
	c.sessions = map[string]*termSession{}
	chats := c.chats
	c.chats = map[string]func(){}
	c.smu.Unlock()
	for _, ts := range all {
		ts.closing.Store(true)
		_ = ts.sess.Close()
	}
	for _, cancel := range chats {
		cancel()
	}
}
```

The forwarding goroutine ends when `cancel` (from `closeChat`, `closeAll` or `Hub.Drop`) closes the channel. Once the WS context is done, `sendBlocking` returns immediately, so the goroutine drains and exits.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd companion && go test -race ./...`
Expected: PASS (all packages).

- [ ] **Step 7: Commit**

```bash
git add companion/internal/proto companion/internal/wsserver
git commit -m "feat(companion): chat_open/chat_close/chat_send frames (protocol 8)"
```

---

### Task 6: Pane `chat` flag + engine wiring + `--chat-socket`

**Files:**
- Modify: `companion/internal/state/store.go`
- Test: `companion/internal/state/store_test.go`
- Modify: `companion/internal/engine/engine.go`
- Modify: `companion/cmd/herdr-mobiled/main.go`

**Interfaces:**
- Consumes: `chatbridge.NewHub`, `Hub.SetOnLiveness`, `Hub.Tick`, `Hub.Drop`, `Hub.Handler`, `chatbridge.Listen`, `chatbridge.SocketPath` (Tasks 3–4); `Server.SetChat` (Task 5).
- Produces: `state.Pane.Chat bool \`json:"chat,omitempty"\``; `func (s *Store) SetChat(paneID string, on bool) (Pane, bool)`; `engine.Config.ChatSocket string` (empty disables chat).

- [ ] **Step 1: Write the failing store tests**

Append to `store_test.go`:
```go
func TestSetChatFlagsKnownPane(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"}))
	p, changed := s.SetChat("w1:p1", true)
	if !changed || !p.Chat {
		t.Fatalf("SetChat on: changed=%v pane=%+v", changed, p)
	}
	if _, again := s.SetChat("w1:p1", true); again {
		t.Fatal("setting the same value should report no change")
	}
	// herdr's next poll must not wipe the flag
	ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"}))
	if len(ch) != 0 || !s.Snapshot()[0].Chat {
		t.Fatalf("poll lost chat flag: changes=%+v snap=%+v", ch, s.Snapshot())
	}
	if p, changed := s.SetChat("w1:p1", false); !changed || p.Chat {
		t.Fatalf("SetChat off: %v %+v", changed, p)
	}
}

func TestSetChatBeforePaneAppears(t *testing.T) {
	s := NewStore()
	if _, changed := s.SetChat("w2:p1", true); changed {
		t.Fatal("unknown pane: nothing to broadcast yet")
	}
	ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w2:p1", WorkspaceID: "w2"}))
	if len(ch) != 1 || !ch[0].Pane.Chat {
		t.Fatalf("new pane should carry chat=true: %+v", ch)
	}
}

func TestRemovedPaneForgetsChat(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1"}))
	s.SetChat("w1:p1", true)
	s.Apply(infos())
	ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1"}))
	if ch[0].Pane.Chat {
		t.Fatal("a re-created pane id must not inherit the old chat flag")
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd companion && go test ./internal/state/`
Expected: FAIL, `p.Chat` / `SetChat` undefined.

- [ ] **Step 3: Implement the store changes**

In `store.go`:
- Add the field to `Pane` after `AgentStatus`:
```go
	// Chat is true while the herdr-chat mod in this pane is syncing (the app
	// opens the chat view for it). omitempty keeps older apps unaffected.
	Chat bool `json:"chat,omitempty"`
```
- Add `chat map[string]bool` to `Store`, initialised in `NewStore` (`chat: map[string]bool{}`).
- In `Apply`, right after `np := toPane(i)`, add `np.Chat = s.chat[np.PaneID]`. In the removal loop, after `delete(s.panes, id)`, add `delete(s.chat, id)`.
- Add:
```go
// SetChat records whether the pane's mod is live. It reports the updated pane
// and true only when a known pane's flag actually changed (so the caller
// broadcasts a pane_update). A flag set before herdr reports the pane is
// kept and applied when the pane appears.
func (s *Store) SetChat(paneID string, on bool) (Pane, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if on {
		s.chat[paneID] = true
	} else {
		delete(s.chat, paneID)
	}
	p, ok := s.panes[paneID]
	if !ok || p.Chat == on {
		return Pane{}, false
	}
	p.Chat = on
	s.panes[paneID] = p
	return p, true
}
```

- [ ] **Step 4: Run store tests**

Run: `cd companion && go test -race ./internal/state/`
Expected: PASS.

- [ ] **Step 5: Wire the engine**

In `engine.go`:
- Import `"log"`, `"net"` and `chatbridge`.
- Add `ChatSocket string` to `Config` and `hub *chatbridge.Hub` to `Engine`.
- In `New`, after `e.srv = wsserver.NewServer(...)`:
```go
	e.hub = chatbridge.NewHub(nil)
	e.hub.SetOnLiveness(func(paneID string, live bool) {
		if p, changed := e.store.SetChat(paneID, live); changed {
			e.srv.Broadcast(proto.PaneUpdate(p))
		}
	})
	e.srv.SetChat(e.hub)
```
- In `Run`, after `go e.pollLoop(ctx)`:
```go
	if e.cfg.ChatSocket != "" {
		if l, err := chatbridge.Listen(e.cfg.ChatSocket); err != nil {
			log.Printf("chat bridge disabled: %v", err)
		} else {
			go e.serveChat(ctx, l)
		}
	}
```
- Add:
```go
// serveChat serves the herdr-chat mods' /sync endpoint and expires mods that
// stop syncing, until ctx ends.
func (e *Engine) serveChat(ctx context.Context, l net.Listener) {
	srv := &http.Server{Handler: e.hub.Handler()}
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				srv.Close()
				return
			case <-t.C:
				e.hub.Tick()
			}
		}
	}()
	_ = srv.Serve(l)
}
```
- In `pollOnce`, in the `ch.Kind == "removed"` branch, add `e.hub.Drop(ch.PaneID)` before the broadcast.

In `main.go`:
- Import `chatbridge`.
- Add the flag: `chatSock := flag.String("chat-socket", chatbridge.SocketPath(), "Unix socket for the herdr-chat Claude Code mod (empty disables the chat view)")`.
- Pass `ChatSocket: *chatSock` in `engine.Config`, and add `chat=%s` to the startup log line.

- [ ] **Step 6: Build and run all tests**

Run: `cd companion && go vet ./... && go test -race ./... && go build ./cmd/herdr-mobiled`
Expected: PASS; binary builds.

- [ ] **Step 7: Smoke test the socket by hand**

```bash
cd companion && HERDR_MOBILE_CHAT_SOCK=$PWD/../.tmp-chat.sock go run ./cmd/herdr-mobiled --listen 127.0.0.1:18787 &
sleep 2
curl -s --unix-socket ../.tmp-chat.sock -X POST http://chat/sync -d '{"paneId":"w1:p1","sessionId":"s","events":[]}'
kill %1; rm -f ../.tmp-chat.sock
```
Expected: `{"messages":[]}`.

- [ ] **Step 8: Commit**

```bash
git add companion
git commit -m "feat(companion): expose chat-capable panes and serve the chat socket"
```

---

### Task 7: App protocol + client

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`

**Interfaces:**
- Produces:
  - `Pane.chat: Boolean = false`
  - `sealed interface ChatEvent { UserText(uuid, text); AssistantText(uuid, text); ToolUse(uuid, toolUseId, tool, summary); ToolResult(toolUseId, isError, preview) }` (all `data class`, `String` fields, `isError: Boolean`)
  - `data class ChatEntry(val seq: Int, val event: ChatEvent)`
  - `ServerFrame.ChatSnapshot(paneId: String, epoch: Int, state: String, entries: List<ChatEntry>)`, `ServerFrame.ChatEventFrame(paneId: String, epoch: Int, entry: ChatEntry?)`, `ServerFrame.ChatState(paneId: String, state: String)`, `ServerFrame.ChatSendResult(reqId: String, ok: Boolean, error: String?)`
  - `ClientMsg.chatOpen(paneId)`, `ClientMsg.chatClose(paneId)`, `ClientMsg.chatSend(reqId, paneId, text)`
  - `suspend fun CompanionClient.sendChat(paneId: String, text: String)` (throws `RuntimeException(errorCode)` when not ok)

- [ ] **Step 1: Write the failing tests**

Append to `ProtocolTest.kt`:
```kotlin
    @Test fun paneChatFlagDefaultsFalse() {
        val p = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1"}}""") as ServerFrame.PaneUpdate).pane
        assertFalse(p.chat)
        val q = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1","chat":true}}""") as ServerFrame.PaneUpdate).pane
        assertTrue(q.chat)
    }

    @Test fun parsesChatSnapshotSkippingUnknownEvents() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"w1:p1","epoch":2,"state":"working","events":[
            {"seq":1,"event":{"type":"user_text","uuid":"u1","text":"hi"}},
            {"seq":2,"event":{"type":"mystery"}},
            {"seq":3,"event":{"type":"tool_use","uuid":"a#1","toolUseId":"t1","tool":"Bash","summary":"Bash: ls"}},
            {"seq":4,"event":{"type":"tool_result","toolUseId":"t1","isError":true,"preview":"boom"}}]}""") as ServerFrame.ChatSnapshot
        assertEquals("w1:p1", f.paneId)
        assertEquals(2, f.epoch)
        assertEquals("working", f.state)
        assertEquals(listOf(1, 3, 4), f.entries.map { it.seq })
        assertEquals(ChatEvent.UserText("u1", "hi"), f.entries[0].event)
        assertEquals(ChatEvent.ToolResult("t1", true, "boom"), f.entries[2].event)
    }

    @Test fun parsesChatEventStateAndSendResult() {
        val e = parseServerFrame("""{"t":"chat_event","paneId":"p","epoch":1,"seq":9,"event":{"type":"assistant_text","uuid":"a","text":"yo"}}""") as ServerFrame.ChatEventFrame
        assertEquals(ChatEntry(9, ChatEvent.AssistantText("a", "yo")), e.entry)
        val unknown = parseServerFrame("""{"t":"chat_event","paneId":"p","epoch":1,"seq":10,"event":{"type":"new_kind"}}""") as ServerFrame.ChatEventFrame
        assertNull(unknown.entry)
        val s = parseServerFrame("""{"t":"chat_state","paneId":"p","state":"idle"}""") as ServerFrame.ChatState
        assertEquals("idle", s.state)
        val r = parseServerFrame("""{"t":"chat_send_result","reqId":"c1","ok":false,"error":"no_mod"}""") as ServerFrame.ChatSendResult
        assertFalse(r.ok)
        assertEquals("no_mod", r.error)
    }

    @Test fun chatClientMessages() {
        assertEquals("""{"t":"chat_open","paneId":"w1:p1"}""", ClientMsg.chatOpen("w1:p1"))
        assertEquals("""{"t":"chat_close","paneId":"w1:p1"}""", ClientMsg.chatClose("w1:p1"))
        assertEquals("""{"t":"chat_send","reqId":"c1","paneId":"w1:p1","text":"hi \"there\""}""", ClientMsg.chatSend("c1", "w1:p1", "hi \"there\""))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests dev.herdr.mobile.ProtocolTest`
Expected: compilation FAIL (unresolved `chat`, `ChatEvent`, …).

- [ ] **Step 3: Implement `Protocol.kt` changes**

- Add `val chat: Boolean = false,` as the last field of `Pane`.
- Above `sealed interface ServerFrame` add:
```kotlin
sealed interface ChatEvent {
    data class UserText(val uuid: String, val text: String) : ChatEvent
    data class AssistantText(val uuid: String, val text: String) : ChatEvent
    data class ToolUse(val uuid: String, val toolUseId: String, val tool: String, val summary: String) : ChatEvent
    data class ToolResult(val toolUseId: String, val isError: Boolean, val preview: String) : ChatEvent
}

data class ChatEntry(val seq: Int, val event: ChatEvent)

/** Null for an event type this app version doesn't know (skipped, not an error). */
fun parseChatEvent(o: JsonObject): ChatEvent? {
    fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
    return when (s("type")) {
        "user_text" -> ChatEvent.UserText(s("uuid"), s("text"))
        "assistant_text" -> ChatEvent.AssistantText(s("uuid"), s("text"))
        "tool_use" -> ChatEvent.ToolUse(s("uuid"), s("toolUseId"), s("tool"), s("summary"))
        "tool_result" -> ChatEvent.ToolResult(s("toolUseId"), o["isError"]?.jsonPrimitive?.booleanOrNull ?: false, s("preview"))
        else -> null
    }
}

private fun parseChatEntry(el: JsonElement): ChatEntry? {
    val o = el as? JsonObject ?: return null
    val ev = (o["event"] as? JsonObject)?.let(::parseChatEvent) ?: return null
    return ChatEntry(o["seq"]?.jsonPrimitive?.intOrNull ?: 0, ev)
}
```
- Inside `ServerFrame` add:
```kotlin
    data class ChatSnapshot(val paneId: String, val epoch: Int, val state: String, val entries: List<ChatEntry>) : ServerFrame
    data class ChatEventFrame(val paneId: String, val epoch: Int, val entry: ChatEntry?) : ServerFrame
    data class ChatState(val paneId: String, val state: String) : ServerFrame
    data class ChatSendResult(val reqId: String, val ok: Boolean, val error: String?) : ServerFrame
```
- In `parseServerFrame` before `else ->` add:
```kotlin
        "chat_snapshot" -> ServerFrame.ChatSnapshot(
            obj["paneId"]?.jsonPrimitive?.content ?: "",
            obj["epoch"]?.jsonPrimitive?.intOrNull ?: 0,
            obj["state"]?.jsonPrimitive?.content ?: "idle",
            (obj["events"] as? JsonArray)?.mapNotNull(::parseChatEntry) ?: emptyList())
        "chat_event" -> ServerFrame.ChatEventFrame(
            obj["paneId"]?.jsonPrimitive?.content ?: "",
            obj["epoch"]?.jsonPrimitive?.intOrNull ?: 0,
            parseChatEntry(obj))
        "chat_state" -> ServerFrame.ChatState(
            obj["paneId"]?.jsonPrimitive?.content ?: "",
            obj["state"]?.jsonPrimitive?.content ?: "idle")
        "chat_send_result" -> ServerFrame.ChatSendResult(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["ok"]?.jsonPrimitive?.boolean ?: false,
            obj["error"]?.jsonPrimitive?.content)
```
(`parseChatEntry(obj)` works for `chat_event` because the frame carries `seq` and `event` at its top level.)
- In `ClientMsg` add:
```kotlin
    fun chatOpen(paneId: String) = obj("t" to JsonPrimitive("chat_open"), "paneId" to JsonPrimitive(paneId))
    fun chatClose(paneId: String) = obj("t" to JsonPrimitive("chat_close"), "paneId" to JsonPrimitive(paneId))
    fun chatSend(reqId: String, paneId: String, text: String) =
        obj("t" to JsonPrimitive("chat_send"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId), "text" to JsonPrimitive(text))
```

`obj` builds from `pairs.toMap()`, a `LinkedHashMap`, so key order is insertion order and the string assertions hold.

- [ ] **Step 4: Implement `CompanionClient.sendChat`**

In `onMessage`'s `when`, add `is ServerFrame.ChatSendResult -> pending.remove(frame.reqId)?.complete(frame)`. After `sendMove`, add:
```kotlin
    /** Queues text for the pane's Claude; throws with the companion's error code. */
    suspend fun sendChat(paneId: String, text: String) {
        val reqId = "c${seq.incrementAndGet()}"
        when (val f = request(reqId, ClientMsg.chatSend(reqId, paneId, text))) {
            is ServerFrame.ChatSendResult -> if (!f.ok) throw RuntimeException(f.error ?: "send failed")
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to chat_send")
        }
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest`
Expected: PASS (all app tests).

- [ ] **Step 6: Commit**

```bash
git add app/app/src
git commit -m "feat(app): parse chat frames and send chat messages (protocol 8)"
```

---

### Task 8: App chat model, reducer and repository

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/data/ChatModel.kt`
- Create: `app/app/src/main/java/dev/herdr/mobile/data/ChatRepository.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/ChatReducerTest.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/ChatRepositoryTest.kt`

**Interfaces:**
- Consumes: `ServerFrame.Chat*`, `ChatEntry`, `ChatEvent`, `ClientMsg.chatOpen/chatClose` (Task 7).
- Produces:
  - `enum class PendingStatus { Queued, Failed, NotDelivered }`
  - `data class PendingMsg(val id: String, val text: String, val sentAt: Long, val status: PendingStatus = PendingStatus.Queued, val error: String? = null)`
  - `data class ChatView(val loaded: Boolean = false, val epoch: Int = 0, val state: String = "idle", val entries: List<ChatEntry> = emptyList(), val lastSeq: Int = 0, val pending: List<PendingMsg> = emptyList())`
  - `const val PENDING_TIMEOUT_MS = 120_000L`, `const val MAX_ENTRIES = 500`
  - `object ChatReducer { fun onFrame(v: ChatView, f: ServerFrame): ChatView; fun addPending(v: ChatView, id: String, text: String, now: Long): ChatView; fun failPending(v: ChatView, id: String, error: String): ChatView; fun removePending(v: ChatView, id: String): ChatView; fun expire(v: ChatView, now: Long): ChatView }`
  - `fun pendingLabel(p: PendingMsg, state: String): String`
  - `class ChatRepository(sendRaw: (String) -> Unit, sendChat: suspend (String, String) -> Unit, now: () -> Long = System::currentTimeMillis)` with `view(paneId): StateFlow<ChatView>`, `onFrame(f: ServerFrame)`, `open(paneId)`, `close(paneId)`, `onReconnected()`, `suspend send(paneId, text)`, `suspend retry(paneId, pendingId)`, `expirePending()`

- [ ] **Step 1: Write the failing reducer tests**

`ChatReducerTest.kt`:
```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.data.*
import dev.herdr.mobile.net.*
import org.junit.Assert.*
import org.junit.Test

class ChatReducerTest {
    private fun user(seq: Int, text: String) = ChatEntry(seq, ChatEvent.UserText("u$seq", text))
    private fun reply(seq: Int, text: String) = ChatEntry(seq, ChatEvent.AssistantText("a$seq", text))
    private fun snap(epoch: Int, vararg e: ChatEntry, state: String = "idle") =
        ServerFrame.ChatSnapshot("p", epoch, state, e.toList())
    private fun ev(epoch: Int, e: ChatEntry) = ServerFrame.ChatEventFrame("p", epoch, e)

    @Test fun snapshotReplacesEntries() {
        var v = ChatReducer.onFrame(ChatView(), snap(1, user(1, "a"), reply(2, "b")))
        assertTrue(v.loaded)
        assertEquals(2, v.lastSeq)
        v = ChatReducer.onFrame(v, snap(2, user(1, "fresh")))
        assertEquals(2, v.epoch)
        assertEquals(listOf("fresh"), v.entries.map { (it.event as ChatEvent.UserText).text })
        assertEquals(1, v.lastSeq)
    }

    @Test fun eventsAppendInOrderAndDuplicatesAreIgnored() {
        var v = ChatReducer.onFrame(ChatView(), snap(1, user(1, "a")))
        v = ChatReducer.onFrame(v, ev(1, reply(2, "b")))
        v = ChatReducer.onFrame(v, ev(1, reply(2, "b")))
        v = ChatReducer.onFrame(v, ev(1, reply(1, "old")))
        assertEquals(listOf(1, 2), v.entries.map { it.seq })
    }

    @Test fun staleEpochAndPreSnapshotEventsAreIgnored() {
        val before = ChatReducer.onFrame(ChatView(), ev(1, reply(1, "x")))
        assertFalse(before.loaded)
        assertTrue(before.entries.isEmpty())
        var v = ChatReducer.onFrame(ChatView(), snap(3))
        v = ChatReducer.onFrame(v, ev(2, reply(5, "stale")))
        assertTrue(v.entries.isEmpty())
    }

    @Test fun unknownEventEntryIsIgnored() {
        val v = ChatReducer.onFrame(ChatReducer.onFrame(ChatView(), snap(1)), ServerFrame.ChatEventFrame("p", 1, null))
        assertTrue(v.entries.isEmpty())
    }

    @Test fun stateFrameUpdatesState() {
        val v = ChatReducer.onFrame(ChatView(), ServerFrame.ChatState("p", "working"))
        assertEquals("working", v.state)
    }

    @Test fun entriesAreCapped() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        for (i in 1..(MAX_ENTRIES + 20)) v = ChatReducer.onFrame(v, ev(1, reply(i, "r$i")))
        assertEquals(MAX_ENTRIES, v.entries.size)
        assertEquals(21, v.entries.first().seq)
    }

    @Test fun userTextConfirmsMatchingPending() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "  run tests ", 0)
        v = ChatReducer.onFrame(v, ev(1, user(1, "run tests")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun duplicateTextsConfirmOnePendingEach() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "yes", 0)
        v = ChatReducer.addPending(v, "p2", "yes", 1)
        v = ChatReducer.onFrame(v, ev(1, user(1, "yes")))
        assertEquals(listOf("p2"), v.pending.map { it.id })
    }

    @Test fun snapshotConfirmsDeliveredPending() {
        var v = ChatReducer.addPending(ChatView(), "p1", "hello", 0)
        v = ChatReducer.onFrame(v, snap(2, user(1, "hello")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun failedPendingIsNotConfirmed() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "hi", 0)
        v = ChatReducer.failPending(v, "p1", "no_mod")
        v = ChatReducer.onFrame(v, ev(1, user(1, "hi")))
        assertEquals(PendingStatus.Failed, v.pending.single().status)
        assertEquals("no_mod", v.pending.single().error)
    }

    @Test fun expireMarksOldQueuedAsNotDelivered() {
        var v = ChatReducer.addPending(ChatView(), "p1", "a", 0)
        v = ChatReducer.addPending(v, "p2", "b", 100_000)
        v = ChatReducer.expire(v, PENDING_TIMEOUT_MS)
        assertEquals(listOf(PendingStatus.NotDelivered, PendingStatus.Queued), v.pending.map { it.status })
    }

    @Test fun lateDeliveryConfirmsNotDelivered() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        v = ChatReducer.expire(ChatReducer.addPending(v, "p1", "late", 0), PENDING_TIMEOUT_MS)
        v = ChatReducer.onFrame(v, ev(1, user(1, "late")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun pendingLabels() {
        val p = PendingMsg("p1", "x", 0)
        assertEquals("queued — Claude is busy", pendingLabel(p, "working"))
        assertEquals("sending…", pendingLabel(p, "idle"))
        assertEquals("not delivered", pendingLabel(p.copy(status = PendingStatus.NotDelivered), "idle"))
        assertEquals("failed: no_mod", pendingLabel(p.copy(status = PendingStatus.Failed, error = "no_mod"), "idle"))
    }
}
```

- [ ] **Step 2: Write the failing repository tests**

`ChatRepositoryTest.kt`:
```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.data.*
import dev.herdr.mobile.net.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatRepositoryTest {
    @Test fun openCloseAndReconnectResubscribe() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.open("w1:p1")
        repo.open("w2:p1")
        repo.close("w2:p1")
        sent.clear()
        repo.onReconnected()
        assertEquals(listOf(ClientMsg.chatOpen("w1:p1")), sent)
    }

    @Test fun framesRouteToTheirPane() {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> })
        repo.onFrame(ServerFrame.ChatState("w1:p1", "working"))
        assertEquals("working", repo.view("w1:p1").value.state)
        assertEquals("idle", repo.view("w2:p1").value.state)
    }

    @Test fun sendAddsPendingAndFailureMarksIt() = runTest {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> throw RuntimeException("no_mod") }, now = { 5 })
        repo.send("p", "  hi  ")
        val p = repo.view("p").value.pending.single()
        assertEquals("hi", p.text)
        assertEquals(PendingStatus.Failed, p.status)
        assertEquals("no_mod", p.error)
    }

    @Test fun blankSendIsIgnored() = runTest {
        var calls = 0
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> calls++ })
        repo.send("p", "   ")
        assertEquals(0, calls)
        assertTrue(repo.view("p").value.pending.isEmpty())
    }

    @Test fun retryResendsAndReplacesThePending() = runTest {
        var fail = true
        val texts = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, t -> texts += t; if (fail) throw RuntimeException("outbox_full") })
        repo.send("p", "again")
        fail = false
        repo.retry("p", repo.view("p").value.pending.single().id)
        val p = repo.view("p").value.pending.single()
        assertEquals(PendingStatus.Queued, p.status)
        assertEquals(listOf("again", "again"), texts)
    }

    @Test fun expirePendingUsesTheClock() = runTest {
        var t = 0L
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, now = { t })
        repo.send("p", "x")
        t = PENDING_TIMEOUT_MS
        repo.expirePending()
        assertEquals(PendingStatus.NotDelivered, repo.view("p").value.pending.single().status)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests 'dev.herdr.mobile.Chat*'`
Expected: compilation FAIL.

- [ ] **Step 4: Implement `data/ChatModel.kt`**

```kotlin
package dev.herdr.mobile.data

import dev.herdr.mobile.net.ChatEntry
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.ServerFrame

enum class PendingStatus { Queued, Failed, NotDelivered }

data class PendingMsg(
    val id: String,
    val text: String,
    val sentAt: Long,
    val status: PendingStatus = PendingStatus.Queued,
    val error: String? = null,
)

data class ChatView(
    val loaded: Boolean = false,
    val epoch: Int = 0,
    val state: String = "idle",
    val entries: List<ChatEntry> = emptyList(),
    val lastSeq: Int = 0,
    val pending: List<PendingMsg> = emptyList(),
)

const val PENDING_TIMEOUT_MS = 120_000L
const val MAX_ENTRIES = 500

object ChatReducer {
    fun onFrame(v: ChatView, f: ServerFrame): ChatView = when (f) {
        is ServerFrame.ChatSnapshot -> v.copy(
            loaded = true,
            epoch = f.epoch,
            state = f.state,
            entries = f.entries.takeLast(MAX_ENTRIES),
            lastSeq = f.entries.maxOfOrNull { it.seq } ?: 0,
            pending = confirm(v.pending, f.entries.mapNotNull { userText(it) }),
        )
        is ServerFrame.ChatEventFrame -> {
            val e = f.entry
            if (!v.loaded || f.epoch != v.epoch || e == null || e.seq <= v.lastSeq) v
            else v.copy(
                entries = (v.entries + e).takeLast(MAX_ENTRIES),
                lastSeq = e.seq,
                pending = confirm(v.pending, listOfNotNull(userText(e))),
            )
        }
        is ServerFrame.ChatState -> v.copy(state = f.state)
        else -> v
    }

    fun addPending(v: ChatView, id: String, text: String, now: Long): ChatView =
        v.copy(pending = v.pending + PendingMsg(id, text.trim(), now))

    fun failPending(v: ChatView, id: String, error: String): ChatView =
        v.copy(pending = v.pending.map { if (it.id == id) it.copy(status = PendingStatus.Failed, error = error) else it })

    fun removePending(v: ChatView, id: String): ChatView =
        v.copy(pending = v.pending.filterNot { it.id == id })

    fun expire(v: ChatView, now: Long): ChatView {
        if (v.pending.none { it.status == PendingStatus.Queued && now - it.sentAt >= PENDING_TIMEOUT_MS }) return v
        return v.copy(pending = v.pending.map {
            if (it.status == PendingStatus.Queued && now - it.sentAt >= PENDING_TIMEOUT_MS) it.copy(status = PendingStatus.NotDelivered) else it
        })
    }

    private fun userText(e: ChatEntry): String? = (e.event as? ChatEvent.UserText)?.text?.trim()

    // Each delivered text confirms the oldest unconfirmed pending bubble with
    // the same text, so sending "yes" twice needs two deliveries.
    private fun confirm(pending: List<PendingMsg>, texts: List<String>): List<PendingMsg> {
        if (pending.isEmpty() || texts.isEmpty()) return pending
        val out = pending.toMutableList()
        for (t in texts) {
            val i = out.indexOfFirst { it.status != PendingStatus.Failed && it.text == t }
            if (i >= 0) out.removeAt(i)
        }
        return out
    }
}

fun pendingLabel(p: PendingMsg, state: String): String = when (p.status) {
    PendingStatus.Queued -> if (state == "working") "queued — Claude is busy" else "sending…"
    PendingStatus.Failed -> "failed: ${p.error ?: "error"}"
    PendingStatus.NotDelivered -> "not delivered"
}
```

- [ ] **Step 5: Implement `data/ChatRepository.kt`**

```kotlin
package dev.herdr.mobile.data

import dev.herdr.mobile.net.ClientMsg
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-pane chat state. Opened panes are re-subscribed after a reconnect; the
 * companion answers every chat_open with a fresh snapshot.
 */
class ChatRepository(
    private val sendRaw: (String) -> Unit,
    private val sendChat: suspend (paneId: String, text: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val views = ConcurrentHashMap<String, MutableStateFlow<ChatView>>()
    private val opened: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val ids = AtomicInteger(0)

    private fun flow(paneId: String) = views.computeIfAbsent(paneId) { MutableStateFlow(ChatView()) }

    fun view(paneId: String): StateFlow<ChatView> = flow(paneId)

    fun onFrame(f: ServerFrame) {
        val paneId = when (f) {
            is ServerFrame.ChatSnapshot -> f.paneId
            is ServerFrame.ChatEventFrame -> f.paneId
            is ServerFrame.ChatState -> f.paneId
            else -> return
        }
        flow(paneId).update { ChatReducer.onFrame(it, f) }
    }

    fun open(paneId: String) {
        opened += paneId
        sendRaw(ClientMsg.chatOpen(paneId))
    }

    fun close(paneId: String) {
        opened -= paneId
        sendRaw(ClientMsg.chatClose(paneId))
    }

    fun onReconnected() = opened.forEach { sendRaw(ClientMsg.chatOpen(it)) }

    suspend fun send(paneId: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = "p${ids.incrementAndGet()}"
        flow(paneId).update { ChatReducer.addPending(it, id, t, now()) }
        runCatching { sendChat(paneId, t) }
            .onFailure { e -> flow(paneId).update { ChatReducer.failPending(it, id, e.message ?: "send failed") } }
    }

    suspend fun retry(paneId: String, pendingId: String) {
        val p = flow(paneId).value.pending.firstOrNull { it.id == pendingId } ?: return
        flow(paneId).update { ChatReducer.removePending(it, pendingId) }
        send(paneId, p.text)
    }

    fun expirePending() {
        val t = now()
        views.values.forEach { f -> f.update { ChatReducer.expire(it, t) } }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/app/src
git commit -m "feat(app): chat reducer and repository with pending-message tracking"
```

---

### Task 9: App chat UI (PaneScreen, ChatScreen, toggle)

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/ChatText.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/ChatTextTest.kt`
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/ChatScreen.kt`
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/PaneScreen.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt:49-52`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (signature + top-bar action)

**Interfaces:**
- Consumes: `ChatRepository`, `ChatView`, `PendingMsg`, `PendingStatus`, `pendingLabel` (Task 8); `CompanionClient.sendChat`, `ChatEvent` (Task 7); `Pane.chat`.
- Produces:
  - `sealed interface TextSegment { data class Prose(val text: String); data class Code(val text: String) }`, `fun splitFences(text: String): List<TextSegment>`
  - `DashboardViewModel.chatView(paneId): StateFlow<ChatView>`, `openChat(paneId)`, `closeChat(paneId)`, `sendChat(paneId, text)`, `retryChat(paneId, pendingId)`, `expireChat()`
  - `@Composable fun PaneScreen(vm, pane, onExit)`, `@Composable fun ChatScreen(vm, pane, onExit, onTerminal)`
  - `TerminalScreen(vm, pane, onExit, onChat: (() -> Unit)? = null)`

- [ ] **Step 1: Write the failing `splitFences` tests**

`ChatTextTest.kt`:
```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.ui.TextSegment
import dev.herdr.mobile.ui.splitFences
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextTest {
    @Test fun plainTextIsOneProseSegment() {
        assertEquals(listOf(TextSegment.Prose("hello\nworld")), splitFences("hello\nworld"))
    }

    @Test fun fencesSplitOutCodeKeepingIndentation() {
        val text = "Run:\n```bash\n  npm test\n```\nthen check."
        assertEquals(
            listOf(TextSegment.Prose("Run:"), TextSegment.Code("  npm test"), TextSegment.Prose("then check.")),
            splitFences(text),
        )
    }

    @Test fun unclosedFenceIsCode() {
        assertEquals(listOf(TextSegment.Prose("a"), TextSegment.Code("b")), splitFences("a\n```\nb"))
    }

    @Test fun blankSegmentsAreDropped() {
        assertEquals(listOf(TextSegment.Code("x")), splitFences("```\nx\n```\n\n"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests dev.herdr.mobile.ChatTextTest`
Expected: compilation FAIL.

- [ ] **Step 3: Implement `ui/ChatText.kt`**

```kotlin
package dev.herdr.mobile.ui

sealed interface TextSegment {
    data class Prose(val text: String) : TextSegment
    data class Code(val text: String) : TextSegment
}

/** Splits assistant text on ``` fences; the only formatting the chat view draws. */
fun splitFences(text: String): List<TextSegment> {
    val out = mutableListOf<TextSegment>()
    val buf = StringBuilder()
    var inCode = false
    fun flush() {
        val s = buf.toString().trim('\n')
        if (s.isNotBlank()) out += if (inCode) TextSegment.Code(s) else TextSegment.Prose(s)
        buf.clear()
    }
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            flush()
            inCode = !inCode
            continue
        }
        buf.append(line).append('\n')
    }
    flush()
    return out
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests dev.herdr.mobile.ChatTextTest`
Expected: PASS.

- [ ] **Step 5: Wire the view model**

In `DashboardViewModel.kt`:
- Imports: `dev.herdr.mobile.data.ChatRepository`, `dev.herdr.mobile.data.ChatView`, `kotlinx.coroutines.flow.filter`.
- Add after `val connected`:
```kotlin
    private val chat = ChatRepository(sendRaw = client::send, sendChat = client::sendChat)
    fun chatView(paneId: String): StateFlow<ChatView> = chat.view(paneId)
    fun openChat(paneId: String) { _lastOpenedPaneId.value = paneId; chat.open(paneId) }
    fun closeChat(paneId: String) = chat.close(paneId)
    fun sendChat(paneId: String, text: String) { viewModelScope.launch { chat.send(paneId, text) } }
    fun retryChat(paneId: String, pendingId: String) { viewModelScope.launch { chat.retry(paneId, pendingId) } }
    fun expireChat() = chat.expirePending()
```
- Replace `start`:
```kotlin
    fun start(url: String) {
        viewModelScope.launch { client.frames.collect { repo.onFrame(it); chat.onFrame(it) } }
        viewModelScope.launch { client.connected.filter { it }.collect { chat.onReconnected() } }
        client.connect(url)
    }
```

- [ ] **Step 6: Add the chat toggle to `TerminalScreen`**

Change the signature to `fun TerminalScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit, onChat: (() -> Unit)? = null)`. Add an `actions` slot to its `TopAppBar` (after `title = { … },`):
```kotlin
                actions = {
                    if (onChat != null) {
                        IconButton(onClick = { hideKeyboard(); onChat() }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, "chat view")
                        }
                    }
                },
```
and import `androidx.compose.material.icons.automirrored.filled.Chat`.

- [ ] **Step 7: Create `ui/PaneScreen.kt`**

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.runtime.*
import dev.herdr.mobile.net.Pane

/**
 * Opens a pane in chat when its herdr-chat mod is reporting, else the
 * terminal; the top-bar toggle flips between them for as long as it is open.
 */
@Composable
fun PaneScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit) {
    var showChat by remember(pane.paneId) { mutableStateOf(pane.chat) }
    if (showChat) {
        ChatScreen(vm, pane, onExit = onExit, onTerminal = { showChat = false })
    } else {
        TerminalScreen(vm, pane, onExit, onChat = if (pane.chat) ({ showChat = true }) else null)
    }
}
```

- [ ] **Step 8: Create `ui/ChatScreen.kt`**

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.data.PendingMsg
import dev.herdr.mobile.data.PendingStatus
import dev.herdr.mobile.data.pendingLabel
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit, onTerminal: () -> Unit) {
    val view by remember(pane.paneId) { vm.chatView(pane.paneId) }.collectAsState()
    val connected by vm.connected.collectAsState()
    var draft by rememberSaveable(pane.paneId) { mutableStateOf("") }
    var expanded by remember(pane.paneId) { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()

    DisposableEffect(pane.paneId) {
        vm.openChat(pane.paneId)
        onDispose { vm.closeChat(pane.paneId) }
    }
    LaunchedEffect(pane.paneId) {
        while (true) { delay(10_000); vm.expireChat() }
    }

    val results = remember(view.entries) {
        view.entries.mapNotNull { it.event as? ChatEvent.ToolResult }.associateBy { it.toolUseId }
    }
    val rows = remember(view.entries) { view.entries.filter { it.event !is ChatEvent.ToolResult } }
    val itemCount = rows.size + view.pending.size
    // Follow new output only while the user is already at the bottom.
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(itemCount) {
        if (itemCount > 0 && atBottom) listState.scrollToItem(itemCount - 1)
    }

    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }
    val status = when {
        !connected -> "reconnecting…"
        view.state == "working" -> "working ${spinnerFrame()}"
        else -> "idle"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { IconButton(onClick = onTerminal) { Icon(Icons.Filled.Terminal, "terminal view") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (!pane.chat) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("chat unavailable — mod not reporting", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onTerminal) { Text("Open terminal") }
                    }
                }
            }
            if (!view.loaded) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { "e${view.epoch}-${it.seq}" }) { entry ->
                        when (val ev = entry.event) {
                            is ChatEvent.UserText -> UserBubble(ev.text)
                            is ChatEvent.AssistantText -> AssistantBlock(ev.text)
                            is ChatEvent.ToolUse -> ToolCard(
                                ev, results[ev.toolUseId], ev.toolUseId in expanded,
                            ) { expanded = if (ev.toolUseId in expanded) expanded - ev.toolUseId else expanded + ev.toolUseId }
                            is ChatEvent.ToolResult -> {}
                        }
                    }
                    items(view.pending, key = { it.id }) { p ->
                        UserBubble(
                            p.text,
                            pending = p,
                            label = pendingLabel(p, view.state),
                            onRetry = if (p.status != PendingStatus.Queued) ({ vm.retryChat(pane.paneId, p.id) }) else null,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Claude") },
                    maxLines = 5,
                )
                IconButton(
                    enabled = connected && pane.chat && draft.isNotBlank(),
                    onClick = { vm.sendChat(pane.paneId, draft); draft = "" },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, pending: PendingMsg? = null, label: String? = null, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp).alpha(if (pending != null) 0.6f else 1f),
        ) {
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (label != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun AssistantBlock(text: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (seg in splitFences(text)) {
            when (seg) {
                is TextSegment.Prose -> Text(seg.text, style = MaterialTheme.typography.bodyMedium)
                is TextSegment.Code -> Text(
                    seg.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .horizontalScroll(rememberScrollState()).padding(8.dp),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun ToolCard(use: ChatEvent.ToolUse, result: ChatEvent.ToolResult?, expanded: Boolean, onToggle: () -> Unit) {
    val accent = if (result?.isError == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = result != null, onClick = onToggle).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            (if (expanded) "▾ " else "▸ ") + use.summary,
            color = accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (expanded && result != null) {
            Text(
                result.preview.ifBlank { "(no output)" },
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
```

- [ ] **Step 9: Route the dashboard through `PaneScreen`**

In `DashboardScreen.kt`, replace:
```kotlin
    selected?.let { pane ->
        TerminalScreen(vm, pane) { selected = null }
        return   // full-screen terminal replaces the dashboard while open
    }
```
with:
```kotlin
    selected?.let { pane ->
        // `selected` is a snapshot from when it was tapped; read the live pane
        // so the chat flag (and the chat-unavailable banner) stays current.
        val live = panes.firstOrNull { it.paneId == pane.paneId } ?: pane
        PaneScreen(vm, live) { selected = null }
        return   // the pane screen replaces the dashboard while open
    }
```

- [ ] **Step 10: Build and run all unit tests**

Run: `cd app && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 11: Commit**

```bash
git add app/app/src
git commit -m "feat(app): chat view for Claude panes with a chat/terminal toggle"
```

---

### Task 10: Docs + live validation

**Files:**
- Modify: `README.md` (new section "Chat view for Claude Code panes")
- Modify: `CHANGELOG.md` (Unreleased entry)

- [ ] **Step 1: Document installing the mod**

Add to `README.md` after the companion setup section:
```markdown
## Chat view for Claude Code panes (optional)

herdr-mobile can show Claude Code panes as a chat instead of a terminal. It needs
the `herdr-chat` Claude Code plugin (Claude Code 2.1.289 or newer), which streams
the conversation to the companion over a private Unix socket.

    claude plugin marketplace add /path/to/herdr-mobile/mod
    claude plugin install herdr-chat@herdr-mobile

Restart running Claude sessions (or run `/reload-plugins` in them). Claude
sessions started inside herdr panes then open in chat on the phone; the top-bar
button switches to the terminal. Sessions outside herdr are unaffected.

The companion listens on `$XDG_RUNTIME_DIR/herdr-mobile/chat.sock` by default
(`--chat-socket` changes it, `--chat-socket ''` disables chat). If you change it,
set `HERDR_MOBILE_CHAT_SOCK` to the same path in the environment Claude runs in.
```

Add to `CHANGELOG.md` under Unreleased: `- Chat view for Claude Code panes via the new herdr-chat Claude Code plugin (companion protocol 8).`

- [ ] **Step 2: Live validation (emulator + real Claude in herdr)**

Use `scripts/dev-emulator.sh` as in earlier phases (companion on `127.0.0.1:8787`, app pointed at `ws://10.0.2.2:8787`). Install the mod from the worktree's `mod/` folder as in Step 1. Then check each item and record the result:
1. Start `claude` in a herdr pane. Within ~2s the dashboard's pane opens in chat and shows the history.
2. Ask Claude something from the desktop. User bubble, tool cards and reply appear live; tapping a tool card shows its result preview; the top bar shows `working ⠋` then `idle`.
3. Send a message from the phone while Claude is idle. It reaches Claude and starts a turn; the dimmed bubble becomes a normal one.
4. Send a message while Claude is working. The bubble says "queued — Claude is busy" and is delivered when the turn ends; the pane stays chat-capable throughout.
5. Toggle to the terminal and back.
6. `/clear` in Claude. The chat empties (new epoch).
7. Quit Claude. The banner "chat unavailable — mod not reporting" appears within ~5s.
8. Restart the companion with Claude running. The chat recovers by itself within a few seconds.

- [ ] **Step 3: Commit**

```bash
git add README.md CHANGELOG.md
git commit -m "docs: installing the herdr-chat plugin for the chat view"
```
