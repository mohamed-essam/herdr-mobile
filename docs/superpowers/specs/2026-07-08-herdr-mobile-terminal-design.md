# herdr-mobile — interactive terminal (v2, phase 1)

**Status:** design approved 2026-07-08
**Predecessor:** `docs/superpowers/plans/2026-07-07-herdr-mobile-v1.md` (monitor + quick-reply, shipped)

## Summary

Add a real, interactive terminal to herdr-mobile. Tapping an **agent** pane on
the dashboard opens a live VT terminal attached to that pane. The companion
brokers the terminal by spawning `herdr agent attach <target>` on a PTY and
streaming raw bytes over the existing WebSocket; the app renders with the
Termux VT engine and sends input/resize back. Quick-reply is retired — the
terminal supersedes it.

## Goals

- Tap an agent pane → a live, interactive terminal for that pane.
- Correct rendering of real agent TUIs (alt-screen, 256-color SGR, cursor
  addressing, wide chars, scrollback).
- Keyboard input + the terminal keys a soft keyboard lacks (Esc, Tab, Ctrl,
  Alt, arrows, Ctrl-C, PgUp/PgDn).
- Correct geometry: the PTY window size tracks the on-screen terminal.
- Clean lifecycle: detaching or disconnecting tears down the PTY client without
  harming the underlying pane.

## Non-goals (explicitly deferred)

- **Non-agent (shell) panes.** `herdr agent attach` is agent-only (it rejects a
  plain pane even by `terminal_id`). Shell-pane terminals would require
  session-attach + zoom, which drags in herdr's full TUI chrome and nested-herdr
  handling. Out of scope for now.
- **Sidebar / tree navigation** (workspace → tab → pane grouping). Later. This
  phase keeps the existing flat dashboard as the launcher.
- **Auth / token on the WS.** Companion stays localhost-only for testing (app
  reaches it via the emulator's `10.0.2.2` bridge or Tailscale). The pluggable
  Authorizer seam remains unused.
- **Structural management** (split/move/close/create/rename via GUI).
- **Multi-host**, **binary WS frames** (base64-in-JSON for now).

## Verified assumptions (probed against herdr 0.7.1, protocol 14)

- `herdr agent attach <agent-target>` spawned on a PTY renders the live pane
  (probe: 60 KB, ~5.6k ANSI sequences, `\x1b[?1049h` alt-screen). Works even
  when the *probe* is nested inside herdr; the companion runs un-nested anyway.
- `agent attach` requires an **agent** target; a shell pane (`w7:p2`) fails with
  `agent_not_found`, even via its `terminal_id` — hence shell panes are deferred.
- Killing the attach client (SIGTERM/SIGKILL) detaches cleanly; the pane
  survives (confirmed via `pane.list` after).
- Every pane exposes `terminal_id`, `workspace_id`, `tab_id`, `agent`,
  `agent_status` via `pane.get` — enough for the flat dashboard today and the
  sidebar later.

## Architecture

```
 ┌────────── Android app ──────────┐            ┌────── companion ──────┐        herdr
 │ Dashboard (agent pane) ──tap──▶  │  term_open │  PtyManager           │
 │ TerminalScreen                   │──────────▶ │   └─ Session ─ pty ──▶ │ herdr agent
 │  AndroidView(TerminalView)       │  term_data │        creack/pty      │   attach <t>
 │  └ TerminalEmulator ◀── bytes ───│◀────────── │   reads pty → term_data│
 │  key toolbar / soft kbd ─input──▶│  term_input│   writes input → pty   │
 │  view resize ───────────────────▶│ term_resize│   SetWinsize           │
 └──────────────────────────────────┘  term_close└────────────────────────┘
```

One WebSocket (the v1 connection) now multiplexes control frames, pane updates,
**and** terminal byte streams, keyed by `termId`.

## Companion (Go)

### `internal/pty` package
- Depends on `github.com/creack/pty` (add to `go.mod`; `go mod download` needs
  network at build — available).
- `Session`:
  - `Start(target string, cols, rows uint16)`: `exec.Command("herdr","agent",
    "attach", target)`, env `TERM=xterm-256color`, started via `pty.StartWithSize`.
  - a read goroutine copies pty → an output callback (chunked, e.g. ≤32 KB).
  - `Write([]byte)` → pty (input).
  - `Resize(cols, rows)` → `pty.Setsize`.
  - `Close()`: kill the process, close the pty, join the goroutine.
  - reports `exit(code)` when the attach client ends.
- Testable **without herdr**: point `Start` at an injectable argv (default
  `herdr agent attach`); tests run `sh`/`cat` and assert round-trip, resize
  (`stty size`), and clean close.

### WS protocol (bump to protocol v2; frames are additive)
App → companion:
- `{"t":"term_open","reqId":R,"target":"<agent-target>"}`
- `{"t":"term_input","termId":T,"data":"<base64>"}`
- `{"t":"term_resize","termId":T,"cols":C,"rows":Rn}`
- `{"t":"term_close","termId":T}`

Companion → app:
- `{"t":"term_opened","reqId":R,"termId":T}`
- `{"t":"term_data","termId":T,"data":"<base64>"}`
- `{"t":"term_exit","termId":T,"code":N}`
- `{"t":"term_error","reqId":R,"termId":T,"message":M}`

PTY bytes are base64 (keeps the single JSON text-frame path). `target` is the
**pane_id** the app already holds — `herdr agent attach <pane_id>` resolves for
agent panes (probe: `attach w6:p1` succeeded; the shell pane `w7:p2` failed with
`agent_not_found`, which is why shell panes are deferred). No separate
agent-name lookup needed.

### wsserver / engine wiring
- `PtyManager` owns `termId → *Session` (termId = server-generated, e.g.
  `t<n>`). Per-connection ownership: a `term_open` binds the session to that WS
  client.
- On `term_open`: create session, start it, stream its output as `term_data`
  frames to that client; reply `term_opened`. On failure: `term_error`.
- On `term_input`/`term_resize`/`term_close`: look up by termId (must belong to
  this client) and act.
- On session exit: send `term_exit`, drop from the map.
- **On WS disconnect: close all of that client's sessions** (detach cleanly).
- Cap concurrent sessions (e.g. 8) to bound resource use; over-cap `term_open`
  → `term_error`.
- Backpressure: OkHttp/gorilla send buffers absorb bursts; if a client's send
  queue is saturated, drop-oldest or close the session with `term_error`
  (favor not blocking the read goroutine). Chunked reads keep frames small.

## App (Kotlin / Compose)

### Terminal engine (Termux, vendored, GPLv3)
- Vendor Termux `terminal-emulator` (pure-Java VT engine) and `terminal-view`
  (Android `View`) as local Gradle modules. **The whole app adopts GPLv3** —
  add `LICENSE` (GPL-3.0) + per-module NOTICE.
- We do **not** use Termux `TerminalSession` (it spawns a local subprocess).
  Instead drive `TerminalEmulator` directly: feed incoming `term_data` bytes via
  `emulator.append(bytes, len)`; host `TerminalView` in an `AndroidView`. Route
  `TerminalView` input (`TerminalOutput`/key + text callbacks) → `term_input`.
- Cols/rows come from the `TerminalView` layout (font metrics); on size change
  (rotation, keyboard show/hide) send `term_resize` and update the emulator.

### Terminal screen (Compose)
- `TerminalScreen(target)`: opens a terminal (`term_open`), shows the
  `TerminalView`, a top bar (pane name + status + close/detach), and a **key
  toolbar**: `Esc Tab Ctrl(sticky) Alt ↑ ↓ ← → Ctrl-C PgUp PgDn`. Ctrl/Alt are
  sticky modifiers applied to the next key.
- Lifecycle: opening sends `term_open`; leaving the screen / back sends
  `term_close`. `term_exit` shows an ended state + a way back.
- Reconnect: if the WS drops (v1 auto-reconnect), the terminal shows a
  "disconnected — reattach" state and re-opens on tap (a fresh attach; scrollback
  from before the drop is not restored in this phase).

### Dashboard changes
- **Retire quick-reply**: delete `QuickReplySheet`; remove the `peek/reply/
  quickKey` path from `DashboardViewModel` (and the `read_pane` client calls it
  used). The companion `read_pane` handler may stay (harmless; useful for the
  later sidebar).
- Tapping an **agent** pane opens `TerminalScreen`. Non-agent panes stay visible
  as status rows but are **not** tappable (no-op) — they're out of scope.
- Keep the redesigned terminal-aesthetic dashboard otherwise unchanged.

## Testing

- **Companion unit tests** (no herdr dependency): `PtyManager`/`Session` against
  `sh`/`cat` — byte round-trip, `Resize` reflected by `stty size`, `Close`
  reaps the process and goroutine; WS frame routing (`term_open`→`term_opened`,
  `term_input`→pty, pty→`term_data`, `term_close`/exit cleanup); disconnect
  closes sessions; over-cap → `term_error`.
- **App unit tests**: `term_*` frame (de)serialization in `Protocol.kt`
  (base64 round-trip, reqId/termId correlation).
- **Live on the emulator harness** (`scripts/dev-emulator.sh`): open omega3's
  terminal, confirm live render, type a command + see output, rotate/resize,
  detach, and verify the pane survives (`pane.list`).

## Licensing

Vendoring Termux (GPLv3) makes the combined app GPLv3. Add a top-level
`LICENSE` (GPL-3.0-or-later) and attribute Termux. The companion (Go) can remain
under its own license as a separate program communicating over a socket.

## Rollout / commits (high level)

1. Companion `pty` package + tests.
2. Companion WS `term_*` frames + `PtyManager` wiring + tests.
3. App: vendor Termux modules; `TerminalView` bridge + `term_*` in `Protocol.kt`.
4. App: `TerminalScreen` + key toolbar; dashboard tap-to-open; retire quick-reply.
5. Live validation on the emulator; docs/memory update.
