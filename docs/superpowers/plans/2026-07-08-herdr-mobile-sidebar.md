# herdr-mobile Sidebar / Tree Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Paseo-style slide-over drawer that shows herdr's workspace → tab → pane hierarchy as a collapsible tree, layered over the unchanged flat dashboard.

**Architecture:** The companion polls `workspace.list` and `tab.list` alongside the existing `pane.list`, stores them, and pushes full-list `workspaces`/`tabs` frames on change (companionProtocol bumps 2→3, additive). The app consumes these into a repository, joins workspaces+tabs+panes into a tree with a pure `buildTree` function, and renders a `ModalNavigationDrawer`. Tapping an agent pane opens the existing terminal.

**Tech Stack:** Go 1.23 (companion), Kotlin + Jetpack Compose Material3 (app), `coder/websocket`, kotlinx.serialization.

## Global Constraints

- Companion `companionProtocol` becomes **3**; all new frames are additive — an older app that ignores `workspaces`/`tabs` still works.
- Reuse the existing theme only: `statusColor`, `statusGlyph`, `SpinnerFrames`, `spinnerFrame()`, the Catppuccin palette, mono typography, sharp shapes. No new colors or fonts.
- App stays GPLv3 (Termux vendoring, unchanged).
- No new app → companion frames; the drawer is read-only navigation.
- Nothing dropped silently: a pane whose workspace/tab is missing surfaces under a synthetic "(unknown)" group.
- App-facing JSON is camelCase (`workspaceId`, `tabId`, `paneCount`, …), matching the existing `state.Pane` tags.
- Non-agent (shell) panes appear in the tree but are dimmed and inert (terminal is agent-only).
- Ordering: workspaces by `number`, tabs by `number`, panes by `paneId`; the synthetic "(unknown)" workspace sorts last.

---

### Task 1: herdr client — workspace/tab wire types + list calls

**Files:**
- Modify: `companion/internal/herdr/types.go`
- Modify: `companion/internal/herdr/client.go`
- Modify: `companion/internal/herdr/fakeherdr_test.go` (fake serves the two new methods)
- Test: `companion/internal/herdr/client_test.go` (create)

**Interfaces:**
- Consumes: existing `Client.Call`, `Response`.
- Produces:
  - `herdr.WorktreeInfo{ RepoName, RepoRoot, CheckoutPath string; IsLinkedWorktree bool }`
  - `herdr.WorkspaceInfo{ WorkspaceID, Label string; Number int; ActiveTabID, AgentStatus string; Focused bool; PaneCount, TabCount int; Worktree *WorktreeInfo }`
  - `herdr.TabInfo{ TabID, Label string; Number int; WorkspaceID, AgentStatus string; Focused bool; PaneCount int }`
  - `Client.ListWorkspaces(ctx) ([]WorkspaceInfo, error)` — calls `workspace.list`
  - `Client.ListTabs(ctx) ([]TabInfo, error)` — calls `tab.list`
  - fake helpers `(*fakeHerdr).SetWorkspaces([]WorkspaceInfo)`, `(*fakeHerdr).SetTabs([]TabInfo)`

- [ ] **Step 1: Add wire types**

In `companion/internal/herdr/types.go`, after the `PaneInfo` block, add:

```go
type WorktreeInfo struct {
	RepoName         string `json:"repo_name"`
	RepoRoot         string `json:"repo_root"`
	CheckoutPath     string `json:"checkout_path"`
	IsLinkedWorktree bool   `json:"is_linked_worktree"`
}

type WorkspaceInfo struct {
	WorkspaceID string        `json:"workspace_id"`
	Label       string        `json:"label"`
	Number      int           `json:"number"`
	ActiveTabID string        `json:"active_tab_id"`
	AgentStatus string        `json:"agent_status"`
	Focused     bool          `json:"focused"`
	PaneCount   int           `json:"pane_count"`
	TabCount    int           `json:"tab_count"`
	Worktree    *WorktreeInfo `json:"worktree,omitempty"`
}

type workspaceListResult struct {
	Type       string          `json:"type"`
	Workspaces []WorkspaceInfo `json:"workspaces"`
}

type TabInfo struct {
	TabID       string `json:"tab_id"`
	Label       string `json:"label"`
	Number      int    `json:"number"`
	WorkspaceID string `json:"workspace_id"`
	AgentStatus string `json:"agent_status"`
	Focused     bool   `json:"focused"`
	PaneCount   int    `json:"pane_count"`
}

type tabListResult struct {
	Type string    `json:"type"`
	Tabs []TabInfo `json:"tabs"`
}
```

- [ ] **Step 2: Add list calls**

In `companion/internal/herdr/client.go`, after `ListPanes`, add:

```go
func (c *Client) ListWorkspaces(ctx context.Context) ([]WorkspaceInfo, error) {
	raw, err := c.Call(ctx, "workspace.list", nil)
	if err != nil {
		return nil, err
	}
	var res workspaceListResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Workspaces, nil
}

func (c *Client) ListTabs(ctx context.Context) ([]TabInfo, error) {
	raw, err := c.Call(ctx, "tab.list", nil)
	if err != nil {
		return nil, err
	}
	var res tabListResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Tabs, nil
}
```

- [ ] **Step 3: Teach the fake herdr the two methods**

In `companion/internal/herdr/fakeherdr_test.go`:

Add fields to the `fakeHerdr` struct (inside the struct definition, after `panes    []PaneInfo`):

```go
	workspaces []WorkspaceInfo
	tabs       []TabInfo
```

Add setters after `SetPanes`:

```go
func (f *fakeHerdr) SetWorkspaces(w []WorkspaceInfo) { f.mu.Lock(); f.workspaces = w; f.mu.Unlock() }
func (f *fakeHerdr) SetTabs(t []TabInfo)             { f.mu.Lock(); f.tabs = t; f.mu.Unlock() }
```

Add two cases to the `switch req.Method` in `handle`, right after the `pane.list` case:

```go
	case "workspace.list":
		f.mu.Lock()
		ws := f.workspaces
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "workspace_list", "workspaces": ws}})
	case "tab.list":
		f.mu.Lock()
		tabs := f.tabs
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "tab_list", "tabs": tabs}})
```

- [ ] **Step 4: Write the failing test**

Create `companion/internal/herdr/client_test.go`:

```go
package herdr

import (
	"context"
	"testing"
)

func TestListWorkspacesAndTabs(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetWorkspaces([]WorkspaceInfo{
		{WorkspaceID: "w3", Label: "apollo", Number: 1, AgentStatus: "idle", PaneCount: 1, TabCount: 1},
		{WorkspaceID: "w5", Label: "wt-cost-dashboards", Number: 2, Focused: true, PaneCount: 1, TabCount: 1,
			Worktree: &WorktreeInfo{RepoName: "ops", IsLinkedWorktree: true}},
	})
	f.SetTabs([]TabInfo{
		{TabID: "w7:t1", Label: "1", Number: 1, WorkspaceID: "w7", AgentStatus: "idle", PaneCount: 1},
		{TabID: "w7:t2", Label: "2", Number: 2, WorkspaceID: "w7", AgentStatus: "unknown", PaneCount: 1},
	})
	c := New(f.SocketPath())

	ws, err := c.ListWorkspaces(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(ws) != 2 || ws[0].Label != "apollo" || ws[1].Worktree == nil || ws[1].Worktree.RepoName != "ops" {
		t.Fatalf("bad workspaces: %+v", ws)
	}

	tabs, err := c.ListTabs(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(tabs) != 2 || tabs[1].Label != "2" || tabs[1].WorkspaceID != "w7" {
		t.Fatalf("bad tabs: %+v", tabs)
	}
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `cd companion && go test ./internal/herdr/ -run TestListWorkspacesAndTabs -v`
Expected: FAIL to compile ("undefined: WorkspaceInfo") before Steps 1–3 are in place; once code is added, PASS.

- [ ] **Step 6: Run the herdr package tests**

Run: `cd companion && go test ./internal/herdr/`
Expected: `ok` (all tests, including the existing ping/subscribe tests, pass).

- [ ] **Step 7: Commit**

```bash
git add companion/internal/herdr/
git commit -m "feat(companion): herdr workspace.list/tab.list wire types + calls"
```

---

### Task 2: state store — hold workspaces and tabs

**Files:**
- Modify: `companion/internal/state/store.go`
- Test: `companion/internal/state/store_test.go`

**Interfaces:**
- Consumes: `herdr.WorkspaceInfo`, `herdr.TabInfo`, `herdr.WorktreeInfo` (Task 1).
- Produces (all with camelCase JSON tags for the app):
  - `state.Worktree{ RepoName string; IsLinkedWorktree bool }`
  - `state.Workspace{ WorkspaceID, Label string; Number int; AgentStatus string; Focused bool; PaneCount, TabCount int; Worktree *Worktree }`
  - `state.Tab{ TabID, Label string; Number int; WorkspaceID, AgentStatus string; Focused bool; PaneCount int }`
  - `(*Store).ApplyWorkspaces([]herdr.WorkspaceInfo) bool` — stores, returns true if changed from prior
  - `(*Store).ApplyTabs([]herdr.TabInfo) bool` — same
  - `(*Store).Workspaces() []Workspace`, `(*Store).Tabs() []Tab` — copies for the snapshot

- [ ] **Step 1: Add types + storage fields**

In `companion/internal/state/store.go`, add `"reflect"` to imports. After the `Pane` type, add:

```go
type Worktree struct {
	RepoName         string `json:"repoName,omitempty"`
	IsLinkedWorktree bool   `json:"isLinkedWorktree,omitempty"`
}

type Workspace struct {
	WorkspaceID string    `json:"workspaceId"`
	Label       string    `json:"label"`
	Number      int       `json:"number"`
	AgentStatus string    `json:"agentStatus,omitempty"`
	Focused     bool      `json:"focused"`
	PaneCount   int       `json:"paneCount"`
	TabCount    int       `json:"tabCount"`
	Worktree    *Worktree `json:"worktree,omitempty"`
}

type Tab struct {
	TabID       string `json:"tabId"`
	Label       string `json:"label"`
	Number      int    `json:"number"`
	WorkspaceID string `json:"workspaceId"`
	AgentStatus string `json:"agentStatus,omitempty"`
	Focused     bool   `json:"focused"`
	PaneCount   int    `json:"paneCount"`
}
```

Add fields to the `Store` struct (after `panes map[string]Pane`):

```go
	workspaces []Workspace
	tabs       []Tab
```

- [ ] **Step 2: Add converters + apply/snapshot methods**

Append to `companion/internal/state/store.go`:

```go
func toWorkspace(i herdr.WorkspaceInfo) Workspace {
	var wt *Worktree
	if i.Worktree != nil && i.Worktree.IsLinkedWorktree {
		wt = &Worktree{RepoName: i.Worktree.RepoName, IsLinkedWorktree: true}
	}
	return Workspace{WorkspaceID: i.WorkspaceID, Label: i.Label, Number: i.Number,
		AgentStatus: i.AgentStatus, Focused: i.Focused, PaneCount: i.PaneCount,
		TabCount: i.TabCount, Worktree: wt}
}

func toTab(i herdr.TabInfo) Tab {
	return Tab{TabID: i.TabID, Label: i.Label, Number: i.Number, WorkspaceID: i.WorkspaceID,
		AgentStatus: i.AgentStatus, Focused: i.Focused, PaneCount: i.PaneCount}
}

// ApplyWorkspaces stores the list and reports whether it changed from the prior one.
func (s *Store) ApplyWorkspaces(infos []herdr.WorkspaceInfo) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := make([]Workspace, 0, len(infos))
	for _, i := range infos {
		next = append(next, toWorkspace(i))
	}
	if reflect.DeepEqual(s.workspaces, next) {
		return false
	}
	s.workspaces = next
	return true
}

func (s *Store) ApplyTabs(infos []herdr.TabInfo) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := make([]Tab, 0, len(infos))
	for _, i := range infos {
		next = append(next, toTab(i))
	}
	if reflect.DeepEqual(s.tabs, next) {
		return false
	}
	s.tabs = next
	return true
}

func (s *Store) Workspaces() []Workspace {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Workspace, len(s.workspaces))
	copy(out, s.workspaces)
	return out
}

func (s *Store) Tabs() []Tab {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Tab, len(s.tabs))
	copy(out, s.tabs)
	return out
}
```

- [ ] **Step 3: Write the failing test**

Append to `companion/internal/state/store_test.go`:

```go
func TestApplyWorkspacesAndTabsChangeDetection(t *testing.T) {
	s := NewStore()

	ws := []herdr.WorkspaceInfo{{WorkspaceID: "w7", Label: "omega3", Number: 4, AgentStatus: "idle", PaneCount: 2, TabCount: 2}}
	if !s.ApplyWorkspaces(ws) {
		t.Fatal("first ApplyWorkspaces should report changed")
	}
	if s.ApplyWorkspaces(ws) {
		t.Fatal("unchanged ApplyWorkspaces should report not-changed")
	}
	if got := s.Workspaces(); len(got) != 1 || got[0].Label != "omega3" {
		t.Fatalf("bad workspaces snapshot: %+v", got)
	}

	// worktree pointer is carried through only when linked
	ws2 := []herdr.WorkspaceInfo{{WorkspaceID: "w5", Label: "wt", Number: 2,
		Worktree: &herdr.WorktreeInfo{RepoName: "ops", IsLinkedWorktree: true}}}
	if !s.ApplyWorkspaces(ws2) {
		t.Fatal("changed workspace list should report changed")
	}
	if got := s.Workspaces(); got[0].Worktree == nil || got[0].Worktree.RepoName != "ops" {
		t.Fatalf("worktree not carried: %+v", got[0])
	}

	tabs := []herdr.TabInfo{{TabID: "w7:t1", Label: "1", Number: 1, WorkspaceID: "w7"}}
	if !s.ApplyTabs(tabs) {
		t.Fatal("first ApplyTabs should report changed")
	}
	if s.ApplyTabs(tabs) {
		t.Fatal("unchanged ApplyTabs should report not-changed")
	}
	if got := s.Tabs(); len(got) != 1 || got[0].TabID != "w7:t1" {
		t.Fatalf("bad tabs snapshot: %+v", got)
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd companion && go test ./internal/state/ -run TestApplyWorkspacesAndTabsChangeDetection -v`
Expected: PASS.

- [ ] **Step 5: Run the state package tests**

Run: `cd companion && go test ./internal/state/`
Expected: `ok` (including the existing pane and concurrency tests).

- [ ] **Step 6: Commit**

```bash
git add companion/internal/state/
git commit -m "feat(companion): store workspaces/tabs with change detection"
```

---

### Task 3: proto frames + wsserver initial snapshot

**Files:**
- Modify: `companion/internal/proto/proto.go`
- Modify: `companion/internal/wsserver/server.go`
- Test: `companion/internal/wsserver/server_test.go`

**Interfaces:**
- Consumes: `state.Workspace`, `state.Tab` (Task 2).
- Produces:
  - `proto.WorkspacesSnapshot([]state.Workspace) []byte` → `{"t":"workspaces","workspaces":[...]}`
  - `proto.TabsSnapshot([]state.Tab) []byte` → `{"t":"tabs","tabs":[...]}`
  - `proto.Welcome` now emits `"companionProtocol": 3`
  - `(*Server).SetWorkspaceSnapshot(func() []state.Workspace)`, `(*Server).SetTabSnapshot(func() []state.Tab)`
  - On WS connect, the client receives `workspaces` and `tabs` frames right after `panes`.

- [ ] **Step 1: Add proto builders + bump protocol**

In `companion/internal/proto/proto.go`, change the `Welcome` function's `"companionProtocol": 2` to `"companionProtocol": 3`. Then after `PanesSnapshot`, add:

```go
func WorkspacesSnapshot(w []state.Workspace) []byte {
	return must(map[string]any{"t": "workspaces", "workspaces": w})
}
func TabsSnapshot(tabs []state.Tab) []byte {
	return must(map[string]any{"t": "tabs", "tabs": tabs})
}
```

- [ ] **Step 2: Add snapshot seams to the server**

In `companion/internal/wsserver/server.go`, add two fields to the `Server` struct (after `snapshot  func() []state.Pane`):

```go
	wsSnapshot  func() []state.Workspace
	tabSnapshot func() []state.Tab
```

In `NewServer`, extend the struct literal defaults (add after `snapshot: func() []state.Pane { return nil },`):

```go
		wsSnapshot: func() []state.Workspace { return nil }, tabSnapshot: func() []state.Tab { return nil },
```

Add setters next to `SetInitialSnapshot`:

```go
func (s *Server) SetWorkspaceSnapshot(fn func() []state.Workspace) { s.wsSnapshot = fn }
func (s *Server) SetTabSnapshot(fn func() []state.Tab)             { s.tabSnapshot = fn }
```

In `Handler`, right after the existing `c.send <- proto.PanesSnapshot(s.snapshot())` line, add:

```go
		c.send <- proto.WorkspacesSnapshot(s.wsSnapshot())
		c.send <- proto.TabsSnapshot(s.tabSnapshot())
```

- [ ] **Step 3: Write the failing test**

Append to `companion/internal/wsserver/server_test.go`:

```go
func TestInitialSnapshotIncludesWorkspacesAndTabs(t *testing.T) {
	s := NewServer(AllowAll{}, stubRPC{})
	s.SetWorkspaceSnapshot(func() []state.Workspace {
		return []state.Workspace{{WorkspaceID: "w7", Label: "omega3", Number: 4, PaneCount: 2, TabCount: 2}}
	})
	s.SetTabSnapshot(func() []state.Tab {
		return []state.Tab{{TabID: "w7:t1", Label: "1", Number: 1, WorkspaceID: "w7"}}
	})

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	welcome := readUntil(t, ctx, c, "welcome")
	if welcome["companionProtocol"].(float64) != 3 {
		t.Fatalf("want companionProtocol 3, got %v", welcome["companionProtocol"])
	}
	ws := readUntil(t, ctx, c, "workspaces")
	arr := ws["workspaces"].([]any)
	if len(arr) != 1 || arr[0].(map[string]any)["label"] != "omega3" {
		t.Fatalf("bad workspaces frame: %+v", ws)
	}
	tabs := readUntil(t, ctx, c, "tabs")
	if len(tabs["tabs"].([]any)) != 1 {
		t.Fatalf("bad tabs frame: %+v", tabs)
	}
}
```

Add `"github.com/mohamed-essam/herdr-mobile/companion/internal/state"` to the test file's imports.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd companion && go test ./internal/wsserver/ -run TestInitialSnapshotIncludesWorkspacesAndTabs -v`
Expected: PASS.

- [ ] **Step 5: Run proto + wsserver tests**

Run: `cd companion && go test ./internal/proto/ ./internal/wsserver/`
Expected: `ok` for both (the existing term/echo/injection-guard tests still pass).

- [ ] **Step 6: Commit**

```bash
git add companion/internal/proto/ companion/internal/wsserver/
git commit -m "feat(companion): workspaces/tabs frames + protocol 3 initial snapshot"
```

---

### Task 4: engine — poll workspace.list/tab.list and broadcast on change

**Files:**
- Modify: `companion/internal/engine/engine.go`
- Test: `companion/internal/engine/engine_test.go` (create)

**Interfaces:**
- Consumes: `Client.ListWorkspaces`/`ListTabs` (Task 1), `Store.ApplyWorkspaces`/`ApplyTabs`/`Workspaces`/`Tabs` (Task 2), `proto.WorkspacesSnapshot`/`TabsSnapshot` and `Server.SetWorkspaceSnapshot`/`SetTabSnapshot` (Task 3).
- Produces: on each poll the engine fetches workspaces+tabs, applies them, and broadcasts a full-list frame when either changed. A newly connected client's initial snapshot is served from the store.

- [ ] **Step 1: Wire the initial snapshot seams**

In `companion/internal/engine/engine.go`, in `New`, right after `e.srv.SetInitialSnapshot(e.store.Snapshot)`, add:

```go
	e.srv.SetWorkspaceSnapshot(e.store.Workspaces)
	e.srv.SetTabSnapshot(e.store.Tabs)
```

- [ ] **Step 2: Fetch + apply + broadcast in pollOnce**

In `companion/internal/engine/engine.go`, at the end of `pollOnce` (after the `for _, tr := range transitions { ... }` loop), add:

```go
	if ws, err := e.client.ListWorkspaces(ctx); err == nil {
		if e.store.ApplyWorkspaces(ws) {
			e.srv.Broadcast(proto.WorkspacesSnapshot(e.store.Workspaces()))
		}
	}
	if tabs, err := e.client.ListTabs(ctx); err == nil {
		if e.store.ApplyTabs(tabs) {
			e.srv.Broadcast(proto.TabsSnapshot(e.store.Tabs()))
		}
	}
```

- [ ] **Step 3: Write the failing test**

Create `companion/internal/engine/engine_test.go`. It stands up a minimal in-package fake herdr unix socket serving `pane.list`/`workspace.list`/`tab.list`, runs one `pollOnce`, and asserts the store was populated:

```go
package engine

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"path/filepath"
	"testing"
)

// tinyHerdr serves just enough of the NDJSON socket API for pollOnce.
func tinyHerdr(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "herdr.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				line, err := bufio.NewReader(c).ReadBytes('\n')
				if err != nil {
					return
				}
				var req struct {
					ID     string `json:"id"`
					Method string `json:"method"`
				}
				json.Unmarshal(line, &req)
				enc := json.NewEncoder(c)
				switch req.Method {
				case "pane.list":
					enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "pane_list", "panes": []any{}}})
				case "workspace.list":
					enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "workspace_list",
						"workspaces": []map[string]any{{"workspace_id": "w7", "label": "omega3", "number": 4, "pane_count": 2, "tab_count": 2}}}})
				case "tab.list":
					enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "tab_list",
						"tabs": []map[string]any{{"tab_id": "w7:t1", "label": "1", "number": 1, "workspace_id": "w7"}}}})
				default:
					enc.Encode(map[string]any{"id": req.ID, "error": map[string]any{"code": "unknown_method", "message": req.Method}})
				}
			}(c)
		}
	}()
	return path
}

func TestPollOncePopulatesWorkspacesAndTabs(t *testing.T) {
	e := New(Config{SocketPath: tinyHerdr(t), ListenAddr: "127.0.0.1:0"})
	e.pollOnce(context.Background())

	ws := e.store.Workspaces()
	if len(ws) != 1 || ws[0].Label != "omega3" {
		t.Fatalf("workspaces not populated: %+v", ws)
	}
	tabs := e.store.Tabs()
	if len(tabs) != 1 || tabs[0].TabID != "w7:t1" {
		t.Fatalf("tabs not populated: %+v", tabs)
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd companion && go test ./internal/engine/ -run TestPollOncePopulatesWorkspacesAndTabs -v`
Expected: PASS.

- [ ] **Step 5: Build + vet + full companion suite**

Run: `cd companion && go build ./... && go vet ./... && go test ./...`
Expected: builds clean, `ok` for every package.

- [ ] **Step 6: Commit**

```bash
git add companion/internal/engine/
git commit -m "feat(companion): poll workspace/tab lists and broadcast on change"
```

---

### Task 5: app — Protocol types + workspaces/tabs frames

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`

**Interfaces:**
- Produces:
  - `Worktree(repoName: String? = null, isLinkedWorktree: Boolean = false)`
  - `Workspace(workspaceId, label, number, agentStatus: String? = null, focused, paneCount, tabCount, worktree: Worktree? = null)`
  - `Tab(tabId, label, number, workspaceId, agentStatus: String? = null, focused, paneCount)`
  - `ServerFrame.Workspaces(workspaces: List<Workspace>)`, `ServerFrame.Tabs(tabs: List<Tab>)`
  - `parseServerFrame` handles `"workspaces"` and `"tabs"`.

- [ ] **Step 1: Add data classes**

In `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`, after the `Pane` data class, add:

```kotlin
@Serializable
data class Worktree(
    val repoName: String? = null,
    val isLinkedWorktree: Boolean = false,
)

@Serializable
data class Workspace(
    val workspaceId: String,
    val label: String = "",
    val number: Int = 0,
    val agentStatus: String? = null,
    val focused: Boolean = false,
    val paneCount: Int = 0,
    val tabCount: Int = 0,
    val worktree: Worktree? = null,
)

@Serializable
data class Tab(
    val tabId: String,
    val label: String = "",
    val number: Int = 0,
    val workspaceId: String = "",
    val agentStatus: String? = null,
    val focused: Boolean = false,
    val paneCount: Int = 0,
)
```

- [ ] **Step 2: Add frame variants + parsing**

In the `ServerFrame` sealed interface, add:

```kotlin
    data class Workspaces(val workspaces: List<Workspace>) : ServerFrame
    data class Tabs(val tabs: List<Tab>) : ServerFrame
```

In `parseServerFrame`, add two branches to the `when` (after the `"panes"` branch):

```kotlin
        "workspaces" -> ServerFrame.Workspaces(json.decodeFromJsonElement(obj["workspaces"]!!))
        "tabs" -> ServerFrame.Tabs(json.decodeFromJsonElement(obj["tabs"]!!))
```

- [ ] **Step 3: Write the failing test**

Append to `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`:

```kotlin
    @Test fun parsesWorkspacesFrameWithWorktree() {
        val f = parseServerFrame("""{"t":"workspaces","workspaces":[{"workspaceId":"w5","label":"wt-cost","number":2,"focused":true,"paneCount":1,"tabCount":1,"worktree":{"repoName":"ops","isLinkedWorktree":true}}]}""")
        assertTrue(f is ServerFrame.Workspaces)
        val w = (f as ServerFrame.Workspaces).workspaces.single()
        assertEquals("wt-cost", w.label)
        assertEquals("ops", w.worktree?.repoName)
        assertTrue(w.worktree?.isLinkedWorktree == true)
    }

    @Test fun parsesWorkspaceWithoutWorktreeAndTabsFrame() {
        val w = (parseServerFrame("""{"t":"workspaces","workspaces":[{"workspaceId":"w3","label":"apollo","number":1,"paneCount":1,"tabCount":1}]}""") as ServerFrame.Workspaces).workspaces.single()
        assertNull(w.worktree)
        val tabs = (parseServerFrame("""{"t":"tabs","tabs":[{"tabId":"w7:t2","label":"2","number":2,"workspaceId":"w7","agentStatus":"unknown","paneCount":1}]}""") as ServerFrame.Tabs).tabs.single()
        assertEquals("w7:t2", tabs.tabId)
        assertEquals("w7", tabs.workspaceId)
    }
```

- [ ] **Step 4: Run the app unit tests**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest"`
Expected: PASS (all ProtocolTest cases green).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt
git commit -m "feat(app): workspaces/tabs protocol types + frame parsing"
```

---

### Task 6: app — repository holds workspaces + tabs

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/data/PaneRepository.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/PaneRepositoryTest.kt`

**Interfaces:**
- Consumes: `ServerFrame.Workspaces`, `ServerFrame.Tabs`, `Workspace`, `Tab` (Task 5).
- Produces: `PaneRepository.workspaces: StateFlow<List<Workspace>>`, `PaneRepository.tabs: StateFlow<List<Tab>>`; `onFrame` updates them from the new frames.

- [ ] **Step 1: Add flows + frame handling**

In `app/app/src/main/java/dev/herdr/mobile/data/PaneRepository.kt`, add imports `dev.herdr.mobile.net.Workspace` and `dev.herdr.mobile.net.Tab`. Add fields after the panes flow:

```kotlin
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()
```

In `onFrame`, add two branches to the `when` before the `else`:

```kotlin
            is ServerFrame.Workspaces -> { _workspaces.value = frame.workspaces; return }
            is ServerFrame.Tabs -> { _tabs.value = frame.tabs; return }
```

(These `return` because they don't affect the pane list / re-sort.)

- [ ] **Step 2: Write the failing test**

Append to `app/app/src/test/java/dev/herdr/mobile/PaneRepositoryTest.kt`:

```kotlin
    @Test fun storesWorkspacesAndTabs() {
        val repo = PaneRepository()
        repo.onFrame(ServerFrame.Workspaces(listOf(
            Workspace(workspaceId = "w7", label = "omega3", number = 4, paneCount = 2, tabCount = 2))))
        repo.onFrame(ServerFrame.Tabs(listOf(
            Tab(tabId = "w7:t1", label = "1", number = 1, workspaceId = "w7"))))
        assertEquals("omega3", repo.workspaces.value.single().label)
        assertEquals("w7:t1", repo.tabs.value.single().tabId)
    }
```

- [ ] **Step 3: Run the test**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.PaneRepositoryTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/data/PaneRepository.kt app/app/src/test/java/dev/herdr/mobile/PaneRepositoryTest.kt
git commit -m "feat(app): repository holds workspaces and tabs"
```

---

### Task 7: app — buildTree pure join function

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/TreeModelTest.kt` (create)

**Interfaces:**
- Consumes: `Workspace`, `Tab`, `Pane` (Tasks 5, existing).
- Produces:
  - `data class TabNode(val tab: Tab, val panes: List<Pane>)`
  - `data class WorkspaceNode(val ws: Workspace, val tabs: List<TabNode>)`
  - `fun buildTree(workspaces: List<Workspace>, tabs: List<Tab>, panes: List<Pane>): List<WorkspaceNode>`
  - Orphan panes (workspace or tab absent) go under a synthetic `WorkspaceNode` whose `ws.workspaceId == ""` and `ws.label == "(unknown)"`, sorted last.

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/TreeModelTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.buildTree
import org.junit.Assert.*
import org.junit.Test

class TreeModelTest {
    private fun ws(id: String, num: Int) = Workspace(workspaceId = id, label = id, number = num)
    private fun tab(id: String, ws: String, num: Int) = Tab(tabId = id, label = "$num", number = num, workspaceId = ws)
    private fun pane(id: String, ws: String, tab: String) = Pane(paneId = id, workspaceId = ws, tabId = tab, agent = "claude", agentStatus = "idle")

    @Test fun joinsAndOrdersByNumberThenPaneId() {
        val tree = buildTree(
            workspaces = listOf(ws("w7", 4), ws("w3", 1)),
            tabs = listOf(tab("w7:t2", "w7", 2), tab("w7:t1", "w7", 1), tab("w3:t1", "w3", 1)),
            panes = listOf(
                pane("w7:p2", "w7", "w7:t1"), pane("w7:p1", "w7", "w7:t1"),
                pane("w3:p1", "w3", "w3:t1")),
        )
        // workspaces ordered by number: w3 (1) then w7 (4)
        assertEquals(listOf("w3", "w7"), tree.map { it.ws.workspaceId })
        val w7 = tree[1]
        // tabs ordered by number: t1 then t2
        assertEquals(listOf("w7:t1", "w7:t2"), w7.tabs.map { it.tab.tabId })
        // panes under w7:t1 ordered by paneId
        assertEquals(listOf("w7:p1", "w7:p2"), w7.tabs[0].panes.map { it.paneId })
    }

    @Test fun orphanPanesGoUnderUnknownGroupSortedLast() {
        val tree = buildTree(
            workspaces = listOf(ws("w3", 1)),
            tabs = listOf(tab("w3:t1", "w3", 1)),
            panes = listOf(pane("w3:p1", "w3", "w3:t1"), pane("wX:p9", "wX", "wX:tZ")),
        )
        assertEquals("", tree.last().ws.workspaceId)
        assertEquals("(unknown)", tree.last().ws.label)
        assertEquals("wX:p9", tree.last().tabs.single().panes.single().paneId)
    }

    @Test fun emptyInputsYieldEmptyTree() {
        assertTrue(buildTree(emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TreeModelTest"`
Expected: FAIL to compile ("unresolved reference: buildTree").

- [ ] **Step 3: Write the implementation**

Create `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt`:

```kotlin
package dev.herdr.mobile.ui

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace

data class TabNode(val tab: Tab, val panes: List<Pane>)
data class WorkspaceNode(val ws: Workspace, val tabs: List<TabNode>)

/**
 * Joins the flat workspace/tab/pane lists into a workspace → tab → pane tree.
 * Ordering: workspaces by number, tabs by number, panes by paneId. Panes whose
 * workspace or tab is missing from the lists surface under a synthetic
 * "(unknown)" workspace (sorted last) so nothing is silently dropped.
 */
fun buildTree(
    workspaces: List<Workspace>,
    tabs: List<Tab>,
    panes: List<Pane>,
): List<WorkspaceNode> {
    val wsById = workspaces.associateBy { it.workspaceId }
    val tabById = tabs.associateBy { it.tabId }
    val panesByTab = LinkedHashMap<String, MutableList<Pane>>()
    val orphanPanes = mutableListOf<Pane>()
    for (p in panes) {
        if (wsById.containsKey(p.workspaceId) && tabById.containsKey(p.tabId)) {
            panesByTab.getOrPut(p.tabId) { mutableListOf() }.add(p)
        } else {
            orphanPanes.add(p)
        }
    }

    val tabsByWs = tabs.groupBy { it.workspaceId }
    val nodes = workspaces.sortedBy { it.number }.map { ws ->
        val tabNodes = (tabsByWs[ws.workspaceId] ?: emptyList())
            .sortedBy { it.number }
            .map { t -> TabNode(t, (panesByTab[t.tabId] ?: emptyList()).sortedBy { it.paneId }) }
        WorkspaceNode(ws, tabNodes)
    }.toMutableList()

    if (orphanPanes.isNotEmpty()) {
        val unknownWs = Workspace(workspaceId = "", label = "(unknown)", number = Int.MAX_VALUE)
        val unknownTab = Tab(tabId = "", label = "", number = 0, workspaceId = "")
        nodes.add(WorkspaceNode(unknownWs, listOf(TabNode(unknownTab, orphanPanes.sortedBy { it.paneId }))))
    }
    return nodes
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TreeModelTest"`
Expected: PASS (all three cases).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt app/app/src/test/java/dev/herdr/mobile/TreeModelTest.kt
git commit -m "feat(app): buildTree join of workspaces/tabs/panes"
```

---

### Task 8: app — ViewModel tree flow + expansion state

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `repo.workspaces`, `repo.tabs`, `repo.panes` (Task 6), `buildTree` (Task 7).
- Produces:
  - `DashboardViewModel.tree: StateFlow<List<WorkspaceNode>>` (combined + built)
  - `DashboardViewModel.collapsed: StateFlow<Set<String>>` — the set holds ids the user has **collapsed**. A node is expanded unless its id is in the set, so the tree is open-by-default with zero seeding.
  - `DashboardViewModel.toggleExpanded(id: String)` — flips membership in `collapsed` (adding = collapse, removing = expand)
  - `DashboardViewModel.lastOpenedPaneId: StateFlow<String?>` — set inside `openTerminal`

- [ ] **Step 1: Add the flows + toggle**

Rewrite `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt` to:

```kotlin
package dev.herdr.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val client: CompanionClient,
    private val repo: PaneRepository,
) : ViewModel() {
    val panes: StateFlow<List<Pane>> = repo.panes
    val connected: StateFlow<Boolean> = client.connected

    val tree: StateFlow<List<WorkspaceNode>> =
        combine(repo.workspaces, repo.tabs, repo.panes) { ws, tabs, panes -> buildTree(ws, tabs, panes) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ids the user has COLLAPSED; a node is expanded unless its id is here.
    private val _collapsed = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = _collapsed
    fun toggleExpanded(id: String) = _collapsed.update { if (id in it) it - id else it + id }

    private val _lastOpenedPaneId = MutableStateFlow<String?>(null)
    val lastOpenedPaneId: StateFlow<String?> = _lastOpenedPaneId

    fun start(url: String) {
        viewModelScope.launch { client.frames.collect { repo.onFrame(it) } }
        client.connect(url)
    }

    fun registerPush(endpoint: String) = client.registerPush(endpoint)

    suspend fun openTerminal(target: String, cols: Int, rows: Int): String {
        _lastOpenedPaneId.value = target
        return client.openTerminal(target, cols, rows)
    }
    fun termInput(termId: String, data: ByteArray) = client.sendTermInput(termId, data)
    fun termResize(termId: String, cols: Int, rows: Int) = client.sendTermResize(termId, cols, rows)
    fun closeTerminal(termId: String) = client.closeTerminal(termId)
    val frames get() = client.frames

    override fun onCleared() { client.close() }
}
```

- [ ] **Step 2: Write the failing test**

The expansion toggle is synchronous. The existing test's `@Before` already sets `Dispatchers.setMain(Dispatchers.Unconfined)`, which the eager `tree` combine needs, so construct the VM inline the same way the existing test does — `DashboardViewModel(CompanionClient(), PaneRepository())` (no `start()` needed for this test). Append to `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt`:

```kotlin
    @Test fun toggleExpandedFlipsCollapsedMembership() {
        val vm = DashboardViewModel(CompanionClient(), PaneRepository())
        assertFalse(vm.collapsed.value.contains("w7"))
        vm.toggleExpanded("w7")
        assertTrue(vm.collapsed.value.contains("w7")) // now collapsed
        vm.toggleExpanded("w7")
        assertFalse(vm.collapsed.value.contains("w7")) // expanded again
    }
```

- [ ] **Step 3: Run the test**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.DashboardViewModelTest"`
Expected: PASS (existing cases + the new toggle test).

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt
git commit -m "feat(app): ViewModel tree flow + collapse state + last-opened tracking"
```

---

### Task 9: app — SidebarDrawer UI + hamburger + tap-to-open

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`

**Interfaces:**
- Consumes: `DashboardViewModel.tree`/`collapsed`/`toggleExpanded`/`lastOpenedPaneId` (Task 8), `WorkspaceNode`/`TabNode` (Task 7), `Pane` (existing), `statusColor`/`statusGlyph`/`spinnerFrame` (existing theme).
- Produces: a `ModalNavigationDrawer` wrapping the dashboard; a hamburger in the top bar opens it; tapping an agent pane in the tree opens the terminal (reusing `DashboardScreen`'s existing `selected` state).

- [ ] **Step 1: Build the drawer composable**

Create `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt`:

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.statusColor
import dev.herdr.mobile.ui.theme.statusGlyph

/** One flattened, renderable row of the tree. */
private sealed interface Row {
    data class Ws(val node: WorkspaceNode, val expanded: Boolean) : Row
    data class TabRow(val node: TabNode, val expanded: Boolean) : Row
    data class PaneRowItem(val pane: Pane) : Row
}

/** A node is expanded unless its id is in [collapsed]. */
private fun flatten(tree: List<WorkspaceNode>, collapsed: Set<String>): List<Row> {
    val rows = mutableListOf<Row>()
    for (w in tree) {
        val wOpen = w.ws.workspaceId !in collapsed
        rows.add(Row.Ws(w, wOpen))
        if (!wOpen) continue
        for (t in w.tabs) {
            val tOpen = t.tab.tabId !in collapsed
            rows.add(Row.TabRow(t, tOpen))
            if (!tOpen) continue
            t.panes.forEach { rows.add(Row.PaneRowItem(it)) }
        }
    }
    return rows
}

@Composable
fun SidebarDrawer(
    tree: List<WorkspaceNode>,
    collapsed: Set<String>,
    focusedPaneId: String?,
    lastOpenedPaneId: String?,
    onToggle: (String) -> Unit,
    onSelectPane: (Pane) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val rows = flatten(tree, collapsed)
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("herdr", style = MaterialTheme.typography.titleMedium)
                Text("  ❯", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { rowKey(it) }) { row ->
                    when (row) {
                        is Row.Ws -> WorkspaceRow(row, dark, onToggle)
                        is Row.TabRow -> TabRowView(row, dark, onToggle)
                        is Row.PaneRowItem -> PaneTreeRow(row.pane, dark, focusedPaneId, lastOpenedPaneId, onSelectPane)
                    }
                }
            }
        }
    }
}

private fun rowKey(r: Row): String = when (r) {
    is Row.Ws -> "w:" + r.node.ws.workspaceId
    is Row.TabRow -> "t:" + r.node.tab.tabId
    is Row.PaneRowItem -> "p:" + r.pane.paneId
}

@Composable
private fun WorkspaceRow(row: Row.Ws, dark: Boolean, onToggle: (String) -> Unit) {
    val ws = row.node.ws
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(ws.workspaceId) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        StatusGlyph(ws.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        Text(ws.label.ifEmpty { "(unknown)" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (ws.number > 0) {
            Spacer(Modifier.width(6.dp))
            Text("#${ws.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.weight(1f))
        ws.worktree?.repoName?.let { repo ->
            Text("⑂ $repo", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f))
            Spacer(Modifier.width(8.dp))
        }
        if (ws.paneCount > 0) Text("${ws.paneCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TabRowView(row: Row.TabRow, dark: Boolean, onToggle: (String) -> Unit) {
    val tab = row.node.tab
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(tab.tabId) }.padding(start = 32.dp, end = 12.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        StatusGlyph(tab.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        Text(if (tab.label.isEmpty()) "—" else "tab ${tab.label}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PaneTreeRow(
    pane: Pane, dark: Boolean, focusedPaneId: String?, lastOpenedPaneId: String?, onSelectPane: (Pane) -> Unit,
) {
    val isAgent = pane.agent != null
    val marked = pane.focused || pane.paneId == focusedPaneId || pane.paneId == lastOpenedPaneId
    val base = Modifier.fillMaxWidth()
    val clickable = if (isAgent) base.clickable { onSelectPane(pane) } else base
    Row(
        clickable
            .then(if (marked) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .padding(start = 52.dp, end = 12.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marked) {
            Text("▎", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
        }
        StatusGlyph(pane.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        val label = pane.agent ?: "shell"
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.alpha(if (isAgent) 1f else 0.5f),
        )
        val base = pane.cwd.substringAfterLast('/')
        if (base.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(base, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(if (isAgent) 0.8f else 0.4f))
        }
    }
}

@Composable
private fun StatusGlyph(status: String?, dark: Boolean) {
    val glyph = if (status == "working") spinnerFrame() else statusGlyph(status)
    Text(glyph, color = statusColor(status, dark), style = MaterialTheme.typography.bodyMedium)
}
```

- [ ] **Step 2: Wrap the dashboard in the drawer + add the hamburger**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`:

Add imports:

```kotlin
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import kotlinx.coroutines.launch
```

Replace the body of `DashboardScreen` (from the `Scaffold(` call to the end of the function) so the `Scaffold` is wrapped by a drawer, and collect the new flows. The terminal short-circuit at the top stays as-is. New structure:

```kotlin
    val tree by vm.tree.collectAsState()
    val collapsed by vm.collapsed.collectAsState()
    val lastOpened by vm.lastOpenedPaneId.collectAsState()
    val focusedPaneId = panes.firstOrNull { it.focused }?.paneId
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidebarDrawer(
                tree = tree,
                collapsed = collapsed,
                focusedPaneId = focusedPaneId,
                lastOpenedPaneId = lastOpened,
                onToggle = vm::toggleExpanded,
                onSelectPane = { p ->
                    scope.launch { drawerState.close() }
                    selected = p
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { HerdrTopBar(connected, panes.size) { scope.launch { drawerState.open() } } },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (!connected) ReconnectingBanner()
                if (panes.isEmpty()) {
                    EmptyState(connected)
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                        items(panes, key = { it.paneId }) { pane ->
                            PaneRow(pane) { p -> if (p.agent != null) selected = p }
                        }
                    }
                }
            }
        }
    }
```

Update `HerdrTopBar`'s signature to accept the menu callback and render the hamburger as its `navigationIcon`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HerdrTopBar(connected: Boolean, count: Int, onMenu: () -> Unit) {
    val dark = isSystemInDarkTheme()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "workspaces", tint = MaterialTheme.colorScheme.onSurface)
            }
        },
        title = { /* unchanged title Column */ },
        actions = { /* unchanged actions */ },
    )
}
```

Keep the existing `title` and `actions` lambda bodies exactly as they are now — only the signature and `navigationIcon` are added.

- [ ] **Step 3: Build the debug APK**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (Fix any unresolved import — `Icons.Filled.Menu` requires `androidx.compose.material:material-icons-core`, already transitively present via Material3; if the icon fails to resolve, replace the `Icon(...)` with `Text("≡", style = MaterialTheme.typography.titleLarge)` inside the `IconButton`.)

- [ ] **Step 4: Run the full app unit-test suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
Expected: all tests pass.

- [ ] **Step 5: Live validation (emulator + phone)**

The companion is already rebuilt (Tasks 1–4) and must be restarted so it serves protocol 3 and the new frames:

```bash
# rebuild + redeploy the companion on 0.0.0.0 (serves emulator + phone)
cd companion && go build -o ~/.local/bin/herdr-mobiled.new ./cmd/herdr-mobiled
OLD=$(pgrep -x herdr-mobiled); kill "$OLD"; sleep 1
mv ~/.local/bin/herdr-mobiled.new ~/.local/bin/herdr-mobiled
nohup ~/.local/bin/herdr-mobiled --listen 0.0.0.0:8787 > /tmp/herdr-companion.log 2>&1 &
```

Install + verify on the emulator via the harness, and on the phone:

```bash
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"
APK=app/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r "$APK"
adb -s adb-R5CY32261JN-KB349E._adb-tls-connect._tcp install -r "$APK"
```

Confirm by hand: open the drawer (hamburger or left edge-swipe) → the real tree renders (e.g. `omega3` with two tabs, `apollo`, `herdr-mobile`, a `⑂ ops` badge on any linked-worktree workspace); collapse/expand works; per-pane + rolled-up status track herdr (spinner on a working agent); the focused pane shows the accent marker; tapping an agent pane closes the drawer and opens its terminal; shell panes are dimmed and do nothing.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): Paseo-style sidebar drawer with workspace/tab/pane tree"
```

---

## Post-implementation

- Update `docs/superpowers/specs/2026-07-08-herdr-mobile-sidebar-design.md` status note if anything diverged.
- Update the memory file `the project design-notes memory` with: the sidebar drawer shipped, `workspace.list`/`tab.list` fields (labels + worktree), companionProtocol 3, and the collapse-set-holds-collapsed-ids convention.
