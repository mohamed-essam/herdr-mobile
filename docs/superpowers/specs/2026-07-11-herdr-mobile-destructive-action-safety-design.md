# herdr-mobile — Destructive-Action Safety — Design Spec

**Date:** 2026-07-11
**Components:** Go companion (`companion/`) + Android app (`app/`).
**Source:** UI/UX audit findings #1, #3, #6 (the "destructive-action safety" group).

> **Post-review amendment (2026-07-11):** takeover *detection* was dropped after
> the whole-branch review showed the marker text-scan produces false positives
> (a normal exit following any "taken over" bytes in the output was mislabelled).
> `term_exit` is now classified by exit code only: `closing → "closed"`,
> `code != 0 → "error"`, else `"ended"` — no `"takeover"` value, no marker scan. A
> real takeover shows the neutral "session ended". The `reason` field and the
> protocol 6→7 bump are retained. The takeover prose below is the original design;
> the shipped code omits it (fix commit `454cbc3`).

## Goal

Make the app's most destructive and least-reversible moments legible and honest:

1. **#6 — Reason-aware terminal exit.** Stop labelling every `term_exit` "taken
   over elsewhere". The companion classifies why the attach ended and sends a
   `reason`; the app shows accurate copy, defaulting to a neutral "session ended".
2. **#3 — Dead key bar reads as dead.** When keystrokes can't land (reconnecting
   or taken over), the key bar dims and its keys stop responding, instead of
   staying fully lit while silently discarding taps.
3. **#1 — Danger cue on Close.** The Close-confirmation dialog colours its
   destructive button as an error and bolds it, matching the red "Close" already
   used in the action sheet.

Non-goal: **undo** for Close. Close sends `action op=close`, which makes herdr
terminate the pane processes — irreversible, with no soft-delete in the protocol.
A true undo is out of scope; the danger cue is the mitigation.

## Global Constraints

- **companionProtocol:** bump `6 → 7` (welcome frame, `proto.go`).
- **`term_exit` gains an optional `reason` string.** The app parses it with a
  default of `""`; an empty or unrecognised reason maps to the neutral
  "Session ended" copy — never to a takeover claim. This keeps a new app working
  against an old companion (no reason field) without the false accusation.
- **Reason vocabulary (exact strings on the wire):** `"closed"`, `"takeover"`,
  `"ended"`, `"error"`. No other values.
- **Takeover marker:** the single substring the companion scans PTY output for is
  the constant `takeoverMarker = "taken over"` (case-insensitive match). It lives
  in exactly one place in `server.go` so a herdr wording change is a one-line fix.
- Reuse the existing theme (`statusColor`/`colorScheme.error`, typography,
  Catppuccin) and the existing `Reattach` overlay/flow — do not add a new overlay.
- Keep the phone's unconditional `--takeover` on open (unchanged policy).
- No new client→companion frames; only the `term_exit` payload grows.

## Reason classification (companion)

On each terminal session the companion already has: the PTY output stream
(`onData`), a client-initiated close path (`closeTerm`/`closeAll`), and the
process exit code (`onExit(code)`). Classify in this priority order:

1. If the companion initiated the teardown (a `closing` flag was set before
   `Session.Close()`), reason = **`"closed"`**.
2. Else if herdr's takeover banner was seen in the PTY output (see marker scan),
   reason = **`"takeover"`**.
3. Else if `code == 0`, reason = **`"ended"`**.
4. Else reason = **`"error"`** (nonzero exit — crash/disconnect).

**Marker scan.** herdr writes its takeover banner to the attach client's stdout
(the PTY the companion reads) just before the attach process exits — confirmed by
the 2026-07-08 terminal-polish design ("the 'terminal taken over' wording is
herdr's own output, fed into the emulator before the attach process exits"). The
companion keeps a small rolling tail per session so the marker is caught even if it
straddles two `onData` chunks:

- Maintain `tail []byte` (last `≤64` bytes of decoded output seen) per session.
- On each `onData(chunk)`: scan `lower(tail+chunk)` for `takeoverMarker`; if found,
  set the session's `sawTakeover` flag; then set `tail = last 64 bytes of (tail+chunk)`.
- This is best-effort: a missed marker falls through to `ended`/`error` (a neutral,
  never-wrong outcome), never a false `takeover`.

## Architecture & Data Flow

```
herdr terminal attach --takeover (PTY subprocess)
  │  stdout ──► pty.Session onData ──► [scan for marker; sawTakeover flag]
  │                                     └─► TermData(termId, b64)  ──► app emulator
  └─ exits(code) ──► onExit ──► classify(closing, sawTakeover, code)
                                └─► TermExit(termId, code, reason) ──► app

app CompanionClient.frames ──► TerminalScreen LaunchedEffect(termId)
   ServerFrame.TermExit(reason,code) ──► exit = terminalExitCopy(reason, code)
   ──► takenOver overlay (title/detail) + top-bar subtitle
```

## Component 1 — Companion (Go)

### `companion/internal/proto/proto.go`
- `func TermExit(termID string, code int, reason string) []byte` — add
  `"reason": reason` to the map.
- `welcome`: change `"companionProtocol": 6` to `7`.

### `companion/internal/wsserver/server.go`
- Add a per-session takeover/closing signal. Two workable shapes; pick the one that
  fits `client`'s existing session bookkeeping:
  - Extend the stored session with `sawTakeover *atomic.Bool` and `closing
    *atomic.Bool` (a small wrapper struct in the `sessions` map), **or**
  - Track two `map[string]*atomic.Bool` keyed by termID alongside `sessions`.
  The implementer chooses based on the current `sessions map[string]*pty.Session`
  shape; the plan will pin the exact structure.
- Module-level: `const takeoverMarker = "taken over"`.
- In `openTerm`, the `onData` callback additionally feeds a marker scanner
  (rolling 64-byte tail, case-insensitive `strings.Contains`) that sets
  `sawTakeover` for that termID. The `onExit` callback classifies:
  ```
  reason := "ended"
  if closing.Load()         { reason = "closed" }
  else if sawTakeover.Load(){ reason = "takeover" }
  else if code != 0         { reason = "error" }
  sendBlocking(ctx, c, proto.TermExit(termID, code, reason))
  ```
- `closeTerm(id)` and `closeAll()` set the `closing` flag for the affected
  session(s) **before** calling `sess.Close()`, so the induced exit classifies as
  `"closed"`.

### Tests (Go)
- `proto_test.go`: `TermExit("t1", 3, "takeover")` marshals `code:3` and
  `reason:"takeover"`; welcome asserts `companionProtocol == 7`.
- `server_test.go`:
  - process ends on its own with code 0 → `term_exit` reason `"ended"`.
  - `term_close` induces exit → reason `"closed"`.
  - a fake attach whose output contains "…taken over…" then exits → reason
    `"takeover"` (use the existing fake-attach test harness / `attachArgv`
    override that the suite already uses; the plan pins the exact seam).

## Component 2 — App (Kotlin)

### `app/.../net/Protocol.kt`
- `data class TermExit(val termId: String, val code: Int, val reason: String = "")`.
- Parser: `reason = obj["reason"]?.jsonPrimitive?.content ?: ""`.

### `app/.../ui/TerminalScreen.kt`

**#6 — reason-aware copy.** Add a pure, top-level, unit-testable helper beside the
existing `showReconnectOverlay`:
```kotlin
data class ExitCopy(val title: String, val detail: String)

fun terminalExitCopy(reason: String, code: Int): ExitCopy = when (reason) {
    "takeover" -> ExitCopy("taken over on another client",
                           "this terminal is now attached elsewhere")
    "error"    -> ExitCopy("terminal disconnected", "ended unexpectedly (code $code)")
    else       -> ExitCopy("session ended", "the terminal process exited")
    //           ↑ covers "ended", "closed", "", and any unknown reason
}
```
- The `LaunchedEffect(termId)` `TermExit` branch stores the `ExitCopy` in state
  (e.g. `var exit by remember { mutableStateOf<ExitCopy?>(null) }`) in addition to
  the existing `takenOver = true; termId = null`. The subtitle uses `exit?.title`;
  the overlay shows `exit.title` (headline) + `exit.detail` (body) in place of the
  hard-coded `"terminal ended or was taken over elsewhere"`.
- Reattach clears `exit` alongside its existing resets.
- Remove the unconditional `status = "taken over elsewhere"` string; the subtitle
  now derives from `exit`.

**#3 — key bar liveness.** Add a pure helper:
```kotlin
fun keysLive(connected: Boolean, termId: String?, takenOver: Boolean): Boolean =
    connected && termId != null && !takenOver
```
- `KeyToolbar(session, mods, enabled)` gains an `enabled: Boolean` param
  (`= keysLive(connected, termId, takenOver)` at the call site, line 239).
- When `!enabled`: wrap the `Surface`/content in `Modifier.alpha(0.4f)` and pass
  `enabled` down so `KeyCap`, `ArrowCap`, and `ModifierKey` apply
  `Modifier.clickable(enabled = enabled, …)` / `combinedClickable(enabled = enabled,
  …)`, and `DPad(mono, enabled) { … }` forwards it. Collapse/expand handles stay
  interactive (they only toggle local UI, send no bytes).

**#1 — Close danger cue.** In `DashboardScreen.kt` (the close-confirm `AlertDialog`,
lines 164-180): the confirm `TextButton`'s `Text("Close")` uses
`color = MaterialTheme.colorScheme.error` and `fontWeight = FontWeight.Bold`. The
dismiss `TextButton` ("Cancel") is unchanged (low-emphasis). Button order is
unchanged (platform-standard: dismiss left, confirm right).

### Tests (Kotlin, JVM)
- `terminalExitCopy`: `"takeover"`, `"ended"`, `"error"` (asserts code interpolated),
  `"closed"`, `""`, and an unknown value all map to the specified copy (unknown/empty
  → "session ended", never a takeover claim).
- `keysLive`: truth table — true only when `connected && termId != null &&
  !takenOver`; false for each of (disconnected), (null termId), (takenOver).

## What is explicitly unchanged

- Takeover *policy* (phone always `--takeover` on open).
- The WS-drop reconnect flow (clear termId, "reconnecting…", auto-reattach) and the
  `showReconnectOverlay` gate.
- The action-sheet "Close" (already red) and `needsCloseConfirm` logic.
- Button order / layout of the confirm dialog (only the confirm button's colour +
  weight change).

## Build / Test Commands

- Companion: `cd companion && go test ./...`
- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

## Live Validation (device)

- Open a cleanly-finished (`done`) pane → overlay reads **"session ended"**, not
  "taken over".
- Attach the same pane from another client (desktop TUI / emulator) to force a real
  takeover → overlay reads **"taken over on another client"**; Reattach restores a
  live terminal.
- Drop the WS mid-session (companion restart on the tailnet) → the key bar dims and
  its keys are inert (tap does nothing visible) → on reconnect it re-lights and works.
- Long-press → Close on a workspace/agent pane → confirm dialog shows a **red, bold
  "Close"** next to a low-emphasis "Cancel".
