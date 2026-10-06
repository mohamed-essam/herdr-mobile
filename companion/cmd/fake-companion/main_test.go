package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
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

func TestFakeCompanionControlAPI(t *testing.T) {
	f, err := newFake("../../../e2e/scenarios", "one-idle-pane")
	if err != nil {
		t.Fatal(err)
	}
	ws := httptest.NewServer(f.wsHandler())
	defer ws.Close()
	ctl := httptest.NewServer(f.controlHandler())
	defer ctl.Close()
	wsURL := "ws" + strings.TrimPrefix(ws.URL, "http")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	for _, want := range []string{"welcome", "panes", "workspaces", "tabs"} {
		if got := readType(ctx, t, c); got != want {
			t.Fatalf("connect frames: got %q, want %q", got, want)
		}
	}

	// GET /received returns what the app sent.
	if err := c.Write(ctx, websocket.MessageText, []byte(`{"t":"read_pane","paneId":"w1:p1"}`)); err != nil {
		t.Fatal(err)
	}
	var got string
	for i := 0; i < 50 && got != `[{"t":"read_pane","paneId":"w1:p1"}]`; i++ {
		time.Sleep(10 * time.Millisecond)
		_, got = call(t, "GET", ctl.URL+"/received", "")
	}
	if got != `[{"t":"read_pane","paneId":"w1:p1"}]` {
		t.Fatalf("received = %s", got)
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

func TestFakeCompanionAnswersHelloAndPingWithPong(t *testing.T) {
	f, err := newFake("../../../e2e/scenarios", "one-idle-pane")
	if err != nil {
		t.Fatal(err)
	}
	ws := httptest.NewServer(f.wsHandler())
	defer ws.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ws.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	for range 4 { // welcome + snapshots
		readType(ctx, t, c)
	}

	for _, msg := range []string{`{"t":"hello","client":"ios"}`, `{"t":"ping"}`} {
		if err := c.Write(ctx, websocket.MessageText, []byte(msg)); err != nil {
			t.Fatal(err)
		}
		if got := readType(ctx, t, c); got != "pong" {
			t.Fatalf("%s: got %q, want pong", msg, got)
		}
	}
}
