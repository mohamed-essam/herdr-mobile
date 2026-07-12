# Dashboard Attention+Recency Sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Order the main dashboard so needs-attention workspaces (blocked, then done) float to the top, then by most recent activity.

**Architecture:** The Go companion tracks a per-workspace `lastActivity` timestamp (bumped on status transitions / pane add / pane remove) and sends it on the workspaces frame. The Android app sorts repos and workspaces in `buildRepoTree` by (attention tier, lastActivity, number). `buildTree`/the sidebar are unchanged.

**Tech Stack:** Go (companion `internal/state`), Kotlin/Compose (app `ui/`, `net/`); JUnit4 + Go testing.

## Global Constraints

- Attention tier: `blocked` = 2, `done` = 1, everything else (working/idle/null/"unknown") = 0.
- Workspaces within a repo sort by: **tier desc → lastActivity desc → number asc**.
- Repos sort by: **"(unknown)" last → maxTier desc → maxLastActivity desc → minNumber asc → displayName** (max/min over the repo's workspaces).
- A workspace's tier = the **max** attentionTier over its panes.
- Companion bumps `lastActivity[workspaceId] = now()` only on: pane **created**, pane **removed**, or an **agentStatus transition** — NOT focus/cwd-only changes.
- `lastActivity` is an additive field on the workspaces frame — **no `companionProtocol` bump** (app has `Json { ignoreUnknownKeys = true }` + a default).
- `buildTree` and the sidebar drawer stay number-ordered.
- Companion tests: `go -C ~/herdr-mobile/companion test ./...`
- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`; app tests: `:app:testDebugUnitTest`.
- Paths are relative to repo root `~/herdr-mobile`.

---

### Task 1: Companion tracks & sends `lastActivity`

**Files:**
- Modify: `companion/internal/state/store.go`
- Test: `companion/internal/state/store_test.go`

**Interfaces:**
- Produces: `state.Workspace` gains `LastActivity int64` (`json:"lastActivity,omitempty"`). `Store` gains an overridable clock `now func() int64` (default `time.Now().UnixMilli`) and internal `lastActivity map[string]int64`. `Apply` bumps recency; `ApplyWorkspaces` attaches it before its change check; `Workspaces()` returns it.

- [ ] **Step 1: Write the failing tests**

Append to `companion/internal/state/store_test.go`:

```go
func TestLastActivityTracking(t *testing.T) {
	s := NewStore()
	clock := int64(1000)
	s.now = func() int64 { return clock }

	// created → bump
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}))
	s.ApplyWorkspaces([]herdr.WorkspaceInfo{{WorkspaceID: "w6", Label: "herdr-mobile", Number: 2}})
	if got := s.Workspaces()[0].LastActivity; got != 1000 {
		t.Fatalf("created should stamp lastActivity=1000, got %d", got)
	}

	// focus-only change → NO bump
	clock = 2000
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working", Focused: true}))
	s.ApplyWorkspaces([]herdr.WorkspaceInfo{{WorkspaceID: "w6", Label: "herdr-mobile", Number: 2}})
	if got := s.Workspaces()[0].LastActivity; got != 1000 {
		t.Fatalf("focus-only change must not bump, want 1000 got %d", got)
	}

	// agentStatus transition → bump
	clock = 3000
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked", Focused: true}))
	s.ApplyWorkspaces([]herdr.WorkspaceInfo{{WorkspaceID: "w6", Label: "herdr-mobile", Number: 2}})
	if got := s.Workspaces()[0].LastActivity; got != 3000 {
		t.Fatalf("transition should bump to 3000, got %d", got)
	}

	// pane removed → bump
	clock = 4000
	s.Apply(infos()) // w6:p1 disappears
	if s.lastActivity["w6"] != 4000 {
		t.Fatalf("removal should bump to 4000, got %d", s.lastActivity["w6"])
	}
}

func TestApplyWorkspacesChangesWhenOnlyLastActivityChanges(t *testing.T) {
	s := NewStore()
	clock := int64(1000)
	s.now = func() int64 { return clock }
	ws := []herdr.WorkspaceInfo{{WorkspaceID: "w6", Label: "herdr-mobile", Number: 2}}

	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", AgentStatus: "working"}))
	if !s.ApplyWorkspaces(ws) {
		t.Fatal("first ApplyWorkspaces should report changed")
	}
	// bump activity via a transition, same workspace list from herdr
	clock = 5000
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", AgentStatus: "blocked"}))
	if !s.ApplyWorkspaces(ws) {
		t.Fatal("lastActivity change alone should report changed (so it rebroadcasts)")
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `go -C ~/herdr-mobile/companion test ./internal/state/ -run 'TestLastActivity|TestApplyWorkspacesChangesWhenOnlyLastActivity' -v`
Expected: FAIL — `s.now` and `s.lastActivity` fields and `Workspace.LastActivity` don't exist (compile error).

- [ ] **Step 3: Add the field, clock, and map**

In `companion/internal/state/store.go`:

Add `"time"` to the imports. Change the `Workspace` struct to add the field (after `TabCount`):

```go
type Workspace struct {
	WorkspaceID  string    `json:"workspaceId"`
	Label        string    `json:"label"`
	Number       int       `json:"number"`
	AgentStatus  string    `json:"agentStatus,omitempty"`
	Focused      bool      `json:"focused"`
	PaneCount    int       `json:"paneCount"`
	TabCount     int       `json:"tabCount"`
	LastActivity int64     `json:"lastActivity,omitempty"`
	Worktree     *Worktree `json:"worktree,omitempty"`
}
```

Add fields to the `Store` struct:

```go
type Store struct {
	mu    sync.Mutex
	panes map[string]Pane

	workspaces   []Workspace
	tabs         []Tab
	lastActivity map[string]int64
	now          func() int64
}
```

Replace `NewStore`:

```go
func NewStore() *Store {
	return &Store{
		panes:        map[string]Pane{},
		lastActivity: map[string]int64{},
		now:          func() int64 { return time.Now().UnixMilli() },
	}
}
```

- [ ] **Step 4: Bump recency in `Apply`**

In `Apply`, add the bumps. The `!existed` (created) branch and the transition branch bump `np.WorkspaceID`; the removal branch bumps the removed pane's workspace (read before delete):

```go
	for _, i := range infos {
		np := toPane(i)
		seen[np.PaneID] = true
		old, existed := s.panes[np.PaneID]
		if !existed {
			s.panes[np.PaneID] = np
			changes = append(changes, Change{Kind: "update", Pane: np})
			s.lastActivity[np.WorkspaceID] = s.now() // created
			continue
		}
		if old != np {
			s.panes[np.PaneID] = np
			changes = append(changes, Change{Kind: "update", Pane: np})
		}
		if old.AgentStatus != np.AgentStatus {
			transitions = append(transitions, Transition{PaneID: np.PaneID,
				WorkspaceID: np.WorkspaceID, From: old.AgentStatus, To: np.AgentStatus})
			s.lastActivity[np.WorkspaceID] = s.now() // status transition
		}
	}
	for id := range s.panes {
		if !seen[id] {
			ws := s.panes[id].WorkspaceID
			delete(s.panes, id)
			changes = append(changes, Change{Kind: "removed", PaneID: id})
			s.lastActivity[ws] = s.now() // removed
		}
	}
```

- [ ] **Step 5: Attach `lastActivity` in `ApplyWorkspaces`**

Change `ApplyWorkspaces` to stamp `LastActivity` on each built workspace before the change check:

```go
func (s *Store) ApplyWorkspaces(infos []herdr.WorkspaceInfo) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := make([]Workspace, 0, len(infos))
	for _, i := range infos {
		w := toWorkspace(i)
		w.LastActivity = s.lastActivity[w.WorkspaceID]
		next = append(next, w)
	}
	if reflect.DeepEqual(s.workspaces, next) {
		return false
	}
	s.workspaces = next
	return true
}
```

(`Workspaces()` is unchanged — the stored workspaces now carry `LastActivity`.)

- [ ] **Step 6: Run the state tests**

Run: `go -C ~/herdr-mobile/companion test ./internal/state/ -v`
Expected: PASS — new tests plus the pre-existing store tests (the added struct field doesn't break `TestApplyWorkspacesAndTabsChangeDetection`, whose workspaces have `LastActivity` 0 on both sides).

- [ ] **Step 7: Run the full companion suite**

Run: `go -C ~/herdr-mobile/companion test ./...`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
cd ~/herdr-mobile
git add companion/internal/state/store.go companion/internal/state/store_test.go
git commit -m "feat(companion): track per-workspace lastActivity"
```

---

### Task 2: App — `Workspace.lastActivity` field + tier helpers

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt` (`Workspace`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` (add `attentionTier`, `workspaceTier`)
- Test: `app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt` (add tier tests)

**Interfaces:**
- Consumes: `WorkspaceNode`, `Pane` (existing).
- Produces: `Workspace.lastActivity: Long`; `fun attentionTier(status: String?): Int`; `fun workspaceTier(node: WorkspaceNode): Int`.

- [ ] **Step 1: Write the failing tests**

Append to `app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt` (inside the class):

```kotlin
    @Test fun attentionTierRanks() {
        assertEquals(2, attentionTier("blocked"))
        assertEquals(1, attentionTier("done"))
        assertEquals(0, attentionTier("working"))
        assertEquals(0, attentionTier("idle"))
        assertEquals(0, attentionTier(null))
        assertEquals(0, attentionTier("unknown"))
    }

    @Test fun workspaceTierIsMaxOverPanes() {
        // a workspace with a working pane and a blocked pane → tier 2 (blocked wins)
        val ws = Workspace(workspaceId = "w1", label = "x", number = 1)
        val panes = listOf(
            Pane(paneId = "w1:p1", workspaceId = "w1", tabId = "w1:t1", agent = "claude", agentStatus = "working"),
            Pane(paneId = "w1:p2", workspaceId = "w1", tabId = "w1:t1", agent = "codex", agentStatus = "blocked"),
        )
        val node = WorkspaceNode(ws, listOf(TabNode(Tab(tabId = "w1:t1", workspaceId = "w1"), panes)))
        assertEquals(2, workspaceTier(node))
        // no panes → 0
        assertEquals(0, workspaceTier(WorkspaceNode(ws, emptyList())))
    }
```

Add the needed import at the top of the file if missing: `import dev.herdr.mobile.ui.attentionTier` and `import dev.herdr.mobile.ui.workspaceTier` (the file already imports `buildRepoTree`/`repoKeyFor` from `dev.herdr.mobile.ui`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RepoTreeTest"`
Expected: FAIL — unresolved `attentionTier` / `workspaceTier` (and `Workspace(... )` has no `lastActivity` yet is fine; these tests don't set it).

- [ ] **Step 3: Add the `lastActivity` field**

In `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`, add to the `Workspace` data class (after `tabCount`, before `worktree`):

```kotlin
    val lastActivity: Long = 0,
```

- [ ] **Step 4: Add the tier helpers**

In `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt`, add (near `repoKeyFor`):

```kotlin
/** Attention rank for sorting: blocked (needs you) > done (finished) > rest. */
fun attentionTier(status: String?): Int = when (status) {
    "blocked" -> 2
    "done" -> 1
    else -> 0
}

/** A workspace's attention tier is the max over its panes (robust vs. herdr's aggregate). */
fun workspaceTier(node: WorkspaceNode): Int =
    node.tabs.flatMap { it.panes }.maxOfOrNull { attentionTier(it.agentStatus) } ?: 0
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RepoTreeTest"`
Expected: PASS for the two new tests. (The pre-existing `groupsSameRepoAndOrders` still passes here because `buildRepoTree` isn't changed yet — it's updated in Task 3.)

- [ ] **Step 6: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt
git commit -m "feat(app): Workspace.lastActivity + attention tier helpers"
```

---

### Task 3: App — sort `buildRepoTree` by attention, then recency

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` (`buildRepoTree`)
- Test: `app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt` (update existing ordering test + add ordering tests)

**Interfaces:**
- Consumes: `attentionTier`, `workspaceTier` (Task 2); `RepoNode`, `WorkspaceNode` (existing).

- [ ] **Step 1: Update the existing ordering test and add new ones**

In `RepoTreeTest.kt`, the existing `groupsSameRepoAndOrders` asserts intra-group **input order** (`["w1","w2"]`). With attention/recency sorting, equal-tier/equal-activity workspaces now order by **number asc**, so w2 (#2) comes before w1 (#4). Update that assertion:

```kotlin
        // intra-group order is now tier→activity→number; a(#4) & b(#2) are equal
        // tier/activity, so number wins: w2 before w1.
        assertEquals(listOf("w2", "w1"), ops.workspaces.map { it.ws.workspaceId })
```

Then add ordering tests (inside the class):

```kotlin
    private fun wsNodeStatus(id: String, number: Int, status: String?, lastActivity: Long = 0, repoName: String? = null): WorkspaceNode {
        val ws = Workspace(
            workspaceId = id, label = id, number = number, lastActivity = lastActivity,
            worktree = repoName?.let { Worktree(repoName = it, isLinkedWorktree = true) },
        )
        val pane = Pane(paneId = "$id:p1", workspaceId = id, tabId = "$id:t1", agent = "claude", agentStatus = status)
        return WorkspaceNode(ws, listOf(TabNode(Tab(tabId = "$id:t1", workspaceId = id), listOf(pane))))
    }

    @Test fun workspacesSortByTierThenActivity() {
        val working = wsNodeStatus("w1", number = 1, status = "working", lastActivity = 100, repoName = "r")
        val done = wsNodeStatus("w2", number = 2, status = "done", lastActivity = 100, repoName = "r")
        val blocked = wsNodeStatus("w3", number = 3, status = "blocked", lastActivity = 100, repoName = "r")
        val workingNewer = wsNodeStatus("w4", number = 4, status = "working", lastActivity = 999, repoName = "r")

        val repo = buildRepoTree(listOf(working, done, blocked, workingNewer)).single { it.repoKey == "r" }
        // blocked > done > (working ordered by activity: w4 newer before w1)
        assertEquals(listOf("w3", "w2", "w4", "w1"), repo.workspaces.map { it.ws.workspaceId })
    }

    @Test fun reposSortByBestWorkspace() {
        val calm = wsNodeStatus("w1", number = 1, status = "working", lastActivity = 100, repoName = "calm")
        val urgent = wsNodeStatus("w2", number = 2, status = "blocked", lastActivity = 50, repoName = "urgent")
        val repos = buildRepoTree(listOf(calm, urgent)).map { it.repoKey }
        // "urgent" has a blocked workspace → sorts above "calm" despite older activity/higher number
        assertEquals(listOf("urgent", "calm"), repos)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RepoTreeTest"`
Expected: FAIL — `workspacesSortByTierThenActivity` and `reposSortByBestWorkspace` fail (current `buildRepoTree` sorts by number only), and the updated `groupsSameRepoAndOrders` assertion fails against the old input-order behavior.

- [ ] **Step 3: Rewrite `buildRepoTree` to sort by attention/recency**

Replace `buildRepoTree` in `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` with:

```kotlin
/**
 * Groups workspace nodes by repo key. Workspaces within a repo, and the repos
 * themselves, sort by attention tier (blocked > done > rest), then recent
 * activity, then number. The "(unknown)" group always sorts last.
 */
fun buildRepoTree(nodes: List<WorkspaceNode>): List<RepoNode> {
    val groups = LinkedHashMap<String, MutableList<WorkspaceNode>>()
    for (n in nodes) groups.getOrPut(repoKeyFor(n)) { mutableListOf() }.add(n)

    val wsOrder = compareByDescending<WorkspaceNode> { workspaceTier(it) }
        .thenByDescending { it.ws.lastActivity }
        .thenBy { it.ws.number }

    return groups.entries
        .map { (key, ws) -> RepoNode(key, key, ws.sortedWith(wsOrder)) }
        .sortedWith(
            compareBy<RepoNode> { if (it.repoKey == "(unknown)") 1 else 0 }
                .thenByDescending { r -> r.workspaces.maxOf { workspaceTier(it) } }
                .thenByDescending { r -> r.workspaces.maxOf { it.ws.lastActivity } }
                .thenBy { r -> r.workspaces.minOf { it.ws.number } }
                .thenBy { it.displayName },
        )
}
```

- [ ] **Step 4: Run the RepoTree tests**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RepoTreeTest"`
Expected: PASS — all RepoTreeTest cases including the updated `groupsSameRepoAndOrders` and the two new ordering tests.

- [ ] **Step 5: Build + full unit suite**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` for both. Note: if `CompanionClientTest`/`DashboardViewModelTest` fails with a MockWebServer/`withTimeout` teardown flake, re-run once (known load-only flake).

- [ ] **Step 6: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt
git commit -m "feat(app): sort dashboard by attention then recent activity"
```

---

## Manual Verification (after all tasks)

Rebuild the companion + app, reinstall, then on device:

1. A workspace with a **blocked** agent appears at the top of its repo, and its repo floats to the top of the dashboard.
2. A **done** workspace sorts above working/idle ones but below blocked.
3. Among equal-tier workspaces, the one with the most recent status change / pane add-remove appears first.
4. The **sidebar drawer** ordering is unchanged (still by number).
