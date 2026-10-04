# herdr-mobile — Chat View for Claude Code Panes — Design Spec

**Date:** 2026-10-04
**Components:** new Claude Code mod (`mod/`), companion (`companion/`), Android app (`app/`).
**Protocol:** `companionProtocol` 7 → 8 (additive only).

## Goal

For panes running Claude Code, the app shows a readable **chat**: your messages,
Claude's replies, and tool activity, plus a box to send a message. It replaces
squinting at, and typing into, a raw terminal on a phone. Panes without the mod
(other agents, shells) keep the terminal exactly as today.

**Scope (v1): read + reply.** Permission prompts are NOT surfaced in chat. They
are still answered in the terminal view, which is one tap away. Approvals are a
candidate for v2 (`tool.check` hook), deferred because of the hook time budget.

**Assumptions:** single host, single user, the user installs the mod into their
Claude Code setup. herdr itself is unchanged.

## Approach

A Claude Code mod (Claude Code ≥ 2.1.289 hooks-module plugin) runs inside every
Claude session started in a herdr pane. It **pushes** structured conversation
events to the companion over a Unix socket, and **polls** the companion for
messages typed on the phone, which it submits with `$.prompt.submit`. The
companion buffers per pane and relays over the existing WebSocket.

Rejected alternatives:
- **Companion tails `~/.claude/projects/*.jsonl` (no mod):** matching a pane to its
  session file is guesswork (two Claudes in one repo), the JSONL format is not a
  stable contract, and replies via `send_text` collide with whatever is already
  typed in Claude's input box.
- **Hybrid (mod only announces the session file):** fixes matching but keeps the
  file-format dependency and terminal injection.

## Global Constraints

- The mod must never delay, change or refuse a conversation row: every
  `session.append` hook calls `next(e)` first and only observes the result.
- The mod is a no-op when `HERDR_PANE_ID` is unset (Claude not running in herdr).
- Protocol changes are additive. An app on protocol 7 keeps working against a
  protocol-8 companion, and vice versa (no `chat` field → terminal).
- No new network listener: the mod↔companion channel is a Unix socket, mode 0600,
  in the user's runtime dir. The WebSocket keeps its current auth.

## Components

1. **`herdr-chat` mod**: `mod/herdr-chat/` in this repo (`.claude-plugin/plugin.json`,
   `hooks/hooks.json`, `hooks/register.ts`, tests). Installed by adding `mod/` as a
   local plugin marketplace (`claude plugin marketplace add <repo>/mod`, then
   `claude plugin install herdr-chat@<marketplace>`), so edits apply with
   `/reload-plugins`.
2. **Companion `internal/chatbridge`**: the Unix-socket HTTP server, per-pane event
   buffers and outboxes, liveness.
3. **Companion `internal/wsserver` / `internal/proto`**: `chat_*` frames, `chat`
   flag on panes.
4. **App**: `PaneScreen` (chat/terminal switch), `ChatScreen`, `ChatRepository` +
   pure reducer, protocol/client additions.

## 1. The mod

**Identity.** On `session.start`: `paneId = await $.env.get("HERDR_PANE_ID")`. If
unset, register no further behaviour. `sessionId` is the Claude session id
(`$.session.id()`); `cwd` from `$.session.cwd()`.

**Socket path.** `HERDR_MOBILE_CHAT_SOCK` if set, else
`$XDG_RUNTIME_DIR/herdr-mobile/chat.sock`. If neither is set, chat is off: the mod
is a no-op and the companion does not listen. There is deliberately no `/tmp`
fallback: a predictable shared path would let another local user create it first,
read the conversation and inject prompts. The companion uses the same rule. The mod makes exactly one kind of request,
`$.http.fetch("http://chat/sync", { method: "POST", socketPath, body })`, from a
timer (see **Sync loop**). Hooks never do I/O themselves: they only append to an
in-memory `pending` list, so a hook never waits on the companion and no request
has to outlive the dispatch that made it.

**Hello + snapshot.** On start (and on every recovery, see below), queue a `hello`
event `{ sessionId, cwd }`, then a `snapshot` event built from
`$.session.messages({ as: "api" })`, which returns the main conversation as
Messages-API messages (`{ role, content }`), the same block form live rows carry,
so the snapshot goes through the **same block normalizer** as live rows. Those
messages carry no `uuid` and no `isMeta`, so `uuid`s are synthesized as
`snap-<index>`, and a snapshot may show a few injected user rows that live
filtering would drop. That is accepted for v1. Only the last 500 normalized
events are sent.

**`/clear`.** A `/clear` fires `session.end` with `reason: "clear"` and no
`session.start` after it, so on that event the mod re-reads the session id and
queues hello + snapshot again (an empty one).

**Live rows (`session.append`).** `const r = await next(e)`, then, only when:
- `e.agentId` is absent (main conversation, not a subagent),
- `e.message.isMeta` is falsy,
- `e.door` is one of `prompt`, `response`, `tool-result`, `delivery`,

normalize the stored row and queue each resulting event (a row can yield several):

| Event | Fields | From |
|---|---|---|
| `user_text` | `uuid, text` | user-role row's text blocks, joined |
| `assistant_text` | `uuid, text` | each assistant text block |
| `tool_use` | `uuid, toolUseId, tool, summary` | each `tool_use` block; `summary` is a one-line digest (`Bash: <command>`, `Edit: <path>`, `Read: <path>`, otherwise the tool name plus its first string input), cut to 120 chars |
| `tool_result` | `toolUseId, isError, preview` | each `tool_result` block; `preview` is its text, first 400 chars |

Thinking blocks, images and documents are dropped. Any text field over 64 KB is
truncated with a `…[truncated]` marker.

**State.** `turn.start` queues `state {state:"working"}`; `turn.complete` queues
`state {state:"idle"}`. Both call `next(e)` and pass its result through unchanged.

**Sync loop.** A `$.clock.every(1000, …)` timer started in `session.start` takes
the whole `pending` list and POSTs `/sync` with `{ paneId, sessionId, events }`
(events in the order they were queued). The response is `{ messages: [{ id, text }] }`,
the phone messages queued for this pane. Each is handed to
`$.prompt.submit({ text, asUser: true })` through a promise chain that the timer
does **not** await: `submit` only resolves when Claude goes idle and its turn
starts, and the loop must keep syncing (it is the heartbeat) while Claude works.
A tick is skipped while the previous one is still in flight.

**Outage handling.** A failed sync (transport error or non-2xx) drops the batch it
carried and sets `offline = true`; nothing is retried. While offline, each tick
sends an empty batch. When one succeeds, the mod clears the flag and queues hello +
snapshot, so the next tick resyncs the companion from scratch. A hot reload of the
mod re-runs `session.start`, which queues hello + snapshot as well.

## 2. Companion: chatbridge

**Socket server.** `net.Listen("unix", path)` after creating the parent dir 0700
and removing a stale socket; chmod the socket 0600. Plain `net/http` over it.

- `POST /sync` body `{ paneId, sessionId, events: [...] }` (at most 8 MB) →
  `200 { messages: [{ id, text }] }`. The events are applied in order (unknown
  types skipped), the pane's `lastSeen` is updated (the heartbeat), and the
  outbox is drained into the response. Missing `paneId` or bad JSON → 400.

**Per-pane state** (in memory, mutex-guarded):
- `epoch` (int, starts at 1), `sessionId`, `state` (`working|idle`, default `idle`),
  `lastSeen`, `events` ring buffer (cap 500, each with a per-pane monotonic `seq`),
  `outbox` (cap 20).
- `hello` records `sessionId`. Every `snapshot` bumps `epoch` and replaces the
  buffer (its events get fresh seqs from 1). The mod always sends a snapshot
  right after hello, so a new session, a resume, a `/clear` and a recovery all
  reset through the snapshot alone.
- **Chat-capable** ⇔ `now - lastSeen < 5s`, checked by a 1s ticker.
- When a pane disappears from the engine's `pane.list` view, its chat state is dropped.

**Subscriptions.** chatbridge exposes `Subscribe(paneId) (snapshot, <-chan Frame, cancel)`
to wsserver; live events, state changes and epoch resets fan out to subscribers.
Liveness flips (chat-capable on/off) are reported to the engine so the pane list
is re-broadcast with the new `chat` flag.

## 3. Protocol (WebSocket, companionProtocol 8)

- **Pane entries** gain `chat: bool` (omitted/false = not chat-capable).
- App → `chat_open {paneId}` → companion replies
  `chat_snapshot {paneId, epoch, state, events: [{seq, event}]}`, then streams
  `chat_event {paneId, epoch, seq, event}` and `chat_state {paneId, state}`.
  An epoch reset is sent as a fresh `chat_snapshot`.
- App → `chat_close {paneId}`: unsubscribe. Subscriptions also end when the WS closes.
- App → `chat_send {reqId, paneId, text}` → `chat_send_result {reqId, ok, error?}`.
  `ok` means queued in the outbox. Errors: `no_mod` (pane not chat-capable),
  `outbox_full`, `empty` (blank text).

## 4. App

**`PaneScreen`** replaces `DashboardScreen`'s direct `TerminalScreen(vm, pane)` call.
- It opens `ChatScreen` when `pane.chat` is true at open time, otherwise `TerminalScreen`.
- A top-bar toggle switches chat ⇄ terminal. The choice lives only while the screen
  is open.
- If `chat` turns false while in chat, show a banner "chat unavailable — mod not
  reporting" with an "Open terminal" button. No automatic switch.

**`ChatScreen`**
- Message list, newest at the bottom, auto-scrolls unless the user scrolled up.
  - `user_text`: right-aligned bubble.
  - `assistant_text`: full-width text. Fenced code blocks (```` ``` ````) are
    split out and drawn monospace on a `surfaceContainer` background; the rest is
    plain body text. No Markdown library in v1.
  - `tool_use`: compact one-line card `▸ <summary>`. Tap expands the matching
    `tool_result` preview. Red accent when `isError`.
- Status line above the input showing the existing working-spinner / idle glyph,
  driven by `state`.
- Input + Send, kept above the keyboard (`imePadding`, as `TerminalScreen` does).
  Send disabled while disconnected or text is blank.

**Pending messages.** On Send, a dimmed pending bubble is added (labelled "queued"
while `state == working`). It is confirmed (made normal) when a `user_text` with
identical text arrives in the same epoch. After 2 minutes unconfirmed it shows
"not delivered" with a Retry action. A `chat_send_result` error marks it failed
immediately, with the error text.

**Data.**
- `ChatRepository`: per-pane `StateFlow<ChatView>`. Sends `chat_open` when a chat
  screen subscribes, `chat_close` when it leaves, and re-sends `chat_open` after a
  WS reconnect.
- Pure reducer `reduce(view: ChatView, frame: ChatFrame, now: Instant): ChatView`:
  snapshot replaces the view; `chat_event` with a different epoch is ignored (a
  snapshot will follow); a `seq` not greater than the last seen is ignored;
  `user_text` confirms the oldest pending bubble with identical text.
- `Protocol.kt` / `CompanionClient`: parse `chat_snapshot`, `chat_event`,
  `chat_state`, `chat_send_result`; `sendChatOpen/Close`, `sendChat` (returns the
  result, keyed by `reqId`).

## 5. Failure cases

| Situation | Behaviour |
|---|---|
| Companion down | Mod requests fail silently; on the first successful poll the mod re-sends hello + snapshot → new epoch → app reloads the view. |
| Mod not installed / Claude outside herdr | `chat: false`, terminal as today. |
| Claude exits | Polling stops, `chat` turns false within 5s, banner shown. Buffer kept until the pane closes. |
| `/clear`, resume, second Claude in the pane | New hello + snapshot (`/clear` via `session.end` reason `clear`) → new epoch → app reloads. |
| Message sent while Claude is working | Queued; `$.prompt.submit` starts its turn when Claude goes idle; pending bubble says "queued". |
| Companion restarts with queued messages | Outbox is in memory and lost; pending bubbles time out to "not delivered" + Retry. |
| Huge tool output | Only a 400-char preview crosses the wire; any text field over 64 KB truncated. |
| App reconnects | `ChatRepository` re-sends `chat_open`, gets a fresh snapshot. |

## 6. Testing

- **Mod:** `claude plugin test mod/herdr-chat` (`*.test.ts`): row filtering
  (subagent, meta, injected doors dropped) and normalization per block type;
  no-op without `HERDR_PANE_ID`; outbox messages → `$.prompt.submit` in order;
  hello + snapshot re-sent after a failed then successful poll; `next(e)` result
  returned unchanged. Plus `claude plugin validate` and `tsc -p`.
- **Companion:** Go unit tests for chatbridge (seq/epoch, snapshot reset, ring cap,
  liveness expiry, outbox cap and drain, HTTP handlers over a temp socket, socket
  mode 0600); wsserver tests for `chat_open` → snapshot + live event, `chat_send`
  ok / `no_mod` / `outbox_full`, `chat` flag on panes.
- **App:** JVM tests for the reducer (epoch reset, stale epoch, duplicate seq,
  pending confirm, pending timeout) and for parsing every new frame.
- **Live (emulator + real Claude in herdr):** chat renders history and live rows;
  a phone message reaches Claude and starts a turn; queued while working; toggle to
  terminal and back; kill Claude → banner; restart companion → recovers on its own.

## Out of scope (v1)

Permission/approval cards, interrupting a running turn, slash-command UI,
streaming partial text (`turn.step`), images, subagent transcripts, persisting
chat history in the companion across restarts, non-Claude agents.
