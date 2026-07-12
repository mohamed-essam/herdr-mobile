# Close-Cascade Warning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When closing a workspace that is a repo's base (non-linked) worktree with ≥2 open members, the confirm dialog names the sibling worktree-workspaces that herdr will also close.

**Architecture:** On workspace-close tap, the app sends a new `close_impact` request. The companion runs herdr `worktree.list` for that workspace's repo, applies herdr's own cascade rule against the worktree entries + the workspace snapshot, and replies with the labeled siblings. The confirm dialog appends an "Also closes …" line. On any error/timeout the app falls back to the current confirm.

**Tech Stack:** Go 1.23 companion (`companion/`, module `github.com/mohamed-essam/herdr-mobile/companion`); Kotlin/Compose app (`app/`); NDJSON over Unix socket to herdr; JSON over WebSocket to the app.

## Global Constraints

- **companionProtocol 5 → 6**, additive only. The `close_impact` request/reply are the only new frames; every existing frame is unchanged.
- **Request frame:** `{"t":"close_impact","reqId":"<string>","workspaceId":"<string>"}`. Reuses existing `proto.ClientMsg` fields (`T`, `ReqID`, `WorkspaceID`) — no new request field.
- **Reply frame:** `{"t":"close_impact","reqId":"<string>","workspaceId":"<string>","alsoCloses":[{"workspaceId":"<string>","label":"<string>"}]}`. `alsoCloses` is ALWAYS present (empty array when no cascade).
- **Cascade rule (from herdr `close_selected_workspace`):** cascade fires **only if** the target's `worktree.list` entry has `is_linked_worktree == false` **AND** ≥2 entries in the repo have a non-empty `open_workspace_id`. Otherwise no cascade.
- **Label fallback:** workspace-snapshot label → worktree `branch` → raw `workspace_id`. Never blank.
- **Fallback discipline:** any companion error → the app shows the current confirm with no "Also closes" line. Never block a close on a failed impact query.
- **Pane/tab closes are unchanged** — they never query `close_impact`.
- Go tests: `cd companion && go test ./...`. App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`.

---

### Task 1: Companion — `worktree.list` client method

Adds `Client.ListWorktrees` and the result types, plus `worktree.list` support in the fake herdr used by client tests.

**Files:**
- Modify: `companion/internal/herdr/types.go` (append result types after line 107)
- Modify: `companion/internal/herdr/client.go` (add method after `ListTabs`, ~line 90)
- Modify: `companion/internal/herdr/fakeherdr_test.go` (add `worktrees` field + `worktree.list` case)
- Test: `companion/internal/herdr/client_test.go` (new test)

**Interfaces:**
- Produces: `type WorktreeEntry struct { Path, Branch string; IsLinkedWorktree bool; OpenWorkspaceID, Label string }` and `func (c *Client) ListWorktrees(ctx context.Context, workspaceID string) ([]WorktreeEntry, error)`.

- [ ] **Step 1: Add result types to `types.go`**

Append to `companion/internal/herdr/types.go`:

```go
// WorktreeEntry is one entry from herdr's worktree.list (scoped to one repo).
// Branch and OpenWorkspaceID are Option in herdr and arrive absent (→ "")
// when None. Label is the repo name (same for every entry), so it is NOT a
// per-sibling label — resolve sibling labels from the workspace snapshot.
type WorktreeEntry struct {
	Path             string `json:"path"`
	Branch           string `json:"branch"`
	IsLinkedWorktree bool   `json:"is_linked_worktree"`
	OpenWorkspaceID  string `json:"open_workspace_id"`
	Label            string `json:"label"`
}

type worktreeListResult struct {
	Type      string          `json:"type"`
	Worktrees []WorktreeEntry `json:"worktrees"`
}
```

- [ ] **Step 2: Add `ListWorktrees` to `client.go`**

Insert after `ListTabs` (after line 90) in `companion/internal/herdr/client.go`:

```go
func (c *Client) ListWorktrees(ctx context.Context, workspaceID string) ([]WorktreeEntry, error) {
	params := map[string]any{}
	if workspaceID != "" {
		params["workspace_id"] = workspaceID
	}
	raw, err := c.Call(ctx, "worktree.list", params)
	if err != nil {
		return nil, err
	}
	var res worktreeListResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Worktrees, nil
}
```

- [ ] **Step 3: Add `worktree.list` to the fake herdr**

In `companion/internal/herdr/fakeherdr_test.go`, add a `worktrees` field to the `fakeHerdr` struct (after the `tabs` field, line 23):

```go
	tabs       []TabInfo
	worktrees  []WorktreeEntry
```

Add a setter next to `SetTabs` (after line 55):

```go
func (f *fakeHerdr) SetWorktrees(w []WorktreeEntry) { f.mu.Lock(); f.worktrees = w; f.mu.Unlock() }
```

Add a `worktree.list` case in `handle`'s switch (after the `tab.list` case, line 111). It records the params so the test can assert `workspace_id` was passed:

```go
	case "worktree.list":
		f.lastCall <- recordedCall{Method: req.Method, Params: req.Params}
		f.mu.Lock()
		wts := f.worktrees
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "worktree_list", "worktrees": wts}})
```

- [ ] **Step 4: Write the failing test**

Add to `companion/internal/herdr/client_test.go`:

```go
func TestClientListWorktrees(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetWorktrees([]WorktreeEntry{
		{Path: "/repo", IsLinkedWorktree: false, OpenWorkspaceID: "w1", Label: "app"},
		{Path: "/repo-wt", Branch: "feat/x", IsLinkedWorktree: true, OpenWorkspaceID: "w2", Label: "app"},
	})
	c := New(f.SocketPath())
	wts, err := c.ListWorktrees(context.Background(), "w1")
	if err != nil {
		t.Fatalf("ListWorktrees: %v", err)
	}
	if len(wts) != 2 || wts[0].OpenWorkspaceID != "w1" || wts[1].Branch != "feat/x" || !wts[1].IsLinkedWorktree {
		t.Fatalf("unexpected entries: %+v", wts)
	}
	select {
	case rec := <-f.lastCall:
		if rec.Method != "worktree.list" || rec.Params["workspace_id"] != "w1" {
			t.Fatalf("bad worktree.list params: %+v", rec)
		}
	case <-time.After(time.Second):
		t.Fatal("worktree.list not recorded")
	}
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd companion && go test ./internal/herdr/ -run TestClientListWorktrees -v`
Expected: compile error or FAIL until Steps 1-3 are in place; after them, PASS.

- [ ] **Step 6: Run the full herdr package tests**

Run: `cd companion && go test ./internal/herdr/`
Expected: `ok` — the new fake field/case must not break existing tests.

- [ ] **Step 7: Commit**

```bash
git add companion/internal/herdr/
git commit -m "feat(companion): worktree.list client method"
```

---

### Task 2: Companion — `close_impact` handler, computation, and protocol bump

Adds the `close_impact` frame end-to-end in the companion: proto builder + protocol 6, the `computeAlsoCloses` pure function, the `HerdrRPC.ListWorktrees` method, the readLoop case, and the test-double updates that keep `go test ./...` compiling.

**Files:**
- Modify: `companion/internal/proto/proto.go` (protocol 5→6; add `AlsoClose` type + `CloseImpact` builder)
- Modify: `companion/internal/wsserver/server.go` (import `herdr`; add `ListWorktrees` to `HerdrRPC`; add `handleCloseImpact` + `computeAlsoCloses`; readLoop case)
- Modify: `companion/internal/wsserver/server_test.go` (add `ListWorktrees` to `stubRPC`; new tests)
- Modify: `companion/internal/engine/engine_test.go` (add `ListWorktrees` to `fakeRPC`)

**Interfaces:**
- Consumes: `herdr.WorktreeEntry`, `herdr.Client.ListWorktrees` (Task 1); `state.Workspace{WorkspaceID, Label string}`.
- Produces: `proto.AlsoClose{WorkspaceID, Label string}` (json `workspaceId`/`label`); `proto.CloseImpact(reqID, workspaceID string, alsoCloses []proto.AlsoClose) []byte`; `HerdrRPC.ListWorktrees(ctx, workspaceID) ([]herdr.WorktreeEntry, error)`; `computeAlsoCloses(target string, entries []herdr.WorktreeEntry, workspaces []state.Workspace) []proto.AlsoClose`.

- [ ] **Step 1: Bump protocol and add the proto frame**

In `companion/internal/proto/proto.go`, change the `companionProtocol` value in `Welcome` (line 48) from `5` to `6`:

```go
func Welcome(version string, protocol int) []byte {
	return must(map[string]any{"t": "welcome", "herdrVersion": version, "herdrProtocol": protocol, "companionProtocol": 6})
}
```

Add after the `Agents` function (after line 91):

```go
type AlsoClose struct {
	WorkspaceID string `json:"workspaceId"`
	Label       string `json:"label"`
}

// CloseImpact reports which sibling workspaces herdr will also close when the
// target workspace is closed. alsoCloses is always a present array (never null).
func CloseImpact(reqID, workspaceID string, alsoCloses []AlsoClose) []byte {
	if alsoCloses == nil {
		alsoCloses = []AlsoClose{}
	}
	return must(map[string]any{"t": "close_impact", "reqId": reqID, "workspaceId": workspaceID, "alsoCloses": alsoCloses})
}
```

- [ ] **Step 2: Write the failing computation test**

Add to `companion/internal/wsserver/server_test.go`:

```go
func TestComputeAlsoClosesBaseCascades(t *testing.T) {
	entries := []herdr.WorktreeEntry{
		{Path: "/repo", IsLinkedWorktree: false, OpenWorkspaceID: "w1", Label: "app"},
		{Path: "/repo-a", Branch: "feat/a", IsLinkedWorktree: true, OpenWorkspaceID: "w2", Label: "app"},
		{Path: "/repo-b", Branch: "feat/b", IsLinkedWorktree: true, OpenWorkspaceID: "", Label: "app"},
	}
	ws := []state.Workspace{{WorkspaceID: "w1", Label: "main"}, {WorkspaceID: "w2", Label: "ops"}}
	got := computeAlsoCloses("w1", entries, ws)
	if len(got) != 1 || got[0].WorkspaceID != "w2" || got[0].Label != "ops" {
		t.Fatalf("expected [w2/ops], got %+v", got)
	}
}

func TestComputeAlsoClosesLinkedTargetNoCascade(t *testing.T) {
	entries := []herdr.WorktreeEntry{
		{Path: "/repo", IsLinkedWorktree: false, OpenWorkspaceID: "w1", Label: "app"},
		{Path: "/repo-a", IsLinkedWorktree: true, OpenWorkspaceID: "w2", Label: "app"},
	}
	if got := computeAlsoCloses("w2", entries, nil); len(got) != 0 {
		t.Fatalf("linked target should not cascade, got %+v", got)
	}
}

func TestComputeAlsoClosesBaseSingleMemberNoCascade(t *testing.T) {
	entries := []herdr.WorktreeEntry{
		{Path: "/repo", IsLinkedWorktree: false, OpenWorkspaceID: "w1", Label: "app"},
		{Path: "/repo-a", IsLinkedWorktree: true, OpenWorkspaceID: "", Label: "app"},
	}
	if got := computeAlsoCloses("w1", entries, nil); len(got) != 0 {
		t.Fatalf("single open member should not cascade, got %+v", got)
	}
}

func TestComputeAlsoClosesLabelFallback(t *testing.T) {
	entries := []herdr.WorktreeEntry{
		{Path: "/repo", IsLinkedWorktree: false, OpenWorkspaceID: "w1", Label: "app"},
		{Path: "/repo-a", Branch: "feat/a", IsLinkedWorktree: true, OpenWorkspaceID: "w2", Label: "app"},
	}
	// no workspace snapshot entry for w2 → falls back to branch "feat/a"
	got := computeAlsoCloses("w1", entries, []state.Workspace{{WorkspaceID: "w1", Label: "main"}})
	if len(got) != 1 || got[0].Label != "feat/a" {
		t.Fatalf("expected branch fallback, got %+v", got)
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd companion && go test ./internal/wsserver/ -run TestComputeAlsoCloses -v`
Expected: compile error — `computeAlsoCloses` and the `herdr` import don't exist yet.

- [ ] **Step 4: Add the import, interface method, computation, and handler**

In `companion/internal/wsserver/server.go`, add the `herdr` import to the import block (after the `pty` import, line 14):

```go
	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
```

Add to the `HerdrRPC` interface (after `ListAgentNames`, line 34):

```go
	ListWorktrees(ctx context.Context, workspaceID string) ([]herdr.WorktreeEntry, error)
```

Add the readLoop case after the `list_agents` case (after line 217, before the closing `}` of the switch):

```go
		case "close_impact":
			s.handleCloseImpact(ctx, c, m)
```

Add the handler and pure computation after `handleMove` (after line 306):

```go
// handleCloseImpact answers a close_impact query: it runs worktree.list for the
// target workspace's repo and returns the sibling workspaces herdr would also
// close. Any error yields an error frame; the app falls back to the plain
// confirm. This is read-only — it never mutates herdr and never pokes.
func (s *Server) handleCloseImpact(ctx context.Context, c *client, m proto.ClientMsg) {
	if m.WorkspaceID == "" {
		c.send <- proto.ErrorFrame(m.ReqID, "close_impact_failed", "invalid workspace id")
		return
	}
	entries, err := s.rpc.ListWorktrees(ctx, m.WorkspaceID)
	if err != nil {
		c.send <- proto.ErrorFrame(m.ReqID, "close_impact_failed", err.Error())
		return
	}
	also := computeAlsoCloses(m.WorkspaceID, entries, s.wsSnapshot())
	c.send <- proto.CloseImpact(m.ReqID, m.WorkspaceID, also)
}

// computeAlsoCloses reproduces herdr's close_selected_workspace cascade rule:
// closing a repo's BASE (non-linked) workspace closes the whole worktree group
// when ≥2 of its worktrees have an open workspace. Returns the OTHER open
// members, labeled from the workspace snapshot (fallback: branch, then id).
// Returns an empty (non-nil) slice when there is no cascade.
func computeAlsoCloses(target string, entries []herdr.WorktreeEntry, workspaces []state.Workspace) []proto.AlsoClose {
	var targetEntry *herdr.WorktreeEntry
	openMembers := 0
	for i := range entries {
		if entries[i].OpenWorkspaceID != "" {
			openMembers++
		}
		if entries[i].OpenWorkspaceID == target {
			targetEntry = &entries[i]
		}
	}
	if targetEntry == nil || targetEntry.IsLinkedWorktree || openMembers < 2 {
		return []proto.AlsoClose{}
	}
	labels := make(map[string]string, len(workspaces))
	for _, w := range workspaces {
		labels[w.WorkspaceID] = w.Label
	}
	out := []proto.AlsoClose{}
	for _, e := range entries {
		if e.OpenWorkspaceID == "" || e.OpenWorkspaceID == target {
			continue
		}
		label := labels[e.OpenWorkspaceID]
		if label == "" {
			label = e.Branch
		}
		if label == "" {
			label = e.OpenWorkspaceID
		}
		out = append(out, proto.AlsoClose{WorkspaceID: e.OpenWorkspaceID, Label: label})
	}
	return out
}
```

- [ ] **Step 5: Add `ListWorktrees` to the test doubles**

In `companion/internal/wsserver/server_test.go`, add to `stubRPC` (after `ListAgentNames`):

```go
func (s *stubRPC) ListWorktrees(context.Context, string) ([]herdr.WorktreeEntry, error) {
	return s.worktrees, nil
}
```

Add a `worktrees` field to the `stubRPC` struct (after `failOn`):

```go
	worktrees []herdr.WorktreeEntry
```

Add the `herdr` import to `server_test.go`'s import block:

```go
	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
```

In `companion/internal/engine/engine_test.go`, add to `fakeRPC` (after `ListAgentNames`, line 102):

```go
func (fakeRPC) ListWorktrees(context.Context, string) ([]herdr.WorktreeEntry, error) { return nil, nil }
```

Ensure `engine_test.go` imports `herdr` (it already imports the herdr package for `herdr.Event`; if not, add `"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"`).

- [ ] **Step 6: Write the failing handler round-trip test**

Add to `companion/internal/wsserver/server_test.go`. This mirrors the existing WS round-trip tests (dial `httptest` server, drain welcome+snapshots, send a frame, read the reply):

```go
func TestCloseImpactReturnsSiblings(t *testing.T) {
	stub := &stubRPC{worktrees: []herdr.WorktreeEntry{
		{Path: "/repo", IsLinkedWorktree: false, OpenWorkspaceID: "w1", Label: "app"},
		{Path: "/repo-a", Branch: "feat/a", IsLinkedWorktree: true, OpenWorkspaceID: "w2", Label: "app"},
	}}
	srv := NewServer(AllowAll{}, stub)
	srv.SetWorkspaceSnapshot(func() []state.Workspace {
		return []state.Workspace{{WorkspaceID: "w1", Label: "main"}, {WorkspaceID: "w2", Label: "ops"}}
	})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	conn, _, err := websocket.Dial(context.Background(), "ws"+strings.TrimPrefix(ts.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	drainSnapshots(t, conn) // welcome + panes + workspaces + tabs
	writeJSON(t, conn, `{"t":"close_impact","reqId":"i1","workspaceId":"w1"}`)
	got := readFrame(t, conn)
	if got["t"] != "close_impact" || got["reqId"] != "i1" {
		t.Fatalf("bad reply: %v", got)
	}
	arr, ok := got["alsoCloses"].([]any)
	if !ok || len(arr) != 1 {
		t.Fatalf("expected 1 alsoCloses, got %v", got["alsoCloses"])
	}
	first := arr[0].(map[string]any)
	if first["workspaceId"] != "w2" || first["label"] != "ops" {
		t.Fatalf("bad sibling: %v", first)
	}
}
```

Reuse the existing helpers in `server_test.go` (`drainSnapshots`, `writeJSON`, `readFrame`). If any is not already present under those exact names, use the equivalent helper the other WS tests in this file already use — check the top of `server_test.go` and match the established pattern; do not invent a new WS harness.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd companion && go test ./internal/wsserver/ -run 'TestComputeAlsoCloses|TestCloseImpact' -v`
Expected: all PASS.

- [ ] **Step 8: Run the whole companion suite**

Run: `cd companion && go test ./...`
Expected: `ok` for every package — the interface change compiles across `wsserver`, `engine`, and the fakes.

- [ ] **Step 9: Commit**

```bash
git add companion/internal/proto/ companion/internal/wsserver/ companion/internal/engine/
git commit -m "feat(companion): close_impact query with worktree-cascade computation"
```

---

### Task 3: App — `close_impact` protocol parse and build

Adds the `AlsoClose` type, the `CloseImpact` server frame with defensive parsing, and the `ClientMsg.closeImpact` request builder.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`

**Interfaces:**
- Produces: `data class AlsoClose(val workspaceId: String, val label: String)`; `ServerFrame.CloseImpact(val reqId: String, val workspaceId: String, val alsoCloses: List<AlsoClose>)`; `ClientMsg.closeImpact(reqId: String, workspaceId: String): String`.

- [ ] **Step 1: Write the failing test**

Add to `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`:

```kotlin
    @Test fun parsesCloseImpactWithSiblings() {
        val f = parseServerFrame("""{"t":"close_impact","reqId":"i1","workspaceId":"w1","alsoCloses":[{"workspaceId":"w2","label":"ops"}]}""")
        assertTrue(f is ServerFrame.CloseImpact)
        val ci = f as ServerFrame.CloseImpact
        assertEquals("w1", ci.workspaceId)
        assertEquals(1, ci.alsoCloses.size)
        assertEquals("ops", ci.alsoCloses.single().label)
    }

    @Test fun parsesCloseImpactEmptyAndMissingArray() {
        val empty = parseServerFrame("""{"t":"close_impact","reqId":"i2","workspaceId":"w1","alsoCloses":[]}""")
        assertTrue((empty as ServerFrame.CloseImpact).alsoCloses.isEmpty())
        // missing / null alsoCloses must not throw — same defensiveness as agents
        val missing = parseServerFrame("""{"t":"close_impact","reqId":"i3","workspaceId":"w1"}""")
        assertTrue((missing as ServerFrame.CloseImpact).alsoCloses.isEmpty())
        val nulled = parseServerFrame("""{"t":"close_impact","reqId":"i4","workspaceId":"w1","alsoCloses":null}""")
        assertTrue((nulled as ServerFrame.CloseImpact).alsoCloses.isEmpty())
    }

    @Test fun buildsCloseImpactRequest() {
        val json = ClientMsg.closeImpact("i1", "w1")
        assertTrue(json.contains("\"t\":\"close_impact\""))
        assertTrue(json.contains("\"workspaceId\":\"w1\""))
        assertTrue(json.contains("\"reqId\":\"i1\""))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest"`
Expected: compile failure — `ServerFrame.CloseImpact`, `AlsoClose`, and `ClientMsg.closeImpact` don't exist yet.

- [ ] **Step 3: Add the `AlsoClose` type**

In `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`, add after the `Tab` data class (after line 47):

```kotlin
@Serializable
data class AlsoClose(
    val workspaceId: String,
    val label: String = "",
)
```

- [ ] **Step 4: Add the `CloseImpact` server frame**

In the `sealed interface ServerFrame`, add after `Agents` (after line 61):

```kotlin
    data class CloseImpact(val reqId: String, val workspaceId: String, val alsoCloses: List<AlsoClose>) : ServerFrame
```

- [ ] **Step 5: Add the parse case**

In `parseServerFrame`, add after the `"agents"` case (after line 98):

```kotlin
        "close_impact" -> ServerFrame.CloseImpact(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["workspaceId"]?.jsonPrimitive?.content ?: "",
            (obj["alsoCloses"] as? JsonArray)?.map { json.decodeFromJsonElement<AlsoClose>(it) } ?: emptyList())
```

- [ ] **Step 6: Add the request builder**

In `object ClientMsg`, add after `listAgents` (after line 167):

```kotlin
    fun closeImpact(reqId: String, workspaceId: String): String =
        JsonObject(mapOf("t" to JsonPrimitive("close_impact"), "reqId" to JsonPrimitive(reqId), "workspaceId" to JsonPrimitive(workspaceId))).toString()
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt
git commit -m "feat(app): close_impact protocol parse and build"
```

---

### Task 4: App — `CompanionClient.closeImpact`

Adds the request/await plumbing so a `close_impact` reply completes its pending deferred, and a `closeImpact` suspend function that returns `[]` on any error or timeout.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/CompanionClientTest.kt`

**Interfaces:**
- Consumes: `ServerFrame.CloseImpact`, `AlsoClose`, `ClientMsg.closeImpact` (Task 3).
- Produces: `suspend fun CompanionClient.closeImpact(workspaceId: String): List<AlsoClose>`.

- [ ] **Step 1: Write the failing test**

Add to `app/app/src/test/java/dev/herdr/mobile/CompanionClientTest.kt` (follow the existing MockWebServer pattern in this file; the server must reply to the client's `close_impact` frame with a matching `reqId`):

```kotlin
    @Test fun closeImpactReturnsSiblings() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!text.contains("\"reqId\"")) return
                val reqId = Regex("\"reqId\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                if (text.contains("\"close_impact\"")) {
                    webSocket.send("""{"t":"close_impact","reqId":"$reqId","workspaceId":"w1","alsoCloses":[{"workspaceId":"w2","label":"ops"}]}""")
                }
            }
        }))
        server.start()
        val client = CompanionClient()
        client.connect(server.url("/").toString().replace("http", "ws"))
        val result = withTimeout(3000) { client.closeImpact("w1") }
        assertEquals(1, result.size)
        assertEquals("ops", result.single().label)
        client.close(); server.shutdown()
    }

    @Test fun closeImpactReturnsEmptyOnError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!text.contains("\"reqId\"")) return
                val reqId = Regex("\"reqId\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                if (text.contains("\"close_impact\"")) {
                    webSocket.send("""{"t":"error","reqId":"$reqId","code":"close_impact_failed","message":"boom"}""")
                }
            }
        }))
        server.start()
        val client = CompanionClient()
        client.connect(server.url("/").toString().replace("http", "ws"))
        val result = withTimeout(3000) { client.closeImpact("w1") }
        assertTrue(result.isEmpty())
        client.close(); server.shutdown()
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.CompanionClientTest"`
Expected: compile failure — `closeImpact` doesn't exist.

- [ ] **Step 3: Complete the pending deferred on `CloseImpact`**

In `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt`, add to the `when (frame)` block in `onMessage` (after the `ServerFrame.Agents` line, line 64):

```kotlin
                    is ServerFrame.CloseImpact -> pending.remove(frame.reqId)?.complete(frame)
```

- [ ] **Step 4: Add the `closeImpact` suspend function**

Add after `listAgents` (after line 172):

```kotlin
    /** Returns the sibling workspaces that will also close; [] on error/timeout. */
    suspend fun closeImpact(workspaceId: String): List<AlsoClose> {
        val reqId = "i${seq.incrementAndGet()}"
        return try {
            when (val f = request(reqId, ClientMsg.closeImpact(reqId, workspaceId))) {
                is ServerFrame.CloseImpact -> f.alsoCloses
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList() // timeout or transport failure → fall back to plain confirm
        }
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.CompanionClientTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt app/app/src/test/java/dev/herdr/mobile/CompanionClientTest.kt
git commit -m "feat(app): CompanionClient.closeImpact query"
```

---

### Task 5: App — confirm-dialog "Also closes" line and wiring

Adds the confirm-copy helper (unit-tested), the ViewModel delegation, and the DashboardScreen wiring that queries impact on a workspace close and shows the extra line.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/RowActionTest.kt`

**Interfaces:**
- Consumes: `CompanionClient.closeImpact` (Task 4); `closeConfirmMessage(a)`, `needsCloseConfirm(a)`, `NodeKind` (existing).
- Produces: `fun closeConfirmMessageWith(a: RowAction, alsoCloses: List<String>): String`; `suspend fun DashboardViewModel.closeImpact(workspaceId: String): List<String>`.

- [ ] **Step 1: Write the failing test**

Add to `app/app/src/test/java/dev/herdr/mobile/RowActionTest.kt`:

```kotlin
    @Test fun closeConfirmMessageWithNoSiblingsIsBaseCopy() {
        val a = RowAction(NodeKind.WORKSPACE, "w1", "main", paneCount = 2, tabCount = 1)
        assertEquals(closeConfirmMessage(a), closeConfirmMessageWith(a, emptyList()))
    }

    @Test fun closeConfirmMessageWithSiblingsAppendsLine() {
        val a = RowAction(NodeKind.WORKSPACE, "w1", "main", paneCount = 2, tabCount = 1)
        val msg = closeConfirmMessageWith(a, listOf("ops", "feat/a"))
        assertTrue(msg.startsWith(closeConfirmMessage(a)))
        assertTrue(msg.contains("ops"))
        assertTrue(msg.contains("feat/a"))
    }
```

Add the `dev.herdr.mobile.ui.*` import to `RowActionTest.kt` if it is not already importing the helpers under test (match the existing imports at the top of that file).

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RowActionTest"`
Expected: compile failure — `closeConfirmMessageWith` doesn't exist.

- [ ] **Step 3: Add the confirm-copy helper**

In `app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt`, add after `closeConfirmMessage` (after line 41):

```kotlin
/**
 * Confirm copy augmented with the sibling worktree-workspaces that herdr will
 * also close (cascade). Falls back to the plain copy when there are none.
 */
fun closeConfirmMessageWith(a: RowAction, alsoCloses: List<String>): String {
    val base = closeConfirmMessage(a)
    if (alsoCloses.isEmpty()) return base
    return base +
        "\n\nAlso closes ${alsoCloses.size} linked worktree workspace(s): ${alsoCloses.joinToString(", ")}."
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RowActionTest"`
Expected: PASS.

- [ ] **Step 5: Add the ViewModel delegation**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`, add after `moveNode` (after line 96):

```kotlin
    /** Sibling labels that will also close with this workspace; [] on error. */
    suspend fun closeImpact(workspaceId: String): List<String> =
        runCatching { client.closeImpact(workspaceId).map { it.label } }.getOrDefault(emptyList())
```

- [ ] **Step 6: Wire the query into the confirm flow**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`, add a state holder next to `confirmTarget` (after line 60):

```kotlin
    var alsoCloses by remember { mutableStateOf<List<String>>(emptyList()) }
```

Replace the `onClose` lambda in `RowActionSheet(...)` (lines 116-120) with:

```kotlin
                onClose = {
                    actionTarget = null
                    if (needsCloseConfirm(target)) {
                        if (target.kind == NodeKind.WORKSPACE) {
                            scope.launch {
                                alsoCloses = vm.closeImpact(target.id)
                                confirmTarget = target
                            }
                        } else {
                            alsoCloses = emptyList()
                            confirmTarget = target
                        }
                    } else vm.closeNode(target.kind.wire, target.id)
                },
```

Replace the confirm `AlertDialog` block (lines 136-149) so the message uses the augmented copy and every dismissal path resets `alsoCloses`:

```kotlin
        confirmTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { confirmTarget = null; alsoCloses = emptyList() },
                title = { Text("Close ${target.label}") },
                text = { Text(closeConfirmMessageWith(target, alsoCloses)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.closeNode(target.kind.wire, target.id)
                        confirmTarget = null
                        alsoCloses = emptyList()
                    }) { Text("Close") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmTarget = null; alsoCloses = emptyList() }) { Text("Cancel") }
                },
            )
        }
```

- [ ] **Step 7: Run the full app unit-test suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — the wiring compiles and all unit tests pass.

- [ ] **Step 8: Build the debug APK**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt app/app/src/test/java/dev/herdr/mobile/RowActionTest.kt
git commit -m "feat(app): warn which sibling workspaces a workspace close will cascade"
```

---

## Self-Review

**Spec coverage:**
- Data flow (tap → `close_impact` → `worktree.list` → reply → confirm line): Tasks 2 (companion) + 4/5 (app). ✅
- Companion computation (base + ≥2 open members; linked/single/no-group → empty; label fallback): Task 2 `computeAlsoCloses` + tests. ✅
- Protocol 5→6 additive: Task 2 Step 1. ✅
- Request/reply frames exactly as specified; `alsoCloses` always present: Task 2 (`CloseImpact` builder coerces nil→`[]`), Task 3 (defensive parse). ✅
- Error/timeout fallback: Task 2 (error frame on `ListWorktrees` failure), Task 4 (`closeImpact` returns `[]` on error/timeout), Task 5 (empty list → plain copy). ✅
- Label fallback snapshot→branch→id: Task 2 `computeAlsoCloses`. ✅ (Spec wrote "label → branch → id"; the worktree `label` is the repo name, identical for all entries and therefore not sibling-distinguishing — the plan uses the snapshot label as primary and branch as the useful worktree-derived fallback, matching the spec's intent of a useful, never-blank name.)
- Pane/tab closes unchanged: Task 5 wiring only branches to the query for `NodeKind.WORKSPACE`. ✅
- Testing plan (companion base-cascades/linked/single/no-group/label-fallback/error; app parse-empty/null + timeout): Tasks 2, 3, 4 tests. ✅ Live validation is the finishing step, not a plan task.

**Placeholder scan:** No TBD/TODO; every code step shows complete code. The one "match the existing helper" instruction (Task 2 Step 6, WS test helpers) points at concrete existing names to reuse rather than inventing a harness — acceptable, as the file's current helpers are the source of truth and must not be duplicated.

**Type consistency:** `WorktreeEntry` (Task 1) fields used identically in Task 2. `proto.AlsoClose{WorkspaceID,Label}` ↔ wire `workspaceId`/`label` ↔ app `AlsoClose(workspaceId,label)` ↔ `CloseImpact.alsoCloses` consistent across Tasks 2/3. `closeImpact` returns `List<AlsoClose>` in the client (Task 4) and is mapped to `List<String>` labels in the VM (Task 5); `closeConfirmMessageWith` consumes `List<String>` — consistent. `computeAlsoCloses` signature identical in test (Task 2 Step 2) and impl (Step 4).
