package herdr

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

// fakeHerdr is a minimal Unix-socket server speaking herdr's NDJSON protocol.
// One request per connection (matching real herdr). For events.subscribe it
// sends subscription_started then streams any events pushed via PushEvent.
type fakeHerdr struct {
	t          *testing.T
	ln         net.Listener
	path       string
	mu         sync.Mutex
	panes      []PaneInfo // returned for pane.list
	workspaces []WorkspaceInfo
	tabs       []TabInfo
	worktrees  []WorktreeEntry
	readText   map[string]string   // pane_id -> text for pane.read
	subs       []chan Event        // active subscription channels
	lastSend   chan map[string]any // records last send_text/send_keys params
	lastCall   chan recordedCall   // records rename/close method+params
}

type recordedCall struct {
	Method string
	Params map[string]any
}

func newFakeHerdr(t *testing.T) *fakeHerdr {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "herdr.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeHerdr{t: t, ln: ln, path: path, readText: map[string]string{},
		lastSend: make(chan map[string]any, 8), lastCall: make(chan recordedCall, 8)}
	go f.serve()
	t.Cleanup(func() { ln.Close(); os.Remove(path) })
	return f
}

func (f *fakeHerdr) SocketPath() string { return f.path }

func (f *fakeHerdr) SetPanes(p []PaneInfo) { f.mu.Lock(); f.panes = p; f.mu.Unlock() }

func (f *fakeHerdr) SetWorkspaces(w []WorkspaceInfo) { f.mu.Lock(); f.workspaces = w; f.mu.Unlock() }
func (f *fakeHerdr) SetTabs(t []TabInfo)             { f.mu.Lock(); f.tabs = t; f.mu.Unlock() }
func (f *fakeHerdr) SetWorktrees(w []WorktreeEntry)  { f.mu.Lock(); f.worktrees = w; f.mu.Unlock() }

func (f *fakeHerdr) PushEvent(e Event) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.subs {
		select {
		case c <- e:
		default:
		}
	}
}

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
	r := bufio.NewReader(c)
	line, err := r.ReadBytes('\n')
	if err != nil {
		return
	}
	var req struct {
		ID     string         `json:"id"`
		Method string         `json:"method"`
		Params map[string]any `json:"params"`
	}
	if err := json.Unmarshal(line, &req); err != nil {
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
	case "worktree.list":
		f.lastCall <- recordedCall{Method: req.Method, Params: req.Params}
		f.mu.Lock()
		wts := f.worktrees
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "worktree_list", "worktrees": wts}})
	case "pane.read":
		pid, _ := req.Params["pane_id"].(string)
		f.mu.Lock()
		txt := f.readText[pid]
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "pane_read",
			"read": map[string]any{"pane_id": pid, "source": req.Params["source"], "text": txt}}})
	case "pane.send_text", "pane.send_keys":
		f.lastSend <- req.Params
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "ok"}})
	case "workspace.rename", "tab.rename", "pane.rename",
		"workspace.close", "tab.close", "pane.close":
		f.lastCall <- recordedCall{Method: req.Method, Params: req.Params}
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "ok"}})
	case "workspace.create":
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{
			"type": "workspace_created", "root_pane": map[string]any{"pane_id": "wZ:p1", "terminal_id": "term_ws"}}})
	case "tab.create":
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
	case "events.subscribe":
		ch := make(chan Event, 16)
		f.mu.Lock()
		f.subs = append(f.subs, ch)
		f.mu.Unlock()
		enc.Encode(map[string]any{"id": req.ID, "result": map[string]any{"type": "subscription_started"}})
		for e := range ch {
			enc.Encode(map[string]any{"type": e.Type, "pane_id": e.PaneID, "agent_status": e.AgentStatus})
		}
	default:
		enc.Encode(map[string]any{"id": req.ID, "error": map[string]any{"code": "unknown_method", "message": req.Method}})
	}
}

func TestFakeHerdrPing(t *testing.T) {
	f := newFakeHerdr(t)
	c, err := net.Dial("unix", f.SocketPath())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.Write([]byte(`{"id":"1","method":"ping","params":{}}` + "\n"))
	var resp Response
	if err := json.NewDecoder(c).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if resp.ID != "1" || resp.Error != nil {
		t.Fatalf("bad ping resp: %+v", resp)
	}
}
