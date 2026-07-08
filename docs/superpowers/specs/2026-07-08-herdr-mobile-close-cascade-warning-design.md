# herdr-mobile — Worktree-Cascade Close Warning (Design)

**Date:** 2026-07-08
**Status:** Approved (brainstorming)
**companionProtocol:** 5 → 6 (additive)

## Problem

herdr's `close_selected_workspace` (src/app/actions.rs) does not always close just
the targeted workspace. When the closed workspace is a repo's **base** (non-linked)
workspace **and** ≥2 workspaces in that repo's worktree group are open, herdr closes
**all** of them. Closing a *linked* worktree closes only itself.

Our close-confirm dialog only counts the target workspace's own panes/tabs, so
closing a repo base silently takes its open linked-worktree siblings with it. A user
reported closing one workspace ("ops") and losing two.

The confirm must name exactly which sibling workspaces will *also* close.

## Why the companion must compute the group

herdr's `workspace.list` reports worktree info **only for linked worktrees** — the
base workspace shows `worktree: None`. So the app cannot derive group membership from
the workspace snapshot alone.

herdr's `worktree.list` is authoritative: scoped to one repo (resolved from a
`workspace_id`/`cwd` param, or the focused workspace), it returns
`source{repo_root,...}` plus `worktrees[{ is_linked_worktree, path, branch, label,
open_workspace_id? }]` — one entry per worktree in the repo, base included, each
tagged with whether a workspace is currently open on it. The companion cross-references
this against the workspace snapshot to compute the cascade set.

## Approach: on-demand query

The impact is computed only when the user taps **Close** on a workspace row — a rare
action — so there is **zero per-poll cost**. No new polling, no new state carried in
the snapshot.

### Data flow

1. User taps **Close** on a *workspace* row.
2. App sends `{"t":"close_impact","reqId":"<id>","workspaceId":"<id>"}`.
3. Companion runs herdr `worktree.list` for that workspace's repo, applies herdr's
   exact cascade rule, and replies
   `{"t":"close_impact","reqId":"<id>","workspaceId":"<id>","alsoCloses":[{"workspaceId":"<id>","label":"<str>"}, ...]}`.
4. App shows the close confirm. If `alsoCloses` is non-empty, it adds an
   **"Also closes: <labels>"** line. On error/timeout, it falls back to the current
   confirm text.

Pane and tab closes never cascade — they skip the query entirely and their confirm is
unchanged.

## Companion computation

Given the target `workspaceId`:

1. Resolve the target's repo and call herdr `worktree.list` scoped to it (pass
   `workspace_id` = target so herdr resolves the correct repo).
2. Find the target's entry in `worktrees[]` by `open_workspace_id == target`.
3. **Cascade fires only if** the target's entry has `is_linked_worktree == false`
   (it is the base) **AND** ≥2 entries in the group have a non-empty
   `open_workspace_id` (≥2 open members).
4. If it fires: `alsoCloses` = every *other* group entry that has an
   `open_workspace_id`, each mapped to its app-facing label — resolved from the
   workspace snapshot by id; on miss, fall back to the worktree entry's `label`, then
   `branch`, then the raw id (never blank).
5. Otherwise (target is a linked worktree, no group, or <2 open members):
   `alsoCloses = []`.

## Protocol changes

companionProtocol **5 → 6**, additive only.

**Request (app → companion):**
```json
{ "t": "close_impact", "reqId": "<string>", "workspaceId": "<string>" }
```

**Reply (companion → app):**
```json
{
  "t": "close_impact",
  "reqId": "<string>",
  "workspaceId": "<string>",
  "alsoCloses": [ { "workspaceId": "<string>", "label": "<string>" } ]
}
```

`alsoCloses` is always present (empty array when no cascade). Existing frames are
unchanged.

## App behavior

- On workspace **Close** tap: send `close_impact`, await the reply.
- Non-empty `alsoCloses` → confirm dialog gains an **"Also closes: <comma-joined
  labels>"** line above the existing confirm body.
- Empty `alsoCloses` → current confirm, unchanged.
- Query error / timeout / companionProtocol < 6 → **fall back** to the current confirm
  (never block the close on a failed impact query).

## Error handling & edge cases

- **Query fails / times out / old protocol:** fall back to the current confirm dialog.
- **Stale membership:** `worktree.list` is read at tap time, not from the poll
  snapshot, so it reflects the close decision moment. A sibling closed between poll and
  tap simply won't appear.
- **Label resolution miss:** worktree `label` → `branch` → raw id fallback; never
  blank.
- **Non-workspace closes:** panes/tabs skip the query; confirm unchanged.

## Testing plan

**Companion unit** (`client_test.go` / `server_test.go`, against fakeherdr):
- base with 2 open members → returns the sibling in `alsoCloses`
- linked-worktree target → empty
- base with 1 open member → empty
- no worktree group → empty
- label fallback when the workspace snapshot lacks the sibling id
- `worktree.list` error → error frame (app falls back)

**App unit** (`Protocol` parse + `CompanionClient`):
- parse `close_impact` reply including empty `alsoCloses`
- JsonNull / missing `alsoCloses` array → `emptyList()` (same defensiveness as the
  `agents` fix)
- timeout → fallback path

**Live validation:** a real base + linked-worktree pair on the phone — confirm the
"Also closes" line names the sibling, and that closing a linked worktree does *not*
warn.

## Out of scope

- Changing herdr's cascade behavior (this is herdr's intended semantics).
- Cascade preview for pane/tab closes (they don't cascade).
- Any per-poll precomputation of impact (rejected: cost without benefit).
