# herdr-mobile — raw-terminal attach + structural actions (rename/close) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make non-agent (shell) panes first-class attachable terminals, and let the user rename/close workspaces, tabs, and panes from the sidebar tree.

**Architecture:** Attach switches from `herdr agent attach <target>` to `herdr terminal attach <terminal_id>` (streams any pane, no agent resolution); `terminal_id` is plumbed from the companion's existing `pane.list` poll to the app. Structural actions ride a new additive `action`/`action_result` frame pair over the existing WebSocket; the companion maps `(op,kind)` to herdr's six rename/close socket methods and pokes an immediate re-poll on success. All UI (action sheet, dialogs, snackbar) is hoisted into the dashboard.

**Tech Stack:** Go 1.23 companion (`coder/websocket`, Unix-socket NDJSON to herdr); Kotlin/Compose app (Material 3, kotlinx.serialization, OkHttp). Build/test: companion `cd companion && go test ./...`; app `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`.

## Global Constraints

- `companionProtocol` becomes **4** and every change is strictly additive: a new `terminalId` field on the pane frame, plus new `action` (app→companion) and `action_result` (companion→app) frame types. Old clients ignore unknown fields/frames.
- Attach uses `herdr terminal attach <terminal_id> --takeover` for **all** panes (agent and shell). Do not keep a second agent-attach code path. `--takeover` stays a fixed literal (never client input).
- The attach target sent from the app becomes the pane's **`terminal_id`**; the pane's **`paneId`** is still what the sidebar accent (`lastOpenedPaneId`) matches on and what structural pane actions (`pane.rename`/`pane.close`) target. Keep the two distinct.
- Rename always sends a **non-empty** label. No "reset to default" affordance.
- Close confirmation follows `needsCloseConfirm` exactly: confirm when the close terminates an agent (agent pane), closes more than one pane (multi-pane tab), a tab that contains an agent, or any workspace.
- Only herdr's six documented methods are used for actions — `workspace.rename {workspace_id,label}`, `tab.rename {tab_id,label}`, `pane.rename {pane_id,label}`, `workspace.close {workspace_id}`, `tab.close {tab_id}`, `pane.close {pane_id}` — plus the `terminal attach` CLI. No new socket methods are invented.
- Reuse the existing Catppuccin theme (`statusColor`/`statusGlyph`/typography), the existing tree (`buildTree`, `WorkspaceNode`/`TabNode`), and the existing poll/broadcast path (`engine.pollOnce`, snapshot frames).

---

### Task 1: Companion — raw-terminal attach (plumb `terminal_id`, switch attach argv, bump protocol)

**Files:**
- Modify: `companion/internal/herdr/types.go` (PaneInfo struct, ~line 18-26)
- Modify: `companion/internal/state/store.go` (Pane struct ~line 10-20; `toPane` ~line 68-71)
- Modify: `companion/internal/wsserver/server.go` (`attachArgv` default ~line 53-58; the `openTerm` target comment ~line 232)
- Modify: `companion/internal/proto/proto.go` (`Welcome`, ~line 36)
- Modify: `companion/internal/proto/proto_test.go` (assertion ~line 83)
- Modify: `companion/internal/wsserver/server_test.go` (assertion ~line 198)
- Test: `companion/internal/herdr/types_test.go`, `companion/internal/state/store_test.go`, `companion/internal/wsserver/server_test.go`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `herdr.PaneInfo.TerminalID string` (json `terminal_id`)
  - `state.Pane.TerminalID string` (json `terminalId`)
  - `wsserver` default `attachArgv(target) == []string{"herdr","terminal","attach",target,"--takeover"}`
  - `proto.Welcome` emits `"companionProtocol": 4`

- [ ] **Step 1: Write the failing test — PaneInfo parses `terminal_id`**

Add to `companion/internal/herdr/types_test.go`:

```go
func TestPaneInfoParsesTerminalID(t *testing.T) {
	raw := `{"type":"pane_list","panes":[{"pane_id":"w7:p2","terminal_id":"term_abc","agent_status":"unknown"}]}`
	var res paneListResult
	if err := json.Unmarshal([]byte(raw), &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Panes) != 1 || res.Panes[0].TerminalID != "term_abc" {
		t.Fatalf("terminal_id not parsed: %+v", res.Panes)
	}
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd companion && go test ./internal/herdr/ -run TestPaneInfoParsesTerminalID`
Expected: FAIL (compile error — `TerminalID` field does not exist).

- [ ] **Step 3: Add the field to `PaneInfo`**

In `companion/internal/herdr/types.go`, add `TerminalID` to `PaneInfo`:

```go
type PaneInfo struct {
	PaneID      string `json:"pane_id"`
	WorkspaceID string `json:"workspace_id"`
	TabID       string `json:"tab_id"`
	CWD         string `json:"cwd"`
	Focused     bool   `json:"focused"`
	Agent       string `json:"agent"`
	AgentStatus string `json:"agent_status"`
	TerminalID  string `json:"terminal_id"`
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd companion && go test ./internal/herdr/ -run TestPaneInfoParsesTerminalID`
Expected: PASS.

- [ ] **Step 5: Write the failing test — `toPane` carries `terminalId`**

Add to `companion/internal/state/store_test.go`:

```go
func TestToPaneCarriesTerminalID(t *testing.T) {
	s := NewStore()
	s.Apply([]herdr.PaneInfo{{PaneID: "w7:p2", TerminalID: "term_abc"}})
	got := s.Snapshot()
	if len(got) != 1 || got[0].TerminalID != "term_abc" {
		t.Fatalf("terminalId not carried into state.Pane: %+v", got)
	}
}
```

(`herdr` is already imported in `store_test.go` via the package under test; if the test file lacks the import, add `"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"`.)

- [ ] **Step 6: Run it to confirm it fails**

Run: `cd companion && go test ./internal/state/ -run TestToPaneCarriesTerminalID`
Expected: FAIL (compile error — `TerminalID` field does not exist on `state.Pane`).

- [ ] **Step 7: Add the field and map it**

In `companion/internal/state/store.go`, add `TerminalID` to `Pane` (after `TabID`, before the agent fields so it is always sent — panes always have a terminal id):

```go
type Pane struct {
	PaneID      string `json:"paneId"`
	WorkspaceID string `json:"workspaceId"`
	TabID       string `json:"tabId"`
	TerminalID  string `json:"terminalId"`
	CWD         string `json:"cwd"`
	Focused     bool   `json:"focused"`
	// omitempty so non-agent panes send no agent/agentStatus at all; the app
	// then decodes them as null and renders "—" rather than a blank cell.
	Agent       string `json:"agent,omitempty"`
	AgentStatus string `json:"agentStatus,omitempty"`
}
```

And in `toPane`:

```go
func toPane(i herdr.PaneInfo) Pane {
	return Pane{PaneID: i.PaneID, WorkspaceID: i.WorkspaceID, TabID: i.TabID,
		TerminalID: i.TerminalID, CWD: i.CWD, Focused: i.Focused,
		Agent: i.Agent, AgentStatus: i.AgentStatus}
}
```

- [ ] **Step 8: Run it to confirm it passes**

Run: `cd companion && go test ./internal/state/ -run TestToPaneCarriesTerminalID`
Expected: PASS. (`state.Pane` stays a comparable struct, so the `old != np` change-detection in `Apply` still compiles and works.)

- [ ] **Step 9: Write the failing test — default attach argv uses `terminal attach`, and welcome protocol is 4**

Add to `companion/internal/wsserver/server_test.go`:

```go
func TestDefaultAttachArgvUsesTerminalAttach(t *testing.T) {
	s := NewServer(AllowAll{}, stubRPC{})
	got := s.attachArgv("term_abc")
	want := []string{"herdr", "terminal", "attach", "term_abc", "--takeover"}
	if len(got) != len(want) {
		t.Fatalf("argv len: got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("argv[%d]: got %q want %q (full %v)", i, got[i], want[i], got)
		}
	}
}
```

Also update the existing assertion in `TestInitialSnapshotIncludesWorkspacesAndTabs` (currently `!= 3`) to expect `4`:

```go
	if welcome["companionProtocol"].(float64) != 4 {
		t.Fatalf("want companionProtocol 4, got %v", welcome["companionProtocol"])
	}
```

- [ ] **Step 10: Run it to confirm it fails**

Run: `cd companion && go test ./internal/wsserver/ -run 'TestDefaultAttachArgvUsesTerminalAttach|TestInitialSnapshotIncludesWorkspacesAndTabs'`
Expected: FAIL (argv still says `agent attach`; welcome still says 3).

- [ ] **Step 11: Switch the attach argv and bump the protocol**

In `companion/internal/wsserver/server.go`, change the default `attachArgv` (keep the security comment, updated to name the new command):

```go
	// --takeover: the phone seizes the pane's attachment even if a client (e.g. the
	// desktop herdr TUI or a stale attach) already holds it. --takeover is a fixed
	// literal we control, not client input, so it can't be a flag-injection vector.
	// `terminal attach` streams ANY pane's PTY by terminal_id (agent or shell),
	// unlike `agent attach` which only resolves agent panes.
	srv.attachArgv = func(target string) []string {
		return []string{"herdr", "terminal", "attach", target, "--takeover"}
	}
```

Also update the comment at the top of `openTerm` (~line 232) from `` `herdr agent attach` `` to `` `herdr terminal attach` `` (the flag-injection guard on `target` is still correct and unchanged).

In `companion/internal/proto/proto.go`, bump the literal in `Welcome`:

```go
func Welcome(version string, protocol int) []byte {
	return must(map[string]any{"t": "welcome", "herdrVersion": version, "herdrProtocol": protocol, "companionProtocol": 4})
}
```

In `companion/internal/proto/proto_test.go` (~line 83), update the assertion to `4`:

```go
	if got["companionProtocol"].(float64) != 4 {
		t.Fatalf("want companionProtocol 4, got %v", got["companionProtocol"])
	}
```

- [ ] **Step 12: Run the full companion suite**

Run: `cd companion && go test ./...`
Expected: PASS (all packages).

- [ ] **Step 13: Commit**

```bash
git add companion/internal/herdr/types.go companion/internal/herdr/types_test.go \
  companion/internal/state/store.go companion/internal/state/store_test.go \
  companion/internal/wsserver/server.go companion/internal/wsserver/server_test.go \
  companion/internal/proto/proto.go companion/internal/proto/proto_test.go
git commit -m "feat(companion): raw-terminal attach via terminal_id + proto 4"
```

---

### Task 2: App — raw-terminal attach (parse `terminalId`, attach by it, make shell panes tappable)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt` (`Pane` data class ~line 9-17)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt` (`openTerminal` ~line 49-52)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (`attachOnce` call ~line 55)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (`PaneTreeRow` clickable gate ~line 131-134)
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`

**Interfaces:**
- Consumes: `state.Pane.terminalId` field on the wire (Task 1).
- Produces:
  - `net.Pane.terminalId: String` (default `""`)
  - `DashboardViewModel.openTerminal(pane: Pane, cols: Int, rows: Int): String` — attaches `pane.terminalId`, sets `lastOpenedPaneId = pane.paneId`.

- [ ] **Step 1: Write the failing test — `Pane` parses `terminalId`**

Add to `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`:

```kotlin
    @Test fun parsesPaneTerminalId() {
        val f = parseServerFrame("""{"t":"panes","panes":[{"paneId":"w7:p2","workspaceId":"w7","tabId":"w7:t2","terminalId":"term_abc","agent":null,"agentStatus":"unknown"}]}""")
        val p = (f as ServerFrame.Panes).panes.single()
        assertEquals("term_abc", p.terminalId)
        assertNull(p.agent)
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest.parsesPaneTerminalId"`
Expected: FAIL (compile error — `terminalId` not a member of `Pane`).

- [ ] **Step 3: Add the field**

In `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`:

```kotlin
@Serializable
data class Pane(
    val paneId: String,
    val workspaceId: String = "",
    val tabId: String = "",
    val terminalId: String = "",
    val cwd: String = "",
    val focused: Boolean = false,
    val agent: String? = null,
    val agentStatus: String? = null,
)
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest.parsesPaneTerminalId"`
Expected: PASS.

- [ ] **Step 5: Change `openTerminal` to attach by `terminalId` while tracking `paneId`**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`, replace the `openTerminal` function. It now takes the `Pane`: it tracks `paneId` for the sidebar accent but attaches the `terminalId`.

```kotlin
    suspend fun openTerminal(pane: Pane, cols: Int, rows: Int): String {
        _lastOpenedPaneId.value = pane.paneId
        return client.openTerminal(pane.terminalId, cols, rows)
    }
```

(`Pane` is already imported in this file.)

- [ ] **Step 6: Update the caller in `TerminalScreen`**

In `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`, in `attachOnce` (~line 55), change the call from `vm.openTerminal(pane.paneId, cols, rows)` to pass the whole pane:

```kotlin
            runCatching { vm.openTerminal(pane, cols, rows) }
                .onSuccess { termId = it; status = "connected"; takenOver = false }
                .onFailure { status = "failed: ${it.message}" }
```

- [ ] **Step 7: Make shell panes tappable in the sidebar**

In `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt`, `PaneTreeRow` (~line 131-134): remove the `isAgent` gate on the click. Keep `isAgent` only for the dim styling (`.alpha(...)`).

Replace:

```kotlin
    val isAgent = pane.agent != null
    val marked = pane.focused || pane.paneId == focusedPaneId || pane.paneId == lastOpenedPaneId
    val base = Modifier.fillMaxWidth()
    val clickable = if (isAgent) base.clickable { onSelectPane(pane) } else base
```

with:

```kotlin
    val isAgent = pane.agent != null
    val marked = pane.focused || pane.paneId == focusedPaneId || pane.paneId == lastOpenedPaneId
    // Shell panes are now attachable too (herdr terminal attach by terminal_id);
    // keep the dimmed styling as a cue but allow the tap.
    val clickable = Modifier.fillMaxWidth().clickable { onSelectPane(pane) }
```

- [ ] **Step 8: Build + run the full app suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt \
  app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt
git commit -m "feat(app): attach shell panes by terminalId; make them tappable"
```

---

### Task 3: Companion — structural action plumbing (rename/close methods, action handler, re-poll poke)

**Files:**
- Modify: `companion/internal/herdr/client.go` (add six methods after `SendKeys`, ~line 115)
- Modify: `companion/internal/proto/proto.go` (`ClientMsg` struct ~line 9-25; add `ActionResult` builder)
- Modify: `companion/internal/wsserver/server.go` (`HerdrRPC` interface ~line 18-22; `Server` struct + `poke`; `NewServer` default; `SetPoke`; `readLoop` switch add `action`)
- Modify: `companion/internal/engine/engine.go` (`New` wires `SetPoke`; add `Poke` method)
- Modify: `companion/internal/herdr/fakeherdr_test.go` (handle the six methods)
- Modify: `companion/internal/wsserver/server_test.go` (`stubRPC` gains the six methods + capture)
- Test: `companion/internal/herdr/client_test.go`, `companion/internal/wsserver/server_test.go`

**Interfaces:**
- Consumes: nothing new from Task 1/2.
- Produces:
  - `herdr.Client`: `RenameWorkspace(ctx, id, label)`, `RenameTab(ctx, id, label)`, `RenamePane(ctx, id, label)`, `CloseWorkspace(ctx, id)`, `CloseTab(ctx, id)`, `ClosePane(ctx, id)` — each `error`.
  - `wsserver.HerdrRPC` extended with the same six methods.
  - `wsserver.Server.SetPoke(func())`; a successful `action` calls `poke()`.
  - `proto.ActionResult(reqID string, ok bool, message string) []byte` → `{"t":"action_result","reqId":..,"ok":..,"error"?:..}`.
  - `proto.ClientMsg` gains `Op`, `Kind`, `ID`, `Label` (json `op`,`kind`,`id`,`label`).
  - `engine.Engine.Poke()` — non-blocking send on the poll `trigger`.

- [ ] **Step 1: Write the failing test — client rename/close hit the right methods**

Add to `companion/internal/herdr/client_test.go`:

```go
func TestClientRenameAndCloseReachHerdr(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	ctx := context.Background()

	cases := []struct {
		call   func() error
		method string
		key    string // param key carrying the id
		id     string
		label  string // "" for close
	}{
		{func() error { return c.RenameWorkspace(ctx, "w7", "omega3") }, "workspace.rename", "workspace_id", "w7", "omega3"},
		{func() error { return c.RenameTab(ctx, "w7:t1", "build") }, "tab.rename", "tab_id", "w7:t1", "build"},
		{func() error { return c.RenamePane(ctx, "w7:p2", "logs") }, "pane.rename", "pane_id", "w7:p2", "logs"},
		{func() error { return c.CloseWorkspace(ctx, "w7") }, "workspace.close", "workspace_id", "w7", ""},
		{func() error { return c.CloseTab(ctx, "w7:t1") }, "tab.close", "tab_id", "w7:t1", ""},
		{func() error { return c.ClosePane(ctx, "w7:p2") }, "pane.close", "pane_id", "w7:p2", ""},
	}
	for _, tc := range cases {
		if err := tc.call(); err != nil {
			t.Fatalf("%s: %v", tc.method, err)
		}
		select {
		case rec := <-f.lastCall:
			if rec.Method != tc.method {
				t.Fatalf("want method %s, got %s", tc.method, rec.Method)
			}
			if rec.Params[tc.key] != tc.id {
				t.Fatalf("%s: want %s=%s, got %v", tc.method, tc.key, tc.id, rec.Params[tc.key])
			}
			if tc.label != "" && rec.Params["label"] != tc.label {
				t.Fatalf("%s: want label=%s, got %v", tc.method, tc.label, rec.Params["label"])
			}
		case <-time.After(time.Second):
			t.Fatalf("%s never reached herdr", tc.method)
		}
	}
}
```

- [ ] **Step 2: Extend `fakeHerdr` to record and answer these methods**

In `companion/internal/herdr/fakeherdr_test.go`:

Add a recorded-call type and channel to the `fakeHerdr` struct (add the field alongside `lastSend`):

```go
	lastCall chan recordedCall // records rename/close method+params
```

Above `newFakeHerdr`, add:

```go
type recordedCall struct {
	Method string
	Params map[string]any
}
```

In `newFakeHerdr`, initialize it:

```go
	f := &fakeHerdr{t: t, ln: ln, path: path, readText: map[string]string{},
		lastSend: make(chan map[string]any, 8), lastCall: make(chan recordedCall, 8)}
```

In `handle`'s `switch req.Method`, add a case before `default`:

```go
	case "workspace.rename", "tab.rename", "pane.rename",
		"workspace.close", "tab.close", "pane.close":
		f.lastCall <- recordedCall{Method: req.Method, Params: req.Params}
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "ok"}})
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `cd companion && go test ./internal/herdr/ -run TestClientRenameAndCloseReachHerdr`
Expected: FAIL (compile error — the six client methods don't exist).

- [ ] **Step 4: Add the six client methods**

In `companion/internal/herdr/client.go`, after `SendKeys` (~line 115):

```go
func (c *Client) RenameWorkspace(ctx context.Context, id, label string) error {
	_, err := c.Call(ctx, "workspace.rename", map[string]any{"workspace_id": id, "label": label})
	return err
}

func (c *Client) RenameTab(ctx context.Context, id, label string) error {
	_, err := c.Call(ctx, "tab.rename", map[string]any{"tab_id": id, "label": label})
	return err
}

func (c *Client) RenamePane(ctx context.Context, id, label string) error {
	_, err := c.Call(ctx, "pane.rename", map[string]any{"pane_id": id, "label": label})
	return err
}

func (c *Client) CloseWorkspace(ctx context.Context, id string) error {
	_, err := c.Call(ctx, "workspace.close", map[string]any{"workspace_id": id})
	return err
}

func (c *Client) CloseTab(ctx context.Context, id string) error {
	_, err := c.Call(ctx, "tab.close", map[string]any{"tab_id": id})
	return err
}

func (c *Client) ClosePane(ctx context.Context, id string) error {
	_, err := c.Call(ctx, "pane.close", map[string]any{"pane_id": id})
	return err
}
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `cd companion && go test ./internal/herdr/ -run TestClientRenameAndCloseReachHerdr`
Expected: PASS.

- [ ] **Step 6: Write the failing test — wsserver `action` dispatches, pokes, and replies**

Add to `companion/internal/wsserver/server_test.go`. First replace `stubRPC` with a recording version (extend the existing type — it currently only has the three read/send methods):

```go
// stubRPC satisfies HerdrRPC without touching herdr, and records action calls.
type stubRPC struct {
	mu     sync.Mutex
	calls  []string // "method:id" for each rename/close
	failOn string   // method name that should return an error
}

func (s *stubRPC) ReadPane(context.Context, string, string, int) (string, error) { return "", nil }
func (s *stubRPC) SendText(context.Context, string, string) error                { return nil }
func (s *stubRPC) SendKeys(context.Context, string, string) error                { return nil }

func (s *stubRPC) record(method, id string) error {
	s.mu.Lock()
	s.calls = append(s.calls, method+":"+id)
	s.mu.Unlock()
	if s.failOn == method {
		return errors.New("boom")
	}
	return nil
}
func (s *stubRPC) RenameWorkspace(_ context.Context, id, _ string) error { return s.record("workspace.rename", id) }
func (s *stubRPC) RenameTab(_ context.Context, id, _ string) error       { return s.record("tab.rename", id) }
func (s *stubRPC) RenamePane(_ context.Context, id, _ string) error      { return s.record("pane.rename", id) }
func (s *stubRPC) CloseWorkspace(_ context.Context, id string) error     { return s.record("workspace.close", id) }
func (s *stubRPC) CloseTab(_ context.Context, id string) error           { return s.record("tab.close", id) }
func (s *stubRPC) ClosePane(_ context.Context, id string) error          { return s.record("pane.close", id) }
```

Update the imports in `server_test.go` to include `"errors"` and `"sync"`. Every existing `NewServer(AllowAll{}, stubRPC{})` becomes `NewServer(AllowAll{}, &stubRPC{})`.

Then add the tests:

```go
func TestActionDispatchesAndPokes(t *testing.T) {
	rpc := &stubRPC{}
	s := NewServer(AllowAll{}, rpc)
	poked := make(chan struct{}, 1)
	s.SetPoke(func() { poked <- struct{}{} })

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"action","reqId":"a1","op":"rename","kind":"workspace","id":"w7","label":"omega3"}`))
	res := readUntil(t, ctx, c, "action_result")
	if res["ok"] != true || res["reqId"] != "a1" {
		t.Fatalf("bad action_result: %+v", res)
	}
	select {
	case <-poked:
	case <-time.After(time.Second):
		t.Fatal("successful action did not poke a re-poll")
	}
	rpc.mu.Lock()
	defer rpc.mu.Unlock()
	if len(rpc.calls) != 1 || rpc.calls[0] != "workspace.rename:w7" {
		t.Fatalf("bad recorded calls: %v", rpc.calls)
	}
}

func TestActionFailureReturnsErrorAndNoPoke(t *testing.T) {
	rpc := &stubRPC{failOn: "pane.close"}
	s := NewServer(AllowAll{}, rpc)
	poked := make(chan struct{}, 1)
	s.SetPoke(func() { poked <- struct{}{} })

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"action","reqId":"a2","op":"close","kind":"pane","id":"w7:p2"}`))
	res := readUntil(t, ctx, c, "action_result")
	if res["ok"] != false || res["error"] == nil || res["error"] == "" {
		t.Fatalf("expected ok=false with error, got %+v", res)
	}
	select {
	case <-poked:
		t.Fatal("failed action must not poke a re-poll")
	case <-time.After(200 * time.Millisecond):
	}
}

func TestActionRejectsUnknownAndEmpty(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	s.SetPoke(func() {})
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")

	for _, frame := range []string{
		`{"t":"action","reqId":"e1","op":"rename","kind":"bogus","id":"x","label":"y"}`,
		`{"t":"action","reqId":"e2","op":"bogus","kind":"pane","id":"x"}`,
		`{"t":"action","reqId":"e3","op":"close","kind":"pane","id":""}`,
	} {
		c.Write(ctx, websocket.MessageText, []byte(frame))
		res := readUntil(t, ctx, c, "action_result")
		if res["ok"] != false {
			t.Fatalf("expected ok=false for %s, got %+v", frame, res)
		}
	}
}
```

- [ ] **Step 7: Run it to confirm it fails**

Run: `cd companion && go test ./internal/wsserver/ -run TestAction`
Expected: FAIL (compile errors — `HerdrRPC` lacks the six methods, `SetPoke`/`poke` and `proto.ActionResult` and the `action` case don't exist).

- [ ] **Step 8: Add `Op/Kind/ID/Label` to `ClientMsg` and the `ActionResult` builder**

In `companion/internal/proto/proto.go`, add fields to `ClientMsg` (after `Data`):

```go
	Op    string `json:"op"`
	Kind  string `json:"kind"`
	ID    string `json:"id"`
	Label string `json:"label"`
```

And add the builder (after `ErrorFrame`):

```go
func ActionResult(reqID string, ok bool, message string) []byte {
	m := map[string]any{"t": "action_result", "reqId": reqID, "ok": ok}
	if message != "" {
		m["error"] = message
	}
	return must(m)
}
```

- [ ] **Step 9: Extend `HerdrRPC`, add `poke`, and handle the `action` frame**

In `companion/internal/wsserver/server.go`:

Extend the interface:

```go
type HerdrRPC interface {
	ReadPane(ctx context.Context, paneID, source string, lines int) (string, error)
	SendText(ctx context.Context, paneID, text string) error
	SendKeys(ctx context.Context, paneID, keys string) error
	RenameWorkspace(ctx context.Context, id, label string) error
	RenameTab(ctx context.Context, id, label string) error
	RenamePane(ctx context.Context, id, label string) error
	CloseWorkspace(ctx context.Context, id string) error
	CloseTab(ctx context.Context, id string) error
	ClosePane(ctx context.Context, id string) error
}
```

Add a `poke` field to `Server` (after `onPush`):

```go
	poke func()
```

Initialize it in `NewServer` (in the composite literal, add `poke: func() {}`), and add the setter next to the others:

```go
func (s *Server) SetPoke(fn func()) { s.poke = fn }
```

Add the `action` case in `readLoop`'s switch (after `term_close`):

```go
		case "action":
			s.handleAction(ctx, c, m)
```

Add the handler method (place it after `readLoop`):

```go
// handleAction routes a structural rename/close to the matching herdr socket
// method. On success it pokes an immediate re-poll so the tree refreshes
// without waiting for the poll tick; the change itself reaches the app through
// the existing snapshot broadcast. action_result carries only ok/error.
func (s *Server) handleAction(ctx context.Context, c *client, m proto.ClientMsg) {
	if m.ID == "" {
		c.send <- proto.ActionResult(m.ReqID, false, "invalid id")
		return
	}
	var err error
	switch m.Op {
	case "rename":
		switch m.Kind {
		case "workspace":
			err = s.rpc.RenameWorkspace(ctx, m.ID, m.Label)
		case "tab":
			err = s.rpc.RenameTab(ctx, m.ID, m.Label)
		case "pane":
			err = s.rpc.RenamePane(ctx, m.ID, m.Label)
		default:
			c.send <- proto.ActionResult(m.ReqID, false, "unknown kind: "+m.Kind)
			return
		}
	case "close":
		switch m.Kind {
		case "workspace":
			err = s.rpc.CloseWorkspace(ctx, m.ID)
		case "tab":
			err = s.rpc.CloseTab(ctx, m.ID)
		case "pane":
			err = s.rpc.ClosePane(ctx, m.ID)
		default:
			c.send <- proto.ActionResult(m.ReqID, false, "unknown kind: "+m.Kind)
			return
		}
	default:
		c.send <- proto.ActionResult(m.ReqID, false, "unknown op: "+m.Op)
		return
	}
	if err != nil {
		c.send <- proto.ActionResult(m.ReqID, false, err.Error())
		return
	}
	s.poke()
	c.send <- proto.ActionResult(m.ReqID, true, "")
}
```

- [ ] **Step 10: Run it to confirm it passes**

Run: `cd companion && go test ./internal/wsserver/ -run TestAction`
Expected: PASS.

- [ ] **Step 11: Wire the engine's poke and add `Poke`**

In `companion/internal/engine/engine.go`, in `New` after the other `Set*` calls (~line 52):

```go
	e.srv.SetPoke(e.Poke)
```

And add the method (after `setEndpoint`):

```go
// Poke requests an immediate poll (coalesced with the ticker). Used by the
// wsserver after a successful structural action so the tree refreshes fast.
func (e *Engine) Poke() {
	select {
	case e.trigger <- struct{}{}:
	default:
	}
}
```

Note: `e.trigger` is created in `New` (`make(chan struct{}, 1)`) before `SetPoke` would ever be invoked at runtime, and `Poke` only sends on it, so there is no nil-channel or ordering hazard.

- [ ] **Step 12: Run the full companion suite**

Run: `cd companion && go test ./...`
Expected: PASS (all packages, including the engine and any test that constructed `stubRPC{}` — now `&stubRPC{}`).

- [ ] **Step 13: Commit**

```bash
git add companion/internal/herdr/client.go companion/internal/herdr/client_test.go \
  companion/internal/herdr/fakeherdr_test.go companion/internal/proto/proto.go \
  companion/internal/wsserver/server.go companion/internal/wsserver/server_test.go \
  companion/internal/engine/engine.go
git commit -m "feat(companion): structural action (rename/close) frame + re-poll poke"
```

---

### Task 4: App — action protocol + view-model wiring

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt` (`ServerFrame` sealed interface + `parseServerFrame` + `ClientMsg`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt` (`onMessage` pending-completion ~line 56-63; add `sendAction`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt` (add `actionErrors`, `renameNode`, `closeNode`)
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`, `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: companion `action`/`action_result` frames (Task 3).
- Produces:
  - `ServerFrame.ActionResult(reqId: String, ok: Boolean, error: String?)`
  - `ClientMsg.action(reqId, op, kind, id, label: String?): String`
  - `CompanionClient.sendAction(op: String, kind: String, id: String, label: String? = null)` — throws `RuntimeException(error)` on failure.
  - `DashboardViewModel.actionErrors: SharedFlow<String>`, `renameNode(kind: String, id: String, label: String)`, `closeNode(kind: String, id: String)`.

- [ ] **Step 1: Write the failing test — parse `action_result` and build `action`**

Add to `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`:

```kotlin
    @Test fun parsesActionResult() {
        val ok = parseServerFrame("""{"t":"action_result","reqId":"a1","ok":true}""")
        assertTrue(ok is ServerFrame.ActionResult)
        assertTrue((ok as ServerFrame.ActionResult).ok)
        assertNull(ok.error)
        val bad = parseServerFrame("""{"t":"action_result","reqId":"a2","ok":false,"error":"nope"}""")
        assertFalse((bad as ServerFrame.ActionResult).ok)
        assertEquals("nope", bad.error)
    }

    @Test fun buildsActionMessages() {
        val rn = ClientMsg.action("a1", "rename", "workspace", "w7", "omega3")
        assertTrue(rn.contains("\"t\":\"action\""))
        assertTrue(rn.contains("\"op\":\"rename\""))
        assertTrue(rn.contains("\"kind\":\"workspace\""))
        assertTrue(rn.contains("\"id\":\"w7\""))
        assertTrue(rn.contains("\"label\":\"omega3\""))
        val cl = ClientMsg.action("a2", "close", "pane", "w7:p2", null)
        assertTrue(cl.contains("\"op\":\"close\""))
        assertFalse(cl.contains("\"label\""))
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest.parsesActionResult" --tests "dev.herdr.mobile.ProtocolTest.buildsActionMessages"`
Expected: FAIL (compile errors — `ActionResult` and `ClientMsg.action` don't exist).

- [ ] **Step 3: Add the frame, parse branch, and builder**

In `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`:

Add to the `ServerFrame` sealed interface (after `ErrorFrame`):

```kotlin
    data class ActionResult(val reqId: String, val ok: Boolean, val error: String?) : ServerFrame
```

Add to `parseServerFrame`'s `when` (after the `"error"` branch):

```kotlin
        "action_result" -> ServerFrame.ActionResult(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["ok"]?.jsonPrimitive?.boolean ?: false,
            obj["error"]?.jsonPrimitive?.content)
```

Add to `ClientMsg` (after `sendKeys`):

```kotlin
    fun action(reqId: String, op: String, kind: String, id: String, label: String?): String {
        val pairs = mutableListOf(
            "t" to JsonPrimitive("action"),
            "reqId" to JsonPrimitive(reqId),
            "op" to JsonPrimitive(op),
            "kind" to JsonPrimitive(kind),
            "id" to JsonPrimitive(id),
        )
        if (label != null) pairs.add("label" to JsonPrimitive(label))
        return JsonObject(pairs.toMap()).toString()
    }
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest.parsesActionResult" --tests "dev.herdr.mobile.ProtocolTest.buildsActionMessages"`
Expected: PASS.

- [ ] **Step 5: Complete pending on `ActionResult` and add `sendAction`**

In `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt`, add to the `onMessage` `when` (alongside the other pending-completions, ~line 56-62):

```kotlin
                    is ServerFrame.ActionResult -> pending.remove(frame.reqId)?.complete(frame)
```

Add the method (after `sendKeys`, ~line 129):

```kotlin
    suspend fun sendAction(op: String, kind: String, id: String, label: String? = null) {
        val reqId = "a${seq.incrementAndGet()}"
        when (val f = request(reqId, ClientMsg.action(reqId, op, kind, id, label))) {
            is ServerFrame.ActionResult -> if (!f.ok) throw RuntimeException(f.error ?: "action failed")
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to action")
        }
    }
```

- [ ] **Step 6: Write the failing test — VM `renameNode` sends the frame; failures reach `actionErrors`**

Add to `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt`:

```kotlin
    @Test fun renameNodeSendsActionAndSurfacesError() = runBlocking {
        val server = MockWebServer()
        val seenOps = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"t":"welcome"}""") }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("\"action\"")) {
                    seenOps.add(text)
                    val reqId = Regex("\"reqId\":\"(.*?)\"").find(text)!!.groupValues[1]
                    // rename -> ok; close -> failure, to exercise both paths
                    if (text.contains("\"op\":\"close\"")) {
                        ws.send("""{"t":"action_result","reqId":"$reqId","ok":false,"error":"cannot close"}""")
                    } else {
                        ws.send("""{"t":"action_result","reqId":"$reqId","ok":true}""")
                    }
                }
            }
        }))
        server.start()
        val client = CompanionClient()
        val vm = DashboardViewModel(client, PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (!vm.connected.value) delay(20) }

        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val job = launch { vm.actionErrors.collect { errors.add(it) } }

        vm.renameNode("workspace", "w7", "omega3")
        withTimeout(3000) { while (seenOps.none { it.contains("\"op\":\"rename\"") }) delay(20) }
        assertTrue(seenOps.first { it.contains("rename") }.contains("\"label\":\"omega3\""))

        vm.closeNode("pane", "w7:p2")
        withTimeout(3000) { while (errors.isEmpty()) delay(20) }
        assertEquals("cannot close", errors.first())

        job.cancel()
        server.shutdown()
    }
```

- [ ] **Step 7: Run it to confirm it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.DashboardViewModelTest.renameNodeSendsActionAndSurfacesError"`
Expected: FAIL (compile error — `actionErrors`, `renameNode`, `closeNode` don't exist).

- [ ] **Step 8: Add the VM members**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`, add the imports `import kotlinx.coroutines.flow.MutableSharedFlow` and `import kotlinx.coroutines.flow.SharedFlow` and `import kotlinx.coroutines.flow.asSharedFlow`, then add:

```kotlin
    private val _actionErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val actionErrors: SharedFlow<String> = _actionErrors.asSharedFlow()

    fun renameNode(kind: String, id: String, label: String) {
        viewModelScope.launch {
            runCatching { client.sendAction("rename", kind, id, label) }
                .onFailure { _actionErrors.tryEmit(it.message ?: "rename failed") }
        }
    }

    fun closeNode(kind: String, id: String) {
        viewModelScope.launch {
            runCatching { client.sendAction("close", kind, id) }
                .onFailure { _actionErrors.tryEmit(it.message ?: "close failed") }
        }
    }
```

- [ ] **Step 9: Run it to confirm it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.DashboardViewModelTest.renameNodeSendsActionAndSurfacesError"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt \
  app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt \
  app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt \
  app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt
git commit -m "feat(app): action/action_result protocol + VM rename/close"
```

---

### Task 5: App — `RowAction` model, close-confirm rule, and confirm copy (pure, unit-tested)

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/RowActionTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class NodeKind(val wire: String) { WORKSPACE("workspace"), TAB("tab"), PANE("pane") }`
  - `data class RowAction(kind, id, label, paneCount=0, tabCount=0, isAgent=false, hasAgent=false)`
  - `fun needsCloseConfirm(a: RowAction): Boolean`
  - `fun closeConfirmMessage(a: RowAction): String`

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/RowActionTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.ui.NodeKind
import dev.herdr.mobile.ui.RowAction
import dev.herdr.mobile.ui.closeConfirmMessage
import dev.herdr.mobile.ui.needsCloseConfirm
import org.junit.Assert.*
import org.junit.Test

class RowActionTest {
    @Test fun confirmRuleMatchesSpecTruthTable() {
        // shell pane alone -> no confirm
        assertFalse(needsCloseConfirm(RowAction(NodeKind.PANE, "w7:p2", "shell", isAgent = false)))
        // agent pane -> confirm
        assertTrue(needsCloseConfirm(RowAction(NodeKind.PANE, "w6:p1", "claude", isAgent = true)))
        // single shell-pane tab -> no confirm
        assertFalse(needsCloseConfirm(RowAction(NodeKind.TAB, "w7:t2", "2", paneCount = 1, hasAgent = false)))
        // single agent-pane tab -> confirm
        assertTrue(needsCloseConfirm(RowAction(NodeKind.TAB, "w6:t1", "1", paneCount = 1, hasAgent = true)))
        // multi-pane tab -> confirm
        assertTrue(needsCloseConfirm(RowAction(NodeKind.TAB, "w7:t1", "1", paneCount = 2, hasAgent = false)))
        // workspace -> always confirm
        assertTrue(needsCloseConfirm(RowAction(NodeKind.WORKSPACE, "w7", "omega3")))
    }

    @Test fun confirmCopyNamesTargetAndBlastRadius() {
        assertTrue(closeConfirmMessage(RowAction(NodeKind.PANE, "w6:p1", "claude", isAgent = true)).contains("agent"))
        assertTrue(closeConfirmMessage(RowAction(NodeKind.TAB, "w7:t1", "build", paneCount = 3)).let {
            it.contains("build") && it.contains("3")
        })
        assertTrue(closeConfirmMessage(RowAction(NodeKind.WORKSPACE, "w7", "omega3", paneCount = 4, tabCount = 2)).let {
            it.contains("omega3") && it.contains("4") && it.contains("2")
        })
    }

    @Test fun nodeKindWireStringsAreStable() {
        assertEquals("workspace", NodeKind.WORKSPACE.wire)
        assertEquals("tab", NodeKind.TAB.wire)
        assertEquals("pane", NodeKind.PANE.wire)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RowActionTest"`
Expected: FAIL (compile error — `RowAction.kt` does not exist).

- [ ] **Step 3: Create the model and pure functions**

Create `app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt`:

```kotlin
package dev.herdr.mobile.ui

/** The three structural node kinds; `wire` is the string the companion expects. */
enum class NodeKind(val wire: String) {
    WORKSPACE("workspace"),
    TAB("tab"),
    PANE("pane"),
}

/**
 * A structural action target built from a tapped tree row. Carries just enough
 * to drive the rename dialog (label), the confirm decision, and the confirm copy.
 */
data class RowAction(
    val kind: NodeKind,
    val id: String,
    val label: String,
    val paneCount: Int = 0,
    val tabCount: Int = 0,
    val isAgent: Boolean = false,
    val hasAgent: Boolean = false,
)

/**
 * Confirm a close when it terminates an agent, closes more than one pane, closes
 * a tab that contains an agent, or closes any workspace.
 */
fun needsCloseConfirm(a: RowAction): Boolean = when (a.kind) {
    NodeKind.PANE -> a.isAgent
    NodeKind.TAB -> a.paneCount > 1 || a.hasAgent
    NodeKind.WORKSPACE -> true
}

/** Confirmation body copy, scaled to the blast radius. */
fun closeConfirmMessage(a: RowAction): String = when (a.kind) {
    NodeKind.PANE -> "Close this agent pane? The running agent will be terminated."
    NodeKind.TAB -> "Close tab '${a.label}'? This ends ${a.paneCount} pane(s)."
    NodeKind.WORKSPACE ->
        "Close workspace '${a.label}'? This ends ${a.paneCount} pane(s) across ${a.tabCount} tab(s)."
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RowActionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt \
  app/app/src/test/java/dev/herdr/mobile/RowActionTest.kt
git commit -m "feat(app): RowAction model + close-confirm rule"
```

---

### Task 6: App — sidebar affordances, action sheet, rename/confirm dialogs, snackbar, return-to-dashboard

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (rows get `⋮` + long-press; new `onRowAction` param; new `RowActionSheet`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (host the sheet/dialogs/snackbar; wire callbacks; pop terminal when the viewed pane disappears)
- Test: none new (all testable logic lives in Tasks 4-5; this task is Compose wiring, verified by build + live validation).

**Interfaces:**
- Consumes: `NodeKind`, `RowAction`, `needsCloseConfirm`, `closeConfirmMessage` (Task 5); `vm.renameNode`, `vm.closeNode`, `vm.actionErrors` (Task 4); `buildTree` nodes (existing).
- Produces: `SidebarDrawer(..., onRowAction: (RowAction) -> Unit)` new parameter.

- [ ] **Step 1: Add `⋮` + long-press to each row and an `onRowAction` parameter**

In `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt`:

Add imports:

```kotlin
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
```

Add `onRowAction: (RowAction) -> Unit` to `SidebarDrawer`'s parameter list, and thread it into each row composable. Update the `items` block:

```kotlin
                items(rows, key = { rowKey(it) }) { row ->
                    when (row) {
                        is Row.Ws -> WorkspaceRow(row, dark, onToggle, onRowAction)
                        is Row.TabRow -> TabRowView(row, dark, onToggle, onRowAction)
                        is Row.PaneRowItem -> PaneTreeRow(row.pane, dark, focusedPaneId, lastOpenedPaneId, onSelectPane, onRowAction)
                    }
                }
```

For each row, wrap its `Modifier` with `combinedClickable` (replacing the plain `clickable`) and add a trailing `⋮` `IconButton`. Each builds its `RowAction`. Add `@OptIn(ExperimentalFoundationApi::class)` to the three row composables.

`WorkspaceRow` — build the action from the workspace node and add the icon before the closing of the row. Replace the `Modifier.fillMaxWidth().clickable { onToggle(ws.workspaceId) }...` with `combinedClickable(onClick = { onToggle(ws.workspaceId) }, onLongClick = { onRowAction(wsAction(row.node)) })`, and add — after the pane-count `Text` — an `IconButton`:

```kotlin
        IconButton(onClick = { onRowAction(wsAction(row.node)) }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "workspace actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
```

`TabRowView` — same pattern: `onLongClick = { onRowAction(tabAction(row.node)) }`, and a trailing `Spacer(Modifier.weight(1f))` then the `⋮` `IconButton` calling `onRowAction(tabAction(row.node))`.

`PaneTreeRow` — keep the tap = attach (`onSelectPane`); add `onLongClick = { onRowAction(paneAction(pane)) }` via `combinedClickable`, and a trailing `Spacer(Modifier.weight(1f))` + `⋮` `IconButton` calling `onRowAction(paneAction(pane))`.

Add these private builders at the bottom of the file:

```kotlin
private fun wsAction(node: WorkspaceNode) = RowAction(
    kind = NodeKind.WORKSPACE,
    id = node.ws.workspaceId,
    label = node.ws.label.ifEmpty { "(unknown)" },
    paneCount = node.ws.paneCount,
    tabCount = node.ws.tabCount,
)

private fun tabAction(node: TabNode) = RowAction(
    kind = NodeKind.TAB,
    id = node.tab.tabId,
    label = node.tab.label.ifEmpty { node.tab.number.toString() },
    paneCount = node.panes.size,
    hasAgent = node.panes.any { it.agent != null },
)

private fun paneAction(pane: Pane) = RowAction(
    kind = NodeKind.PANE,
    id = pane.paneId,
    label = pane.agent ?: "shell",
    isAgent = pane.agent != null,
)
```

Note: the synthetic "(unknown)" workspace and its orphan tab have empty ids; guard in the host (Step 3) by ignoring a `RowAction` whose `id` is blank, so those rows expose no actions.

- [ ] **Step 2: Add the `RowActionSheet` composable**

In `SidebarDrawer.kt`, add (with `@OptIn(ExperimentalMaterial3Api::class)` and imports `androidx.compose.material3.ModalBottomSheet`, `androidx.compose.material3.ExperimentalMaterial3Api`, `androidx.compose.foundation.layout.navigationBarsPadding`):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowActionSheet(
    target: RowAction,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                target.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                "Rename",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onRename() }.padding(horizontal = 20.dp, vertical = 14.dp),
            )
            Text(
                "Close",
                style = MaterialTheme.typography.bodyLarge,
                color = statusColor("blocked", isSystemInDarkTheme()),
                modifier = Modifier.fillMaxWidth().clickable { onClose() }.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}
```

- [ ] **Step 3: Host the sheet, dialogs, snackbar, and callbacks in `DashboardScreen`**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`:

**Ordering is critical.** The return-to-dashboard effect MUST be placed **above** the existing `selected?.let { TerminalScreen(...); return }` early-return (~line 33) — otherwise, while a terminal is open, the early-return skips it and it never fires. Insert it immediately after the existing `LaunchedEffect(initialPaneId, panes)` block (~line 31), before the `selected?.let`:

```kotlin
    // Pop back to the dashboard when the pane we're viewing disappears (closed
    // from the sidebar, or taken over / closed elsewhere).
    LaunchedEffect(panes) {
        val open = selected
        if (open != null && panes.none { it.paneId == open.paneId }) selected = null
    }
```

The remaining state and effects are used only by the dashboard (after the early-return), so declare them after it, alongside the existing `val tree`/`drawerState`/`scope` (~line 38-43):

```kotlin
    var actionTarget by remember { mutableStateOf<RowAction?>(null) }   // action sheet open for
    var renameTarget by remember { mutableStateOf<RowAction?>(null) }   // rename dialog open for
    var confirmTarget by remember { mutableStateOf<RowAction?>(null) }  // close-confirm open for
    val snackbarHostState = remember { SnackbarHostState() }
```

Collect action errors into the snackbar (with the other dashboard-scope declarations, after the early-return):

```kotlin
    LaunchedEffect(Unit) {
        vm.actionErrors.collect { snackbarHostState.showSnackbar(it) }
    }
```

Pass `onRowAction` into `SidebarDrawer` (ignore blank-id synthetic rows):

```kotlin
                onRowAction = { a -> if (a.id.isNotBlank()) actionTarget = a },
```

Add the `SnackbarHost` to the `Scaffold`:

```kotlin
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { HerdrTopBar(connected, panes.size) { scope.launch { drawerState.open() } } },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { pad ->
```

After the `Scaffold` block (still inside `ModalNavigationDrawer`'s content lambda), render the sheet and dialogs:

```kotlin
        actionTarget?.let { target ->
            RowActionSheet(
                target = target,
                onRename = { renameTarget = target; actionTarget = null },
                onClose = {
                    actionTarget = null
                    if (needsCloseConfirm(target)) confirmTarget = target
                    else vm.closeNode(target.kind.wire, target.id)
                },
                onDismiss = { actionTarget = null },
            )
        }

        renameTarget?.let { target ->
            RenameDialog(
                target = target,
                onConfirm = { newLabel ->
                    vm.renameNode(target.kind.wire, target.id, newLabel)
                    renameTarget = null
                },
                onDismiss = { renameTarget = null },
            )
        }

        confirmTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { confirmTarget = null },
                title = { Text("Close ${target.label}") },
                text = { Text(closeConfirmMessage(target)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.closeNode(target.kind.wire, target.id)
                        confirmTarget = null
                    }) { Text("Close") }
                },
                dismissButton = { TextButton(onClick = { confirmTarget = null }) { Text("Cancel") } },
            )
        }
```

Add the `RenameDialog` composable at the bottom of `DashboardScreen.kt`:

```kotlin
@Composable
private fun RenameDialog(target: RowAction, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(target.id) { mutableStateOf(target.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != target.label,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

Add the imports `DashboardScreen.kt` needs: `androidx.compose.material3.SnackbarHost`, `androidx.compose.material3.SnackbarHostState`, `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.TextButton`, `androidx.compose.material3.OutlinedTextField`.

- [ ] **Step 4: Build + run the full app suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass (no new tests here; existing ones stay green).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): row action sheet, rename/close dialogs, snackbar"
```

---

### Task 7: Live validation (emulator + phone) + docs/memory update

**Files:**
- Modify: `the project design-notes memory` (record raw-attach + structural actions)

**Interfaces:** none.

- [ ] **Step 1: Rebuild the companion and restart it on 0.0.0.0**

```bash
cd companion && go build -o ~/.local/bin/herdr-mobiled ./cmd/herdr-mobiled
pgrep -x herdr-mobiled | xargs -r kill
~/.local/bin/herdr-mobiled --listen 0.0.0.0:8787 > /tmp/herdr-mobiled.log 2>&1 &
```

(If the binary path/flags differ, check `companion/cmd/` and the prior run in `/tmp/herdr-mobiled.log`.)

- [ ] **Step 2: Install the app on the emulator and the phone**

```bash
cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug
$HOME/Android/Sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
$HOME/Android/Sdk/platform-tools/adb -s adb-R5CY32261JN-KB349E._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Validate raw attach** — in the sidebar, tap a **shell** pane (e.g. `w7:p2`, dimmed "shell" row). Expected: a live interactive terminal opens (not the old inert/no-op). Screenshot via `adb -s emulator-5554 exec-out screencap -p > /tmp/shot.png` and show it to the user.

- [ ] **Step 4: Validate rename** — long-press (and separately, tap `⋮` on) a workspace, a tab, and a pane; Rename → change the label → Save. Expected: the tree row shows the new label within ~1-2s (re-poll). Screenshot each and show the user.

- [ ] **Step 5: Validate close + confirm rule** — Close a shell pane (no confirm), an agent pane (confirm), a multi-pane tab (confirm names the count), and a workspace (confirm names panes across tabs). Expected: confirmed closes remove the node from the tree. Screenshot the confirm dialogs and show the user.

- [ ] **Step 6: Validate return-to-dashboard** — open a pane's terminal, then close that same pane (from the sidebar on another path, or force it). Expected: the app returns to the dashboard (no stuck stale terminal).

- [ ] **Step 7: Validate error snackbar** — attempt to close the last workspace (or another op herdr refuses). Expected: a snackbar with herdr's error; the tree is unchanged.

- [ ] **Step 8: Update memory**

Append to `the project design-notes memory`: raw-terminal attach via `herdr terminal attach <terminal_id> --takeover` (agent-attach is agent-only; terminal-attach streams any pane by terminal_id); shell panes now tappable; structural actions (rename/close) via `action`/`action_result` frames (companionProtocol 4) mapping to herdr's six rename/close methods with an immediate re-poll poke; close-confirm rule (agents, multi-pane tabs, workspaces). Move create/split/move/swap/zoom to the still-deferred list.

- [ ] **Step 9: Commit any doc changes** (memory lives outside the repo; if the in-repo spec/plan need a status note, commit that)

```bash
git add -A && git commit -m "docs: record structural-actions validation" || echo "nothing to commit"
```

---

## Notes for the executor

- **Order matters only across the two halves:** Tasks 1-2 (raw attach) are independent of Tasks 3-6 (actions) and could be reviewed/merged separately, but the plan runs them in sequence for one coherent branch. Task 6 depends on Tasks 4 and 5; Task 5 depends on nothing; do Task 5 before Task 6.
- **`stubRPC` migration:** Task 3 changes `stubRPC` from a value type to a pointer with fields, so every `NewServer(AllowAll{}, stubRPC{})` in `server_test.go` must become `NewServer(AllowAll{}, &stubRPC{})`. Grep for `stubRPC{}` after editing to catch them all.
- **Two distinct ids:** attach uses `terminalId`; structural pane actions use `paneId`. Do not cross them.
