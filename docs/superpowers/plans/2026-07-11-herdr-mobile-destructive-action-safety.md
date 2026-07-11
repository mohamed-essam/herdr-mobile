# Destructive-Action Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix audit findings #1/#3/#6 — a reason-aware terminal exit (companion classifies why an attach ended; app shows honest copy), a key bar that visibly dies when input can't land, and a danger cue on the Close confirmation.

**Architecture:** The Go companion tags each `term_exit` with a `reason` derived from a per-session `closing` flag, a best-effort takeover-marker scan of the PTY stream, and the exit code. The app parses `reason` (optional, defaults neutral), maps it to copy via a pure helper, gates the key bar on a pure `keysLive` predicate, and reddens the confirm button.

**Tech Stack:** Go (companion, `coder/websocket`, `creack/pty`), Kotlin/Jetpack Compose (app), JUnit + Go `testing`.

> **Post-review amendment (2026-07-11):** the whole-branch review found the
> takeover text-scan produces false positives (the `sawTakeover` flag was sticky
> for the whole session, so any normal exit after the bytes "taken over" appeared
> in output — e.g. from `cat`-ing a doc — was mislabelled a takeover). Decision:
> **takeover detection was dropped entirely.** `term_exit` is classified by exit
> code only — `closing → "closed"`, `code != 0 → "error"`, else `"ended"`. There
> is no `"takeover"` reason value and no marker scan. The `reason` field, the
> `companionProtocol` 6→7 bump, and the `termSession`/`closing` wrapper stay. The
> `terminalExitCopy` `"takeover"` branch was removed; a real takeover now shows the
> neutral "session ended". The takeover-related steps below are kept for the
> historical record — the shipped code omits them (fix commit `454cbc3`).

## Global Constraints

- `companionProtocol`: bump `6 → 7` in the welcome frame (`proto.go`).
- `term_exit` gains an **optional** `reason` string; the app defaults it to `""`. Empty/unknown reason → neutral **"session ended"** copy, never a takeover claim.
- Reason vocabulary — exactly these on the wire: `"closed"`, `"takeover"`, `"ended"`, `"error"`.
- Takeover marker: one module-level constant `takeoverMarker = "taken over"`, matched case-insensitively against the PTY output stream. Best-effort; a miss falls through to `ended`/`error` (never a wrong claim).
- Classification priority (companion): `closing` → `"closed"`; else `sawTakeover` → `"takeover"`; else `code==0` → `"ended"`; else `"error"`.
- Reuse existing theme (`statusColor`/`colorScheme.error`, Catppuccin), the existing Reattach overlay/flow, and the `--takeover`-on-open policy (unchanged).
- No new client→companion frames; only the `term_exit` payload grows.
- No undo for Close (irreversible process kill; out of scope).

---

## Task 1: Companion — reason-aware `term_exit`

**Files:**
- Modify: `companion/internal/proto/proto.go` (TermExit signature, companionProtocol)
- Modify: `companion/internal/wsserver/server.go` (session wrapper, marker scan, closing flag, classification)
- Test: `companion/internal/proto/proto_test.go`, `companion/internal/wsserver/server_test.go`

**Interfaces:**
- Produces (wire): `term_exit` frame now carries `"reason": <string>`, and `welcome` carries `"companionProtocol": 7`. Task 2 (app) consumes `reason`.

- [ ] **Step 1: Write failing proto test**

In `companion/internal/proto/proto_test.go`, update the existing `TermExit` assertion and add a reason assertion:

```go
func TestTermExit(t *testing.T) {
	var got map[string]any
	json.Unmarshal(TermExit("t1", 3, "takeover"), &got)
	if got["t"] != "term_exit" || got["termId"] != "t1" || got["code"].(float64) != 3 || got["reason"] != "takeover" {
		t.Fatalf("bad term_exit: %v", got)
	}
}
```

(If the existing test is inline in another function, adapt the call site to the 3-arg form and add the `reason` check.)

- [ ] **Step 2: Run it — expect FAIL (compile error: too few args)**

Run: `cd companion && go test ./internal/proto/`
Expected: build failure — `TermExit` wants 2 args.

- [ ] **Step 3: Update `proto.go`**

```go
func TermExit(termID string, code int, reason string) []byte {
	return must(map[string]any{"t": "term_exit", "termId": termID, "code": code, "reason": reason})
}
```

And in the `welcome` frame, change `"companionProtocol": 6` to `"companionProtocol": 7`.

- [ ] **Step 4: Add the session wrapper + classification in `server.go`**

Add a module-level marker and a per-session wrapper (near the other type decls):

```go
const takeoverMarker = "taken over" // herdr's displaced-attach banner (best-effort)

type termSession struct {
	sess    *pty.Session
	closing atomic.Bool
}
```

Change the field type: `sessions map[string]*termSession` (line ~60), and the client init (line ~112) to `sessions: map[string]*termSession{}`.

Rewrite `get`, `closeTerm`, `closeAll`:

```go
func (c *client) get(id string) *pty.Session {
	c.smu.Lock()
	defer c.smu.Unlock()
	if ts := c.sessions[id]; ts != nil {
		return ts.sess
	}
	return nil
}

func (c *client) closeTerm(id string) {
	c.smu.Lock()
	ts := c.sessions[id]
	delete(c.sessions, id)
	c.smu.Unlock()
	if ts != nil {
		ts.closing.Store(true) // classify the induced exit as "closed"
		_ = ts.sess.Close()
	}
}

func (c *client) closeAll() {
	c.smu.Lock()
	all := c.sessions
	c.sessions = map[string]*termSession{}
	c.smu.Unlock()
	for _, ts := range all {
		ts.closing.Store(true)
		_ = ts.sess.Close()
	}
}
```

In `openTerm`, allocate the wrapper **before** `pty.Start`, keep the marker tail/flag as closure-locals (onData and onExit share one goroutine, so no atomics needed there), classify **before** calling `closeTerm`, and store the wrapper after Start:

```go
ts := &termSession{}
var tail []byte
sawTakeover := false

sess, err := pty.Start(s.attachArgv(target), uint16(cols), uint16(rows),
	func(b []byte) {
		if !sawTakeover {
			scan := append(append([]byte(nil), tail...), b...)
			if strings.Contains(strings.ToLower(string(scan)), takeoverMarker) {
				sawTakeover = true
			}
			if len(scan) > 64 {
				tail = append(tail[:0], scan[len(scan)-64:]...)
			} else {
				tail = scan
			}
		}
		sendBlocking(ctx, c, proto.TermData(termID, base64.StdEncoding.EncodeToString(b)))
	},
	func(code int) {
		reason := "ended"
		switch {
		case ts.closing.Load():
			reason = "closed"
		case sawTakeover:
			reason = "takeover"
		case code != 0:
			reason = "error"
		}
		c.closeTerm(termID)
		sendBlocking(ctx, c, proto.TermExit(termID, code, reason))
	},
)
if err != nil {
	c.send <- proto.TermError(reqID, "", err.Error())
	return
}
ts.sess = sess
c.smu.Lock()
c.sessions[termID] = ts
c.smu.Unlock()
c.send <- proto.TermOpened(reqID, termID)
```

(`sync/atomic` and `strings` are already imported.)

- [ ] **Step 5: Fix the existing welcome-protocol test if present**

Run: `cd companion && grep -rn "companionProtocol" internal/`
If a test asserts `companionProtocol == 6`, update it to `7`.

- [ ] **Step 6: Add server classification tests**

In `companion/internal/wsserver/server_test.go`, add three tests (mirror `TestTermExitOnProcessEnd`'s harness — `attachArgv` override + `readUntil`):

```go
func TestTermExitReasonEnded(t *testing.T) {
	s := newTestServer(t) // use whatever constructor the existing tests use
	s.attachArgv = func(target string) []string { return []string{"sh", "-c", "exit 0"} }
	// ... open a WS client, term_open, then:
	f := readUntil(t, ctx, c, "term_exit")
	if f["reason"] != "ended" {
		t.Fatalf("want ended, got %v", f["reason"])
	}
}

func TestTermExitReasonTakeover(t *testing.T) {
	s := newTestServer(t)
	s.attachArgv = func(target string) []string { return []string{"sh", "-c", "printf 'session taken over here'; exit 0"} }
	// ... term_open, then:
	f := readUntil(t, ctx, c, "term_exit")
	if f["reason"] != "takeover" {
		t.Fatalf("want takeover, got %v", f["reason"])
	}
}

func TestTermExitReasonClosed(t *testing.T) {
	s := newTestServer(t)
	s.attachArgv = func(target string) []string { return []string{"sh", "-c", "sleep 30"} }
	// ... term_open, read term_opened, send {"t":"term_close","termId":<id>}, then:
	f := readUntil(t, ctx, c, "term_exit")
	if f["reason"] != "closed" {
		t.Fatalf("want closed, got %v", f["reason"])
	}
}
```

Match the exact client/WS setup boilerplate to the existing tests in the file (constructor name, `ctx`, `c` dial, sending `term_open` with reqId/target, extracting `termId` from the `term_opened` frame). Reuse `readUntil`. For the takeover test, the printf output reaches `onData` (setting the flag) before the process exits, on the same session goroutine.

- [ ] **Step 7: Run companion tests — expect PASS**

Run: `cd companion && go test ./...`
Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add companion/internal/proto/proto.go companion/internal/proto/proto_test.go companion/internal/wsserver/server.go companion/internal/wsserver/server_test.go
git commit -m "feat(companion): classify term_exit reason (closed/takeover/ended/error)"
```

---

## Task 2: App — reason-aware exit copy (#6)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt` (TermExit.reason + parse)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (`terminalExitCopy` helper, overlay/subtitle wiring)
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt` (reason parse), new `app/app/src/test/java/dev/herdr/mobile/TerminalExitTest.kt`

**Interfaces:**
- Consumes (wire): `term_exit` `reason` from Task 1.
- Produces: `data class ExitCopy(val title: String, val detail: String)` and `fun terminalExitCopy(reason: String, code: Int): ExitCopy` (top-level in `TerminalScreen.kt`, package `dev.herdr.mobile.ui`). Task 3 adds a sibling `keysLive`.

- [ ] **Step 1: Write failing tests**

Extend `ProtocolTest.kt` term_exit case to assert `reason`:

```kotlin
@Test fun termExitParsesReason() {
    val x = parseServerFrame("""{"t":"term_exit","termId":"t1","code":3,"reason":"takeover"}""")
    x as ServerFrame.TermExit
    assertEquals(3, x.code)
    assertEquals("takeover", x.reason)
    // missing reason defaults to ""
    val y = parseServerFrame("""{"t":"term_exit","termId":"t2","code":0}""") as ServerFrame.TermExit
    assertEquals("", y.reason)
}
```

Create `app/app/src/test/java/dev/herdr/mobile/TerminalExitTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.ui.terminalExitCopy
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalExitTest {
    @Test fun takeover() {
        assertEquals("taken over on another client", terminalExitCopy("takeover", 0).title)
    }
    @Test fun endedClosedUnknownAreNeutral() {
        for (r in listOf("ended", "closed", "", "weird-future-value")) {
            assertEquals("session ended", terminalExitCopy(r, 0).title)
        }
    }
    @Test fun errorShowsCode() {
        val c = terminalExitCopy("error", 137)
        assertEquals("terminal disconnected", c.title)
        assertEquals(true, c.detail.contains("137"))
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TerminalExitTest" --tests "dev.herdr.mobile.ProtocolTest"`
Expected: FAIL — `reason` unknown / `terminalExitCopy` unresolved.

- [ ] **Step 3: Add `reason` to the wire type**

In `Protocol.kt`:
- `data class TermExit(val termId: String, val code: Int, val reason: String = "") : ServerFrame`
- In `parseServerFrame`, the `"term_exit"` branch:

```kotlin
"term_exit" -> ServerFrame.TermExit(
    obj["termId"]!!.jsonPrimitive.content,
    obj["code"]?.jsonPrimitive?.int ?: 0,
    obj["reason"]?.jsonPrimitive?.content ?: "")
```

- [ ] **Step 4: Add the `terminalExitCopy` helper**

In `TerminalScreen.kt`, top-level (next to `showReconnectOverlay`):

```kotlin
/** Overlay/subtitle copy for a terminal that ended, keyed by the companion's
 *  reason. Unknown/empty reason falls back to the neutral "session ended" — we
 *  never claim a takeover we can't prove. */
data class ExitCopy(val title: String, val detail: String)

fun terminalExitCopy(reason: String, code: Int): ExitCopy = when (reason) {
    "takeover" -> ExitCopy("taken over on another client", "this terminal is now attached elsewhere")
    "error"    -> ExitCopy("terminal disconnected", "ended unexpectedly (code $code)")
    else       -> ExitCopy("session ended", "the terminal process exited")
}
```

- [ ] **Step 5: Wire it into state + overlay + subtitle**

In `TerminalScreen`:
- Add state near the other `remember`s: `var exit by remember { mutableStateOf<ExitCopy?>(null) }`
- In `LaunchedEffect(termId)`, the `ServerFrame.TermExit` branch — replace the hard-coded status with:

```kotlin
is ServerFrame.TermExit -> if (f.termId == id) {
    val copy = terminalExitCopy(f.reason, f.code)
    exit = copy
    status = copy.title
    termId = null
    takenOver = true
}
```

- In the `if (takenOver)` overlay block, replace the single hard-coded `Text("terminal ended or was taken over elsewhere", …)` with title + detail:

```kotlin
Text(exit?.title ?: "session ended",
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurface,
    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
Spacer(Modifier.height(4.dp))
Text(exit?.detail ?: "",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
```

- In the Reattach `onClick` (and `attachOnce` success path already sets `takenOver = false`), also clear `exit = null` so a re-attach drops the overlay copy. Set `exit = null` in the Reattach button's `onClick` before `scope.launch { attachOnce() }`.

- [ ] **Step 6: Run tests — expect PASS**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TerminalExitTest" --tests "dev.herdr.mobile.ProtocolTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt app/app/src/test/java/dev/herdr/mobile/TerminalExitTest.kt
git commit -m "feat(app): reason-aware terminal exit copy (no false 'taken over')"
```

---

## Task 3: App — key bar dies visibly when input can't land (#3)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (`keysLive` helper; `enabled` threaded through `KeyToolbar`/`KeyCap`/`ArrowCap`/`ModifierKey`/`DPad`)
- Test: `app/app/src/test/java/dev/herdr/mobile/TerminalExitTest.kt` (add `keysLive` cases)

**Interfaces:**
- Consumes: `connected` (already collected in `TerminalScreen`), `termId`, `takenOver`.
- Produces: `fun keysLive(connected: Boolean, termId: String?, takenOver: Boolean): Boolean`.

- [ ] **Step 1: Write failing test**

Add to `TerminalExitTest.kt`:

```kotlin
import dev.herdr.mobile.ui.keysLive
// ...
@Test fun keysLiveTruthTable() {
    assertEquals(true,  keysLive(true,  "t1", false))
    assertEquals(false, keysLive(false, "t1", false)) // disconnected
    assertEquals(false, keysLive(true,  null, false)) // no attach
    assertEquals(false, keysLive(true,  "t1", true))  // taken over
}
```

- [ ] **Step 2: Run — expect FAIL** (`keysLive` unresolved)

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TerminalExitTest"`
Expected: FAIL.

- [ ] **Step 3: Add the helper**

In `TerminalScreen.kt`, top-level:

```kotlin
/** The key bar's taps only reach the PTY when we hold a live attach on a live
 *  socket; otherwise sendInput no-ops. Gate the bar's interactivity on this so a
 *  dead bar reads as dead instead of silently swallowing keystrokes. */
fun keysLive(connected: Boolean, termId: String?, takenOver: Boolean): Boolean =
    connected && termId != null && !takenOver
```

- [ ] **Step 4: Thread `enabled` through the key bar**

- Call site (currently `session?.let { KeyToolbar(it, mods) }`):

```kotlin
session?.let { KeyToolbar(it, mods, enabled = keysLive(connected, termId, takenOver)) }
```

- `KeyToolbar(session, mods, enabled)`: add `enabled: Boolean` param. Wrap the expanded `Row` content in reduced opacity when not live — apply `Modifier.alpha(if (enabled) 1f else 0.4f)` to the `Row` (keep the `Surface` background full so the bar still reads as present). The collapse/expand handles stay fully interactive (local UI only).
- Pass `enabled` to each key composable and gate its clickable:
  - `KeyCap(label, mono, modifier, enabled) { … }` → `Modifier.clickable(enabled = enabled, onClick = onClick)` (inside the `Keycap` `click` arg).
  - `ArrowCap(label, mono, enabled) { … }` → same, `clickable(enabled = enabled, …)`.
  - `ModifierKey(label, state, mono, modifier, enabled, onTap, onLock)` → `combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLock)`.
  - `DPad(mono, enabled) { … }` forwards `enabled` to its three `ArrowCap`s.
- Add import `androidx.compose.ui.draw.alpha`.

(Modifiers' one-shot/lock still function structurally, but with `enabled=false` the taps are inert and the bar is dimmed, matching the dead state.)

- [ ] **Step 5: Run tests + build — expect PASS**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt app/app/src/test/java/dev/herdr/mobile/TerminalExitTest.kt
git commit -m "feat(app): dim + disable key bar when input can't reach the PTY"
```

---

## Task 4: App — danger cue on Close confirmation (#1)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (confirm dialog button)

**Interfaces:** none (leaf styling change).

- [ ] **Step 1: Redden + bold the confirm button**

In the close-confirm `AlertDialog` `confirmButton` (lines ~169-174), change the `Text("Close")`:

```kotlin
confirmButton = {
    TextButton(onClick = {
        vm.closeNode(target.kind.wire, target.id)
        confirmTarget = null
        alsoCloses = emptyList()
    }) {
        Text("Close",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold)
    }
},
```

The `dismissButton` ("Cancel") and button order are unchanged. `FontWeight` and `MaterialTheme` are already imported in `DashboardScreen.kt`.

- [ ] **Step 2: Build + full test suite — expect green**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): red, bold Close in the destructive confirm dialog"
```

---

## Final Verification (whole branch)

- Companion: `cd companion && go test ./...`
- App: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
- Live on device (deploy the new companion + APK):
  - Open a cleanly-finished (`done`) pane → overlay reads **"session ended"**, not "taken over".
  - Force a real takeover (attach the same pane from the emulator/desktop TUI) → overlay reads **"taken over on another client"**; Reattach restores a live terminal.
  - Drop the WS mid-session (restart the companion on the tailnet) → the key bar dims and its keys are inert → on reconnect it re-lights and works.
  - Long-press → Close on a workspace/agent pane → confirm dialog shows a **red, bold "Close"** beside a low-emphasis "Cancel".
