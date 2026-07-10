# Paseo-Inspired Dashboard Refresh — Design Spec

**Date:** 2026-07-10
**Component:** herdr-mobile Android app (`ui/` — theme + dashboard)
**Inspiration:** Paseo (paseo.sh) — see memory `paseo-design-reference`.

Two independent, approved changes, delivered together because both touch the app's
`ui/` layer:

1. **Mixed typography** — headers monospace, body/labels system sans.
2. **Repo-grouped dashboard** — the main dashboard becomes a collapsible
   **Repo > Workspace > Tab > Pane** tree with colored letter-monogram repo avatars.

Non-goals: no companion/protocol change; the embedded terminal (`TerminalView`, its own
font) is untouched; the sidebar drawer's Workspace→Tab→Pane tree is left as-is.

---

## Part 1 — Mixed Typography (headers mono, body sans)

### Current

`ui/theme/Theme.kt` `HerdrTypography` sets `FontFamily.Monospace` on **every** text
style (title/body/label). Everything in the app renders monospace.

### Change

In `HerdrTypography`, keep `FontFamily.Monospace` on the **title** styles
(`titleLarge`, `titleMedium`, `titleSmall`) and drop the explicit `fontFamily` from the
**body** (`bodyLarge/Medium/Small`) and **label** (`labelLarge/Medium/Small`) styles so
they inherit the system default (sans). Preserve the existing `fontWeight` overrides on
each style (e.g. `titleLarge` Bold, `labelLarge` Medium).

Result: the `herdr ❯` wordmark, section/row headers, and titles stay monospace (terminal
identity); prose, subtitles, dialog text, snackbars, and buttons become sans (readability).

### Testing

No unit-test seam for a Compose theme. Verified by build + on-device visual check: titles
render monospace, body/labels render sans, terminal unchanged.

---

## Part 2 — Repo > Workspace > Tab > Pane Dashboard

### Current

`DashboardScreen`'s main content is a flat `LazyColumn` of `panes` rendered by `PaneRow`.
The Workspace→Tab→Pane tree (`vm.tree: StateFlow<List<WorkspaceNode>>`, built by
`buildTree`) is used only in the sidebar drawer, which renders it via a
`flatten(tree, collapsed) -> List<Row>` pattern (sealed `Row` = Ws | TabRow | PaneRowItem),
with a node expanded unless its id is in `vm.collapsed` (toggled by `vm.toggleExpanded(id)`).

### Data model

New pure builder in `ui/TreeModel.kt`:

```
data class RepoNode(val repoKey: String, val displayName: String, val workspaces: List<WorkspaceNode>)

fun buildRepoTree(nodes: List<WorkspaceNode>): List<RepoNode>
```

- **Repo key per workspace** (`repoKeyFor(node: WorkspaceNode): String`, pure), first
  non-blank of:
  1. `node.ws.worktree?.repoName`
  2. the cwd basename of the workspace's first pane with a non-blank `cwd`
     (`cwd.substringAfterLast('/')`)
  3. `node.ws.label`
  4. `node.ws.workspaceId`
  5. `"(unknown)"` (only when all above are blank — e.g. the synthetic unknown workspace)
- **Grouping:** workspaces sharing a `repoKey` group into one `RepoNode`, preserving each
  group's internal workspace order (already sorted by number in `buildTree`).
- **displayName:** the `repoKey` (used verbatim).
- **Repo ordering:** by the minimum workspace `number` within the group (stable and
  consistent with the existing number-based ordering); the `"(unknown)"` group sorts last.
- **Known heuristic / limitation:** a repo's *base* workspace has no
  `worktree.repoName` (the companion only sends `worktree` for linked worktrees), so a base
  workspace groups with its worktrees only when the base's cwd basename equals the
  worktrees' `repoName` — usually true. When it differs, the base lands in its own group.
  Accepted for v1; no companion change.

### Repo avatar

New composable `RepoAvatar(name: String)` in a new `ui/RepoAvatar.kt`:

- A small rounded square (~28dp) showing the **monogram**: the first 1–2 alphanumeric
  characters of `name`, uppercased (`monogram(name): String`, pure).
- Background color deterministic from the name: `avatarColor(name, dark)` added to
  `ui/theme/Palette.kt`, selecting from a fixed list of Catppuccin accent colors
  (mauve/blue/green/yellow/peach/teal/red) via a pure
  `colorIndexFor(seed: String, size: Int): Int` (stable, always non-negative).
- Monogram text color: the theme's crust/base (dark text on the accent) for contrast.

### Dashboard rendering

`DashboardScreen` replaces the flat pane `LazyColumn` with a repo-grouped collapsible tree,
mirroring the sidebar's flatten pattern:

- New `vm.repoTree: StateFlow<List<RepoNode>>` = `tree.map { buildRepoTree(it) }` (as a
  `StateFlow` via `stateIn`, consistent with `vm.tree`).
- A `flatten(repoTree, collapsed) -> List<Row>` local to the dashboard, with sealed rows
  **Repo | Ws | TabRow | PaneRowItem**:
  - **Repo** expanded unless `"repo:${repoKey}"` in `collapsed`; when collapsed, its
    workspaces/tabs/panes are omitted.
  - **Workspace** expanded unless `workspaceId` in `collapsed`; **Tab** expanded unless
    `tabId` in `collapsed` (same keys the sidebar uses).
  - Repos default **expanded** (empty `collapsed` set → everything open).
- Row rendering:
  - **Repo header:** `RepoAvatar` + repo `displayName` (monospace title style) + a count
    (panes in group) + a collapse chevron; tap toggles `vm.toggleExpanded("repo:$repoKey")`.
  - **Workspace / Tab headers:** compact indented rows with the label + collapse chevron;
    tap toggles their id.
  - **Pane leaf:** the existing `PaneRow`, indented, tapping opens the terminal for agent
    panes (unchanged `onClick` behavior: opens only when `pane.agent != null`).
- The not-connected `ReconnectingBanner` and `EmptyState` are unchanged; the empty check
  becomes "no panes" as today.

### Collapse state

Reuses `vm.collapsed` / `vm.toggleExpanded`. Repo rows use the `"repo:"`-prefixed key so
they never collide with workspace/tab ids. No persistence (matches current behavior).

### Testing

Unit tests (`app/src/test`, JUnit4) for the pure logic:

- `RepoTreeTest` — `buildRepoTree`:
  - two workspaces with the same `worktree.repoName` group into one `RepoNode`.
  - repo-key fallback order: repoName > cwd basename > label > workspaceId.
  - repo ordering by min workspace number; the synthetic unknown workspace → `"(unknown)"`
    group sorted last.
  - a `RepoNode`'s workspaces retain `buildTree` order.
- `RepoAvatarTest` — `monogram("getpaseo/paseo")` etc. (1–2 char, uppercased, alphanumeric)
  and `colorIndexFor` determinism (same seed → same index; always in `0 until size`).

UI wiring (dashboard rewrite, avatar composable) verified by build + on-device check:
repos render with colored monograms, collapse/expand works at all four levels, tapping an
agent pane opens the terminal.

---

## Touch Points

- `app/app/src/main/java/dev/herdr/mobile/ui/theme/Theme.kt` — typography split (Part 1).
- `app/app/src/main/java/dev/herdr/mobile/ui/theme/Palette.kt` — `avatarColor` + `colorIndexFor`.
- `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` — `RepoNode`, `buildRepoTree`, `repoKeyFor`.
- `app/app/src/main/java/dev/herdr/mobile/ui/RepoAvatar.kt` (new) — `RepoAvatar`, `monogram`.
- `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt` — `repoTree` StateFlow.
- `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` — repo-tree render.
- `app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt`, `RepoAvatarTest.kt` (new).

## Build / Test Commands

- Build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- Unit tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
