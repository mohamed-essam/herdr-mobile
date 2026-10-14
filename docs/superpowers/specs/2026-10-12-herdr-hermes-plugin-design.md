# herdr-hermes: Hermes chat view for herdr-mobile

Date: 2026-10-12. Status: draft for review.

## Goal

A Hermes session running in a herdr pane opens as a chat on the phone, as a
Claude Code session does today with the `herdr-chat` mod. The phone shows the
history, live replies, tool calls and working/idle state, and can send
prompts and slash commands into the session.

Out of scope for v1: answering Hermes questions from the phone, agent threads
and background-task rows, token usage, Claude Code's general mod API (panes,
bands, toasts).

## Decisions made

- **Approach:** a native Hermes plugin, written in Python, that speaks the
  companion's existing `/sync` protocol over `chat.sock`. The companion's wire
  format does not change.
- **Interaction:** prompts and slash commands from the phone go into the
  session. Questions (`clarify`, approvals, sudo) are mirrored read-only: the
  phone shows them and says to answer in the terminal. Reason: Hermes'
  `pre_tool_call` can only proceed, block or modify args, so a phone answer
  would mean blocking the terminal dialog while the phone is polled. The
  terminal stays always responsive.
- **Location:** `hermes/herdr-hermes/` in this repo, next to `mod/`.
- **Agent identity:** `hello` gains an optional `agent` field (`"hermes"` from
  this plugin). The companion treats an empty or absent `agent` as `"claude"`,
  so existing `herdr-chat` mods keep working unchanged. The companion stores
  the value per pane and uses it wherever behavior is agent-specific.

## What the mod does today (the contract to match)

`mod/herdr-chat/hooks/register.ts` runs one loop per session:

1. It reads `HERDR_PANE_ID` and the socket path (`HERDR_MOBILE_CHAT_SOCK`, else
   `$XDG_RUNTIME_DIR/herdr-mobile/chat.sock`, else chat is off, with no `/tmp`
   fallback).
2. Each tick it POSTs `http://chat/sync` over the Unix socket with
   `{ paneId, sessionId, events, images?, usage? }`.
3. The reply `{ messages, resync?, threads }` carries the phone's prompts
   (`messages: [{ id, text }]`). It is also the heartbeat. `resync: true`
   means the companion has no history for this pane and wants a
   `hello` + `snapshot_begin / snapshot_chunk / snapshot_end` sequence.
4. Events are `user_text`, `assistant_text`, `tool_use`, `tool_result`,
   `command_output` (`mod/herdr-chat/hooks/normalize.ts`). Controls are
   `hello`, `state` (`working` | `idle`), `commands`.
5. If a POST fails, the mod goes offline, drops its queue, and resyncs when the
   companion is reachable again.

## Design

### Units

Each has one purpose and is testable alone. Python 3, standard library only
(Unix-socket HTTP via `http.client` over `socket.AF_UNIX`).

| File | Purpose | Depends on |
|---|---|---|
| `__init__.py` | `register(ctx)`: subscribes hooks, starts the sync loop, registers commands | the units below |
| `socket_client.py` | `sync(body) -> reply`: one POST to `/sync` over the Unix socket, with the same socket-path and directory-permission rules as the companion | stdlib |
| `events.py` | Pure functions: Hermes hook payloads to `ChatEvent` rows, with the same caps as `normalize.ts` (`MAX_TEXT` 64 KiB, `PREVIEW` 400, `SUMMARY` 120) | none |
| `history.py` | Builds the `hello` + snapshot sequence from Hermes' stored session messages, newest events only, chunked under the 4 MB body limit | `events.py` |
| `loop.py` | The state machine: queue, offline/resync handling, heartbeat, inbound prompt dispatch | `socket_client.py` |
| `inject.py` | Phone prompt or slash command to `ctx.inject_message` / the registered command | Hermes plugin API |

### Hermes hook to chat event mapping

| Hermes hook | Chat output |
|---|---|
| `on_session_start` | `hello { sessionId, cwd, agent: "hermes" }`, then resync history |
| `pre_llm_call` (user turn) | `user_text` (skipped when it was a phone-injected prompt, so it isn't echoed twice; deduped by text and time window) |
| `post_llm_call` | `assistant_text` |
| `pre_tool_call` | `tool_use { tool, summary }` |
| `post_tool_call` | `tool_result { isError, preview }` |
| `on_stream_start` / `agent_loop_stopped` | `state: working` / `state: idle` |
| `on_human_input_request` | `assistant_text` notice "Hermes is asking: ...; answer in the terminal" (read-only) |
| `on_session_end` / `on_session_reset` | final `idle`, then the loop stops |

Streaming deltas are not forwarded: the Claude mod sends whole messages, so
the phone receives `assistant_text` at `post_llm_call`. This keeps the
protocol unchanged.

### Inbound path

The `/sync` reply's `messages` are submitted in order, serialized so two
phone messages cannot interleave. A message that names a registered command
runs it. Anything else goes through `ctx.inject_message(text, "user",
session_key=...)`. For CLI sessions this uses the attached REPL queues and
needs nothing more. For TUI or gateway sessions it needs
`plugins.entries.herdr-hermes.allow_gateway_injection: true`, which the README
documents and `hermes plugins doctor` should flag when missing.

### Failure behavior

- **No socket or no `HERDR_PANE_ID`:** the plugin loads and does nothing. A
  Hermes session outside herdr is unaffected.
- **Companion down or POST fails:** offline mode as in the mod; the queue is
  dropped and a full resync is sent on reconnect.
- **Hook callbacks never block the agent:** they only enqueue. Network work
  runs on one daemon thread, since Hermes callbacks have a 30 s timeout and run
  off the token path.
- **`inject_message` returns False:** the phone gets a `command_output` row
  with `isError: true` explaining injection is not allowed for this surface.

### Security

Same rule as the companion and the mod: the socket must be in a private
directory; no `/tmp` fallback. The plugin never reads secrets. Hermes payloads
for sudo or approvals are already redacted by the host and the plugin
forwards only the redacted prompt.

### Testing

- `events.py` and `history.py`: unit tests (pytest) over recorded Hermes
  payloads, asserting exact `ChatEvent` output, caps and chunk sizing.
- `loop.py`: tests against a fake socket server that returns scripted
  `/sync` replies (resync, messages, 500s), covering offline and resync paths.
- Backward compatibility: a Go test sends a `hello` with no `agent` and
  asserts the pane is treated as `claude`.
- Contract test: a Go test in `companion/internal/chatbridge` feeds a captured
  plugin `/sync` body to the real handler, so a protocol drift on either side
  fails CI.
- `hermes plugins validate hermes/herdr-hermes` in CI.
- Manual: run Hermes in a herdr pane, open it on the phone.

## Open questions to settle during planning

1. Where the companion assumes Claude today (to be found by grep during
   planning) and which of those places need to read the pane's `agent`. An
   older companion must ignore the new field.
2. Which hook fires for the user's own typed turn: `pre_llm_call` carries it, but
   the exact field name for the user message must be confirmed from
   `agent/` before writing `events.py`.
3. Whether Hermes keeps enough stored messages per session for `history.py` to
   rebuild a snapshot, or whether the plugin must buffer events from
   `on_session_start` on.
4. Packaging: ship as a directory plugin installed with `hermes plugins
   install`, with a README section beside the existing Claude Code one.
