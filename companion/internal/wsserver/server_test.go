package wsserver

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/messam/herdr-mobile/companion/internal/state"
)

// stubRPC satisfies HerdrRPC without touching herdr.
type stubRPC struct{}

func (stubRPC) ReadPane(context.Context, string, string, int) (string, error) { return "", nil }
func (stubRPC) SendText(context.Context, string, string) error                { return nil }
func (stubRPC) SendKeys(context.Context, string, string) error                { return nil }

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
	s := NewServer(AllowAll{}, stubRPC{})
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
	s := NewServer(AllowAll{}, stubRPC{})
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
	s := NewServer(AllowAll{}, stubRPC{})
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
	s := NewServer(AllowAll{}, stubRPC{})
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

func TestInitialSnapshotIncludesWorkspacesAndTabs(t *testing.T) {
	s := NewServer(AllowAll{}, stubRPC{})
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
	if welcome["companionProtocol"].(float64) != 3 {
		t.Fatalf("want companionProtocol 3, got %v", welcome["companionProtocol"])
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
