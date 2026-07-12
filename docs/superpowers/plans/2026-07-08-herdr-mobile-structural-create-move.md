# herdr-mobile — structural create + move Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create workspaces/tabs/shell-panes/agents and move panes from the phone, landing in the new pane's terminal for creates.

**Architecture:** New additive WebSocket frames (`create`/`created`, `move`, `list_agents`/`agents`; companionProtocol 4→5) carry the richer params; the companion maps them to herdr's `workspace.create`/`tab.create`/`pane.split`/`agent.start`/`pane.move`/`server.agent_manifests` methods, returns the new pane's ids on create, and pokes the existing re-poll. The app extends the existing action sheet (context-aware items + header `+`), adds an agent picker (known agents + free-typed "Other…") and a move-destination sheet, and auto-opens a created pane by matching its returned `terminalId` against the next tree snapshot.

**Tech Stack:** Go 1.23 companion (`coder/websocket`, Unix-socket NDJSON to herdr); Kotlin/Compose app (Material 3, kotlinx.serialization, OkHttp). Build/test: companion `cd companion && go test ./...`; app `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`.

## Global Constraints

- companionProtocol becomes **5**, strictly additive: new `create`/`created`, `move`, `list_agents`/`agents` frames. Old clients/companions ignore unknown frames/fields. `action`/`action_result` (rename/close) are unchanged.
- Use only these herdr methods, with the verified param shapes: `workspace.create {focus}`, `tab.create {workspace_id,focus}`, `pane.split {target_pane_id?/workspace_id?,direction,focus}`, `agent.start {name,argv,workspace_id?/tab_id?,split?,focus}`, `pane.move {pane_id,destination,focus}`, `server.agent_manifests`.
- `SplitDirection` serializes snake_case: `"right"` | `"down"`. `PaneMoveDestination` is internally tagged `{"type":"tab"|"new_tab"|"new_workspace", ...}` (tab: `{tab_id, split}`; new_tab/new_workspace: no required fields).
- Create responses return the new pane directly: `workspace.create`→`{type:"workspace_created", root_pane:{pane_id,terminal_id}}`; `tab.create`→`{type:"tab_created", root_pane:{...}}`; `pane.split`→`{type:"pane_info", pane:{...}}`; `agent.start`→`{type:"agent_started", agent:{pane_id,terminal_id}}`.
- Create is one-tap (no label prompt); only "Other…" takes typed input. Create (agent/shell/tab/workspace) auto-opens the returned pane; move stays in the tree.
- Auto-open matches the returned `terminalId` against the next tree snapshot (do NOT synthesize a pane and do NOT change the existing return-to-dashboard effect's semantics).
- Reuse the existing action sheet, `actionErrors` snackbar, and `Poke()` re-poll. Swap and zoom are out of scope.

---

### Task 1: Companion — herdr.Client create/split/start/move + agent-name methods

**Files:**
- Modify: `companion/internal/herdr/client.go` (add methods after the existing `ClosePane`, ~line 145)
- Modify: `companion/internal/herdr/fakeherdr_test.go` (handle the new methods; add a manifests case)
- Test: `companion/internal/herdr/client_test.go`

**Interfaces:**
- Consumes: existing `Client.Call(ctx, method string, params any) (json.RawMessage, error)`.
- Produces (all on `*Client`):
  - `CreateWorkspace(ctx context.Context) (paneID, terminalID string, err error)`
  - `CreateTab(ctx context.Context, workspaceID string) (paneID, terminalID string, err error)`
  - `SplitPane(ctx context.Context, targetPaneID, workspaceID, direction string) (paneID, terminalID string, err error)`
  - `StartAgent(ctx context.Context, name string, argv []string, workspaceID, tabID, split string) (paneID, terminalID string, err error)`
  - `MovePane(ctx context.Context, paneID, dest, tabID, direction string) error`
  - `ListAgentNames(ctx context.Context) ([]string, error)`

- [ ] **Step 1: Write the failing test**

Add to `companion/internal/herdr/client_test.go`:

```go
func TestClientCreateMoveAndAgents(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	ctx := context.Background()

	// create methods return the new pane's ids parsed from each result envelope
	pid, tid, err := c.CreateWorkspace(ctx)
	if err != nil || pid != "wZ:p1" || tid != "term_ws" {
		t.Fatalf("CreateWorkspace: %q %q %v", pid, tid, err)
	}
	pid, tid, err = c.CreateTab(ctx, "w7")
	if err != nil || pid != "w7:pT" || tid != "term_tab" {
		t.Fatalf("CreateTab: %q %q %v", pid, tid, err)
	}
	pid, tid, err = c.SplitPane(ctx, "w7:p2", "", "down")
	if err != nil || pid != "w7:pS" || tid != "term_split" {
		t.Fatalf("SplitPane: %q %q %v", pid, tid, err)
	}
	pid, tid, err = c.StartAgent(ctx, "claude", []string{"claude"}, "w7", "", "down")
	if err != nil || pid != "w7:pA" || tid != "term_agent" {
		t.Fatalf("StartAgent: %q %q %v", pid, tid, err)
	}
	if err := c.MovePane(ctx, "w7:p2", "new_tab", "", ""); err != nil {
		t.Fatalf("MovePane: %v", err)
	}
	names, err := c.ListAgentNames(ctx)
	if err != nil || len(names) != 2 || names[0] != "claude" || names[1] != "codex" {
		t.Fatalf("ListAgentNames: %v %v", names, err)
	}

	// verify the params the split/agent/move calls sent
	got := map[string]map[string]any{}
	for i := 0; i < 3; i++ {
		select {
		case rec := <-f.lastCall:
			got[rec.Method] = rec.Params
		case <-time.After(time.Second):
			t.Fatal("missing recorded call")
		}
	}
	if got["pane.split"]["direction"] != "down" || got["pane.split"]["target_pane_id"] != "w7:p2" {
		t.Fatalf("pane.split params: %v", got["pane.split"])
	}
	if got["agent.start"]["name"] != "claude" || got["agent.start"]["split"] != "down" {
		t.Fatalf("agent.start params: %v", got["agent.start"])
	}
	dest, _ := got["pane.move"]["destination"].(map[string]any)
	if dest["type"] != "new_tab" {
		t.Fatalf("pane.move destination: %v", got["pane.move"])
	}
}
```

- [ ] **Step 2: Extend the fake herdr to answer these methods**

In `companion/internal/herdr/fakeherdr_test.go`, add cases inside `handle`'s `switch req.Method` (before `default`). `recordedCall`/`lastCall` already exist from the rename/close work.

```go
	case "workspace.create":
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{
			"type": "workspace_created", "root_pane": map[string]any{"pane_id": "wZ:p1", "terminal_id": "term_ws"}}})
	case "tab.create":
		f.lastCall <- recordedCall{Method: req.Method, Params: req.Params}
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{
			"type": "tab_created", "root_pane": map[string]any{"pane_id": "w7:pT", "terminal_id": "term_tab"}}})
	case "pane.split":
		f.lastCall <- recordedCall{Method: req.Method, Params: req.Params}
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{
			"type": "pane_info", "pane": map[string]any{"pane_id": "w7:pS", "terminal_id": "term_split"}}})
	case "agent.start":
		f.lastCall <- recordedCall{Method: req.Method, Params: req.Params}
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{
			"type": "agent_started", "agent": map[string]any{"pane_id": "w7:pA", "terminal_id": "term_agent"}}})
	case "pane.move":
		f.lastCall <- recordedCall{Method: req.Method, Params: req.Params}
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{
			"type": "pane_move", "move_result": map[string]any{"changed": true}}})
	case "server.agent_manifests":
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{
			"type": "agent_manifest_status", "manifests": []map[string]any{{"agent": "claude"}, {"agent": "codex"}}}})
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `cd companion && go test ./internal/herdr/ -run TestClientCreateMoveAndAgents`
Expected: FAIL (compile error — the six methods don't exist).

- [ ] **Step 4: Add result-parsing structs**

In `companion/internal/herdr/types.go`, add at the end:

```go
// paneRef is the {pane_id, terminal_id} carried by create/split/start results.
type paneRef struct {
	PaneID     string `json:"pane_id"`
	TerminalID string `json:"terminal_id"`
}
type paneInfoResult struct {
	Pane paneRef `json:"pane"`
}
type rootPaneResult struct {
	RootPane paneRef `json:"root_pane"`
}
type agentStartedResult struct {
	Agent paneRef `json:"agent"`
}
type agentManifestsResult struct {
	Manifests []struct {
		Agent string `json:"agent"`
	} `json:"manifests"`
}
```

- [ ] **Step 5: Add the client methods**

In `companion/internal/herdr/client.go`, after `ClosePane` (~line 145):

```go
func (c *Client) CreateWorkspace(ctx context.Context) (string, string, error) {
	raw, err := c.Call(ctx, "workspace.create", map[string]any{"focus": true})
	if err != nil {
		return "", "", err
	}
	var res rootPaneResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", "", err
	}
	return res.RootPane.PaneID, res.RootPane.TerminalID, nil
}

func (c *Client) CreateTab(ctx context.Context, workspaceID string) (string, string, error) {
	params := map[string]any{"focus": true}
	if workspaceID != "" {
		params["workspace_id"] = workspaceID
	}
	raw, err := c.Call(ctx, "tab.create", params)
	if err != nil {
		return "", "", err
	}
	var res rootPaneResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", "", err
	}
	return res.RootPane.PaneID, res.RootPane.TerminalID, nil
}

func (c *Client) SplitPane(ctx context.Context, targetPaneID, workspaceID, direction string) (string, string, error) {
	params := map[string]any{"direction": direction, "focus": true}
	if targetPaneID != "" {
		params["target_pane_id"] = targetPaneID
	}
	if workspaceID != "" {
		params["workspace_id"] = workspaceID
	}
	raw, err := c.Call(ctx, "pane.split", params)
	if err != nil {
		return "", "", err
	}
	var res paneInfoResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", "", err
	}
	return res.Pane.PaneID, res.Pane.TerminalID, nil
}

func (c *Client) StartAgent(ctx context.Context, name string, argv []string, workspaceID, tabID, split string) (string, string, error) {
	params := map[string]any{"name": name, "argv": argv, "focus": true}
	if workspaceID != "" {
		params["workspace_id"] = workspaceID
	}
	if tabID != "" {
		params["tab_id"] = tabID
	}
	if split != "" {
		params["split"] = split
	}
	raw, err := c.Call(ctx, "agent.start", params)
	if err != nil {
		return "", "", err
	}
	var res agentStartedResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", "", err
	}
	return res.Agent.PaneID, res.Agent.TerminalID, nil
}

func (c *Client) MovePane(ctx context.Context, paneID, dest, tabID, direction string) error {
	var destination map[string]any
	switch dest {
	case "tab":
		d := direction
		if d == "" {
			d = "down"
		}
		destination = map[string]any{"type": "tab", "tab_id": tabID, "split": d}
	case "new_tab":
		destination = map[string]any{"type": "new_tab"}
	case "new_workspace":
		destination = map[string]any{"type": "new_workspace"}
	default:
		return &RPCError{Code: "bad_dest", Message: "unknown move destination: " + dest}
	}
	_, err := c.Call(ctx, "pane.move", map[string]any{"pane_id": paneID, "destination": destination, "focus": false})
	return err
}

func (c *Client) ListAgentNames(ctx context.Context) ([]string, error) {
	raw, err := c.Call(ctx, "server.agent_manifests", nil)
	if err != nil {
		return nil, err
	}
	var res agentManifestsResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	names := make([]string, 0, len(res.Manifests))
	for _, m := range res.Manifests {
		names = append(names, m.Agent)
	}
	return names, nil
}
```

- [ ] **Step 6: Run it to confirm it passes**

Run: `cd companion && go test ./internal/herdr/ -run TestClientCreateMoveAndAgents`
Expected: PASS.

- [ ] **Step 7: Run the full companion suite**

Run: `cd companion && go test ./...`
Expected: PASS (all packages).

- [ ] **Step 8: Commit**

```bash
git add companion/internal/herdr/client.go companion/internal/herdr/types.go \
  companion/internal/herdr/fakeherdr_test.go companion/internal/herdr/client_test.go
git commit -m "feat(companion): herdr client create/split/start/move + agent names"
```

---

### Task 2: Companion — create/move/list_agents frames + handlers (proto 4→5)

**Files:**
- Modify: `companion/internal/proto/proto.go` (`ClientMsg` fields; `Created`, `Agents` builders; bump `Welcome` to 5)
- Modify: `companion/internal/proto/proto_test.go` (assertion 4→5)
- Modify: `companion/internal/wsserver/server.go` (`HerdrRPC` interface; `readLoop` cases; `handleCreate`/`handleMove`/`handleListAgents`)
- Modify: `companion/internal/wsserver/server_test.go` (`stubRPC` gains the six methods; assertion 4→5; add tests)
- Modify: `companion/internal/engine/engine_test.go` (its local `fakeRPC` gains the six no-op methods to satisfy the widened interface)

**Interfaces:**
- Consumes: `herdr.Client` methods from Task 1 (via the `HerdrRPC` interface).
- Produces:
  - `proto.ClientMsg` gains `What`, `AgentName`, `Argv []string`, `Cwd`, `Direction`, `Dest`, `WorkspaceID`, `TabID` (json `what`,`agentName`,`argv`,`cwd`,`direction`,`dest`,`workspaceId`,`tabId`). `PaneID`/`ReqID`/`TabID` reuse existing where present.
  - `proto.Created(reqID string, ok bool, paneID, terminalID, message string) []byte` → `{"t":"created","reqId",...,"ok",...,"paneId"?,"terminalId"?,"error"?}`.
  - `proto.Agents(reqID string, names []string) []byte` → `{"t":"agents","reqId",...,"agents":[...]}`.
  - `wsserver.HerdrRPC` extended with `CreateWorkspace`/`CreateTab`/`SplitPane`/`StartAgent`/`MovePane`/`ListAgentNames` (same signatures as Task 1).
  - `readLoop` handles `create`, `move`, `list_agents`.
  - `Welcome` advertises `companionProtocol: 5`.

- [ ] **Step 1: Write the failing test**

Add to `companion/internal/wsserver/server_test.go`:

```go
func TestCreateReturnsPaneAndPokes(t *testing.T) {
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

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"create","reqId":"c1","what":"agent","tabId":"w7:t1","agentName":"claude","argv":["claude"]}`))
	res := readUntil(t, ctx, c, "created")
	if res["ok"] != true || res["paneId"] != "w7:pA" || res["terminalId"] != "term_agent" {
		t.Fatalf("bad created: %+v", res)
	}
	select {
	case <-poked:
	case <-time.After(time.Second):
		t.Fatal("create did not poke re-poll")
	}
	rpc.mu.Lock()
	defer rpc.mu.Unlock()
	if len(rpc.calls) != 1 || rpc.calls[0] != "agent.start:claude" {
		t.Fatalf("calls: %v", rpc.calls)
	}
}

func TestMoveAndListAgents(t *testing.T) {
	rpc := &stubRPC{}
	s := NewServer(AllowAll{}, rpc)
	s.SetPoke(func() {})
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"move","reqId":"m1","paneId":"w7:p2","dest":"new_tab"}`))
	res := readUntil(t, ctx, c, "action_result")
	if res["ok"] != true || res["reqId"] != "m1" {
		t.Fatalf("bad move result: %+v", res)
	}
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"list_agents","reqId":"a1"}`))
	ag := readUntil(t, ctx, c, "agents")
	names := ag["agents"].([]any)
	if len(names) != 1 || names[0] != "claude" {
		t.Fatalf("bad agents: %+v", ag)
	}
}

func TestCreateRejectsUnknownWhat(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	s.SetPoke(func() {})
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"create","reqId":"c9","what":"bogus"}`))
	res := readUntil(t, ctx, c, "created")
	if res["ok"] != false {
		t.Fatalf("expected ok=false for unknown what, got %+v", res)
	}
}
```

Extend the `stubRPC` in `server_test.go` with the six methods (it already has `mu`, `calls`, `record`):

```go
func (s *stubRPC) CreateWorkspace(context.Context) (string, string, error) {
	return "wZ:p1", "term_ws", s.recordErr("workspace.create")
}
func (s *stubRPC) CreateTab(_ context.Context, ws string) (string, string, error) {
	return "w7:pT", "term_tab", s.recordErr("tab.create:" + ws)
}
func (s *stubRPC) SplitPane(_ context.Context, target, ws, dir string) (string, string, error) {
	return "w7:pS", "term_split", s.recordErr("pane.split:" + dir)
}
func (s *stubRPC) StartAgent(_ context.Context, name string, _ []string, ws, tab, split string) (string, string, error) {
	return "w7:pA", "term_agent", s.recordErr("agent.start:" + name)
}
func (s *stubRPC) MovePane(_ context.Context, pane, dest, tab, dir string) error {
	return s.recordErr("pane.move:" + dest)
}
func (s *stubRPC) ListAgentNames(context.Context) ([]string, error) {
	return []string{"claude"}, nil
}
```

Add a `recordErr` helper next to `record` (record already appends; reuse it and honor `failOn`):

```go
func (s *stubRPC) recordErr(tag string) error {
	s.mu.Lock()
	s.calls = append(s.calls, tag)
	fail := s.failOn == tag
	s.mu.Unlock()
	if fail {
		return errors.New("boom")
	}
	return nil
}
```

(Note: `agent.start:claude` etc. are the recorded tags; the existing rename/close tests use `record`, which stays.)

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd companion && go test ./internal/wsserver/ -run 'TestCreate|TestMoveAndListAgents'`
Expected: FAIL (compile errors — `HerdrRPC` lacks the six methods; `proto.Created`/`Agents` and the frame cases don't exist).

- [ ] **Step 3: Add proto fields, builders, and bump the version**

In `companion/internal/proto/proto.go`, add to `ClientMsg` (after the existing `Data` field):

```go
	What        string   `json:"what"`
	AgentName   string   `json:"agentName"`
	Argv        []string `json:"argv"`
	Cwd         string   `json:"cwd"`
	Direction   string   `json:"direction"`
	Dest        string   `json:"dest"`
	WorkspaceID string   `json:"workspaceId"`
	TabID       string   `json:"tabId"`
```

Add the builders (after `ActionResult`):

```go
func Created(reqID string, ok bool, paneID, terminalID, message string) []byte {
	m := map[string]any{"t": "created", "reqId": reqID, "ok": ok}
	if paneID != "" {
		m["paneId"] = paneID
	}
	if terminalID != "" {
		m["terminalId"] = terminalID
	}
	if message != "" {
		m["error"] = message
	}
	return must(m)
}

func Agents(reqID string, names []string) []byte {
	return must(map[string]any{"t": "agents", "reqId": reqID, "agents": names})
}
```

Bump `Welcome`:

```go
func Welcome(version string, protocol int) []byte {
	return must(map[string]any{"t": "welcome", "herdrVersion": version, "herdrProtocol": protocol, "companionProtocol": 5})
}
```

Update `companion/internal/proto/proto_test.go` (the `!= 4` assertion) to `5`, and rename the test function to `TestWelcomeAdvertisesProtocol5` (keep the name in sync with the value).

- [ ] **Step 4: Extend the interface and handle the frames**

In `companion/internal/wsserver/server.go`, extend `HerdrRPC`:

```go
	CreateWorkspace(ctx context.Context) (paneID, terminalID string, err error)
	CreateTab(ctx context.Context, workspaceID string) (paneID, terminalID string, err error)
	SplitPane(ctx context.Context, targetPaneID, workspaceID, direction string) (paneID, terminalID string, err error)
	StartAgent(ctx context.Context, name string, argv []string, workspaceID, tabID, split string) (paneID, terminalID string, err error)
	MovePane(ctx context.Context, paneID, dest, tabID, direction string) error
	ListAgentNames(ctx context.Context) ([]string, error)
```

Add cases to `readLoop`'s switch (after `case "action":`):

```go
		case "create":
			s.handleCreate(ctx, c, m)
		case "move":
			s.handleMove(ctx, c, m)
		case "list_agents":
			names, err := s.rpc.ListAgentNames(ctx)
			if err != nil {
				names = nil // app still shows the "Other…" option
			}
			c.send <- proto.Agents(m.ReqID, names)
```

Add the handlers (after `handleAction`):

```go
// handleCreate maps a create frame to the right herdr method, returns the new
// pane's ids for the app to auto-open, and pokes a re-poll on success.
func (s *Server) handleCreate(ctx context.Context, c *client, m proto.ClientMsg) {
	var paneID, termID string
	var err error
	switch m.What {
	case "workspace":
		paneID, termID, err = s.rpc.CreateWorkspace(ctx)
	case "tab":
		paneID, termID, err = s.rpc.CreateTab(ctx, m.WorkspaceID)
	case "shell":
		paneID, termID, err = s.rpc.SplitPane(ctx, m.PaneID, m.WorkspaceID, dirOrDown(m.Direction))
	case "agent":
		paneID, termID, err = s.rpc.StartAgent(ctx, m.AgentName, m.Argv, m.WorkspaceID, m.TabID, m.Direction)
	default:
		c.send <- proto.Created(m.ReqID, false, "", "", "unknown what: "+m.What)
		return
	}
	if err != nil {
		c.send <- proto.Created(m.ReqID, false, "", "", err.Error())
		return
	}
	s.poke()
	c.send <- proto.Created(m.ReqID, true, paneID, termID, "")
}

func (s *Server) handleMove(ctx context.Context, c *client, m proto.ClientMsg) {
	if m.PaneID == "" {
		c.send <- proto.ActionResult(m.ReqID, false, "invalid pane id")
		return
	}
	if err := s.rpc.MovePane(ctx, m.PaneID, m.Dest, m.TabID, m.Direction); err != nil {
		c.send <- proto.ActionResult(m.ReqID, false, err.Error())
		return
	}
	s.poke()
	c.send <- proto.ActionResult(m.ReqID, true, "")
}

func dirOrDown(d string) string {
	if d == "" {
		return "down"
	}
	return d
}
```

- [ ] **Step 5: Satisfy the engine's test stub**

In `companion/internal/engine/engine_test.go`, add six no-op methods to its local `fakeRPC` (the type it passes to `NewServer`) so it still satisfies `HerdrRPC`:

```go
func (fakeRPC) CreateWorkspace(context.Context) (string, string, error) { return "", "", nil }
func (fakeRPC) CreateTab(context.Context, string) (string, string, error) { return "", "", nil }
func (fakeRPC) SplitPane(context.Context, string, string, string) (string, string, error) {
	return "", "", nil
}
func (fakeRPC) StartAgent(context.Context, string, []string, string, string, string) (string, string, error) {
	return "", "", nil
}
func (fakeRPC) MovePane(context.Context, string, string, string, string) error { return nil }
func (fakeRPC) ListAgentNames(context.Context) ([]string, error)              { return nil, nil }
```

(If `engine_test.go`'s stub is named differently, add the methods to whatever type it passes as the `HerdrRPC` to `wsserver.NewServer`. Also update its welcome-protocol assertion if it checks `4`.)

- [ ] **Step 6: Run it to confirm it passes**

Run: `cd companion && go test ./internal/wsserver/ -run 'TestCreate|TestMoveAndListAgents'`
Expected: PASS.

- [ ] **Step 7: Run the full companion suite**

Run: `cd companion && go test ./...`
Expected: PASS (all packages).

- [ ] **Step 8: Commit**

```bash
git add companion/internal/proto/proto.go companion/internal/proto/proto_test.go \
  companion/internal/wsserver/server.go companion/internal/wsserver/server_test.go \
  companion/internal/engine/engine_test.go
git commit -m "feat(companion): create/move/list_agents frames + handlers (proto 5)"
```

---

### Task 3: App — protocol frames, builders, "Other…" helper, and view-model

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt` (frames + parse + `ClientMsg` builders)
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt` (`onMessage` completions; `sendCreate`/`sendMove`/`listAgents`)
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/AgentCommand.kt` (pure `parseAgentCommand`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt` (`createNode`/`moveNode`/`agents`/`refreshAgents`/`autoOpen`)
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`, `app/app/src/test/java/dev/herdr/mobile/AgentCommandTest.kt`, `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: companion `created`/`agents` frames and the `create`/`move`/`list_agents` protocol from Task 2.
- Produces:
  - `ServerFrame.Created(reqId: String, ok: Boolean, paneId: String?, terminalId: String?, error: String?)`, `ServerFrame.Agents(reqId: String, agents: List<String>)`.
  - `ClientMsg.create(...)`, `ClientMsg.move(...)`, `ClientMsg.listAgents(reqId)`.
  - `CompanionClient.sendCreate(what, workspaceId, tabId, paneId, direction, agentName, argv): String` (returns the new `terminalId`; throws on failure), `sendMove(paneId, dest, tabId, direction)` (throws on failure), `listAgents(): List<String>`.
  - `parseAgentCommand(input: String): AgentCommand` where `data class AgentCommand(val name: String, val argv: List<String>)`.
  - `DashboardViewModel.createNode(...)`, `moveNode(...)`, `agents: StateFlow<List<String>>`, `refreshAgents()`, `autoOpen: SharedFlow<String>` (terminalId).

- [ ] **Step 1: Write the failing test — parse/build frames**

Add to `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`:

```kotlin
    @Test fun parsesCreatedAndAgents() {
        val ok = parseServerFrame("""{"t":"created","reqId":"c1","ok":true,"paneId":"w7:pA","terminalId":"term_agent"}""")
        assertTrue(ok is ServerFrame.Created)
        ok as ServerFrame.Created
        assertTrue(ok.ok); assertEquals("term_agent", ok.terminalId); assertEquals("w7:pA", ok.paneId)
        val bad = parseServerFrame("""{"t":"created","reqId":"c2","ok":false,"error":"nope"}""") as ServerFrame.Created
        assertFalse(bad.ok); assertEquals("nope", bad.error); assertNull(bad.terminalId)
        val ag = parseServerFrame("""{"t":"agents","reqId":"a1","agents":["claude","codex"]}""") as ServerFrame.Agents
        assertEquals(listOf("claude", "codex"), ag.agents)
    }

    @Test fun buildsCreateAndMove() {
        val cr = ClientMsg.create("c1", "agent", workspaceId = "w7", tabId = "w7:t1", paneId = null,
            direction = "down", agentName = "claude", argv = listOf("claude"))
        assertTrue(cr.contains("\"t\":\"create\""))
        assertTrue(cr.contains("\"what\":\"agent\""))
        assertTrue(cr.contains("\"agentName\":\"claude\""))
        assertTrue(cr.contains("\"argv\":[\"claude\"]"))
        assertTrue(cr.contains("\"tabId\":\"w7:t1\""))
        val shell = ClientMsg.create("c2", "shell", workspaceId = null, tabId = null, paneId = "w7:p2",
            direction = "right", agentName = null, argv = null)
        assertTrue(shell.contains("\"paneId\":\"w7:p2\""))
        assertFalse(shell.contains("\"agentName\""))
        assertFalse(shell.contains("\"argv\""))
        val mv = ClientMsg.move("m1", "w7:p2", "tab", tabId = "w7:t1", direction = "down")
        assertTrue(mv.contains("\"t\":\"move\"") && mv.contains("\"dest\":\"tab\"") && mv.contains("\"tabId\":\"w7:t1\""))
        assertTrue(ClientMsg.listAgents("a1").contains("\"list_agents\""))
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest.parsesCreatedAndAgents" --tests "dev.herdr.mobile.ProtocolTest.buildsCreateAndMove"`
Expected: FAIL (compile errors).

- [ ] **Step 3: Add frames, parse branches, builders**

In `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`, add to `ServerFrame` (after `ActionResult`):

```kotlin
    data class Created(val reqId: String, val ok: Boolean, val paneId: String?, val terminalId: String?, val error: String?) : ServerFrame
    data class Agents(val reqId: String, val agents: List<String>) : ServerFrame
```

Add to `parseServerFrame`'s `when` (after `"action_result"`):

```kotlin
        "created" -> ServerFrame.Created(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["ok"]?.jsonPrimitive?.boolean ?: false,
            obj["paneId"]?.jsonPrimitive?.content,
            obj["terminalId"]?.jsonPrimitive?.content,
            obj["error"]?.jsonPrimitive?.content)
        "agents" -> ServerFrame.Agents(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["agents"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
```

Add to `ClientMsg` (after `action`):

```kotlin
    fun create(
        reqId: String, what: String, workspaceId: String?, tabId: String?, paneId: String?,
        direction: String?, agentName: String?, argv: List<String>?,
    ): String {
        val pairs = mutableListOf(
            "t" to JsonPrimitive("create"),
            "reqId" to JsonPrimitive(reqId),
            "what" to JsonPrimitive(what),
        )
        if (workspaceId != null) pairs.add("workspaceId" to JsonPrimitive(workspaceId))
        if (tabId != null) pairs.add("tabId" to JsonPrimitive(tabId))
        if (paneId != null) pairs.add("paneId" to JsonPrimitive(paneId))
        if (direction != null) pairs.add("direction" to JsonPrimitive(direction))
        if (agentName != null) pairs.add("agentName" to JsonPrimitive(agentName))
        if (argv != null) pairs.add("argv" to JsonArray(argv.map { JsonPrimitive(it) }))
        return JsonObject(pairs.toMap()).toString()
    }

    fun move(reqId: String, paneId: String, dest: String, tabId: String?, direction: String?): String {
        val pairs = mutableListOf(
            "t" to JsonPrimitive("move"),
            "reqId" to JsonPrimitive(reqId),
            "paneId" to JsonPrimitive(paneId),
            "dest" to JsonPrimitive(dest),
        )
        if (tabId != null) pairs.add("tabId" to JsonPrimitive(tabId))
        if (direction != null) pairs.add("direction" to JsonPrimitive(direction))
        return JsonObject(pairs.toMap()).toString()
    }

    fun listAgents(reqId: String): String =
        JsonObject(mapOf("t" to JsonPrimitive("list_agents"), "reqId" to JsonPrimitive(reqId))).toString()
```

Ensure `Protocol.kt` imports `kotlinx.serialization.json.jsonArray` and `kotlinx.serialization.json.JsonArray` (the file already imports `kotlinx.serialization.json.*`, so this is covered).

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest.parsesCreatedAndAgents" --tests "dev.herdr.mobile.ProtocolTest.buildsCreateAndMove"`
Expected: PASS.

- [ ] **Step 5: Write the failing test — `parseAgentCommand`**

Create `app/app/src/test/java/dev/herdr/mobile/AgentCommandTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.ui.parseAgentCommand
import org.junit.Assert.*
import org.junit.Test

class AgentCommandTest {
    @Test fun singleBinary() {
        val c = parseAgentCommand("claude")
        assertEquals("claude", c.name)
        assertEquals(listOf("claude"), c.argv)
    }

    @Test fun binaryWithArgs() {
        val c = parseAgentCommand("claude --model opus")
        assertEquals("claude", c.name)
        assertEquals(listOf("claude", "--model", "opus"), c.argv)
    }

    @Test fun trimsAndCollapsesWhitespaceAndUsesBasename() {
        val c = parseAgentCommand("  /usr/bin/htop  -d 5 ")
        assertEquals("htop", c.name)
        assertEquals(listOf("/usr/bin/htop", "-d", "5"), c.argv)
    }
}
```

- [ ] **Step 6: Run it to confirm it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.AgentCommandTest"`
Expected: FAIL (compile error — `parseAgentCommand` does not exist).

- [ ] **Step 7: Implement `parseAgentCommand`**

Create `app/app/src/main/java/dev/herdr/mobile/ui/AgentCommand.kt`:

```kotlin
package dev.herdr.mobile.ui

/** A free-typed "Other…" agent command split for herdr's agent.start. */
data class AgentCommand(val name: String, val argv: List<String>)

/**
 * Split a typed command into argv (on runs of whitespace) and derive a display
 * name from the basename of argv[0]. Simple whitespace split — no shell quoting
 * (YAGNI for the mobile "Other…" field).
 */
fun parseAgentCommand(input: String): AgentCommand {
    val argv = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val name = argv.firstOrNull()?.substringAfterLast('/') ?: ""
    return AgentCommand(name, argv)
}
```

- [ ] **Step 8: Run it to confirm it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.AgentCommandTest"`
Expected: PASS.

- [ ] **Step 9: Add `sendCreate`/`sendMove`/`listAgents` to the client**

In `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt`, add to the `onMessage` `when` (alongside the other pending-completions):

```kotlin
                    is ServerFrame.Created -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.Agents -> pending.remove(frame.reqId)?.complete(frame)
```

Add the methods (after `sendAction`):

```kotlin
    /** Returns the new pane's terminalId (for auto-open); throws on failure. */
    suspend fun sendCreate(
        what: String, workspaceId: String? = null, tabId: String? = null, paneId: String? = null,
        direction: String? = null, agentName: String? = null, argv: List<String>? = null,
    ): String {
        val reqId = "n${seq.incrementAndGet()}"
        val raw = ClientMsg.create(reqId, what, workspaceId, tabId, paneId, direction, agentName, argv)
        return when (val f = request(reqId, raw)) {
            is ServerFrame.Created -> if (f.ok) (f.terminalId ?: "") else throw RuntimeException(f.error ?: "create failed")
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to create")
        }
    }

    suspend fun sendMove(paneId: String, dest: String, tabId: String? = null, direction: String? = null) {
        val reqId = "v${seq.incrementAndGet()}"
        when (val f = request(reqId, ClientMsg.move(reqId, paneId, dest, tabId, direction))) {
            is ServerFrame.ActionResult -> if (!f.ok) throw RuntimeException(f.error ?: "move failed")
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to move")
        }
    }

    suspend fun listAgents(): List<String> {
        val reqId = "g${seq.incrementAndGet()}"
        return when (val f = request(reqId, ClientMsg.listAgents(reqId))) {
            is ServerFrame.Agents -> f.agents
            else -> emptyList()
        }
    }
```

- [ ] **Step 10: Write the failing test — VM create/move/agents**

Add to `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt`:

```kotlin
    @Test fun createNodeEmitsAutoOpenAndMoveSurfacesErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"t":"welcome"}""") }
            override fun onMessage(ws: WebSocket, text: String) {
                val reqId = Regex("\"reqId\":\"(.*?)\"").find(text)!!.groupValues[1]
                when {
                    text.contains("\"t\":\"create\"") ->
                        ws.send("""{"t":"created","reqId":"$reqId","ok":true,"paneId":"w7:pA","terminalId":"term_new"}""")
                    text.contains("\"t\":\"move\"") ->
                        ws.send("""{"t":"action_result","reqId":"$reqId","ok":false,"error":"cannot move"}""")
                    text.contains("\"t\":\"list_agents\"") ->
                        ws.send("""{"t":"agents","reqId":"$reqId","agents":["claude","codex"]}""")
                }
            }
        }))
        server.start()
        val client = CompanionClient()
        val vm = DashboardViewModel(client, PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (!vm.connected.value) delay(20) }

        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val errs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val j1 = launch { vm.autoOpen.collect { opened.add(it) } }
        val j2 = launch { vm.actionErrors.collect { errs.add(it) } }

        vm.createNode(what = "agent", tabId = "w7:t1", agentName = "claude", argv = listOf("claude"))
        withTimeout(3000) { while (opened.isEmpty()) delay(20) }
        assertEquals("term_new", opened.first())

        vm.moveNode(paneId = "w7:p2", dest = "new_tab")
        withTimeout(3000) { while (errs.isEmpty()) delay(20) }
        assertEquals("cannot move", errs.first())

        vm.refreshAgents()
        withTimeout(3000) { while (vm.agents.value.isEmpty()) delay(20) }
        assertEquals(listOf("claude", "codex"), vm.agents.value)

        j1.cancel(); j2.cancel(); server.shutdown()
    }
```

- [ ] **Step 11: Run it to confirm it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.DashboardViewModelTest.createNodeEmitsAutoOpenAndMoveSurfacesErrors"`
Expected: FAIL (compile errors — `createNode`/`moveNode`/`agents`/`refreshAgents`/`autoOpen` don't exist).

- [ ] **Step 12: Add the VM members**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`, add imports `import kotlinx.coroutines.flow.asStateFlow` (if missing), and add:

```kotlin
    private val _autoOpen = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val autoOpen: SharedFlow<String> = _autoOpen.asSharedFlow()

    private val _agents = MutableStateFlow<List<String>>(emptyList())
    val agents: StateFlow<List<String>> = _agents.asStateFlow()

    fun refreshAgents() {
        viewModelScope.launch { runCatching { client.listAgents() }.onSuccess { _agents.value = it } }
    }

    fun createNode(
        what: String, workspaceId: String? = null, tabId: String? = null, paneId: String? = null,
        direction: String? = null, agentName: String? = null, argv: List<String>? = null,
    ) {
        viewModelScope.launch {
            runCatching { client.sendCreate(what, workspaceId, tabId, paneId, direction, agentName, argv) }
                .onSuccess { terminalId -> if (terminalId.isNotEmpty()) _autoOpen.tryEmit(terminalId) }
                .onFailure { _actionErrors.tryEmit(it.message ?: "create failed") }
        }
    }

    fun moveNode(paneId: String, dest: String, tabId: String? = null, direction: String? = null) {
        viewModelScope.launch {
            runCatching { client.sendMove(paneId, dest, tabId, direction) }
                .onFailure { _actionErrors.tryEmit(it.message ?: "move failed") }
        }
    }
```

(These reuse `_actionErrors` from the predecessor. `MutableStateFlow`/`StateFlow`/`MutableSharedFlow`/`SharedFlow` are already imported.)

- [ ] **Step 13: Run it to confirm it passes, then the full app suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 14: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt \
  app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/AgentCommand.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt \
  app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt \
  app/app/src/test/java/dev/herdr/mobile/AgentCommandTest.kt \
  app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt
git commit -m "feat(app): create/move/list_agents protocol + VM + Other-command helper"
```

---

### Task 4: App — simple creates (header +, new tab, new shell, split) + auto-open

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (header `+`; context-aware sheet items for the non-picker creates)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (auto-open plumbing; pass create callbacks)
- Test: none new (Compose wiring; logic tested in Task 3). Verify by build + suite staying green.

**Interfaces:**
- Consumes: `vm.createNode(...)`, `vm.autoOpen` (Task 3); existing `RowActionSheet`, `RowAction`, `NodeKind`.
- Produces: `SidebarDrawer` gains `onNewWorkspace: () -> Unit`; `RowActionSheet` gains the create items relevant to each kind and an `onCreate: (what) -> Unit`-style callback set (see below).

- [ ] **Step 1: Add the create actions to the sheet and a header `+`**

In `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt`:

Add a header `+` in the `SidebarDrawer` header `Row` (after the "❯" text add a spacer + a clickable "+"). First add `onNewWorkspace: () -> Unit` to `SidebarDrawer`'s params, then:

```kotlin
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("herdr", style = MaterialTheme.typography.titleMedium)
                Text("  ❯", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "+",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .clickable(onClick = onNewWorkspace)
                        .semantics { contentDescription = "new workspace" }
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
```

Extend `RowActionSheet` to render create items by kind. Change its signature to accept the target and callbacks:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowActionSheet(
    target: RowAction,
    onNewTab: () -> Unit,
    onNewAgent: () -> Unit,
    onNewShell: () -> Unit,
    onSplit: (String) -> Unit,   // "right" | "down"
    onMove: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(target.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            when (target.kind) {
                NodeKind.WORKSPACE -> {
                    SheetItem("New tab", onNewTab)
                    SheetItem("New agent", onNewAgent)
                }
                NodeKind.TAB -> {
                    SheetItem("New shell", onNewShell)
                    SheetItem("New agent", onNewAgent)
                }
                NodeKind.PANE -> {
                    SheetItem("Split right") { onSplit("right") }
                    SheetItem("Split down") { onSplit("down") }
                    SheetItem("Move…", onMove)
                }
            }
            SheetItem("Rename", onRename)
            SheetItem("Close", onClose, color = statusColor("blocked", isSystemInDarkTheme()))
        }
    }
}

@Composable
private fun SheetItem(label: String, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
```

(Add imports: `androidx.compose.ui.semantics.contentDescription`, `androidx.compose.ui.semantics.semantics` are already present from the compact-dots change; add `androidx.compose.material3.LocalContentColor`.)

- [ ] **Step 2: Wire the create callbacks and auto-open in DashboardScreen**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`:

Add auto-open state near the other new state, and a `LaunchedEffect` that opens the created pane once it appears in the tree (matched by `terminalId`). Place this ABOVE the early `selected?.let { ... return }` (same rule as the return-to-dashboard effect) so it runs regardless:

```kotlin
    var pendingOpenTerminalId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.autoOpen.collect { pendingOpenTerminalId = it } }
    LaunchedEffect(panes, pendingOpenTerminalId) {
        val tid = pendingOpenTerminalId ?: return@LaunchedEffect
        panes.firstOrNull { it.terminalId == tid }?.let { selected = it; pendingOpenTerminalId = null }
    }
```

Update the `SidebarDrawer(...)` call to pass `onNewWorkspace`, and update the `RowActionSheet` invocation (in the `actionTarget?.let { ... }` block) to pass the new callbacks. For this task, wire the non-picker creates; leave `onNewAgent` and `onMove` as stubs that set placeholder state (Task 5 fills them):

```kotlin
            SidebarDrawer(
                tree = tree,
                collapsed = collapsed,
                focusedPaneId = focusedPaneId,
                lastOpenedPaneId = lastOpened,
                onToggle = vm::toggleExpanded,
                onSelectPane = { p -> scope.launch { drawerState.close() }; selected = p },
                onRowAction = { a -> if (a.id.isNotBlank()) actionTarget = a },
                onNewWorkspace = { vm.createNode(what = "workspace") },
            )
```

```kotlin
        actionTarget?.let { target ->
            RowActionSheet(
                target = target,
                onNewTab = { vm.createNode(what = "tab", workspaceId = target.id); actionTarget = null },
                onNewAgent = { agentPickerFor = target; actionTarget = null },
                onNewShell = { vm.createNode(what = "shell", workspaceId = target.workspaceId); actionTarget = null },
                onSplit = { dir -> vm.createNode(what = "shell", paneId = target.id, direction = dir); actionTarget = null },
                onMove = { moveTargetPaneId = target.id; actionTarget = null },
                onRename = { renameTarget = target; actionTarget = null },
                onClose = {
                    actionTarget = null
                    if (needsCloseConfirm(target)) confirmTarget = target else vm.closeNode(target.kind.wire, target.id)
                },
                onDismiss = { actionTarget = null },
            )
        }
```

For `onNewShell` to know the tab's workspace id, add `workspaceId` to `RowAction` and populate it in `tabAction` (the tab knows its `workspaceId`). In `RowAction.kt` add a field `val workspaceId: String = ""`, and in `SidebarDrawer.kt`'s `tabAction`:

```kotlin
private fun tabAction(node: TabNode) = RowAction(
    kind = NodeKind.TAB,
    id = node.tab.tabId,
    label = node.tab.label.ifEmpty { node.tab.number.toString() },
    paneCount = node.panes.size,
    hasAgent = node.panes.any { it.agent != null },
    workspaceId = node.tab.workspaceId,
)
```

`onNewShell` above references `target.workspaceId` (the field just added). Declare the two Task-5 placeholders so this task compiles:

```kotlin
    var agentPickerFor by remember { mutableStateOf<RowAction?>(null) }
    var moveTargetPaneId by remember { mutableStateOf<String?>(null) }
```

(They are set here and consumed in Task 5; unused reads are fine.)

- [ ] **Step 3: Build + run the suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; existing tests pass (incl. `RowActionTest` — adding a defaulted `workspaceId` field does not break it).

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt
git commit -m "feat(app): create workspace/tab/shell + split from sidebar, auto-open"
```

---

### Task 5: App — agent picker (+ Other…) and move-destination sheet

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (add `AgentPickerSheet`, `MoveDestinationSheet`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (host the two sheets + the "Other…" dialog; refresh agents)
- Test: none new (Compose wiring; `parseAgentCommand` + move-frame already tested in Task 3).

**Interfaces:**
- Consumes: `vm.agents`, `vm.refreshAgents()`, `vm.createNode(what="agent", ...)`, `vm.moveNode(...)`, `parseAgentCommand`, the tree (`vm.tree`) for the move destination list; `agentPickerFor`/`moveTargetPaneId` state from Task 4.
- Produces: `AgentPickerSheet` and `MoveDestinationSheet` composables.

- [ ] **Step 1: Add the AgentPickerSheet and MoveDestinationSheet composables**

In `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerSheet(agents: List<String>, onPick: (String) -> Unit, onOther: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("New agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            agents.forEach { name -> SheetItem(name) { onPick(name) } }
            SheetItem("Other…", onOther, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveDestinationSheet(
    tree: List<WorkspaceNode>,
    currentTabId: String,
    onExistingTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewWorkspace: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("Move to…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            SheetItem("New tab", onNewTab)
            SheetItem("New workspace", onNewWorkspace)
            tree.forEach { w ->
                w.tabs.forEach { t ->
                    if (t.tab.tabId != currentTabId && t.tab.tabId.isNotBlank()) {
                        val wsLabel = w.ws.label.ifEmpty { "(unknown)" }
                        val tabLabel = t.tab.label.ifEmpty { t.tab.number.toString() }
                        SheetItem("$wsLabel / $tabLabel") { onExistingTab(t.tab.tabId) }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Host the sheets + Other dialog in DashboardScreen**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`, add an "Other…" dialog state and render the two sheets after the existing dialogs. Refresh agents when the picker opens:

```kotlin
    var showOtherDialog by remember { mutableStateOf<RowAction?>(null) }

    agentPickerFor?.let { target ->
        LaunchedEffect(target) { vm.refreshAgents() }
        val agents by vm.agents.collectAsState()
        AgentPickerSheet(
            agents = agents,
            onPick = { name ->
                val ctx = target
                if (ctx.kind == NodeKind.TAB) {
                    vm.createNode(what = "agent", tabId = ctx.id, direction = "down", agentName = name, argv = listOf(name))
                } else {
                    vm.createNode(what = "agent", workspaceId = ctx.id, agentName = name, argv = listOf(name))
                }
                agentPickerFor = null
            },
            onOther = { showOtherDialog = target; agentPickerFor = null },
            onDismiss = { agentPickerFor = null },
        )
    }

    showOtherDialog?.let { target ->
        OtherAgentDialog(
            onConfirm = { input ->
                val cmd = parseAgentCommand(input)
                if (cmd.argv.isNotEmpty()) {
                    if (target.kind == NodeKind.TAB) {
                        vm.createNode(what = "agent", tabId = target.id, direction = "down", agentName = cmd.name, argv = cmd.argv)
                    } else {
                        vm.createNode(what = "agent", workspaceId = target.id, agentName = cmd.name, argv = cmd.argv)
                    }
                }
                showOtherDialog = null
            },
            onDismiss = { showOtherDialog = null },
        )
    }

    moveTargetPaneId?.let { paneId ->
        val moveTree by vm.tree.collectAsState()
        val currentTab = panes.firstOrNull { it.paneId == paneId }?.tabId ?: ""
        MoveDestinationSheet(
            tree = moveTree,
            currentTabId = currentTab,
            onExistingTab = { tabId -> vm.moveNode(paneId, "tab", tabId = tabId, direction = "down"); moveTargetPaneId = null },
            onNewTab = { vm.moveNode(paneId, "new_tab"); moveTargetPaneId = null },
            onNewWorkspace = { vm.moveNode(paneId, "new_workspace"); moveTargetPaneId = null },
            onDismiss = { moveTargetPaneId = null },
        )
    }
```

Add the `OtherAgentDialog` composable at the bottom of `DashboardScreen.kt` (mirrors the existing `RenameDialog`):

```kotlin
@Composable
private fun OtherAgentDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run command") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("e.g. claude --model opus") }, shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

(`agentPickerFor` and `moveTargetPaneId` were declared in Task 4. `collectAsState`, `AlertDialog`, `OutlinedTextField`, `TextButton` are already imported from the predecessor.)

- [ ] **Step 3: Build + run the suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt \
  app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): agent picker (+ Other) and move-destination sheet"
```

---

### Task 6: Live validation (emulator + phone) + docs/memory

**Files:**
- Modify: `the project design-notes memory`

**Interfaces:** none.

- [ ] **Step 1: Rebuild + restart the companion on 0.0.0.0**

```bash
cd companion && go build -o ~/.local/bin/herdr-mobiled ./cmd/herdr-mobiled
pgrep -x herdr-mobiled | xargs -r kill
nohup ~/.local/bin/herdr-mobiled --listen 0.0.0.0:8787 > /tmp/herdr-mobiled.log 2>&1 &
```

- [ ] **Step 2: Install on emulator + phone**

```bash
cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug
$HOME/Android/Sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
$HOME/Android/Sdk/platform-tools/adb -s adb-R5CY32261JN-KB349E._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Validate create** — header `+` → new workspace opens its shell terminal; on a workspace row → New tab; on a tab row → New shell; on a pane row → Split right / Split down. Each lands in / adds the pane and (for shell/agent/workspace/tab) auto-opens the terminal. Screenshot each and show the user.

- [ ] **Step 4: Validate new agent** — on a workspace/tab → New agent → pick `claude` → agent starts in context and its terminal opens; reopen → Other… → type `htop` → it runs. Screenshot and show the user.

- [ ] **Step 5: Validate move** — on a pane → Move… → move to an existing tab, to New tab, and to New workspace; the tree reflects each and stays in the tree (no auto-open). Screenshot and show the user.

- [ ] **Step 6: Validate errors** — trigger a herdr failure (e.g. Other… with an empty/garbage command) → error snackbar. Clean up any throwaway panes/workspaces created during validation via `herdr pane close`/`herdr workspace close`.

- [ ] **Step 7: Update memory**

Append a "V2 PHASE 5" entry to `herdr-mobile-v1-design.md`: structural create (workspace/tab/shell/agent via `workspace.create`/`tab.create`/`pane.split`/`agent.start`) + move (`pane.move`, internally-tagged destination) from the sidebar; companionProtocol 4→5 (`create`/`created`, `move`, `list_agents`/`agents`); create responses carry the new pane (`root_pane`/`pane`/`agent`) so auto-open matches the returned `terminalId` against the next snapshot; agent picker from `server.agent_manifests` + free-typed "Other…" (`parseAgentCommand`). Move swap/zoom to the permanently-excluded list (poor mobile fit).

- [ ] **Step 8: Commit any in-repo doc changes**

```bash
git add -A && git commit -m "docs: record create+move validation" || echo "nothing to commit"
```

---

## Notes for the executor

- **Task order:** 1→2 (companion) then 3→4→5 (app) then 6. Task 4 declares the `agentPickerFor`/`moveTargetPaneId`/`RowAction.workspaceId` that Task 5 consumes; do them in order.
- **`stubRPC` / `fakeRPC` migration:** Task 2 widens `HerdrRPC` by six methods — update BOTH the `stubRPC` in `wsserver/server_test.go` and the `fakeRPC` (or equivalent) in `engine_test.go`, or those packages won't compile.
- **Auto-open is snapshot-matched, not synthetic:** `createNode` emits the returned `terminalId`; `DashboardScreen` opens the pane only once it appears in `panes` (matched by `terminalId`). Do not synthesize a `Pane` and do not weaken the existing return-to-dashboard effect.
- **Two distinct ids stay distinct:** attach/auto-open use `terminalId`; structural targets (`create` context, `move` paneId, rename/close) use `paneId`/`tabId`/`workspaceId`.
