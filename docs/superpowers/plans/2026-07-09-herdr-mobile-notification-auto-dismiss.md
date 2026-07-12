# Notification Auto-Dismiss on Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-dismiss a phone's blocked/finished notification when the agent starts working again, even with the app backgrounded or killed.

**Architecture:** The Go companion already emits a `state.Transition{From, To}` on every agent-status change. Add a `clear` push kind fired on any `→working` transition; the Android app routes `kind == "clear"` to `NotificationManager.cancel(paneId.hashCode())` instead of posting. Stateless and idempotent — no outstanding-notification bookkeeping.

**Tech Stack:** Go (companion, `internal/notify` + `internal/engine`), Kotlin/Compose Android app (`push/` package), UnifiedPush.

## Global Constraints

- New push `kind` value is exactly `"clear"` (lowercase).
- Push payloads are NOT versioned — this change is additive, no `companionProtocol` bump.
- A `clear` push carries `paneId` and `workspaceId` only; title and body stay empty.
- Notifications are keyed by `paneId.hashCode()` (must match the existing `Notifications.post` key).
- Go companion test command: `go -C ~/herdr-mobile/companion test ./...`
- Android build: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`

---

### Task 1: Companion emits a `clear` push on resume-to-working

**Files:**
- Modify: `companion/internal/notify/notifier.go` (`ShouldNotify`, the `switch` block)
- Test: `companion/internal/notify/notifier_test.go`
- Test: `companion/internal/engine/engine_test.go` (add one end-to-end case)

**Interfaces:**
- Consumes: `state.Transition{PaneID, WorkspaceID, From, To string}` (existing); `notify.Push{Kind, PaneID, WorkspaceID, Title, Body string}` (existing).
- Produces: `ShouldNotify` returns `Push{Kind: "clear", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID}, true` when `tr.To == "working"`. The engine fires this push immediately via `e.fire` (no debounce), because `handleTransition` only special-cases `push.Kind == "finished"`.

- [ ] **Step 1: Replace the "ignore idle→working" unit test with the clear-push expectation**

In `companion/internal/notify/notifier_test.go`, DELETE `TestShouldNotifyIgnoresOther` entirely and add in its place:

```go
func TestShouldNotifyClearOnResume(t *testing.T) {
	cases := []state.Transition{
		{PaneID: "w6:p1", WorkspaceID: "w6", From: "blocked", To: "working"},
		{PaneID: "w6:p1", WorkspaceID: "w6", From: "done", To: "working"},
		{PaneID: "w6:p1", WorkspaceID: "w6", From: "idle", To: "working"},
	}
	for _, tr := range cases {
		p, ok := ShouldNotify(tr, "", "")
		if !ok || p.Kind != "clear" || p.PaneID != "w6:p1" || p.WorkspaceID != "w6" {
			t.Fatalf("%s->working want clear push, got %+v ok=%v", tr.From, p, ok)
		}
		if p.Title != "" || p.Body != "" {
			t.Fatalf("clear push must have empty title/body, got %+v", p)
		}
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `go -C ~/herdr-mobile/companion test ./internal/notify/ -run TestShouldNotifyClearOnResume -v`
Expected: FAIL — `ShouldNotify` currently returns `ok=false` for `To == "working"`, so the `!ok` branch fires with `got {} ok=false`.

- [ ] **Step 3: Add the `clear` case to `ShouldNotify`**

In `companion/internal/notify/notifier.go`, add a case to the `switch` in `ShouldNotify`, immediately before `default:`:

```go
	case tr.To == "working":
		return Push{Kind: "clear", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID}, true
```

The resulting switch reads:

```go
	switch {
	case tr.To == "blocked":
		return Push{Kind: "blocked", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID,
			Title: name + " needs you", Body: lastBody}, true
	case tr.From == "working" && (tr.To == "idle" || tr.To == "done"):
		return Push{Kind: "finished", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID,
			Title: name + " finished", Body: ""}, true
	case tr.To == "working":
		return Push{Kind: "clear", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID}, true
	default:
		return Push{}, false
	}
```

- [ ] **Step 4: Run the notify tests to verify they pass**

Run: `go -C ~/herdr-mobile/companion test ./internal/notify/ -v`
Expected: PASS — `TestShouldNotifyClearOnResume` passes and all pre-existing notify tests (`TestShouldNotifyBlocked`, `TestShouldNotifyUsesDisplayName`, `TestShouldNotifyFinishedOnlyFromWorking`, the HTTP notifier tests) still pass. `TestShouldNotifyFinishedOnlyFromWorking` still verifies `idle→done` does NOT notify — that is unaffected (To is `done`, not `working`).

- [ ] **Step 5: Add an engine end-to-end test that a resume fires a `clear` push immediately**

The engine test harness already stands up a fake herdr + a push endpoint and asserts `m["kind"]` — model the new test on `TestEngineFiresBlockedPushToRegisteredEndpoint` (`engine_test.go:226`), reusing its exact API: `newFakeHerdr(t)`, `New(Config{...})`, `e.setEndpoint`, and `go e.pollLoop(ctx)`. Add this sibling test:

```go
func TestResumeFiresClearPush(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked"}})

	gotPush := make(chan map[string]any, 4)
	pushSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var m map[string]any
		json.NewDecoder(r.Body).Decode(&m)
		gotPush <- m
	}))
	defer pushSrv.Close()

	e := New(Config{SocketPath: f.SocketPath(), ListenAddr: "127.0.0.1:0", PollInterval: 100 * time.Millisecond})
	e.setEndpoint(pushSrv.URL)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go e.pollLoop(ctx)

	time.Sleep(200 * time.Millisecond) // first poll establishes "blocked"
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}})

	deadline := time.After(2 * time.Second)
	for {
		select {
		case m := <-gotPush:
			if m["kind"] == "clear" {
				if m["paneId"] != "w6:p1" || m["workspaceId"] != "w6" {
					t.Fatalf("bad clear push: %v", m)
				}
				return
			}
			// ignore any other push the harness delivers first
		case <-deadline:
			t.Fatal("no clear push fired on resume to working")
		}
	}
}
```

All required imports (`context`, `encoding/json`, `net/http`, `net/http/httptest`, `time`, the `herdr` package) are already present in `engine_test.go`. Note the payload keys are `paneId`/`workspaceId` (camelCase, from the `Push` JSON tags), matching the existing blocked-push assertion.

- [ ] **Step 6: Run the engine test to verify it passes**

Run: `go -C ~/herdr-mobile/companion test ./internal/engine/ -run TestResumeFiresClearPush -v`
Expected: PASS — the resume-to-working transition produces a push with `kind == "clear"` within 2s.

- [ ] **Step 7: Run the full companion suite**

Run: `go -C ~/herdr-mobile/companion test ./...`
Expected: PASS — all packages green.

- [ ] **Step 8: Commit**

```bash
cd ~/herdr-mobile
git add companion/internal/notify/notifier.go companion/internal/notify/notifier_test.go companion/internal/engine/engine_test.go
git commit -m "feat(companion): clear push on resume to working"
```

---

### Task 2: App cancels the notification on a `clear` push

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/push/Notifications.kt` (add `cancel`, guard `post`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/push/UnifiedPushReceiver.kt` (`onMessage` routing)

**Interfaces:**
- Consumes: `PushPayload{kind, paneId, workspaceId, title, body}` (existing, in `push/PushPayload.kt`); `Notifications.post(ctx: Context, p: PushPayload)` (existing).
- Produces: `Notifications.cancel(ctx: Context, paneId: String)` — cancels the notification keyed by `paneId.hashCode()`.

This task has no unit-test seam (the receiver and `NotificationManager` are Android framework types with no test harness in this project). It is verified by build + on-device manual check. Do NOT add an Android test dependency or framework for this — it would be scaffolding the project doesn't have (YAGNI). The companion tests in Task 1 cover the push-generation logic; this task is the thin, mechanical consumer.

- [ ] **Step 1: Add `cancel` to `Notifications` and guard `post` against a `clear` payload**

In `app/app/src/main/java/dev/herdr/mobile/push/Notifications.kt`, add a `cancel` function to the `Notifications` object (place it right after `ensureChannels`):

```kotlin
    fun cancel(ctx: Context, paneId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(paneId.hashCode())
    }
```

Then add a guard as the FIRST line inside `post`, before `ensureChannels(ctx)`:

```kotlin
    fun post(ctx: Context, p: PushPayload) {
        if (p.kind == "clear") return
        ensureChannels(ctx)
        // ... rest unchanged
```

(`Context` and `NotificationManager` are already imported in this file.)

- [ ] **Step 2: Route `clear` in the receiver**

In `app/app/src/main/java/dev/herdr/mobile/push/UnifiedPushReceiver.kt`, change `onMessage` from:

```kotlin
    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        parsePush(message.content)?.let { Notifications.post(context, it) }
    }
```

to:

```kotlin
    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        parsePush(message.content)?.let { p ->
            if (p.kind == "clear") Notifications.cancel(context, p.paneId)
            else Notifications.post(context, p)
        }
    }
```

- [ ] **Step 3: Build the debug APK to verify it compiles**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/push/Notifications.kt app/app/src/main/java/dev/herdr/mobile/push/UnifiedPushReceiver.kt
git commit -m "feat(app): dismiss notification on clear push"
```

---

## Manual Verification (after both tasks)

Rebuild + reinstall the companion and app, then:

1. Trigger a `blocked` notification (agent asks a question in herdr on the laptop).
2. Background or close the phone app.
3. Answer the prompt on the laptop so the agent resumes working.
4. Confirm the "needs you" notification disappears from the phone.
5. Repeat with a `finished` notification followed by the agent resuming work — it should also clear.
