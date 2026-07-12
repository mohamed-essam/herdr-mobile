package engine

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/wsserver"
)

// wsHarness spins up the server handler on an httptest server and returns a
// connected client conn.
func dialWS(t *testing.T, srv *httptest.Server) *websocket.Conn {
	t.Helper()
	url := "ws" + strings.TrimPrefix(srv.URL, "http")
	c, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func readFrame(t *testing.T, c *websocket.Conn) map[string]any {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, b, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	json.Unmarshal(b, &m)
	return m
}

func TestServerSendsWelcomeThenSnapshotThenUpdate(t *testing.T) {
	// Build a store + server directly (engine's guts) to test the WS contract.
	store := state.NewStore()
	store.Apply([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}})

	srvObj := wsserver.NewServer(wsserver.AllowAll{}, fakeRPC{})
	srvObj.SetInitialSnapshot(store.Snapshot) // see server API note below
	httpSrv := httptest.NewServer(srvObj.Handler())
	defer httpSrv.Close()

	c := dialWS(t, httpSrv)
	defer c.Close(websocket.StatusNormalClosure, "")

	if f := readFrame(t, c); f["t"] != "welcome" {
		t.Fatalf("want welcome, got %v", f["t"])
	}
	if f := readFrame(t, c); f["t"] != "panes" {
		t.Fatalf("want panes, got %v", f["t"])
	}
	if f := readFrame(t, c); f["t"] != "workspaces" {
		t.Fatalf("want workspaces, got %v", f["t"])
	}
	if f := readFrame(t, c); f["t"] != "tabs" {
		t.Fatalf("want tabs, got %v", f["t"])
	}

	// broadcast an update
	srvObj.Broadcast([]byte(`{"t":"pane_update","pane":{"paneId":"w6:p1","agentStatus":"blocked"}}`))
	f := readFrame(t, c)
	if f["t"] != "pane_update" {
		t.Fatalf("want pane_update, got %v", f["t"])
	}
}

type fakeRPC struct{}

func (fakeRPC) ReadPane(context.Context, string, string, int) (string, error) { return "", nil }
func (fakeRPC) SendText(context.Context, string, string) error                { return nil }
func (fakeRPC) SendKeys(context.Context, string, string) error                { return nil }
func (fakeRPC) RenameWorkspace(context.Context, string, string) error         { return nil }
func (fakeRPC) RenameTab(context.Context, string, string) error               { return nil }
func (fakeRPC) RenamePane(context.Context, string, string) error              { return nil }
func (fakeRPC) CloseWorkspace(context.Context, string) error                  { return nil }
func (fakeRPC) CloseTab(context.Context, string) error                        { return nil }
func (fakeRPC) ClosePane(context.Context, string) error                       { return nil }

func (fakeRPC) CreateWorkspace(context.Context) (string, string, error)   { return "", "", nil }
func (fakeRPC) CreateTab(context.Context, string) (string, string, error) { return "", "", nil }
func (fakeRPC) SplitPane(context.Context, string, string, string) (string, string, error) {
	return "", "", nil
}
func (fakeRPC) StartAgent(context.Context, string, []string, string, string, string) (string, string, error) {
	return "", "", nil
}
func (fakeRPC) MovePane(context.Context, string, string, string, string) error { return nil }
func (fakeRPC) ListAgentNames(context.Context) ([]string, error)               { return nil, nil }
func (fakeRPC) ListWorktrees(context.Context, string) ([]herdr.WorktreeEntry, error) {
	return nil, nil
}

// fakeHerdr is a minimal in-process herdr daemon stand-in, local to the
// engine package's tests. It intentionally does not touch the internal/herdr
// package's own A2 test fake.
type fakeHerdr struct {
	ln         net.Listener
	path       string
	mu         sync.Mutex
	panes      []herdr.PaneInfo
	workspaces []herdr.WorkspaceInfo
	tabs       []herdr.TabInfo
	subs       []chan map[string]any
}

func newFakeHerdr(t *testing.T) *fakeHerdr {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "herdr.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeHerdr{ln: ln, path: path}
	go f.serve()
	t.Cleanup(func() {
		ln.Close()
		f.mu.Lock()
		for _, ch := range f.subs {
			close(ch)
		}
		f.subs = nil
		f.mu.Unlock()
	})
	return f
}

func (f *fakeHerdr) SocketPath() string { return f.path }

func (f *fakeHerdr) SetPanes(p []herdr.PaneInfo) { f.mu.Lock(); f.panes = p; f.mu.Unlock() }

func (f *fakeHerdr) SetWorkspaces(w []herdr.WorkspaceInfo) {
	f.mu.Lock()
	f.workspaces = w
	f.mu.Unlock()
}

func (f *fakeHerdr) SetTabs(t []herdr.TabInfo) { f.mu.Lock(); f.tabs = t; f.mu.Unlock() }

func (f *fakeHerdr) serve() {
	for {
		c, err := f.ln.Accept()
		if err != nil {
			return
		}
		go f.handle(c)
	}
}

func (f *fakeHerdr) handle(c net.Conn) {
	defer c.Close()
	line, err := bufio.NewReader(c).ReadBytes('\n')
	if err != nil {
		return
	}
	var req struct {
		ID     string         `json:"id"`
		Method string         `json:"method"`
		Params map[string]any `json:"params"`
	}
	if json.Unmarshal(line, &req) != nil {
		return
	}
	enc := json.NewEncoder(c)
	switch req.Method {
	case "ping":
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "pong", "version": "0.7.1", "protocol": 14}})
	case "pane.list":
		f.mu.Lock()
		panes := f.panes
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "pane_list", "panes": panes}})
	case "pane.read":
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "pane_read", "read": map[string]any{"pane_id": req.Params["pane_id"], "source": req.Params["source"], "text": ""}}})
	case "workspace.list":
		f.mu.Lock()
		workspaces := f.workspaces
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "workspace_list", "workspaces": workspaces}})
	case "tab.list":
		f.mu.Lock()
		tabs := f.tabs
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "tab_list", "tabs": tabs}})
	case "events.subscribe":
		ch := make(chan map[string]any, 16)
		f.mu.Lock()
		f.subs = append(f.subs, ch)
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "subscription_started"}})
		for ev := range ch {
			if err := enc.Encode(ev); err != nil {
				return
			}
		}
	default:
		enc.Encode(map[string]any{"id": req.ID, "error": map[string]any{"code": "unknown_method", "message": req.Method}})
	}
}

func (f *fakeHerdr) PushEvent(paneID, status string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, ch := range f.subs {
		select {
		case ch <- map[string]any{"type": "pane.agent_status_changed", "pane_id": paneID, "agent_status": status}:
		default:
		}
	}
}

func TestEngineFiresBlockedPushToRegisteredEndpoint(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}})

	gotPush := make(chan map[string]any, 1)
	pushSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var m map[string]any
		json.NewDecoder(r.Body).Decode(&m)
		gotPush <- m
	}))
	defer pushSrv.Close()

	e := New(Config{SocketPath: f.SocketPath(), ListenAddr: "127.0.0.1:0", PollInterval: 100 * time.Millisecond})
	e.setEndpoint(pushSrv.URL)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go e.pollLoop(ctx)

	time.Sleep(200 * time.Millisecond) // first poll establishes "working"
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked"}})

	select {
	case m := <-gotPush:
		if m["kind"] != "blocked" || m["workspaceId"] != "w6" {
			t.Fatalf("bad push: %v", m)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no push fired")
	}
}

func TestResumeFiresClearPush(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked"}})

	gotPush := make(chan map[string]any, 4)
	pushSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var m map[string]any
		json.NewDecoder(r.Body).Decode(&m)
		gotPush <- m
	}))
	defer pushSrv.Close()

	e := New(Config{SocketPath: f.SocketPath(), ListenAddr: "127.0.0.1:0", PollInterval: 100 * time.Millisecond})
	e.setEndpoint(pushSrv.URL)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go e.pollLoop(ctx)

	time.Sleep(200 * time.Millisecond) // first poll establishes "blocked"
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}})

	deadline := time.After(2 * time.Second)
	for {
		select {
		case m := <-gotPush:
			if m["kind"] == "clear" {
				if m["paneId"] != "w6:p1" || m["workspaceId"] != "w6" {
					t.Fatalf("bad clear push: %v", m)
				}
				return
			}
			// ignore any other push the harness delivers first
		case <-deadline:
			t.Fatal("no clear push fired on resume to working")
		}
	}
}

func TestEngineSubscriptionTriggersFastPoll(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}})

	gotPush := make(chan map[string]any, 1)
	pushSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var m map[string]any
		json.NewDecoder(r.Body).Decode(&m)
		select {
		case gotPush <- m:
		default:
		}
	}))
	defer pushSrv.Close()

	// Poll interval long enough that the TICKER cannot be what delivers the push.
	e := New(Config{SocketPath: f.SocketPath(), ListenAddr: "127.0.0.1:0", PollInterval: 5 * time.Second})
	e.setEndpoint(pushSrv.URL)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go e.pollLoop(ctx)

	// Let the immediate first poll establish "working" and open the subscription.
	time.Sleep(300 * time.Millisecond)

	// Flip to blocked and notify via the subscription (not the ticker).
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked"}})
	f.PushEvent("w6:p1", "blocked")

	select {
	case m := <-gotPush:
		if m["kind"] != "blocked" {
			t.Fatalf("want blocked push, got %v", m)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("subscription did not trigger a fast poll/push within 2s (ticker is 5s)")
	}
}

func TestPollOncePopulatesWorkspacesAndTabs(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetWorkspaces([]herdr.WorkspaceInfo{
		{WorkspaceID: "w7", Label: "omega3", Number: 4, PaneCount: 2, TabCount: 2},
	})
	f.SetTabs([]herdr.TabInfo{
		{TabID: "w7:t1", Label: "1", Number: 1, WorkspaceID: "w7"},
	})

	e := New(Config{SocketPath: f.SocketPath(), ListenAddr: "127.0.0.1:0"})
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
