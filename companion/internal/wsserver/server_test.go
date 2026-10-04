package wsserver

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

// stubRPC satisfies HerdrRPC without touching herdr, and records action calls.
type stubRPC struct {
	mu        sync.Mutex
	calls     []string // "method:id" for each rename/close
	failOn    string   // method name that should return an error
	worktrees []herdr.WorktreeEntry
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
func (s *stubRPC) RenameWorkspace(_ context.Context, id, _ string) error {
	return s.record("workspace.rename", id)
}
func (s *stubRPC) RenameTab(_ context.Context, id, _ string) error { return s.record("tab.rename", id) }
func (s *stubRPC) RenamePane(_ context.Context, id, _ string) error {
	return s.record("pane.rename", id)
}
func (s *stubRPC) CloseWorkspace(_ context.Context, id string) error {
	return s.record("workspace.close", id)
}
func (s *stubRPC) CloseTab(_ context.Context, id string) error  { return s.record("tab.close", id) }
func (s *stubRPC) ClosePane(_ context.Context, id string) error { return s.record("pane.close", id) }

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
func (s *stubRPC) ListWorktrees(context.Context, string) ([]herdr.WorktreeEntry, error) {
	return s.worktrees, nil
}

// readUntil reads frames until one with t==want is seen (or timeout).
func readUntil(t *testing.T, ctx context.Context, c *websocket.Conn, want string) map[string]any {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		rctx, cancel := context.WithTimeout(ctx, time.Second)
		_, b, err := c.Read(rctx)
		cancel()
		if err != nil {
			continue
		}
		var m map[string]any
		json.Unmarshal(b, &m)
		if m["t"] == want {
			return m
		}
	}
	t.Fatalf("never saw frame t=%q", want)
	return nil
}

func TestTermOpenEchoBridge(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	// Bridge to `cat` instead of herdr: echoes input straight back as term_data.
	s.attachArgv = func(target string) []string { return []string{"cat"} }

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	// open a terminal
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r1","target":"w6:p1","cols":80,"rows":24}`))
	opened := readUntil(t, ctx, c, "term_opened")
	termID, _ := opened["termId"].(string)
	if termID == "" {
		t.Fatal("no termId in term_opened")
	}

	// send input; cat echoes it back as term_data
	in := base64.StdEncoding.EncodeToString([]byte("ping\n"))
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_input","termId":"`+termID+`","data":"`+in+`"}`))
	data := readUntil(t, ctx, c, "term_data")
	dec, _ := base64.StdEncoding.DecodeString(data["data"].(string))
	if !strings.Contains(string(dec), "ping") {
		t.Fatalf("echo not received, got %q", dec)
	}
}

func TestTermExitOnProcessEnd(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	s.attachArgv = func(target string) []string { return []string{"sh", "-c", "exit 0"} }
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r1","target":"x"}`))
	readUntil(t, ctx, c, "term_opened")
	readUntil(t, ctx, c, "term_exit")
}

func TestTermExitReasonEnded(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	s.attachArgv = func(target string) []string { return []string{"sh", "-c", "exit 0"} }
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r1","target":"x"}`))
	opened := readUntil(t, ctx, c, "term_opened")
	if id, _ := opened["termId"].(string); id == "" {
		t.Fatal("no termId in term_opened")
	}

	f := readUntil(t, ctx, c, "term_exit")
	if f["reason"] != "ended" {
		t.Fatalf("want ended, got %v", f["reason"])
	}
}

func TestTermExitReasonClosed(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	s.attachArgv = func(target string) []string { return []string{"sh", "-c", "sleep 30"} }
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r1","target":"x"}`))
	opened := readUntil(t, ctx, c, "term_opened")
	termID, _ := opened["termId"].(string)
	if termID == "" {
		t.Fatal("no termId in term_opened")
	}

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_close","termId":"`+termID+`"}`))
	f := readUntil(t, ctx, c, "term_exit")
	if f["reason"] != "closed" {
		t.Fatalf("want closed, got %v", f["reason"])
	}
}

// readNoneUntil drains frames for a short window and fails if one with
// t==unwanted shows up before the window elapses.
func readNoneUntil(t *testing.T, ctx context.Context, c *websocket.Conn, unwanted string, window time.Duration) {
	t.Helper()
	deadline := time.Now().Add(window)
	for time.Now().Before(deadline) {
		rctx, cancel := context.WithTimeout(ctx, 100*time.Millisecond)
		_, b, err := c.Read(rctx)
		cancel()
		if err != nil {
			continue
		}
		var m map[string]any
		json.Unmarshal(b, &m)
		if m["t"] == unwanted {
			t.Fatalf("unexpected frame t=%q: %v", unwanted, m)
		}
	}
}

func TestTermOpenRejectsInvalidTarget(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	// Would blow up if a session were ever started with this target.
	s.attachArgv = func(target string) []string { return []string{"herdr", "agent", "attach", target} }

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	for _, target := range []string{"-x", "", "--attach-flags"} {
		c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r1","target":"`+target+`","cols":80,"rows":24}`))
		errFrame := readUntil(t, ctx, c, "term_error")
		if errFrame["message"] == nil || errFrame["message"] == "" {
			t.Fatalf("expected non-empty error message for target %q, got %v", target, errFrame)
		}
	}
	readNoneUntil(t, ctx, c, "term_opened", 200*time.Millisecond)
}

func TestTermOpenMaxTermsCap(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	// Long-lived process so sessions stay open for the duration of the test.
	s.attachArgv = func(target string) []string { return []string{"sh", "-c", "sleep 30"} }

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	var termIDs []string
	for i := 0; i < maxTerms; i++ {
		c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r","target":"w6:p1","cols":80,"rows":24}`))
		opened := readUntil(t, ctx, c, "term_opened")
		id, _ := opened["termId"].(string)
		if id == "" {
			t.Fatalf("open %d: no termId in term_opened", i)
		}
		termIDs = append(termIDs, id)
	}

	// The 9th open should be rejected: cap is enforced against live sessions.
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"over","target":"w6:p1","cols":80,"rows":24}`))
	errFrame := readUntil(t, ctx, c, "term_error")
	msg, _ := errFrame["message"].(string)
	if !strings.Contains(strings.ToLower(msg), "too many") {
		t.Fatalf("expected 'too many terminals' style error, got %v", errFrame)
	}

	// Closing one live session frees a slot for a new one to succeed.
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_close","termId":"`+termIDs[0]+`"}`))
	// Draining the resulting term_exit isn't required for correctness, but
	// avoids leaving it to be misread by the next readUntil.
	readUntil(t, ctx, c, "term_exit")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r2","target":"w6:p1","cols":80,"rows":24}`))
	opened := readUntil(t, ctx, c, "term_opened")
	if id, _ := opened["termId"].(string); id == "" {
		t.Fatal("expected term_opened after freeing a slot via term_close")
	}
}

func TestDefaultAttachArgvUsesTerminalAttach(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
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

func TestInitialSnapshotIncludesWorkspacesAndTabs(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
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
	if welcome["companionProtocol"].(float64) != 8 {
		t.Fatalf("want companionProtocol 8, got %v", welcome["companionProtocol"])
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

func TestCloseImpactReturnsSiblings(t *testing.T) {
	stub := &stubRPC{worktrees: []herdr.WorktreeEntry{
		{Path: "/repo", IsLinkedWorktree: false, OpenWorkspaceID: "w1", Label: "app"},
		{Path: "/repo-a", Branch: "feat/a", IsLinkedWorktree: true, OpenWorkspaceID: "w2", Label: "app"},
	}}
	s := NewServer(AllowAll{}, stub)
	s.SetWorkspaceSnapshot(func() []state.Workspace {
		return []state.Workspace{{WorkspaceID: "w1", Label: "main"}, {WorkspaceID: "w2", Label: "ops"}}
	})
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"close_impact","reqId":"i1","workspaceId":"w1"}`))
	got := readUntil(t, ctx, c, "close_impact")
	if got["reqId"] != "i1" {
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

func TestCreateFailureNoPoke(t *testing.T) {
	rpc := &stubRPC{failOn: "agent.start:claude"}
	s := NewServer(AllowAll{}, rpc)
	poked := make(chan struct{}, 1)
	s.SetPoke(func() { poked <- struct{}{} })
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"create","reqId":"cf","what":"agent","tabId":"w7:t1","agentName":"claude","argv":["claude"]}`))
	res := readUntil(t, ctx, c, "created")
	if res["ok"] != false || res["error"] == nil || res["error"] == "" {
		t.Fatalf("expected create ok=false with error, got %+v", res)
	}
	select {
	case <-poked:
		t.Fatal("failed create must not poke")
	case <-time.After(200 * time.Millisecond):
	}
}

func TestMoveFailureNoPoke(t *testing.T) {
	rpc := &stubRPC{failOn: "pane.move:new_tab"}
	s := NewServer(AllowAll{}, rpc)
	poked := make(chan struct{}, 1)
	s.SetPoke(func() { poked <- struct{}{} })
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"move","reqId":"mf","paneId":"w7:p2","dest":"new_tab"}`))
	res := readUntil(t, ctx, c, "action_result")
	if res["ok"] != false || res["error"] == nil || res["error"] == "" {
		t.Fatalf("expected move ok=false, got %+v", res)
	}
	select {
	case <-poked:
		t.Fatal("failed move must not poke")
	case <-time.After(200 * time.Millisecond):
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

func dialChat(t *testing.T, hub *chatbridge.Hub) (*websocket.Conn, context.Context) {
	t.Helper()
	s := NewServer(AllowAll{}, &stubRPC{})
	s.SetChat(hub)
	srv := httptest.NewServer(s.Handler())
	t.Cleanup(srv.Close)
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close(websocket.StatusNormalClosure, "") })
	return c, ctx
}

func TestChatOpenSnapshotThenLiveEvents(t *testing.T) {
	hub := chatbridge.NewHub(nil)
	hub.Sync("w1:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"snapshot","events":[{"type":"user_text","uuid":"u1","text":"hi"}]}`)})
	c, ctx := dialChat(t, hub)

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	snap := readUntil(t, ctx, c, "chat_snapshot")
	if evs := snap["events"].([]any); len(evs) != 1 {
		t.Fatalf("snapshot events: %v", snap["events"])
	}
	hub.Sync("w1:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"assistant_text","uuid":"a1","text":"hello"}`), json.RawMessage(`{"type":"state","state":"working"}`)})
	ev := readUntil(t, ctx, c, "chat_event")
	if ev["seq"].(float64) != 2 || ev["paneId"] != "w1:p1" {
		t.Fatalf("chat_event: %v", ev)
	}
	st := readUntil(t, ctx, c, "chat_state")
	if st["state"] != "working" {
		t.Fatalf("chat_state: %v", st)
	}
}

func TestChatCloseStopsUpdates(t *testing.T) {
	hub := chatbridge.NewHub(nil)
	hub.Sync("w1:p1", "s", nil)
	c, ctx := dialChat(t, hub)
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	readUntil(t, ctx, c, "chat_snapshot")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_close","paneId":"w1:p1"}`))
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"ping"}`))
	readUntil(t, ctx, c, "pong") // close processed before the next sync
	hub.Sync("w1:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"user_text","uuid":"u","text":"x"}`)})
	readNoneUntil(t, ctx, c, "chat_event", 500*time.Millisecond)
}

func TestChatSendQueuesOrReportsNoMod(t *testing.T) {
	hub := chatbridge.NewHub(nil)
	hub.Sync("w1:p1", "s", nil)
	c, ctx := dialChat(t, hub)

	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_send","reqId":"c1","paneId":"w1:p1","text":"hi"}`))
	ok := readUntil(t, ctx, c, "chat_send_result")
	if ok["ok"] != true || ok["reqId"] != "c1" {
		t.Fatalf("send ok: %v", ok)
	}
	if out := hub.Sync("w1:p1", "s", nil); len(out) != 1 || out[0].Text != "hi" {
		t.Fatalf("outbox: %+v", out)
	}
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_send","reqId":"c2","paneId":"w9:p9","text":"hi"}`))
	bad := readUntil(t, ctx, c, "chat_send_result")
	if bad["ok"] != false || bad["error"] != "no_mod" {
		t.Fatalf("send no_mod: %v", bad)
	}
}

func TestChatSendWithoutHubIsNoMod(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_send","reqId":"c1","paneId":"p","text":"hi"}`))
	if r := readUntil(t, ctx, c, "chat_send_result"); r["error"] != "no_mod" {
		t.Fatalf("%v", r)
	}
}

func TestChatReopenDropsStaleFramesFromOldSubscription(t *testing.T) {
	hub := chatbridge.NewHub(nil)
	hub.Sync("w1:p1", "s", nil)
	c, ctx := dialChat(t, hub)
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	readUntil(t, ctx, c, "chat_snapshot")
	// Queue updates for the first subscription, then re-open straight away.
	hub.Sync("w1:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"user_text","uuid":"old","text":"old"}`), json.RawMessage(`{"type":"state","state":"working"}`)})
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	// Read until the second snapshot (the one containing the old event);
	// anything before it is allowed.
	for {
		rctx, cancel := context.WithTimeout(ctx, 3*time.Second)
		_, b, err := c.Read(rctx)
		cancel()
		if err != nil {
			t.Fatal("second chat_snapshot never arrived")
		}
		var m map[string]any
		json.Unmarshal(b, &m)
		if m["t"] == "chat_snapshot" {
			if evs, _ := m["events"].([]any); len(evs) == 1 {
				break
			}
		}
	}
	hub.Sync("w1:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"assistant_text","uuid":"new","text":"new"}`)})
	ev := readUntil(t, ctx, c, "chat_event")
	if ev["seq"].(float64) != 2 {
		t.Fatalf("stale frame after re-open snapshot: %v", ev)
	}
}

// fakeChat is a ChatHub whose subscriptions are test-controlled channels.
// cancel does not close the channel, so tests can push into a "cancelled"
// subscription and prove the server no longer forwards it.
type fakeChat struct {
	mu   sync.Mutex
	subs []chan chatbridge.Update
}

func (f *fakeChat) Subscribe(paneID string) (chatbridge.Snapshot, <-chan chatbridge.Update, func()) {
	ch := make(chan chatbridge.Update, 16)
	f.mu.Lock()
	f.subs = append(f.subs, ch)
	f.mu.Unlock()
	return chatbridge.Snapshot{PaneID: paneID, State: "idle"}, ch, func() {}
}

func (f *fakeChat) Send(string, string) error { return nil }

func (f *fakeChat) sub(i int) chan chatbridge.Update {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.subs[i]
}

func staleUpdate() chatbridge.Update {
	return chatbridge.Update{Kind: "event", Epoch: 1, Entry: chatbridge.Entry{Seq: 99, Event: json.RawMessage(`{"type":"user_text","uuid":"stale","text":"stale"}`)}}
}

func dialFake(t *testing.T, f *fakeChat) (*websocket.Conn, context.Context) {
	t.Helper()
	s := NewServer(AllowAll{}, &stubRPC{})
	s.SetChat(f)
	srv := httptest.NewServer(s.Handler())
	t.Cleanup(srv.Close)
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close(websocket.StatusNormalClosure, "") })
	return c, ctx
}

func TestChatReopenIgnoresOldSubscriptionUpdates(t *testing.T) {
	f := &fakeChat{}
	c, ctx := dialFake(t, f)
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	readUntil(t, ctx, c, "chat_snapshot")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	readUntil(t, ctx, c, "chat_snapshot")
	f.sub(0) <- staleUpdate()
	time.Sleep(150 * time.Millisecond) // a leaked stale frame would be enqueued by now
	u := staleUpdate()
	u.Entry.Seq = 1
	f.sub(1) <- u
	// The only chat_event that may arrive is the new subscription's (seq 1).
	if ev := readUntil(t, ctx, c, "chat_event"); ev["seq"].(float64) != 1 {
		t.Fatalf("stale frame from old subscription: %v", ev)
	}
}

func TestChatCloseIgnoresLaterUpdates(t *testing.T) {
	f := &fakeChat{}
	c, ctx := dialFake(t, f)
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_open","paneId":"w1:p1"}`))
	readUntil(t, ctx, c, "chat_snapshot")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_close","paneId":"w1:p1"}`))
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"ping"}`))
	readUntil(t, ctx, c, "pong")
	f.sub(0) <- staleUpdate()
	readNoneUntil(t, ctx, c, "chat_event", 300*time.Millisecond)
}

// After closeChat returns, nothing more may be enqueued by that subscription,
// even when an update is racing the close.
func TestChatCloseIsABarrierAgainstInFlightForwarding(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	f := &fakeChat{}
	s.SetChat(f)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	for i := 0; i < 3000; i++ {
		c := &client{send: make(chan []byte, 64), chats: map[string]func(){}}
		s.openChat(ctx, c, "p")
		<-c.send // snapshot
		f.sub(i) <- staleUpdate()
		c.closeChat("p")
		n := len(c.send)
		for j := 0; j < 20; j++ {
			runtime.Gosched()
		}
		if len(c.send) != n {
			t.Fatalf("iteration %d: frame enqueued after closeChat returned", i)
		}
	}
}

// Deterministic version of the check-then-send race: the forwarder is parked
// after taking an update; closeChat runs to completion; only then is the
// forwarder released. It must not enqueue the stale frame.
func TestChatCloseWinsAgainstParkedForwarder(t *testing.T) {
	parked := make(chan struct{})
	release := make(chan struct{})
	hook := func() {
		parked <- struct{}{}
		<-release
	}
	chatForwardHook.Store(&hook)
	defer chatForwardHook.Store(nil)

	s := NewServer(AllowAll{}, &stubRPC{})
	f := &fakeChat{}
	s.SetChat(f)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	for i := 0; i < 200; i++ {
		c := &client{send: make(chan []byte, 64), chats: map[string]func(){}}
		s.openChat(ctx, c, "p")
		<-c.send // snapshot
		f.sub(i) <- staleUpdate()
		<-parked
		c.closeChat("p")
		release <- struct{}{}
		time.Sleep(time.Millisecond)
		if len(c.send) != 0 {
			t.Fatalf("iteration %d: stale frame enqueued after closeChat returned", i)
		}
	}
}
