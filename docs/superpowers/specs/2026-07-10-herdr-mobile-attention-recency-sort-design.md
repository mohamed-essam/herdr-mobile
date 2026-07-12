# Dashboard Sort: Needs-Attention, then Recent Activity — Design Spec

**Date:** 2026-07-10
**Component:** herdr-mobile — Go companion (`internal/state`) + Android app (`ui/`)

## Goal

Order the main dashboard so workspaces (and their repo groups) that **need
attention** float to the top, then by **most recent activity**. Attention tiers:
**blocked** (agent waiting for you) first, then **done** (finished), then the
rest (working / idle / shell).

## Constraint that shapes the design

herdr exposes **no timestamp / last-activity** on `PaneInfo` / `WorkspaceInfo` /
`TabInfo`. Recency must be derived. Per decision, the **companion** tracks it
(it already detects status transitions and runs continuously, so recency
survives app restarts and cold-start ordering degrades gracefully to workspace
number).

Split of responsibility: the **companion tracks & sends** a per-workspace
recency value; the **app sorts** (presentation) using it plus the existing
agent-status for the attention tier.

## Part 1 — Companion: track & send `lastActivity`

`internal/state/store.go`:

- `Store` gains `lastActivity map[string]int64` (workspaceId → unix millis) and
  an injected clock `now func() int64` (default `time.Now().UnixMilli`;
  overridable in tests). `NewStore()` initializes both.
- In `Apply(panes)`, bump `lastActivity[workspaceId] = now()` for **meaningful**
  activity only:
  - a pane **created** (the `!existed` branch),
  - a pane **removed** (read the old pane's `WorkspaceID` before `delete`),
  - an **agentStatus transition** (the existing `old.AgentStatus != np.AgentStatus` branch).
  A field-only change (focus/cwd with no status change) does **not** bump — that
  would make the list jitter.
- `state.Workspace` gains `LastActivity int64 \`json:"lastActivity,omitempty"\``.
- `ApplyWorkspaces(infos)` attaches `lastActivity[workspaceId]` to each built
  `next` workspace **before** the `reflect.DeepEqual` change check, so a recency
  bump (even when herdr's workspace aggregate is unchanged) counts as a change
  and triggers a rebroadcast. `Workspaces()` returns the stored list (now
  carrying `LastActivity`).
- Engine ordering already favors this: `pollOnce` calls `store.Apply(panes)`
  (bumps recency) before `store.ApplyWorkspaces(ws)` (reads it), so a transition
  and its recency land in the same poll's workspace broadcast.

Seed behavior: the first snapshot marks all current panes as "created", stamping
every workspace ~now (equal) — so cold-start ties fall back to number (app-side
secondary sort) until real activity diverges them.

Protocol: additive field on the existing workspaces frame — **no
`companionProtocol` bump**. The app's `Json { ignoreUnknownKeys = true }` +
default value make it compatible in both directions (old companion → field
absent → 0).

### Testing (companion)

`store_test.go` with a fake clock:
- an agentStatus transition bumps the workspace's `lastActivity`; `Workspaces()`
  carries the value.
- a pane created and a pane removed each bump `lastActivity`.
- a focus-only change does **not** bump.
- `ApplyWorkspaces` reports changed when only `lastActivity` changed (so it
  rebroadcasts).

## Part 2 — App: sort by attention, then recency

`net/Protocol.kt`:
- `Workspace` gains `val lastActivity: Long = 0`.

`ui/TreeModel.kt` — pure, tested helpers + sorting in `buildRepoTree` only
(`buildTree` is unchanged, so the **sidebar drawer keeps its number ordering**):

- `fun attentionTier(status: String?): Int` → `"blocked"` = 2, `"done"` = 1,
  else 0.
- `fun workspaceTier(node: WorkspaceNode): Int` = the **max** `attentionTier`
  over the workspace's panes (`node.tabs.flatMap { it.panes }`), else 0 — robust
  regardless of herdr's workspace-level aggregate.
- In `buildRepoTree`:
  - **Workspaces within each repo** sort by: `tier desc → ws.lastActivity desc →
    ws.number asc`.
  - **Repos** sort by: `maxTier(desc) → maxLastActivity(desc) → minNumber(asc) →
    displayName`, with the `"(unknown)"` group always last (unchanged rule).
    `maxTier`/`maxLastActivity` are taken over the repo's workspaces.

`buildTree` continues to sort workspaces by `number` (feeds the sidebar and is
the input to `buildRepoTree`, which then re-sorts for the dashboard).

### Testing (app)

`RepoTreeTest.kt`:
- `attentionTier` truth table (blocked=2, done=1, working/idle/null/"unknown"=0).
- within a repo: a blocked workspace sorts above a done workspace above a
  working one; among equal tiers, higher `lastActivity` first; equal tier +
  activity falls back to lower `number`.
- repos: a repo containing a blocked workspace sorts above one whose best is
  working; `"(unknown)"` stays last.

## Touch Points

- `companion/internal/state/store.go` — `lastActivity` map + clock; bump in
  `Apply`; `LastActivity` on `Workspace`; attach in `ApplyWorkspaces`.
- `companion/internal/state/store_test.go` — recency tests.
- `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt` — `Workspace.lastActivity`.
- `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` — `attentionTier`,
  `workspaceTier`, sorting in `buildRepoTree`.
- `app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt` — ordering tests.

## Non-Goals

- No change to `buildTree` / the sidebar ordering (stays by number).
- No change to tab/pane ordering within a workspace.
- No persistence of recency across companion restarts beyond what the running
  process observes (accepted; cold start falls back to number).

## Build / Test Commands

- Companion: `go -C ~/herdr-mobile/companion test ./...`
- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
