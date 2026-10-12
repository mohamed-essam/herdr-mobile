package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"flag"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func readType(ctx context.Context, t *testing.T, c *websocket.Conn) string {
	t.Helper()
	_, b, err := c.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var f struct{ T string }
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatalf("frame %s: %v", b, err)
	}
	return f.T
}

func call(t *testing.T, method, url, body string) (int, string) {
	t.Helper()
	req, _ := http.NewRequest(method, url, strings.NewReader(body))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, url, err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, strings.TrimSpace(string(b))
}

// dial serves f's WS side and connects an app to it.
func dial(ctx context.Context, t *testing.T, f *fake) (*websocket.Conn, string) {
	t.Helper()
	ws := httptest.NewServer(f.wsHandler())
	t.Cleanup(ws.Close)
	wsURL := "ws" + strings.TrimPrefix(ws.URL, "http")
	c, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.CloseNow() })
	return c, wsURL
}

func TestFakeCompanionControlAPI(t *testing.T) {
	f, err := newFake("../../../e2e/scenarios", "one-idle-pane")
	if err != nil {
		t.Fatal(err)
	}
	ctl := httptest.NewServer(f.controlHandler())
	defer ctl.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, wsURL := dial(ctx, t, f)
	for _, want := range []string{"welcome", "panes", "workspaces", "tabs"} {
		if got := readType(ctx, t, c); got != want {
			t.Fatalf("connect frames: got %q, want %q", got, want)
		}
	}

	// GET /received returns the companion calls the app caused, answered as wsserver does.
	// hello and ping cause no call; wsserver answers both with pong.
	for _, step := range []struct{ msg, reply string }{
		{`{"t":"hello","client":"ios"}`, "pong"},
		{`{"t":"ping"}`, "pong"},
		{`{"t":"action","reqId":"a1","op":"rename","kind":"pane","id":"w1:p1","label":"build"}`, "action_result"},
		{`{"t":"register_push","endpoint":"https://push.example/abc"}`, "ack"},
	} {
		if err := c.Write(ctx, websocket.MessageText, []byte(step.msg)); err != nil {
			t.Fatal(err)
		}
		if got := readType(ctx, t, c); got != step.reply {
			t.Fatalf("%s: got %q, want %q", step.msg, got, step.reply)
		}
	}
	want := `[{"call":"RenamePane","args":["w1:p1","build"]},{"call":"RegisterPush","args":["https://push.example/abc"]}]`
	if _, got := call(t, "GET", ctl.URL+"/received", ""); got != want {
		t.Fatalf("received = %s, want %s", got, want)
	}

	// POST /emit sends a frame mid-flow; it must be JSON.
	if code, _ := call(t, "POST", ctl.URL+"/emit", `{"t":"pane_removed","paneId":"w1:p1"}`); code != http.StatusNoContent {
		t.Fatalf("emit: %d", code)
	}
	if got := readType(ctx, t, c); got != "pane_removed" {
		t.Fatalf("emitted frame: got %q", got)
	}
	if code, _ := call(t, "POST", ctl.URL+"/emit", `not json`); code != http.StatusBadRequest {
		t.Fatalf("emit non-JSON: %d", code)
	}

	// POST /stop drops the app and refuses new connections.
	call(t, "POST", ctl.URL+"/stop", "")
	if _, _, err := c.Read(ctx); err == nil {
		t.Fatal("client still connected after /stop")
	}
	if _, resp, err := websocket.Dial(ctx, wsURL, nil); err == nil || resp == nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("dial after /stop: err=%v resp=%v", err, resp)
	}

	// POST /scenario restarts the companion with an empty received log.
	if code, _ := call(t, "POST", ctl.URL+"/scenario", `{"name":"nope"}`); code != http.StatusBadRequest {
		t.Fatalf("unknown scenario: %d", code)
	}
	if code, body := call(t, "POST", ctl.URL+"/scenario", `{"name":"one-idle-pane"}`); code != http.StatusNoContent {
		t.Fatalf("scenario: %d %s", code, body)
	}
	c2, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatalf("dial after /scenario: %v", err)
	}
	defer c2.CloseNow()
	if _, got := call(t, "GET", ctl.URL+"/received", ""); got != `[]` {
		t.Fatalf("received after /scenario = %s", got)
	}
}

var update = flag.Bool("update", false, "rewrite the golden .frames.json files")

// TestScenarioFrames keeps e2e/scenarios/<name>.frames.json equal to the frames
// the fake sends an app on connect, read over a real WebSocket. The iOS tests
// decode every frame in them, so a proto or scenario change shows up there too.
// Refresh with: go test ./cmd/fake-companion -run TestScenarioFrames -update
func TestScenarioFrames(t *testing.T) {
	paths, err := filepath.Glob("../../../e2e/scenarios/*.json")
	if err != nil || len(paths) == 0 {
		t.Fatalf("no scenarios: %v", err)
	}
	for _, path := range paths {
		if strings.HasSuffix(path, ".frames.json") {
			continue
		}
		name := strings.TrimSuffix(filepath.Base(path), ".json")
		t.Run(name, func(t *testing.T) {
			f, err := newFake(filepath.Dir(path), name)
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			c, _ := dial(ctx, t, f)
			frames := []json.RawMessage{}
			for range 4 { // welcome + panes, workspaces, tabs
				_, b, err := c.Read(ctx)
				if err != nil {
					t.Fatal(err)
				}
				frames = append(frames, b)
			}
			// Then each chat's snapshot, so the app's decoder reads every scenario event.
			for _, key := range slices.Sorted(maps.Keys(f.current().Chats)) {
				paneID, agentID, _ := strings.Cut(key, "/")
				open, _ := json.Marshal(map[string]string{"t": "chat_open", "paneId": paneID, "agentId": agentID})
				if err := c.Write(ctx, websocket.MessageText, open); err != nil {
					t.Fatal(err)
				}
				_, b, err := c.Read(ctx)
				if err != nil {
					t.Fatal(err)
				}
				frames = append(frames, b)
			}
			got, _ := json.MarshalIndent(frames, "", "  ")
			golden := strings.TrimSuffix(path, ".json") + ".frames.json"
			if *update {
				if err := os.WriteFile(golden, append(got, '\n'), 0o644); err != nil {
					t.Fatal(err)
				}
			}
			want, err := os.ReadFile(golden)
			if err != nil || string(want) != string(got)+"\n" {
				t.Fatalf("%s is stale (%v); rerun with -update", golden, err)
			}
		})
	}
}

// The New Agent picker and the close cascade warning need real replies: the
// fake answers list_agents and close_impact from the scenario's agents and worktrees.
func TestFakeCompanionAnswersFromTheScenario(t *testing.T) {
	f, err := newFake("../../../e2e/scenarios", "dashboard")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _ := dial(ctx, t, f)
	for range 4 { // welcome + panes, workspaces, tabs
		readType(ctx, t, c)
	}
	for _, step := range []struct{ msg, reply string }{
		{`{"t":"list_agents","reqId":"g1"}`, `{"agents":["claude","codex","gemini"],"reqId":"g1","t":"agents"}`},
		{`{"t":"close_impact","reqId":"i2","workspaceId":"w1"}`,
			`{"alsoCloses":[{"workspaceId":"w2","label":"api-feature"}],"reqId":"i2","t":"close_impact","workspaceId":"w1"}`},
		{`{"t":"close_impact","reqId":"i3","workspaceId":"w3"}`, `{"alsoCloses":[],"reqId":"i3","t":"close_impact","workspaceId":"w3"}`},
	} {
		if err := c.Write(ctx, websocket.MessageText, []byte(step.msg)); err != nil {
			t.Fatal(err)
		}
		_, got, err := c.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if string(got) != step.reply {
			t.Fatalf("%s: got %s, want %s", step.msg, got, step.reply)
		}
	}
}

// Chat flows need real chats: the fake serves each scenario chat's snapshot (main or
// thread, else a missing thread), its older history, images, a send error, named frames
// through POST /play, and records chat_close and any GET /fetch/* (a request the app
// should never make).
func TestFakeCompanionServesScenarioChats(t *testing.T) {
	f, err := newFake("../../../e2e/scenarios", "chat")
	if err != nil {
		t.Fatal(err)
	}
	ctl := httptest.NewServer(f.controlHandler())
	defer ctl.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _ := dial(ctx, t, f)
	for range 4 { // welcome + panes, workspaces, tabs
		readType(ctx, t, c)
	}
	ask := func(msg string) map[string]any {
		t.Helper()
		if err := c.Write(ctx, websocket.MessageText, []byte(msg)); err != nil {
			t.Fatal(err)
		}
		_, b, err := c.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		if err := json.Unmarshal(b, &m); err != nil {
			t.Fatal(err)
		}
		return m
	}
	seqs := func(m map[string]any) []float64 {
		var out []float64
		for _, e := range m["events"].([]any) {
			out = append(out, e.(map[string]any)["seq"].(float64))
		}
		return out
	}

	snap := ask(`{"t":"chat_open","paneId":"w1:p1"}`)
	if snap["t"] != "chat_snapshot" || snap["hasMore"] != true || len(snap["agents"].([]any)) != 10 || len(snap["tasks"].([]any)) != 3 {
		t.Fatalf("main snapshot: %v", snap)
	}
	if got := seqs(snap); len(got) != 10 || got[0] != 5 {
		t.Fatalf("main snapshot seqs %v", got)
	}
	page := ask(`{"t":"chat_history","reqId":"h1","paneId":"w1:p1","epoch":1,"beforeSeq":5,"limit":2}`)
	if got := seqs(page); page["hasMore"] != true || len(got) != 2 || got[0] != 3 || got[1] != 4 {
		t.Fatalf("history page: %v", page)
	}
	if stale := ask(`{"t":"chat_history","reqId":"h2","paneId":"w1:p1","epoch":7,"beforeSeq":5,"limit":2}`); stale["stale"] != true {
		t.Fatalf("other epoch's page: %v", stale)
	}
	if th := ask(`{"t":"chat_open","paneId":"w1:p1","agentId":"ag1"}`); th["agentId"] != "ag1" || len(seqs(th)) != 4 || th["missing"] != nil {
		t.Fatalf("thread snapshot: %v", th)
	}
	if gone := ask(`{"t":"chat_open","paneId":"w1:p1","agentId":"ag2"}`); gone["missing"] != true {
		t.Fatalf("unknown thread: %v", gone)
	}
	if img := ask(`{"t":"chat_image","paneId":"w1:p1","id":"img-user"}`); img["mediaType"] != "image/png" || img["data"] == "" {
		t.Fatalf("image: %v", img)
	}
	if img := ask(`{"t":"chat_image","paneId":"w1:p1","id":"nope"}`); img["missing"] != true {
		t.Fatalf("unknown image: %v", img)
	}
	if res := ask(`{"t":"chat_send","reqId":"c1","paneId":"w4:p1","text":"go on"}`); res["ok"] != false || res["error"] != "outbox_full" {
		t.Fatalf("send to a failing chat: %v", res)
	}
	if res := ask(`{"t":"chat_send","reqId":"c2","paneId":"w3:p1","text":"add a test"}`); res["ok"] != true {
		t.Fatalf("send: %v", res)
	}

	if code, _ := call(t, "POST", ctl.URL+"/play", `{"name":"live"}`); code != http.StatusNoContent {
		t.Fatalf("play: %d", code)
	}
	if got := readType(ctx, t, c); got != "chat_event" {
		t.Fatalf("played frame: %q", got)
	}
	if code, _ := call(t, "POST", ctl.URL+"/play", `{"name":"nope"}`); code != http.StatusBadRequest {
		t.Fatalf("play unknown: %d", code)
	}
	if code, _ := call(t, "GET", ctl.URL+"/fetch/probe.png", ""); code != http.StatusNotFound {
		t.Fatalf("fetch: %d", code)
	}
	if err := c.Write(ctx, websocket.MessageText, []byte(`{"t":"chat_close","paneId":"w1:p1","agentId":"ag2"}`)); err != nil {
		t.Fatal(err)
	}
	var got string
	for deadline := time.Now().Add(2 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if _, got = call(t, "GET", ctl.URL+"/received", ""); strings.Contains(got, `"ChatClose","args":["w1:p1","ag2"]`) {
			break
		}
	}
	for _, want := range []string{
		`{"call":"ChatSubscribe","args":["w1:p1",""]}`,
		`{"call":"ChatHistory","args":["w1:p1","",1,5,2]}`,
		`{"call":"ChatSend","args":["w4:p1","go on"]}`,
		`{"call":"Fetch","args":["/fetch/probe.png"]}`,
		`{"call":"ChatClose","args":["w1:p1","ag2"]}`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("received lacks %s: %s", want, got)
		}
	}
}

// The test binary doubles as the fake's attach program (see attachArgs).
func TestMain(m *testing.M) {
	if target, control, ok := attachArgs(os.Args[1:]); ok {
		runAttach(target, control)
		return
	}
	os.Exit(m.Run())
}

// readFrame reads frames until one of type want, and returns it.
func readFrame(ctx context.Context, t *testing.T, c *websocket.Conn, want string) map[string]any {
	t.Helper()
	for {
		_, b, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("waiting for %s: %v", want, err)
		}
		var f map[string]any
		json.Unmarshal(b, &f)
		if f["t"] == want {
			return f
		}
	}
}

// waitReceived polls GET /received until it holds want.
func waitReceived(ctx context.Context, t *testing.T, url, want string) {
	t.Helper()
	for {
		_, got := call(t, "GET", url+"/received", "")
		if strings.Contains(got, want) {
			return
		}
		if ctx.Err() != nil {
			t.Fatalf("received never held %s: %s", want, got)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// Terminal flows: term_open runs the fake's own attach program, never herdr. It prints a
// banner, reports what the app typed as TermInput, and ends with POST /term-exit's code
// (now, or on the next attach when none is live). A create answers with the scenario's
// "created" pane.
func TestFakeCompanionTerminal(t *testing.T) {
	f, err := newFake("../../../e2e/scenarios", "terminal")
	if err != nil {
		t.Fatal(err)
	}
	ctl := httptest.NewServer(f.controlHandler())
	defer ctl.Close()
	f.setControlURL(ctl.URL)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, _ := dial(ctx, t, f)
	write := func(msg string) {
		if err := c.Write(ctx, websocket.MessageText, []byte(msg)); err != nil {
			t.Fatal(err)
		}
	}

	write(`{"t":"create","reqId":"n1","what":"workspace"}`)
	if got := readFrame(ctx, t, c, "created"); got["terminalId"] != "term_9" || got["paneId"] != "w3:p1" {
		t.Fatalf("created = %v", got)
	}

	write(`{"t":"term_open","reqId":"r1","target":"term_1","cols":54,"rows":33}`)
	termID := readFrame(ctx, t, c, "term_opened")["termId"].(string)
	waitReceived(ctx, t, ctl.URL, `{"call":"TermOpen","args":["term_1"]}`)
	var out []byte
	for !strings.Contains(string(out), "fake attach term_1") {
		b, _ := base64.StdEncoding.DecodeString(readFrame(ctx, t, c, "term_data")["data"].(string))
		out = append(out, b...)
	}

	write(`{"t":"term_input","termId":"` + termID + `","data":"` + base64.StdEncoding.EncodeToString([]byte("\x1b[A")) + `"}`)
	waitReceived(ctx, t, ctl.URL, `{"call":"TermInput","args":["term_1","\u001b[A"]}`)
	write(`{"t":"term_input","termId":"` + termID + `","data":"` + base64.StdEncoding.EncodeToString([]byte{3}) + `"}`)
	waitReceived(ctx, t, ctl.URL, `{"call":"TermInput","args":["term_1","\u0003"]}`)

	if code, body := call(t, "POST", ctl.URL+"/term-exit", `{"code":3}`); code != http.StatusNoContent {
		t.Fatalf("term-exit: %d %s", code, body)
	}
	if got := readFrame(ctx, t, c, "term_exit"); got["code"] != 3.0 || got["reason"] != "error" {
		t.Fatalf("term_exit = %v", got)
	}

	// With no attach live, the next one ends at once.
	call(t, "POST", ctl.URL+"/term-exit", `{"code":0}`)
	write(`{"t":"term_open","reqId":"r2","target":"term_1","cols":54,"rows":33}`)
	if got := readFrame(ctx, t, c, "term_exit"); got["code"] != 0.0 || got["reason"] != "ended" {
		t.Fatalf("second term_exit = %v", got)
	}
}
