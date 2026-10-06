package main

import (
	"context"
	"encoding/json"
	"flag"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
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
