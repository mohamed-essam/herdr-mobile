package wsserver

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/messam/herdr-mobile/companion/internal/state"
)

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
	if welcome["companionProtocol"].(float64) != 4 {
		t.Fatalf("want companionProtocol 4, got %v", welcome["companionProtocol"])
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
