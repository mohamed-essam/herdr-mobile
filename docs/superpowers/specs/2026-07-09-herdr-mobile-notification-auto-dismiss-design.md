# Notification Auto-Dismiss on Resume — Design Spec

**Date:** 2026-07-09
**Component:** herdr-mobile (Go companion + Kotlin/Compose Android app)

## Goal

When an agent that triggered a "needs you" (blocked) or "finished" notification
starts working again, the standing notification on the phone should disappear
automatically — including when the resume happens from the user's laptop while
the phone app is backgrounded or killed.

## Problem

The companion pushes two notification kinds today (`notify.ShouldNotify`):

- `blocked` — on any `→blocked` transition (title "<name> needs you").
- `finished` — on `working→idle|done` (title "<name> finished"), debounced by
  `DebounceFinished` and suppressed if the pane is working again after the
  window.

Once a notification is posted (`Notifications.post`, keyed by
`paneId.hashCode()`, `setAutoCancel(true)`), nothing ever retracts it. A
`blocked` notification has no debounce at all, so unblocking the agent from the
laptop leaves a stale "needs you" notification on the phone indefinitely.

## Approach

**Companion-driven dismiss push (stateless, idempotent).**

The companion already emits a `state.Transition{From, To}` on every
`AgentStatus` change. Add a third `ShouldNotify` case: on `tr.To == "working"`,
emit a lightweight push `{kind: "clear", paneId, workspaceId}`. The app routes
`kind == "clear"` to `NotificationManager.cancel(paneId.hashCode())` instead of
posting a notification.

### Why this approach

- **Works when the app is closed.** The notification matters most when the app
  is backgrounded or killed — exactly when its WebSocket isn't connected. A
  UnifiedPush message wakes `UnifiedPushReceiver` regardless, so the cancel
  lands. An app-side "watch the WS pane stream and cancel locally" approach only
  works while foregrounded and connected — the wrong window. **Rejected.**
- **Stateless / idempotent.** The companion does not track whether a
  notification was ever posted for a pane; it sends `clear` on every resume.
  `NotificationManager.cancel` on a non-existent notification is a harmless
  no-op. This avoids adding outstanding-notification bookkeeping to the
  companion for zero user-visible gain (YAGNI). **Chosen over** a state-tracking
  variant that only clears when a notification is known outstanding.
- **Composes with the finished debounce.** An `idle→working` (or
  `done→working`) that suppressed a pending `finished` push still sends a
  `clear`, which cancels nothing. No conflict.

### Protocol

`clear` is a new push `kind`. Push payloads are not versioned like the WS
`companionProtocol`, so this is purely additive — no version bump. The existing
`PushPayload`/`Push` structs already carry `kind`, `paneId`, `workspaceId`; no
new fields.

## Scope boundary

Auto-dismiss fires **only on resume to `working`**, matching the request. It
does not clear a notification when a pane is merely closed or its agent
detaches — those are out of scope for this change.

## Touch Points

### Companion (Go)

1. `internal/notify/notifier.go` — `ShouldNotify`: add
   ```go
   case tr.To == "working":
       return Push{Kind: "clear", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID}, true
   ```
   placed after the `blocked` and `finished` cases. A `clear` push carries no
   title/body.

   Note ordering: the existing `finished` case matches `From == "working" && To
   in {idle,done}`, and the `blocked` case matches `To == "blocked"`. Neither
   overlaps `To == "working"`, so the new case is unambiguous regardless of
   position, but it goes last for readability.

2. `internal/engine/engine.go` — `handleTransition` currently special-cases
   `push.Kind == "finished"` (debounce) and otherwise calls `e.fire`. A `clear`
   push falls through to `e.fire` with no debounce, which is correct — fire it
   immediately. No change needed beyond confirming `clear` is not accidentally
   caught by the `finished` branch (it is not; that branch keys on
   `push.Kind == "finished"`).

### App (Kotlin)

3. `push/Notifications.kt` — add:
   ```kotlin
   fun cancel(ctx: Context, paneId: String) {
       ctx.getSystemService(NotificationManager::class.java).cancel(paneId.hashCode())
   }
   ```

4. `push/UnifiedPushReceiver.kt` — `onMessage`:
   ```kotlin
   parsePush(message.content)?.let { p ->
       if (p.kind == "clear") Notifications.cancel(context, p.paneId)
       else Notifications.post(context, p)
   }
   ```

5. `push/Notifications.kt` — `post` is defensive: if a `clear` payload ever
   reaches `post` directly, it must not create a notification. Guard at the top
   of `post`: `if (p.kind == "clear") return`. (Routing in step 4 already
   prevents this; the guard is belt-and-suspenders.)

## Testing

### Companion

- `notifier_test.go`:
  - `→working` (e.g. `blocked→working`) produces `Push{Kind:"clear",
    PaneID:..., WorkspaceID:...}` with empty title/body, `ok == true`.
  - `done→working` and `idle→working` also produce a `clear` push.
  - Existing `idle→working should not notify` test is **replaced** — that
    behavior is the change. The new expectation is a `clear` push.
- `engine_test.go` (if it exercises transitions end-to-end): a `→working`
  transition fires a `clear` push immediately (no debounce). If the existing
  test harness doesn't cover push emission per-kind, no new engine test is
  required beyond confirming `clear` is not swallowed.

### App

- Unit-test the routing decision if a testable seam exists; otherwise this is
  covered by the companion tests plus manual device verification (post a
  blocked notification, resume the agent from the laptop, confirm the phone
  notification disappears with the app backgrounded).

## Verification (manual, on device)

1. Trigger a `blocked` notification (agent asks a question).
2. Background/close the phone app.
3. Unblock the agent from the laptop (answer the prompt) so it resumes working.
4. Confirm the "needs you" notification disappears from the phone.
5. Repeat for a `finished` notification followed by the agent resuming work.
