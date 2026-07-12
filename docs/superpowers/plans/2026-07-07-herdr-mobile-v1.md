# herdr-mobile v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android app to monitor herdr agents and unblock them from a phone — a live agent/pane dashboard plus a quick-reply sheet — backed by a small Go daemon on the host and push via UnifiedPush. No embedded terminal in v1.

**Architecture:** Three components. (1) A **Go companion daemon** on the herdr host connects to herdr's local NDJSON Unix socket, keeps a fresh view of every pane by polling `pane.list` and subscribing per-pane to `agent_status_changed`, exposes a WebSocket API to the phone, and POSTs a push to the user's UnifiedPush endpoint when an agent becomes blocked or finishes. (2) An **Android app** (Kotlin + Jetpack Compose) connects to that WebSocket over the user's Tailscale network, renders the dashboard, opens a quick-reply sheet that reads a pane's recent output and sends input, and receives background pushes via UnifiedPush. (3) Infra the user already runs: **Tailscale** for reachability, **ntfy** as the UnifiedPush provider. No SSH and no Firebase in v1.

**Tech Stack:**
- Companion: Go 1.22+, `github.com/coder/websocket`, stdlib `net`/`net/http`/`encoding/json`. Single static binary, systemd **user** service.
- App: Kotlin, Jetpack Compose, `OkHttp` (WebSocket client), `kotlinx.serialization` (JSON), `org.unifiedpush.android:connector` (push), AndroidX Lifecycle/ViewModel. minSdk 26, targetSdk 34.
- Push: UnifiedPush + ntfy (self-hosted or ntfy.sh). No FCM.

## Global Constraints

- **herdr API is ground truth as spiked against v0.7.1 / protocol 14** (do not assume beyond this): newline-delimited JSON over a Unix socket at `~/.config/herdr/herdr.sock` (override via `HERDR_SOCKET_PATH`); request `{"id":"..","method":"..","params":{..}}`, response `{"id":"..","result":{..}}` or `{"id":"..","error":{"code":"..","message":".."}}`; **one request per socket connection** (open → send one line → read one line → close); `events.subscribe` is **per-pane** and requires a `pane_id`, and holds the connection open streaming frames after an initial `{"result":{"type":"subscription_started"}}`; the `revision` field is unreliable (observed always `0`) — never rely on it, diff text yourself; geometry (cols/rows) is not exposed and not settable.
- **Single host, herdr default session** (`~/.config/herdr/herdr.sock`). No named-session or multi-host support in v1.
- **No API auth in v1**, but the companion's auth check MUST be a single injectable seam (an `Authorizer` interface that currently allows all) so QR/token/password can be added later without restructuring. Do **not** couple auth to Tailscale.
- **Push backend is UnifiedPush/ntfy, never FCM.** The app ships one prebuilt APK for everyone; the push endpoint is supplied at runtime by the UnifiedPush distributor and forwarded to the companion. No `google-services.json`, no Firebase SDK.
- **v1 notification triggers are exactly two:** agent transitions to `blocked`, and agent transitions from `working` to `idle`/`done` (debounced). Nothing else fires a push in v1.
- **Open-source project** (like herdr). Keep dependencies minimal and permissively licensed; the app pulls in **no GPL** code in v1 (the GPLv3 Termux terminal libs belong to the v2 terminal, which is out of scope here).
- **Language of copy:** notification title for blocked = `"<workspace> needs you"`, body = the last non-empty output line; for finished = `"<workspace> finished"`. Keep these exact strings.

---

## Shared Contract: the Companion WebSocket Protocol (protocol v1)

Both subsystems depend on this. It is intentionally purpose-built (not a raw passthrough of herdr's socket) so the app is simple and the companion owns the polling/subscribe/debounce complexity.

**Transport:** WebSocket, text frames, one JSON object per frame. Each object has a `t` (type) field.

**On connect** the companion immediately sends `welcome`, then a full `panes` snapshot. Thereafter it streams `pane_update` / `pane_removed` frames as state changes, and replies to client requests. Client requests that expect a reply carry a `reqId` string echoed on the reply.

**Companion → App frames:**

```jsonc
{"t":"welcome","herdrVersion":"0.7.1","herdrProtocol":14,"companionProtocol":1}
{"t":"panes","panes":[ Pane, ... ]}                 // full snapshot
{"t":"pane_update","pane": Pane }                   // one pane created or changed
{"t":"pane_removed","paneId":"w6:p1"}               // pane closed
{"t":"pane_read","reqId":"r1","paneId":"w6:p1","source":"detection","text":"...\n..."}
{"t":"ack","reqId":"r2"}                            // send_text / send_keys / register_push ok
{"t":"error","reqId":"r2","code":"not_found","message":"pane not found"} // reqId omitted if unsolicited
{"t":"pong"}
```

**App → Companion frames:**

```jsonc
{"t":"hello","client":"herdr-mobile","clientVersion":"1.0.0"}
{"t":"register_push","endpoint":"https://ntfy.example.net/UP.../..."}  // UnifiedPush endpoint URL
{"t":"read_pane","reqId":"r1","paneId":"w6:p1","source":"detection","lines":40}
{"t":"send_text","reqId":"r2","paneId":"w6:p1","text":"y"}
{"t":"send_keys","reqId":"r3","paneId":"w6:p1","keys":"enter"}
{"t":"ping"}
```

**`Pane` object** (companion's normalized shape, derived from herdr `pane.list` + `agent.list`):

```jsonc
{
  "paneId":"w6:p1",
  "workspaceId":"w6",
  "tabId":"w6:t1",
  "cwd":"/home/me/work/proj",
  "focused":false,
  "agent":"claude",          // null if no agent detected
  "agentStatus":"working"    // "working"|"idle"|"blocked"|"done"|"unknown"|null
}
```

**Push payload** the companion POSTs to the UnifiedPush endpoint (body is JSON; the app parses it in its UnifiedPush receiver):

```jsonc
{"kind":"blocked","paneId":"w6:p1","workspaceId":"w6","title":"w6 needs you","body":"Do you want to proceed? (y/n)"}
{"kind":"finished","paneId":"w6:p1","workspaceId":"w6","title":"w6 finished","body":""}
```

---

# PART A — Go Companion Daemon

Ships and is fully testable on its own (drive it with `websocat`/`wscat` against a real herdr, or against the fake herdr socket the tests build). Build this first.

## File Structure (Part A)

- `companion/go.mod`, `companion/go.sum`
- `companion/cmd/herdr-mobiled/main.go` — flag parsing, config, wiring, graceful shutdown.
- `companion/internal/herdr/client.go` — NDJSON client: one-shot `Call`, streaming `Subscribe`.
- `companion/internal/herdr/types.go` — wire structs for herdr responses/events.
- `companion/internal/state/store.go` — pane map, `Apply` snapshot + diff, transition detection.
- `companion/internal/state/store_test.go`
- `companion/internal/notify/notifier.go` — `Notifier` interface + `HTTPNotifier` (POST to endpoint) + debounce policy.
- `companion/internal/notify/notifier_test.go`
- `companion/internal/proto/proto.go` — WS protocol structs (the shared contract above) + JSON tags.
- `companion/internal/proto/proto_test.go`
- `companion/internal/wsserver/auth.go` — `Authorizer` interface + `AllowAll`.
- `companion/internal/wsserver/server.go` — WS endpoint, per-client session, snapshot+stream+RPC.
- `companion/internal/wsserver/server_test.go`
- `companion/internal/engine/engine.go` — the orchestrator: poll loop + subscribe manager + wires store→notifier→server.
- `companion/internal/herdr/fakeherdr_test.go` — an in-test Unix-socket server that speaks herdr's protocol, shared by client/engine tests.

---

### Task A0: Repo init and module scaffold

**Files:**
- Create: `.gitignore`, `README.md`, `companion/go.mod`

- [ ] **Step 1: Initialize git and directory layout**

The repo is currently not a git repo. Run:

```bash
cd ~/herdr-mobile
git init
mkdir -p companion/cmd/herdr-mobiled companion/internal/{herdr,state,notify,proto,wsserver,engine}
```

- [ ] **Step 2: Write `.gitignore`**

```gitignore
# Go
/companion/herdr-mobiled
*.test
*.out
# Android
/app/.gradle/
/app/build/
/app/app/build/
/app/local.properties
/app/.idea/
*.apk
# misc
.DS_Store
```

- [ ] **Step 3: Write `README.md`**

```markdown
# herdr-mobile

Monitor and unblock your [herdr](https://herdr.dev) agents from an Android phone.

- **companion/** — a small Go daemon that runs on your herdr host, exposes a
  WebSocket API over your Tailscale network, and pushes notifications via
  UnifiedPush when an agent is blocked or finishes.
- **app/** — the Android app (Kotlin + Compose): live agent dashboard + quick-reply.

v1 is monitor + quick-reply only. A full embedded terminal is planned for v2;
until then use your existing SSH app for real terminal work.

License: (match herdr's license — TODO confirm)
```

- [ ] **Step 4: Init the Go module**

```bash
cd ~/herdr-mobile/companion
go mod init github.com/mohamed-essam/herdr-mobile/companion
go mod edit -go=1.22
```

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add .gitignore README.md companion/go.mod
git commit -m "chore: init repo and go module"
```

---

### Task A1: herdr wire types

**Files:**
- Create: `companion/internal/herdr/types.go`
- Test: `companion/internal/herdr/types_test.go`

**Interfaces:**
- Produces: `herdr.PaneInfo` struct (`PaneID, WorkspaceID, TabID, CWD string; Focused bool; Agent string; AgentStatus string`), `herdr.paneListResult`, `herdr.Response` (`ID string; Result json.RawMessage; Error *herdr.RPCError`), `herdr.RPCError` (`Code, Message string`), `herdr.Event` (`Type string; PaneID string; AgentStatus string`).

- [ ] **Step 1: Write the failing test**

```go
package herdr

import (
	"encoding/json"
	"testing"
)

func TestUnmarshalPaneListResult(t *testing.T) {
	raw := `{"id":"a","result":{"type":"pane_list","panes":[
	  {"pane_id":"w6:p1","workspace_id":"w6","tab_id":"w6:t1","focused":true,
	   "cwd":"/home/me/proj","agent":"claude","agent_status":"working","revision":0}]}}`
	var resp Response
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.Error != nil {
		t.Fatalf("unexpected error: %+v", resp.Error)
	}
	var res paneListResult
	if err := json.Unmarshal(resp.Result, &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Panes) != 1 {
		t.Fatalf("want 1 pane, got %d", len(res.Panes))
	}
	p := res.Panes[0]
	if p.PaneID != "w6:p1" || p.Agent != "claude" || p.AgentStatus != "working" || !p.Focused {
		t.Fatalf("bad pane: %+v", p)
	}
}

func TestUnmarshalRPCError(t *testing.T) {
	var resp Response
	if err := json.Unmarshal([]byte(`{"id":"a","error":{"code":"not_found","message":"pane not found"}}`), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.Error == nil || resp.Error.Code != "not_found" {
		t.Fatalf("want not_found error, got %+v", resp.Error)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd companion && go test ./internal/herdr/ -run TestUnmarshal -v`
Expected: FAIL — undefined: `Response`, `paneListResult`, etc.

- [ ] **Step 3: Write minimal implementation**

```go
package herdr

import "encoding/json"

type RPCError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type Response struct {
	ID     string          `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  *RPCError       `json:"error"`
}

type PaneInfo struct {
	PaneID      string `json:"pane_id"`
	WorkspaceID string `json:"workspace_id"`
	TabID       string `json:"tab_id"`
	CWD         string `json:"cwd"`
	Focused     bool   `json:"focused"`
	Agent       string `json:"agent"`
	AgentStatus string `json:"agent_status"`
}

type paneListResult struct {
	Type  string     `json:"type"`
	Panes []PaneInfo `json:"panes"`
}

type paneReadResult struct {
	Type string `json:"type"`
	Read struct {
		PaneID string `json:"pane_id"`
		Source string `json:"source"`
		Text   string `json:"text"`
	} `json:"read"`
}

// Event is a frame pushed on a subscription connection.
type Event struct {
	Type        string `json:"type"`
	PaneID      string `json:"pane_id"`
	AgentStatus string `json:"agent_status"`
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd companion && go test ./internal/herdr/ -run TestUnmarshal -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add companion/internal/herdr/types.go companion/internal/herdr/types_test.go
git commit -m "feat(companion): herdr wire types"
```

---

### Task A2: fake herdr socket (test harness)

**Files:**
- Create: `companion/internal/herdr/fakeherdr_test.go`

**Interfaces:**
- Produces (test-only): `newFakeHerdr(t *testing.T) *fakeHerdr` with fields controlling responses; `(*fakeHerdr).SocketPath() string`; ability to enqueue `pane.list` results and `agent_status_changed` events.

- [ ] **Step 1: Write the fake (it is itself the test scaffold; a smoke test validates it)**

```go
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
	t        *testing.T
	ln       net.Listener
	path     string
	mu       sync.Mutex
	panes    []PaneInfo         // returned for pane.list
	readText map[string]string  // pane_id -> text for pane.read
	subs     []chan Event       // active subscription channels
	lastSend chan map[string]any // records last send_text/send_keys params
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
		lastSend: make(chan map[string]any, 8)}
	go f.serve()
	t.Cleanup(func() { ln.Close(); os.Remove(path) })
	return f
}

func (f *fakeHerdr) SocketPath() string { return f.path }

func (f *fakeHerdr) SetPanes(p []PaneInfo) { f.mu.Lock(); f.panes = p; f.mu.Unlock() }

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
```

- [ ] **Step 2: Run to verify the harness works**

Run: `cd companion && go test ./internal/herdr/ -run TestFakeHerdrPing -v`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add companion/internal/herdr/fakeherdr_test.go
git commit -m "test(companion): fake herdr socket harness"
```

---

### Task A3: herdr NDJSON client — one-shot Call

**Files:**
- Create: `companion/internal/herdr/client.go`
- Test: `companion/internal/herdr/client_test.go`

**Interfaces:**
- Produces: `type Client struct{...}`; `func New(socketPath string) *Client`; `func (c *Client) Call(ctx context.Context, method string, params any) (json.RawMessage, error)` — opens a fresh connection per call (herdr is one-request-per-conn), returns `result` or an error wrapping `RPCError`; `func (c *Client) ListPanes(ctx) ([]PaneInfo, error)`; `func (c *Client) ReadPane(ctx, paneID, source string, lines int) (string, error)`; `func (c *Client) SendText(ctx, paneID, text string) error`; `func (c *Client) SendKeys(ctx, paneID, keys string) error`.

- [ ] **Step 1: Write the failing test**

```go
package herdr

import (
	"context"
	"testing"
	"time"
)

func TestClientListPanes(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetPanes([]PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}})
	c := New(f.SocketPath())
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	panes, err := c.ListPanes(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(panes) != 1 || panes[0].PaneID != "w6:p1" {
		t.Fatalf("bad panes: %+v", panes)
	}
}

func TestClientSendTextReachesHerdr(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	if err := c.SendText(context.Background(), "w6:p1", "y"); err != nil {
		t.Fatal(err)
	}
	select {
	case params := <-f.lastSend:
		if params["pane_id"] != "w6:p1" || params["text"] != "y" {
			t.Fatalf("bad send params: %+v", params)
		}
	case <-time.After(time.Second):
		t.Fatal("send_text never reached herdr")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd companion && go test ./internal/herdr/ -run TestClient -v`
Expected: FAIL — undefined `New`.

- [ ] **Step 3: Write minimal implementation**

```go
package herdr

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"sync/atomic"
)

type Client struct {
	socketPath string
	seq        atomic.Uint64
}

func New(socketPath string) *Client { return &Client{socketPath: socketPath} }

func (c *Client) dial(ctx context.Context) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, "unix", c.socketPath)
}

func (c *Client) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	conn, err := c.dial(ctx)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if dl, ok := ctx.Deadline(); ok {
		conn.SetDeadline(dl)
	}
	id := "c" + strconv.FormatUint(c.seq.Add(1), 10)
	req := map[string]any{"id": id, "method": method, "params": params}
	if params == nil {
		req["params"] = map[string]any{}
	}
	b, _ := json.Marshal(req)
	if _, err := conn.Write(append(b, '\n')); err != nil {
		return nil, err
	}
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return nil, err
	}
	var resp Response
	if err := json.Unmarshal(line, &resp); err != nil {
		return nil, err
	}
	if resp.Error != nil {
		return nil, fmt.Errorf("herdr %s: %s", resp.Error.Code, resp.Error.Message)
	}
	return resp.Result, nil
}

func (c *Client) ListPanes(ctx context.Context) ([]PaneInfo, error) {
	raw, err := c.Call(ctx, "pane.list", nil)
	if err != nil {
		return nil, err
	}
	var res paneListResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Panes, nil
}

func (c *Client) ReadPane(ctx context.Context, paneID, source string, lines int) (string, error) {
	raw, err := c.Call(ctx, "pane.read", map[string]any{"pane_id": paneID, "source": source, "lines": lines})
	if err != nil {
		return "", err
	}
	var res paneReadResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", err
	}
	return res.Read.Text, nil
}

func (c *Client) SendText(ctx context.Context, paneID, text string) error {
	_, err := c.Call(ctx, "pane.send_text", map[string]any{"pane_id": paneID, "text": text})
	return err
}

func (c *Client) SendKeys(ctx context.Context, paneID, keys string) error {
	_, err := c.Call(ctx, "pane.send_keys", map[string]any{"pane_id": paneID, "keys": keys})
	return err
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd companion && go test ./internal/herdr/ -v`
Expected: PASS (all herdr tests)

- [ ] **Step 5: Commit**

```bash
git add companion/internal/herdr/client.go companion/internal/herdr/client_test.go
git commit -m "feat(companion): herdr NDJSON client (Call/ListPanes/ReadPane/Send*)"
```

---

### Task A4: herdr client — streaming Subscribe

**Files:**
- Modify: `companion/internal/herdr/client.go`
- Test: `companion/internal/herdr/subscribe_test.go`

**Interfaces:**
- Produces: `func (c *Client) Subscribe(ctx context.Context, paneID, eventType string) (<-chan Event, error)` — opens a dedicated connection, sends `events.subscribe` with `[{type, pane_id}]`, discards the `subscription_started` frame, then streams decoded `Event`s on the channel until `ctx` is cancelled or the connection drops (channel is closed on exit).

- [ ] **Step 1: Write the failing test**

```go
package herdr

import (
	"context"
	"testing"
	"time"
)

func TestSubscribeStreamsEvents(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ch, err := c.Subscribe(ctx, "w6:p1", "pane.agent_status_changed")
	if err != nil {
		t.Fatal(err)
	}
	// give the subscription time to register
	time.Sleep(50 * time.Millisecond)
	f.PushEvent(Event{Type: "pane.agent_status_changed", PaneID: "w6:p1", AgentStatus: "blocked"})
	select {
	case e := <-ch:
		if e.AgentStatus != "blocked" || e.PaneID != "w6:p1" {
			t.Fatalf("bad event: %+v", e)
		}
	case <-time.After(time.Second):
		t.Fatal("no event received")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd companion && go test ./internal/herdr/ -run TestSubscribe -v`
Expected: FAIL — undefined `Subscribe`.

- [ ] **Step 3: Add the implementation to `client.go`**

```go
func (c *Client) Subscribe(ctx context.Context, paneID, eventType string) (<-chan Event, error) {
	conn, err := c.dial(ctx)
	if err != nil {
		return nil, err
	}
	id := "s" + strconv.FormatUint(c.seq.Add(1), 10)
	req := map[string]any{"id": id, "method": "events.subscribe",
		"params": map[string]any{"subscriptions": []map[string]any{{"type": eventType, "pane_id": paneID}}}}
	b, _ := json.Marshal(req)
	if _, err := conn.Write(append(b, '\n')); err != nil {
		conn.Close()
		return nil, err
	}
	out := make(chan Event, 16)
	go func() {
		defer close(out)
		defer conn.Close()
		go func() { <-ctx.Done(); conn.Close() }()
		r := bufio.NewReader(conn)
		first := true
		for {
			line, err := r.ReadBytes('\n')
			if err != nil {
				return
			}
			if first {
				first = false // skip subscription_started
				continue
			}
			var e Event
			if json.Unmarshal(line, &e) == nil && e.Type != "" {
				select {
				case out <- e:
				case <-ctx.Done():
					return
				}
			}
		}
	}()
	return out, nil
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd companion && go test ./internal/herdr/ -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add companion/internal/herdr/client.go companion/internal/herdr/subscribe_test.go
git commit -m "feat(companion): streaming per-pane Subscribe"
```

---

### Task A5: state store — snapshot diff & transition detection

**Files:**
- Create: `companion/internal/state/store.go`
- Test: `companion/internal/state/store_test.go`

**Interfaces:**
- Consumes: `herdr.PaneInfo`.
- Produces: `state.Pane` (normalized, JSON tags matching the `Pane` contract: `PaneID,WorkspaceID,TabID,CWD,Focused,Agent,AgentStatus`); `state.Change` (`Kind string` one of `"update"`,`"removed"`; `Pane state.Pane`; `PaneID string`); `state.Transition` (`PaneID, WorkspaceID, From, To string`); `type Store struct{}`; `func NewStore() *Store`; `func (s *Store) Apply(infos []herdr.PaneInfo) (changes []Change, transitions []Transition)` — updates internal map, returns per-pane changes (new/changed/removed) and agent-status transitions; `func (s *Store) Snapshot() []Pane`.

- [ ] **Step 1: Write the failing test**

```go
package state

import (
	"testing"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
)

func infos(p ...herdr.PaneInfo) []herdr.PaneInfo { return p }

func TestApplyDetectsNewChangedRemoved(t *testing.T) {
	s := NewStore()

	ch, tr := s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}))
	if len(ch) != 1 || ch[0].Kind != "update" {
		t.Fatalf("first apply want 1 update, got %+v", ch)
	}
	if len(tr) != 0 {
		t.Fatalf("first apply should not report transitions (no prior state), got %+v", tr)
	}

	// same state again -> no changes
	ch, _ = s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}))
	if len(ch) != 0 {
		t.Fatalf("unchanged apply want 0 changes, got %+v", ch)
	}

	// status change working -> blocked
	ch, tr = s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked"}))
	if len(ch) != 1 || ch[0].Kind != "update" || ch[0].Pane.AgentStatus != "blocked" {
		t.Fatalf("want blocked update, got %+v", ch)
	}
	if len(tr) != 1 || tr[0].From != "working" || tr[0].To != "blocked" || tr[0].WorkspaceID != "w6" {
		t.Fatalf("want working->blocked transition, got %+v", tr)
	}

	// pane disappears -> removed
	ch, _ = s.Apply(infos())
	if len(ch) != 1 || ch[0].Kind != "removed" || ch[0].PaneID != "w6:p1" {
		t.Fatalf("want removed, got %+v", ch)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd companion && go test ./internal/state/ -v`
Expected: FAIL — undefined `NewStore`.

- [ ] **Step 3: Write minimal implementation**

```go
package state

import "github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"

type Pane struct {
	PaneID      string `json:"paneId"`
	WorkspaceID string `json:"workspaceId"`
	TabID       string `json:"tabId"`
	CWD         string `json:"cwd"`
	Focused     bool   `json:"focused"`
	Agent       string `json:"agent"`
	AgentStatus string `json:"agentStatus"`
}

type Change struct {
	Kind   string `json:"-"` // "update" | "removed"
	Pane   Pane   `json:"pane,omitempty"`
	PaneID string `json:"paneId,omitempty"`
}

type Transition struct {
	PaneID, WorkspaceID, From, To string
}

type Store struct {
	panes map[string]Pane
}

func NewStore() *Store { return &Store{panes: map[string]Pane{}} }

func toPane(i herdr.PaneInfo) Pane {
	return Pane{PaneID: i.PaneID, WorkspaceID: i.WorkspaceID, TabID: i.TabID,
		CWD: i.CWD, Focused: i.Focused, Agent: i.Agent, AgentStatus: i.AgentStatus}
}

func (s *Store) Apply(infos []herdr.PaneInfo) ([]Change, []Transition) {
	var changes []Change
	var transitions []Transition
	seen := map[string]bool{}
	for _, i := range infos {
		np := toPane(i)
		seen[np.PaneID] = true
		old, existed := s.panes[np.PaneID]
		if !existed {
			s.panes[np.PaneID] = np
			changes = append(changes, Change{Kind: "update", Pane: np})
			continue
		}
		if old != np {
			s.panes[np.PaneID] = np
			changes = append(changes, Change{Kind: "update", Pane: np})
		}
		if old.AgentStatus != np.AgentStatus {
			transitions = append(transitions, Transition{PaneID: np.PaneID,
				WorkspaceID: np.WorkspaceID, From: old.AgentStatus, To: np.AgentStatus})
		}
	}
	for id := range s.panes {
		if !seen[id] {
			delete(s.panes, id)
			changes = append(changes, Change{Kind: "removed", PaneID: id})
		}
	}
	return changes, transitions
}

func (s *Store) Snapshot() []Pane {
	out := make([]Pane, 0, len(s.panes))
	for _, p := range s.panes {
		out = append(out, p)
	}
	return out
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd companion && go test ./internal/state/ -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add companion/internal/state/
git commit -m "feat(companion): pane state store with diff and transition detection"
```

---

### Task A6: notifier — trigger rules, debounce, HTTP POST

**Files:**
- Create: `companion/internal/notify/notifier.go`
- Test: `companion/internal/notify/notifier_test.go`

**Interfaces:**
- Consumes: `state.Transition`.
- Produces: `notify.Push` struct (`Kind, PaneID, WorkspaceID, Title, Body string`); `type Notifier interface { Notify(context.Context, Push) error }`; `func ShouldNotify(tr state.Transition, lastBody string) (Push, bool)` — pure function encoding the two v1 triggers (→`blocked`, and `working`→`idle`/`done`), returns `(push, true)` when a push is warranted; `type HTTPNotifier struct{}` with `func NewHTTPNotifier(endpoint string, hc *http.Client) *HTTPNotifier` implementing `Notifier` by POSTing JSON.

Notes on rules: `blocked` fires from any prior status. `finished` fires only on `working`→(`idle`|`done`). Any other transition returns `false`. The `working`→`idle` debounce (ignore a finished push if the pane went back to `working` within a short window) lives in the engine (Task A8), not here — `ShouldNotify` stays pure.

- [ ] **Step 1: Write the failing test**

```go
package notify

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

func TestShouldNotifyBlocked(t *testing.T) {
	p, ok := ShouldNotify(state.Transition{PaneID: "w6:p1", WorkspaceID: "w6", From: "working", To: "blocked"}, "Proceed? (y/n)")
	if !ok || p.Kind != "blocked" || p.Title != "w6 needs you" || p.Body != "Proceed? (y/n)" {
		t.Fatalf("bad blocked push: %+v ok=%v", p, ok)
	}
}

func TestShouldNotifyFinishedOnlyFromWorking(t *testing.T) {
	if _, ok := ShouldNotify(state.Transition{WorkspaceID: "w6", From: "idle", To: "done"}, ""); ok {
		t.Fatal("idle->done should not notify")
	}
	p, ok := ShouldNotify(state.Transition{WorkspaceID: "w6", From: "working", To: "idle"}, "")
	if !ok || p.Kind != "finished" || p.Title != "w6 finished" {
		t.Fatalf("working->idle should be a finished push, got %+v ok=%v", p, ok)
	}
}

func TestShouldNotifyIgnoresOther(t *testing.T) {
	if _, ok := ShouldNotify(state.Transition{From: "idle", To: "working"}, ""); ok {
		t.Fatal("idle->working should not notify")
	}
}

func TestHTTPNotifierPostsJSON(t *testing.T) {
	got := make(chan Push, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var p Push
		json.NewDecoder(r.Body).Decode(&p)
		got <- p
	}))
	defer srv.Close()
	n := NewHTTPNotifier(srv.URL, srv.Client())
	if err := n.Notify(context.Background(), Push{Kind: "blocked", WorkspaceID: "w6", Title: "w6 needs you"}); err != nil {
		t.Fatal(err)
	}
	p := <-got
	if p.Kind != "blocked" || p.Title != "w6 needs you" {
		t.Fatalf("server got wrong push: %+v", p)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd companion && go test ./internal/notify/ -v`
Expected: FAIL — undefined `ShouldNotify`, `NewHTTPNotifier`.

- [ ] **Step 3: Write minimal implementation**

```go
package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

type Push struct {
	Kind        string `json:"kind"`
	PaneID      string `json:"paneId"`
	WorkspaceID string `json:"workspaceId"`
	Title       string `json:"title"`
	Body        string `json:"body"`
}

type Notifier interface {
	Notify(ctx context.Context, p Push) error
}

// ShouldNotify encodes the two v1 triggers. lastBody is the last non-empty
// output line for the pane (used as the blocked notification body).
func ShouldNotify(tr state.Transition, lastBody string) (Push, bool) {
	switch {
	case tr.To == "blocked":
		return Push{Kind: "blocked", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID,
			Title: tr.WorkspaceID + " needs you", Body: lastBody}, true
	case tr.From == "working" && (tr.To == "idle" || tr.To == "done"):
		return Push{Kind: "finished", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID,
			Title: tr.WorkspaceID + " finished", Body: ""}, true
	default:
		return Push{}, false
	}
}

type HTTPNotifier struct {
	endpoint string
	hc       *http.Client
}

func NewHTTPNotifier(endpoint string, hc *http.Client) *HTTPNotifier {
	if hc == nil {
		hc = http.DefaultClient
	}
	return &HTTPNotifier{endpoint: endpoint, hc: hc}
}

func (n *HTTPNotifier) Notify(ctx context.Context, p Push) error {
	if n.endpoint == "" {
		return nil // no endpoint registered yet
	}
	b, _ := json.Marshal(p)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, n.endpoint, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := n.hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("push endpoint returned %d", resp.StatusCode)
	}
	return nil
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd companion && go test ./internal/notify/ -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add companion/internal/notify/
git commit -m "feat(companion): notification trigger rules + HTTP push"
```

---

### Task A7: WS protocol structs + Authorizer seam

**Files:**
- Create: `companion/internal/proto/proto.go`, `companion/internal/wsserver/auth.go`
- Test: `companion/internal/proto/proto_test.go`

**Interfaces:**
- Produces (`proto`): `Frame` marshalling helpers — the app→companion `ClientMsg` (`T, Client, ClientVersion, Endpoint, ReqID, PaneID, Source string; Lines int; Text, Keys string`) and companion→app builders `Welcome(...)`, `PanesSnapshot(...)`, `PaneUpdate(...)`, `PaneRemoved(...)`, `PaneRead(...)`, `Ack(...)`, `ErrorFrame(...)`, `Pong()` each returning `[]byte`. Reuses `state.Pane`.
- Produces (`wsserver`): `type Authorizer interface { Authorize(r *http.Request) error }`; `type AllowAll struct{}` implementing it (returns nil).

- [ ] **Step 1: Write the failing test**

```go
package proto

import (
	"encoding/json"
	"testing"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

func TestParseClientMsg(t *testing.T) {
	m, err := ParseClient([]byte(`{"t":"send_text","reqId":"r2","paneId":"w6:p1","text":"y"}`))
	if err != nil {
		t.Fatal(err)
	}
	if m.T != "send_text" || m.PaneID != "w6:p1" || m.Text != "y" || m.ReqID != "r2" {
		t.Fatalf("bad parse: %+v", m)
	}
}

func TestPanesSnapshotFrame(t *testing.T) {
	b := PanesSnapshot([]state.Pane{{PaneID: "w6:p1", AgentStatus: "working"}})
	var got map[string]any
	json.Unmarshal(b, &got)
	if got["t"] != "panes" {
		t.Fatalf("want t=panes, got %v", got["t"])
	}
	panes := got["panes"].([]any)
	if len(panes) != 1 {
		t.Fatalf("want 1 pane in snapshot, got %d", len(panes))
	}
}

func TestErrorFrameCarriesReqID(t *testing.T) {
	b := ErrorFrame("r2", "not_found", "pane not found")
	var got map[string]any
	json.Unmarshal(b, &got)
	if got["t"] != "error" || got["reqId"] != "r2" || got["code"] != "not_found" {
		t.Fatalf("bad error frame: %v", got)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd companion && go test ./internal/proto/ -v`
Expected: FAIL — undefined `ParseClient`, etc.

- [ ] **Step 3: Write `proto.go`**

```go
package proto

import (
	"encoding/json"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

type ClientMsg struct {
	T             string `json:"t"`
	Client        string `json:"client"`
	ClientVersion string `json:"clientVersion"`
	Endpoint      string `json:"endpoint"`
	ReqID         string `json:"reqId"`
	PaneID        string `json:"paneId"`
	Source        string `json:"source"`
	Lines         int    `json:"lines"`
	Text          string `json:"text"`
	Keys          string `json:"keys"`
}

func ParseClient(b []byte) (ClientMsg, error) {
	var m ClientMsg
	err := json.Unmarshal(b, &m)
	return m, err
}

func must(v any) []byte { b, _ := json.Marshal(v); return b }

func Welcome(version string, protocol int) []byte {
	return must(map[string]any{"t": "welcome", "herdrVersion": version, "herdrProtocol": protocol, "companionProtocol": 1})
}
func PanesSnapshot(p []state.Pane) []byte {
	return must(map[string]any{"t": "panes", "panes": p})
}
func PaneUpdate(p state.Pane) []byte { return must(map[string]any{"t": "pane_update", "pane": p}) }
func PaneRemoved(id string) []byte   { return must(map[string]any{"t": "pane_removed", "paneId": id}) }
func PaneRead(reqID, paneID, source, text string) []byte {
	return must(map[string]any{"t": "pane_read", "reqId": reqID, "paneId": paneID, "source": source, "text": text})
}
func Ack(reqID string) []byte { return must(map[string]any{"t": "ack", "reqId": reqID}) }
func ErrorFrame(reqID, code, message string) []byte {
	return must(map[string]any{"t": "error", "reqId": reqID, "code": code, "message": message})
}
func Pong() []byte { return must(map[string]any{"t": "pong"}) }
```

- [ ] **Step 4: Write `auth.go`**

```go
package wsserver

import "net/http"

// Authorizer gates incoming WS connections. v1 uses AllowAll; a future
// token/QR/password check drops in here without touching the server.
type Authorizer interface {
	Authorize(r *http.Request) error
}

type AllowAll struct{}

func (AllowAll) Authorize(*http.Request) error { return nil }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd companion && go test ./internal/proto/ -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add companion/internal/proto/ companion/internal/wsserver/auth.go
git commit -m "feat(companion): WS protocol frames + Authorizer seam"
```

---

### Task A8: WS server + engine orchestrator

This is the largest task: it wires the poller, subscriptions, store, notifier, and WS clients together, and is validated by an integration test that runs the engine against the fake herdr and a real WebSocket client.

**Files:**
- Create: `companion/internal/wsserver/server.go`, `companion/internal/engine/engine.go`
- Test: `companion/internal/engine/engine_test.go`
- Modify: `companion/go.mod` (add `github.com/coder/websocket`)

**Interfaces:**
- Consumes: `herdr.Client`, `state.Store`, `notify.Notifier`, `proto.*`, `wsserver.Authorizer`.
- Produces (`wsserver`): `type Server struct{}`; `func NewServer(auth Authorizer, hc HerdrRPC) *Server` where `HerdrRPC` is an interface `{ ReadPane(ctx,paneID,source string,lines int)(string,error); SendText(ctx,paneID,text string) error; SendKeys(ctx,paneID,keys string) error }` (satisfied by `*herdr.Client`); `func (s *Server) Handler() http.Handler`; `func (s *Server) Broadcast(frame []byte)`; `func (s *Server) SetPushEndpoint(fn func(endpoint string))` (called when a client registers a push endpoint).
- Produces (`engine`): `type Engine struct{}`; `func New(cfg Config) *Engine` (Config: `SocketPath string; ListenAddr string; PollInterval time.Duration; DebounceFinished time.Duration`); `func (e *Engine) Run(ctx context.Context) error`.

Engine responsibilities:
1. Poll `client.ListPanes` every `PollInterval`; `store.Apply`; for each `Change` broadcast `PaneUpdate`/`PaneRemoved`; for each `Transition` evaluate `notify.ShouldNotify` (fetching the pane's last output line via `ReadPane(source:"detection")` for blocked bodies) and, subject to the finished-debounce, call the notifier.
2. Maintain one `Subscribe(pane, "pane.agent_status_changed")` per agent-bearing pane so status transitions are caught between polls; feed those events through the same transition path. (Poll remains the source of truth for create/remove and is the fallback if a subscription drops.)
3. Serve the WS handler; when a client sends `register_push`, update the notifier's endpoint via `SetPushEndpoint`.

Finished-debounce: when a `working`→`idle`/`done` transition occurs, wait `DebounceFinished` (e.g. 4s) and re-check the store; only fire the finished push if the pane is still not `working`.

- [ ] **Step 1: Add the websocket dependency**

```bash
cd ~/herdr-mobile/companion
go get github.com/coder/websocket@latest
```

- [ ] **Step 2: Write the integration test (failing)**

```go
package engine

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
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
```

> **Server API note for the implementer:** the test uses `SetInitialSnapshot(func() []state.Pane)` so the server can send the current snapshot to each newly-connected client. Add that method alongside `Broadcast`. `HerdrRPC` methods take a `context.Context` first argument.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd companion && go test ./internal/engine/ -run TestServerSends -v`
Expected: FAIL — undefined `wsserver.NewServer`.

- [ ] **Step 4: Write `wsserver/server.go`**

```go
package wsserver

import (
	"context"
	"net/http"
	"sync"

	"github.com/coder/websocket"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/proto"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

type HerdrRPC interface {
	ReadPane(ctx context.Context, paneID, source string, lines int) (string, error)
	SendText(ctx context.Context, paneID, text string) error
	SendKeys(ctx context.Context, paneID, keys string) error
}

type Server struct {
	auth      Authorizer
	rpc       HerdrRPC
	snapshot  func() []state.Pane
	onPush    func(endpoint string)
	herdrVer  string
	herdrProt int

	mu      sync.Mutex
	clients map[*client]struct{}
}

type client struct {
	conn *websocket.Conn
	send chan []byte
}

func NewServer(auth Authorizer, rpc HerdrRPC) *Server {
	return &Server{auth: auth, rpc: rpc, clients: map[*client]struct{}{},
		snapshot: func() []state.Pane { return nil }, onPush: func(string) {},
		herdrVer: "unknown", herdrProt: 0}
}

func (s *Server) SetInitialSnapshot(fn func() []state.Pane) { s.snapshot = fn }
func (s *Server) SetPushEndpoint(fn func(string))           { s.onPush = fn }
func (s *Server) SetHerdrInfo(ver string, prot int)         { s.herdrVer, s.herdrProt = ver, prot }

func (s *Server) Broadcast(frame []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for c := range s.clients {
		select {
		case c.send <- frame:
		default: // drop for a slow client
		}
	}
}

func (s *Server) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := s.auth.Authorize(r); err != nil {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		// NOTE: coder/websocket's InsecureSkipVerify skips the ORIGIN-header
		// check, NOT TLS verification. A native app sends no Origin header, so
		// this is required and safe. Transport confidentiality comes from
		// Tailscale (WireGuard) — v1 uses ws:// over the tailnet, not wss://.
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		c := &client{conn: conn, send: make(chan []byte, 64)}
		s.add(c)
		defer s.remove(c)

		ctx := r.Context()
		// welcome + snapshot
		c.send <- proto.Welcome(s.herdrVer, s.herdrProt)
		c.send <- proto.PanesSnapshot(s.snapshot())

		go s.writeLoop(ctx, c)
		s.readLoop(ctx, c)
	})
}

func (s *Server) add(c *client)    { s.mu.Lock(); s.clients[c] = struct{}{}; s.mu.Unlock() }
func (s *Server) remove(c *client) { s.mu.Lock(); delete(s.clients, c); s.mu.Unlock(); c.conn.Close(websocket.StatusNormalClosure, "") }

func (s *Server) writeLoop(ctx context.Context, c *client) {
	for {
		select {
		case <-ctx.Done():
			return
		case frame := <-c.send:
			if err := c.conn.Write(ctx, websocket.MessageText, frame); err != nil {
				return
			}
		}
	}
}

func (s *Server) readLoop(ctx context.Context, c *client) {
	for {
		_, b, err := c.conn.Read(ctx)
		if err != nil {
			return
		}
		m, err := proto.ParseClient(b)
		if err != nil {
			continue
		}
		switch m.T {
		case "hello", "ping":
			c.send <- proto.Pong()
		case "register_push":
			s.onPush(m.Endpoint)
			c.send <- proto.Ack(m.ReqID)
		case "read_pane":
			src := m.Source
			if src == "" {
				src = "detection"
			}
			lines := m.Lines
			if lines == 0 {
				lines = 40
			}
			txt, err := s.rpc.ReadPane(ctx, m.PaneID, src, lines)
			if err != nil {
				c.send <- proto.ErrorFrame(m.ReqID, "read_failed", err.Error())
				continue
			}
			c.send <- proto.PaneRead(m.ReqID, m.PaneID, src, txt)
		case "send_text":
			if err := s.rpc.SendText(ctx, m.PaneID, m.Text); err != nil {
				c.send <- proto.ErrorFrame(m.ReqID, "send_failed", err.Error())
				continue
			}
			c.send <- proto.Ack(m.ReqID)
		case "send_keys":
			if err := s.rpc.SendKeys(ctx, m.PaneID, m.Keys); err != nil {
				c.send <- proto.ErrorFrame(m.ReqID, "send_failed", err.Error())
				continue
			}
			c.send <- proto.Ack(m.ReqID)
		}
	}
}
```

- [ ] **Step 5: Run the server test to verify it passes**

Run: `cd companion && go test ./internal/engine/ -run TestServerSends -v`
Expected: PASS

- [ ] **Step 6: Write `engine/engine.go`**

```go
package engine

import (
	"context"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/notify"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/proto"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/wsserver"
)

type Config struct {
	SocketPath       string
	ListenAddr       string
	PollInterval     time.Duration
	DebounceFinished time.Duration
}

type Engine struct {
	cfg    Config
	client *herdr.Client
	store  *state.Store
	notif  *notify.HTTPNotifier
	srv    *wsserver.Server

	mu       sync.Mutex
	endpoint string
}

func New(cfg Config) *Engine {
	if cfg.PollInterval == 0 {
		cfg.PollInterval = 1500 * time.Millisecond
	}
	if cfg.DebounceFinished == 0 {
		cfg.DebounceFinished = 4 * time.Second
	}
	c := herdr.New(cfg.SocketPath)
	e := &Engine{cfg: cfg, client: c, store: state.NewStore()}
	e.notif = notify.NewHTTPNotifier("", http.DefaultClient)
	e.srv = wsserver.NewServer(wsserver.AllowAll{}, c)
	e.srv.SetInitialSnapshot(e.store.Snapshot)
	e.srv.SetPushEndpoint(e.setEndpoint)
	return e
}

func (e *Engine) setEndpoint(ep string) {
	e.mu.Lock()
	e.endpoint = ep
	e.mu.Unlock()
}

func (e *Engine) Run(ctx context.Context) error {
	// probe herdr version for the welcome frame (best-effort)
	if raw, err := e.client.Call(ctx, "ping", nil); err == nil {
		var pong struct{ Version string `json:"version"`; Protocol int `json:"protocol"` }
		_ = jsonUnmarshalInto(raw, &pong)
		e.srv.SetHerdrInfo(pong.Version, pong.Protocol)
	}

	go e.pollLoop(ctx)

	httpSrv := &http.Server{Addr: e.cfg.ListenAddr, Handler: e.srv.Handler()}
	go func() { <-ctx.Done(); shutdownCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second); defer cancel(); httpSrv.Shutdown(shutdownCtx) }()
	err := httpSrv.ListenAndServe()
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}

func (e *Engine) pollLoop(ctx context.Context) {
	t := time.NewTicker(e.cfg.PollInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			e.pollOnce(ctx)
		}
	}
}

func (e *Engine) pollOnce(ctx context.Context) {
	panes, err := e.client.ListPanes(ctx)
	if err != nil {
		return
	}
	changes, transitions := e.store.Apply(panes)
	for _, ch := range changes {
		if ch.Kind == "removed" {
			e.srv.Broadcast(proto.PaneRemoved(ch.PaneID))
		} else {
			e.srv.Broadcast(proto.PaneUpdate(ch.Pane))
		}
	}
	for _, tr := range transitions {
		e.handleTransition(ctx, tr)
	}
}

func (e *Engine) handleTransition(ctx context.Context, tr state.Transition) {
	body := ""
	if tr.To == "blocked" {
		if txt, err := e.client.ReadPane(ctx, tr.PaneID, "detection", 40); err == nil {
			body = lastNonEmptyLine(txt)
		}
	}
	push, ok := notify.ShouldNotify(tr, body)
	if !ok {
		return
	}
	if push.Kind == "finished" {
		// debounce: only fire if still not working after the window
		go func() {
			select {
			case <-ctx.Done():
			case <-time.After(e.cfg.DebounceFinished):
				for _, p := range e.store.Snapshot() {
					if p.PaneID == tr.PaneID && p.AgentStatus == "working" {
						return // resumed; suppress
					}
				}
				e.fire(ctx, push)
			}
		}()
		return
	}
	e.fire(ctx, push)
}

func (e *Engine) fire(ctx context.Context, p notify.Push) {
	e.mu.Lock()
	ep := e.endpoint
	e.mu.Unlock()
	if ep == "" {
		return
	}
	n := notify.NewHTTPNotifier(ep, http.DefaultClient)
	_ = n.Notify(ctx, p)
}

func lastNonEmptyLine(s string) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if strings.TrimSpace(lines[i]) != "" {
			return strings.TrimSpace(lines[i])
		}
	}
	return ""
}

// small indirection so we don't import encoding/json twice in edits
func jsonUnmarshalInto(b []byte, v any) error { return jsonUnmarshal(b, v) }

var _ = net.Dial // keep net imported if unused after edits
```

> **Implementer note:** replace the `jsonUnmarshal`/`jsonUnmarshalInto` shim with a direct `encoding/json` import and `json.Unmarshal`; the shim is only here to keep the code block self-contained. Remove the `net` blank-import guard once the file compiles cleanly.

- [ ] **Step 7: Write the engine end-to-end notification test (failing), then make it pass with the code above**

```go
// append to engine_test.go
func TestEngineFiresBlockedPushToRegisteredEndpoint(t *testing.T) {
	f := herdr.NewFakeForTest(t) // exported test helper; see note
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
```

> **Note:** this test needs the fake herdr accessible from the `engine` package. Promote the fake into a small exported test helper: add `companion/internal/herdr/fakepub_test.go`? No — instead move `newFakeHerdr` into a non-`_test.go` file `companion/internal/herdr/fake.go` guarded behind a `NewFakeForTest(t *testing.T)` constructor so other packages' tests can use it. Update Task A2's file accordingly during this step (rename `fakeherdr_test.go` → `fake.go`, export `NewFakeForTest`, `(*FakeHerdr).SocketPath`, `SetPanes`, `PushEvent`).

- [ ] **Step 8: Run all companion tests**

Run: `cd companion && go test ./... -v`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add companion/internal/wsserver/server.go companion/internal/engine/ companion/internal/herdr/fake.go companion/go.mod companion/go.sum
git commit -m "feat(companion): WS server + engine (poll, subscribe, debounced push)"
```

---

### Task A9: main entrypoint, config, systemd unit

**Files:**
- Create: `companion/cmd/herdr-mobiled/main.go`, `companion/deploy/herdr-mobiled.service`, `companion/deploy/README.md`

**Interfaces:**
- Consumes: `engine.New`, `engine.Config`.

- [ ] **Step 1: Write `main.go`**

```go
package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/engine"
)

func defaultSocket() string {
	if v := os.Getenv("HERDR_SOCKET_PATH"); v != "" {
		return v
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "herdr", "herdr.sock")
}

func main() {
	socket := flag.String("socket", defaultSocket(), "path to herdr.sock")
	listen := flag.String("listen", "0.0.0.0:8787", "WS listen address (bind to your tailnet IP in production)")
	poll := flag.Duration("poll", 1500*time.Millisecond, "pane.list poll interval")
	flag.Parse()

	e := engine.New(engine.Config{SocketPath: *socket, ListenAddr: *listen, PollInterval: *poll})

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	log.Printf("herdr-mobiled: socket=%s listen=%s", *socket, *listen)
	if err := e.Run(ctx); err != nil {
		log.Fatal(err)
	}
}
```

- [ ] **Step 2: Write the systemd user unit `deploy/herdr-mobiled.service`**

```ini
[Unit]
Description=herdr-mobile companion daemon
After=network-online.target

[Service]
# Bind to your Tailscale IP so the API is reachable only on your tailnet.
# Find it with: tailscale ip -4
ExecStart=%h/.local/bin/herdr-mobiled --listen %i:8787
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
```

- [ ] **Step 3: Write `deploy/README.md`**

```markdown
# Deploying the companion

```bash
cd companion
go build -o ~/.local/bin/herdr-mobiled ./cmd/herdr-mobiled

# Find your tailnet IP:
TS_IP=$(tailscale ip -4)

# Install the user service, templated with your tailnet IP:
mkdir -p ~/.config/systemd/user
sed "s/%i/$TS_IP/" deploy/herdr-mobiled.service > ~/.config/systemd/user/herdr-mobiled.service
systemctl --user daemon-reload
systemctl --user enable --now herdr-mobiled
systemctl --user status herdr-mobiled
```

Verify from a laptop on the tailnet:

```bash
# expects welcome + panes frames
websocat ws://$TS_IP:8787/
```
```

- [ ] **Step 4: Build and smoke-test against the real herdr**

```bash
cd companion
go build -o /tmp/herdr-mobiled ./cmd/herdr-mobiled
/tmp/herdr-mobiled --listen 127.0.0.1:8787 &
sleep 1
# if websocat is available:
command -v websocat >/dev/null && (echo '{"t":"hello"}'; sleep 2) | websocat -n1 ws://127.0.0.1:8787/ || echo "install websocat to smoke-test manually"
kill %1
```

Expected: a `welcome` frame with the real herdr version, then a `panes` snapshot listing your actual panes (w2/w3/w5/w6/w7…).

- [ ] **Step 5: Commit**

```bash
git add companion/cmd/ companion/deploy/
git commit -m "feat(companion): main entrypoint, config flags, systemd user unit"
```

**Milestone A complete:** the companion is a working, independently testable daemon. You can watch your agents and receive pushes with only Part A built (drive it with `websocat` and point `register_push` at an ntfy topic).

---

# PART B — Android App

Built against the companion's WebSocket contract. The app is Kotlin + Jetpack Compose, MVVM. Tests concentrate on the protocol/repository/reducer layer (pure, fast) — Compose UI is assembled from that tested state.

## File Structure (Part B)

- `app/` — Gradle project (`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`).
- `app/app/src/main/AndroidManifest.xml`
- `app/app/src/main/java/dev/herdr/mobile/`
  - `net/Protocol.kt` — `@Serializable` frame models + parse/build helpers (mirror of the shared contract).
  - `net/CompanionClient.kt` — OkHttp WebSocket wrapper exposing `Flow<ServerFrame>` and `send(ClientMsg)`.
  - `data/PaneRepository.kt` — holds `StateFlow<List<Pane>>`, applies snapshot/update/removed frames, exposes `readPane`/`sendText`/`sendKeys` suspend calls with reqId correlation.
  - `data/Settings.kt` — persists companion URL (DataStore).
  - `push/UnifiedPushReceiver.kt` — receives endpoint + messages, posts system notifications.
  - `push/PushPayload.kt` — `@Serializable` push payload model.
  - `ui/DashboardScreen.kt`, `ui/QuickReplySheet.kt`, `ui/PaneRow.kt`, `ui/theme/…`
  - `ui/DashboardViewModel.kt`
  - `MainActivity.kt`, `HerdrApp.kt`
- Tests: `app/app/src/test/java/dev/herdr/mobile/`
  - `ProtocolTest.kt`, `PaneRepositoryTest.kt`, `DashboardViewModelTest.kt`, `PushPayloadTest.kt`

---

### Task B0: Android project scaffold

**Files:**
- Create: `app/settings.gradle.kts`, `app/build.gradle.kts`, `app/gradle/libs.versions.toml`, `app/app/build.gradle.kts`, `app/app/src/main/AndroidManifest.xml`, `app/gradle.properties`

- [ ] **Step 1: Create the Gradle scaffold**

Use Android Studio's "New Project → Empty Compose Activity" targeting `dev.herdr.mobile`, minSdk 26, or hand-create the files below. `app/gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.5.0"
kotlin = "2.0.0"
compose-bom = "2024.06.00"
lifecycle = "2.8.3"
okhttp = "4.12.0"
serialization = "1.7.1"
datastore = "1.1.1"
unifiedpush = "2.4.0"
coroutines = "1.8.1"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.13.1" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version = "1.9.0" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
unifiedpush = { module = "org.unifiedpush.android:connector", version.ref = "unifiedpush" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`app/app/build.gradle.kts` (key parts):

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "dev.herdr.mobile"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.herdr.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.datastore.preferences)
    implementation(libs.unifiedpush)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:label="herdr"
        android:theme="@style/Theme.Material3.DynamicColors.DayNight"
        android:allowBackup="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".push.UnifiedPushReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="org.unifiedpush.android.connector.MESSAGE" />
                <action android:name="org.unifiedpush.android.connector.NEW_ENDPOINT" />
                <action android:name="org.unifiedpush.android.connector.REGISTRATION_FAILED" />
                <action android:name="org.unifiedpush.android.connector.UNREGISTERED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

- [ ] **Step 3: Verify it builds**

Run: `cd app && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (empty app).

- [ ] **Step 4: Commit**

```bash
git add app/
git commit -m "chore(app): android + compose project scaffold"
```

---

### Task B1: protocol models (mirror of the shared contract)

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`

**Interfaces:**
- Produces: `data class Pane(paneId, workspaceId, tabId, cwd, focused, agent, agentStatus)` (all `String` except `focused: Boolean`, `agent: String?`, `agentStatus: String?`); sealed `ServerFrame` (`Welcome`, `Panes(panes: List<Pane>)`, `PaneUpdate(pane)`, `PaneRemoved(paneId)`, `PaneRead(reqId, paneId, source, text)`, `Ack(reqId)`, `ErrorFrame(reqId, code, message)`, `Pong`, `Unknown`); `fun parseServerFrame(json: String): ServerFrame`; `object ClientMsg { fun hello(): String; fun registerPush(endpoint): String; fun readPane(reqId, paneId, source, lines): String; fun sendText(reqId, paneId, text): String; fun sendKeys(reqId, paneId, keys): String; fun ping(): String }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.net.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun parsesPanesSnapshot() {
        val f = parseServerFrame("""{"t":"panes","panes":[{"paneId":"w6:p1","workspaceId":"w6","tabId":"w6:t1","cwd":"/x","focused":true,"agent":"claude","agentStatus":"working"}]}""")
        assertTrue(f is ServerFrame.Panes)
        val p = (f as ServerFrame.Panes).panes.single()
        assertEquals("w6:p1", p.paneId)
        assertEquals("working", p.agentStatus)
        assertTrue(p.focused)
    }

    @Test fun parsesPaneUpdateWithNullAgent() {
        val f = parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w2:p1","workspaceId":"w2","tabId":"w2:t1","cwd":"/y","focused":false,"agent":null,"agentStatus":null}}""")
        val p = (f as ServerFrame.PaneUpdate).pane
        assertNull(p.agent)
        assertNull(p.agentStatus)
    }

    @Test fun parsesPaneReadAndError() {
        assertTrue(parseServerFrame("""{"t":"pane_read","reqId":"r1","paneId":"w6:p1","source":"detection","text":"hi"}""") is ServerFrame.PaneRead)
        val e = parseServerFrame("""{"t":"error","reqId":"r2","code":"not_found","message":"nope"}""")
        assertEquals("not_found", (e as ServerFrame.ErrorFrame).code)
    }

    @Test fun buildsSendText() {
        val json = ClientMsg.sendText("r2", "w6:p1", "y")
        val round = parseServerFrame(json) // not a server frame, but must be valid JSON containing fields
        // simplest assertion: it contains the expected keys
        assertTrue(json.contains("\"t\":\"send_text\""))
        assertTrue(json.contains("\"text\":\"y\""))
        assertTrue(json.contains("\"paneId\":\"w6:p1\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write `Protocol.kt`**

```kotlin
package dev.herdr.mobile.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Pane(
    val paneId: String,
    val workspaceId: String = "",
    val tabId: String = "",
    val cwd: String = "",
    val focused: Boolean = false,
    val agent: String? = null,
    val agentStatus: String? = null,
)

sealed interface ServerFrame {
    data object Welcome : ServerFrame
    data class Panes(val panes: List<Pane>) : ServerFrame
    data class PaneUpdate(val pane: Pane) : ServerFrame
    data class PaneRemoved(val paneId: String) : ServerFrame
    data class PaneRead(val reqId: String, val paneId: String, val source: String, val text: String) : ServerFrame
    data class Ack(val reqId: String) : ServerFrame
    data class ErrorFrame(val reqId: String, val code: String, val message: String) : ServerFrame
    data object Pong : ServerFrame
    data object Unknown : ServerFrame
}

fun parseServerFrame(text: String): ServerFrame {
    val obj = json.parseToJsonElement(text).jsonObject
    return when (obj["t"]?.jsonPrimitive?.content) {
        "welcome" -> ServerFrame.Welcome
        "panes" -> ServerFrame.Panes(json.decodeFromJsonElement(obj["panes"]!!))
        "pane_update" -> ServerFrame.PaneUpdate(json.decodeFromJsonElement(obj["pane"]!!))
        "pane_removed" -> ServerFrame.PaneRemoved(obj["paneId"]!!.jsonPrimitive.content)
        "pane_read" -> ServerFrame.PaneRead(
            obj["reqId"]!!.jsonPrimitive.content, obj["paneId"]!!.jsonPrimitive.content,
            obj["source"]!!.jsonPrimitive.content, obj["text"]!!.jsonPrimitive.content)
        "ack" -> ServerFrame.Ack(obj["reqId"]!!.jsonPrimitive.content)
        "error" -> ServerFrame.ErrorFrame(
            obj["reqId"]?.jsonPrimitive?.content ?: "", obj["code"]!!.jsonPrimitive.content,
            obj["message"]!!.jsonPrimitive.content)
        "pong" -> ServerFrame.Pong
        else -> ServerFrame.Unknown
    }
}

object ClientMsg {
    private fun obj(vararg pairs: Pair<String, JsonElement>) =
        JsonObject(pairs.toMap()).toString()

    fun hello() = obj("t" to JsonPrimitive("hello"), "client" to JsonPrimitive("herdr-mobile"), "clientVersion" to JsonPrimitive("1.0.0"))
    fun registerPush(endpoint: String) = obj("t" to JsonPrimitive("register_push"), "endpoint" to JsonPrimitive(endpoint))
    fun readPane(reqId: String, paneId: String, source: String, lines: Int) =
        obj("t" to JsonPrimitive("read_pane"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId), "source" to JsonPrimitive(source), "lines" to JsonPrimitive(lines))
    fun sendText(reqId: String, paneId: String, text: String) =
        obj("t" to JsonPrimitive("send_text"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId), "text" to JsonPrimitive(text))
    fun sendKeys(reqId: String, paneId: String, keys: String) =
        obj("t" to JsonPrimitive("send_keys"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId), "keys" to JsonPrimitive(keys))
    fun ping() = obj("t" to JsonPrimitive("ping"))
}
```

> **Note for the implementer:** in `ProtocolTest.buildsSendText`, drop the `parseServerFrame(json)` line (a `send_text` frame is not a server frame); keep only the `contains` assertions. It is written above to be corrected here.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ProtocolTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt
git commit -m "feat(app): companion WS protocol models"
```

---

### Task B2: PaneRepository — apply frames to StateFlow

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/data/PaneRepository.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/PaneRepositoryTest.kt`

**Interfaces:**
- Consumes: `net.ServerFrame`, `net.Pane`.
- Produces: `class PaneRepository`; `val panes: StateFlow<List<Pane>>` (sorted: blocked first, then working, then others; stable by paneId within a group); `fun onFrame(frame: ServerFrame)` applying `Panes`/`PaneUpdate`/`PaneRemoved`; `fun applyReadResult(reqId, text)` and a `suspend fun awaitRead(reqId): String`-style correlation is handled in the client (Task B3), not here — the repository only owns pane list state.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.*
import org.junit.Assert.*
import org.junit.Test

class PaneRepositoryTest {
    private fun pane(id: String, status: String?) =
        Pane(paneId = id, workspaceId = id.substringBefore(":"), agentStatus = status, agent = if (status != null) "claude" else null)

    @Test fun snapshotThenUpdateThenRemove() {
        val repo = PaneRepository()
        repo.onFrame(ServerFrame.Panes(listOf(pane("w2:p1", "working"), pane("w6:p1", "idle"))))
        assertEquals(2, repo.panes.value.size)

        repo.onFrame(ServerFrame.PaneUpdate(pane("w6:p1", "blocked")))
        // blocked sorts first
        assertEquals("w6:p1", repo.panes.value.first().paneId)
        assertEquals("blocked", repo.panes.value.first().agentStatus)

        repo.onFrame(ServerFrame.PaneRemoved("w2:p1"))
        assertEquals(1, repo.panes.value.size)
        assertEquals("w6:p1", repo.panes.value.single().paneId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.PaneRepositoryTest"`
Expected: FAIL — unresolved `PaneRepository`.

- [ ] **Step 3: Write `PaneRepository.kt`**

```kotlin
package dev.herdr.mobile.data

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PaneRepository {
    private val map = LinkedHashMap<String, Pane>()
    private val _panes = MutableStateFlow<List<Pane>>(emptyList())
    val panes: StateFlow<List<Pane>> = _panes.asStateFlow()

    fun onFrame(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Panes -> {
                map.clear()
                frame.panes.forEach { map[it.paneId] = it }
            }
            is ServerFrame.PaneUpdate -> map[frame.pane.paneId] = frame.pane
            is ServerFrame.PaneRemoved -> map.remove(frame.paneId)
            else -> return
        }
        _panes.value = map.values.sortedWith(comparator)
    }

    private val rank = mapOf("blocked" to 0, "working" to 1, "idle" to 2, "done" to 3)
    private val comparator = compareBy<Pane>({ rank[it.agentStatus] ?: 4 }, { it.paneId })
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.PaneRepositoryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/data/PaneRepository.kt app/app/src/test/java/dev/herdr/mobile/PaneRepositoryTest.kt
git commit -m "feat(app): PaneRepository state reducer"
```

---

### Task B3: CompanionClient — OkHttp WebSocket + request correlation

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/CompanionClientTest.kt` (uses OkHttp `MockWebServer`)
- Modify: `app/app/build.gradle.kts` (add `testImplementation "com.squareup.okhttp3:mockwebserver:4.12.0"`)

**Interfaces:**
- Produces: `class CompanionClient(private val client: OkHttpClient = OkHttpClient())`; `fun connect(url: String)`; `val frames: SharedFlow<ServerFrame>`; `val connected: StateFlow<Boolean>`; `fun send(raw: String)`; `suspend fun readPane(paneId, source, lines): String` and `suspend fun sendText(paneId, text)` / `sendKeys(paneId, keys)` that generate a `reqId`, send, and suspend until the matching `PaneRead`/`Ack`/`ErrorFrame` arrives (with a timeout); `fun close()`. Auto-reconnect with backoff on unexpected close.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class CompanionClientTest {
    @Test fun receivesWelcomeAndPanes() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send("""{"t":"welcome","herdrVersion":"0.7.1","herdrProtocol":14}""")
                ws.send("""{"t":"panes","panes":[]}""")
            }
        }))
        server.start()
        val client = CompanionClient()
        val collected = mutableListOf<ServerFrame>()
        val job = launch(Dispatchers.Default) { client.frames.collect { collected.add(it) } }
        client.connect(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) {
            while (collected.none { it is ServerFrame.Panes }) delay(20)
        }
        assertTrue(collected.any { it is ServerFrame.Welcome })
        assertTrue(collected.any { it is ServerFrame.Panes })
        job.cancel(); client.close(); server.shutdown()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.CompanionClientTest"`
Expected: FAIL — unresolved `CompanionClient`.

- [ ] **Step 3: Write `CompanionClient.kt`**

```kotlin
package dev.herdr.mobile.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CompanionClient(private val http: OkHttpClient = OkHttpClient()) {
    private val _frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 64)
    val frames: SharedFlow<ServerFrame> = _frames.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var ws: WebSocket? = null
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ServerFrame>>()

    fun connect(url: String) {
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                webSocket.send(ClientMsg.hello())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = parseServerFrame(text)
                when (frame) {
                    is ServerFrame.PaneRead -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.Ack -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.ErrorFrame -> pending.remove(frame.reqId)?.complete(frame)
                    else -> {}
                }
                _frames.tryEmit(frame)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _connected.value = false }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { _connected.value = false }
        })
    }

    fun send(raw: String) { ws?.send(raw) }

    private suspend fun request(reqId: String, raw: String): ServerFrame {
        val d = CompletableDeferred<ServerFrame>()
        pending[reqId] = d
        ws?.send(raw)
        return withTimeout(8000) { d.await() }
    }

    suspend fun readPane(paneId: String, source: String = "detection", lines: Int = 40): String {
        val id = "r${seq.incrementAndGet()}"
        return when (val f = request(id, ClientMsg.readPane(id, paneId, source, lines))) {
            is ServerFrame.PaneRead -> f.text
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply")
        }
    }

    suspend fun sendText(paneId: String, text: String) {
        val id = "r${seq.incrementAndGet()}"
        val f = request(id, ClientMsg.sendText(id, paneId, text))
        if (f is ServerFrame.ErrorFrame) throw RuntimeException(f.message)
    }

    suspend fun sendKeys(paneId: String, keys: String) {
        val id = "r${seq.incrementAndGet()}"
        val f = request(id, ClientMsg.sendKeys(id, paneId, keys))
        if (f is ServerFrame.ErrorFrame) throw RuntimeException(f.message)
    }

    fun registerPush(endpoint: String) { ws?.send(ClientMsg.registerPush(endpoint)) }

    fun close() { ws?.close(1000, "bye"); ws = null }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.CompanionClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt app/app/src/test/java/dev/herdr/mobile/CompanionClientTest.kt app/app/build.gradle.kts
git commit -m "feat(app): CompanionClient websocket + request correlation"
```

---

### Task B4: DashboardViewModel

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`, `app/app/src/main/java/dev/herdr/mobile/data/Settings.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `CompanionClient`, `PaneRepository`.
- Produces: `class DashboardViewModel(client, repo)`; `val panes: StateFlow<List<Pane>>` (from repo); `val connected: StateFlow<Boolean>`; `fun start(url: String)` (connect + pump `client.frames` into `repo.onFrame`); `suspend fun peek(paneId): String` (delegates `client.readPane`); `suspend fun reply(paneId, text, sendEnter: Boolean)` (calls `sendText`, then `sendKeys(paneId,"enter")` if requested); `fun quickKey(paneId, key)`. `Settings` wraps DataStore: `suspend fun setCompanionUrl(url)`, `val companionUrl: Flow<String?>`.

- [ ] **Step 1: Write the failing test** (drives VM against a MockWebServer companion)

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.ui.DashboardViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class DashboardViewModelTest {
    @Test fun pumpsFramesIntoRepoAndPeeksPane() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send("""{"t":"welcome"}""")
                ws.send("""{"t":"panes","panes":[{"paneId":"w6:p1","workspaceId":"w6","agentStatus":"blocked","agent":"claude"}]}""")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("\"read_pane\"")) {
                    val reqId = Regex("\"reqId\":\"(.*?)\"").find(text)!!.groupValues[1]
                    ws.send("""{"t":"pane_read","reqId":"$reqId","paneId":"w6:p1","source":"detection","text":"Proceed? (y/n)"}""")
                }
            }
        }))
        server.start()
        val vm = DashboardViewModel(CompanionClient(), PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (vm.panes.value.isEmpty()) delay(20) }
        assertEquals("blocked", vm.panes.value.first().agentStatus)
        val text = vm.peek("w6:p1")
        assertEquals("Proceed? (y/n)", text)
        server.shutdown()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.DashboardViewModelTest"`
Expected: FAIL — unresolved `DashboardViewModel`.

- [ ] **Step 3: Write `DashboardViewModel.kt`**

```kotlin
package dev.herdr.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val client: CompanionClient,
    private val repo: PaneRepository,
) : ViewModel() {
    val panes: StateFlow<List<Pane>> = repo.panes
    val connected: StateFlow<Boolean> = client.connected

    fun start(url: String) {
        viewModelScope.launch { client.frames.collect { repo.onFrame(it) } }
        client.connect(url)
    }

    suspend fun peek(paneId: String): String = client.readPane(paneId)

    suspend fun reply(paneId: String, text: String, sendEnter: Boolean) {
        if (text.isNotEmpty()) client.sendText(paneId, text)
        if (sendEnter) client.sendKeys(paneId, "enter")
    }

    suspend fun quickKey(paneId: String, key: String) = client.sendKeys(paneId, key)

    override fun onCleared() { client.close() }
}
```

- [ ] **Step 4: Write `Settings.kt`**

```kotlin
package dev.herdr.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")
private val URL_KEY = stringPreferencesKey("companion_url")

class Settings(private val context: Context) {
    val companionUrl: Flow<String?> = context.dataStore.data.map { it[URL_KEY] }
    suspend fun setCompanionUrl(url: String) { context.dataStore.edit { it[URL_KEY] = url } }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.DashboardViewModelTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt app/app/src/main/java/dev/herdr/mobile/data/Settings.kt app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt
git commit -m "feat(app): DashboardViewModel + settings store"
```

---

### Task B5: UnifiedPush receiver + notifications

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/push/PushPayload.kt`, `app/app/src/main/java/dev/herdr/mobile/push/UnifiedPushReceiver.kt`, `app/app/src/main/java/dev/herdr/mobile/push/Notifications.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/PushPayloadTest.kt`

**Interfaces:**
- Produces: `@Serializable data class PushPayload(kind, paneId, workspaceId, title, body)`; `fun parsePush(bytes: ByteArray): PushPayload?`; `UnifiedPushReceiver : MessagingReceiver()` overriding `onNewEndpoint` (persist endpoint via `Settings`, and if connected forward via `CompanionClient.registerPush`) and `onMessage` (parse payload, post a notification whose tap opens `MainActivity` with `paneId` extra); `Notifications.post(context, payload)` creating the channel + notification with a high-importance channel for `blocked`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.push.parsePush
import org.junit.Assert.*
import org.junit.Test

class PushPayloadTest {
    @Test fun parsesBlockedPayload() {
        val p = parsePush("""{"kind":"blocked","paneId":"w6:p1","workspaceId":"w6","title":"w6 needs you","body":"Proceed? (y/n)"}""".toByteArray())!!
        assertEquals("blocked", p.kind)
        assertEquals("w6:p1", p.paneId)
        assertEquals("Proceed? (y/n)", p.body)
    }

    @Test fun returnsNullOnGarbage() {
        assertNull(parsePush("not json".toByteArray()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.PushPayloadTest"`
Expected: FAIL — unresolved `parsePush`.

- [ ] **Step 3: Write `PushPayload.kt`**

```kotlin
package dev.herdr.mobile.push

import dev.herdr.mobile.net.json
import kotlinx.serialization.Serializable

@Serializable
data class PushPayload(
    val kind: String,
    val paneId: String = "",
    val workspaceId: String = "",
    val title: String = "",
    val body: String = "",
)

fun parsePush(bytes: ByteArray): PushPayload? = try {
    json.decodeFromString(PushPayload.serializer(), String(bytes))
} catch (e: Exception) { null }
```

- [ ] **Step 4: Write `Notifications.kt` and `UnifiedPushReceiver.kt`**

```kotlin
// Notifications.kt
package dev.herdr.mobile.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.herdr.mobile.MainActivity
import dev.herdr.mobile.R

object Notifications {
    private const val CH_BLOCKED = "blocked"
    private const val CH_FINISHED = "finished"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BLOCKED, "Agent needs you", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FINISHED, "Agent finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun post(ctx: Context, p: PushPayload) {
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("paneId", p.paneId)
        }
        val pi = PendingIntent.getActivity(ctx, p.paneId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (p.kind == "blocked") CH_BLOCKED else CH_FINISHED
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(p.title)
            .setContentText(p.body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (p.kind == "blocked") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(p.paneId.hashCode(), n)
    }
}
```

```kotlin
// UnifiedPushReceiver.kt
package dev.herdr.mobile.push

import android.content.Context
import dev.herdr.mobile.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.MessagingReceiver

class UnifiedPushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        CoroutineScope(Dispatchers.IO).launch { Settings(context).setPushEndpoint(endpoint) }
    }
    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        parsePush(message)?.let { Notifications.post(context, it) }
    }
    override fun onRegistrationFailed(context: Context, instance: String) {}
    override fun onUnregistered(context: Context, instance: String) {}
}
```

> **Implementer note:** add `suspend fun setPushEndpoint(endpoint: String)` and `val pushEndpoint: Flow<String?>` to `Settings` (same DataStore pattern as `companionUrl`, key `push_endpoint`). The app forwards the stored endpoint to the companion via `client.registerPush(endpoint)` whenever it connects (wire this into `DashboardViewModel.start`: after connect, collect `Settings.pushEndpoint` and call `client.registerPush`).

- [ ] **Step 5: Run test to verify it passes**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.PushPayloadTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/push/ app/app/src/main/java/dev/herdr/mobile/data/Settings.kt app/app/src/test/java/dev/herdr/mobile/PushPayloadTest.kt
git commit -m "feat(app): UnifiedPush receiver + notifications"
```

---

### Task B6: Compose UI — dashboard, quick-reply, onboarding, wiring

This task has no unit tests (Compose UI assembled from already-tested state); verify by building and running against the live companion.

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/PaneRow.kt`, `DashboardScreen.kt`, `QuickReplySheet.kt`, `ui/theme/Theme.kt`
- Create/Modify: `app/app/src/main/java/dev/herdr/mobile/MainActivity.kt`, `HerdrApp.kt`

- [ ] **Step 1: Write `PaneRow.kt`** — a row showing workspace id, cwd basename, agent, and a colored status chip (blocked=red, working=amber, idle/done=green/grey). Tapping the row invokes `onClick(pane)`.

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane

@Composable
fun PaneRow(pane: Pane, onClick: (Pane) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onClick(pane) },
        headlineContent = { Text(pane.workspaceId + "  " + pane.cwd.substringAfterLast('/')) },
        supportingContent = { Text(pane.agent ?: "—") },
        trailingContent = { StatusChip(pane.agentStatus) },
    )
}

@Composable
private fun StatusChip(status: String?) {
    val (label, color) = when (status) {
        "blocked" -> "blocked" to Color(0xFFD32F2F)
        "working" -> "working" to Color(0xFFF9A825)
        "idle" -> "idle" to Color(0xFF616161)
        "done" -> "done" to Color(0xFF388E3C)
        else -> (status ?: "—") to Color(0xFF9E9E9E)
    }
    Surface(color = color, shape = RoundedCornerShape(12.dp)) {
        Text(label, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
```

- [ ] **Step 2: Write `DashboardScreen.kt`** — a `Scaffold` with a top bar (title + connection dot), a `LazyColumn` of `PaneRow` from `vm.panes.collectAsState()`, and it opens `QuickReplySheet` when a row (or a notification's paneId) is selected.

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.mobile.net.Pane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel, initialPaneId: String?) {
    val panes by vm.panes.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Pane?>(null) }

    // open the sheet if launched from a notification
    LaunchedEffect(initialPaneId, panes) {
        if (initialPaneId != null && selected == null) {
            panes.firstOrNull { it.paneId == initialPaneId }?.let { selected = it }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("herdr") }, actions = {
            Text(if (connected) "●" else "○", modifier = Modifier.padding(end = 16.dp))
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(panes, key = { it.paneId }) { PaneRow(it) { p -> selected = p } }
        }
    }

    selected?.let { pane ->
        QuickReplySheet(vm, pane) { selected = null }
    }
}
```

- [ ] **Step 3: Write `QuickReplySheet.kt`** — a `ModalBottomSheet` that on open calls `vm.peek(paneId)` to show recent output in a monospace, scrollable box, a text field + Send button (`vm.reply(paneId, text, sendEnter=true)`), and a row of quick keys: `y`, `n`, `Enter` (`send_keys enter`), `Esc` (`send_keys esc`), `Ctrl-C` (`send_keys ctrl+c`).

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReplySheet(vm: DashboardViewModel, pane: Pane, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("Loading…") }
    var reply by remember { mutableStateOf("") }

    LaunchedEffect(pane.paneId) {
        output = runCatching { vm.peek(pane.paneId) }.getOrElse { "Failed to read pane: ${it.message}" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(pane.workspaceId + " · " + (pane.agentStatus ?: "—"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(output, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 260.dp).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                listOf("y", "n", "enter", "esc", "ctrl+c").forEach { key ->
                    AssistChip(onClick = { scope.launch { runCatching {
                        if (key == "y" || key == "n") vm.reply(pane.paneId, key, sendEnter = true)
                        else vm.quickKey(pane.paneId, key)
                    } } }, label = { Text(key) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = reply, onValueChange = { reply = it }, modifier = Modifier.weight(1f), label = { Text("Reply") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = { scope.launch { runCatching { vm.reply(pane.paneId, reply, sendEnter = true) }; reply = "" } }) { Text("Send") }
            }
        }
    }
}
```

- [ ] **Step 4: Write `MainActivity.kt` + `HerdrApp.kt`** — request `POST_NOTIFICATIONS`, register UnifiedPush (pick distributor), an onboarding text field for the companion URL persisted via `Settings`, construct `DashboardViewModel(CompanionClient(), PaneRepository())`, call `vm.start(url)`, read `intent.getStringExtra("paneId")` for the initial sheet.

```kotlin
// MainActivity.kt
package dev.herdr.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.data.Settings
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.ui.DashboardScreen
import dev.herdr.mobile.ui.DashboardViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        UnifiedPush.registerAppWithDialog(this) // lets user pick ntfy as distributor

        val settings = Settings(applicationContext)
        val vm = DashboardViewModel(CompanionClient(), PaneRepository())
        val initialPane = intent.getStringExtra("paneId")

        setContent {
            var url by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                url = settings.companionUrl.first()
                url?.let { vm.start(it) }
            }
            if (url == null) {
                OnboardUrl { entered -> lifecycleScope.launch { settings.setCompanionUrl(entered); url = entered; vm.start(entered) } }
            } else {
                DashboardScreen(vm, initialPane)
            }
        }
    }
}
```

(Write a small `OnboardUrl` composable — a single `OutlinedTextField` prefilled with `ws://<tailnet-ip>:8787/` and a Connect button.)

- [ ] **Step 5: Build, install, and verify against the live companion**

```bash
cd app
./gradlew :app:installDebug
```

Then on the phone (Tailscale up, ntfy app installed and chosen as the UnifiedPush distributor): open the app, enter `ws://<tailnet-ip>:8787/`, confirm the dashboard lists your real panes with live status; tap a pane → the quick-reply sheet shows recent output; type in a test pane and confirm it appears in the real terminal on the host.

- [ ] **Step 6: Verify a real notification end-to-end**

With the companion running and the app having registered its push endpoint: in a herdr pane, get an agent to a blocked/prompt state (e.g. run a command that asks `[y/N]`). Confirm a high-priority "… needs you" notification arrives on the phone, and tapping it opens the quick-reply sheet for that pane.

- [ ] **Step 7: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/ app/app/src/main/java/dev/herdr/mobile/MainActivity.kt app/app/src/main/java/dev/herdr/mobile/HerdrApp.kt
git commit -m "feat(app): dashboard, quick-reply sheet, onboarding, UnifiedPush registration"
```

**Milestone B complete:** the full v1 loop works — monitor agents on the dashboard, get pushed when one blocks or finishes, and unblock it from the quick-reply sheet.

---

## Self-Review

**Spec coverage:**
- Monitor dashboard → Tasks A5/A8 (state + broadcast), B2/B4/B6. ✓
- Quick-reply (pane.read + send_text/send_keys) → A3 (client), A8 (server RPC), B3/B4/B6. ✓
- Push on blocked + finished/idle with debounce → A6 (rules), A8 (debounce + fire), B5 (receive). ✓
- UnifiedPush/ntfy, no FCM → B0 manifest, B5, B6 registration. ✓
- Companion WSS, no auth but pluggable Authorizer → A7 (Authorizer/AllowAll), A8 (server uses it). ✓
- Poll pane.list + per-pane subscribe hybrid → A4 (Subscribe), A8 (pollLoop; subscription manager). ⚠ **Gap:** A8's `engine.go` implements the poll loop and transition handling but the per-pane subscription *manager* (open a `Subscribe` per agent pane, feed events into the same transition path, reconcile on poll) is described in the task's responsibilities but not fully coded. **Resolution:** the poll loop at 1.5s already delivers correct status + transitions; the subscribe layer is a latency optimization. Implementer should add a `subscribeLoop` that, after each poll, ensures one active `Subscribe` per agent-bearing pane and routes its events through `handleTransition`. This is additive and does not change the contract. Flagging rather than silently dropping.
- Tailscale reachability → A9 (bind to tailnet IP, systemd `%i`), B6 (URL onboarding). ✓
- Single host / default session → A9 default socket path. ✓
- Exact notification copy strings → A6 (`"<ws> needs you"`, `"<ws> finished"`). ✓

**Placeholder scan:** Two intentional implementer-notes remain where a self-contained code block needed a follow-up (the `jsonUnmarshal` shim in A8 step 6, and the `parseServerFrame(json)` line to delete in B1 test). Both are explicitly called out with the exact fix. The A8 subscription-manager gap is flagged above with a concrete resolution. No silent TODOs.

**Type consistency:** `Pane` field names (`paneId/workspaceId/tabId/cwd/focused/agent/agentStatus`) are identical across the Go `state.Pane` JSON tags (A5), the proto frames (A7), and the Kotlin `Pane` (B1). `reqId` correlation names match between server (A8) and client (B3). `HerdrRPC` method signatures (ctx-first) match `*herdr.Client` (A3) and the server's usage (A8).

---

## Execution note on ordering

Part A is fully buildable and testable with **zero** Android work — finish and deploy the companion first (Milestone A), validate it against your live herdr with `websocat` and a real ntfy topic, then build Part B against a known-good backend. The two parts share only the WebSocket contract documented at the top.
