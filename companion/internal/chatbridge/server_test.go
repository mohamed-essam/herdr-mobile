package chatbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func unixClient(path string) *http.Client {
	return &http.Client{Transport: &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", path)
		},
	}}
}

func serve(t *testing.T, h *Hub) (string, *http.Client) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "sub", "chat.sock")
	l, err := Listen(path)
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: h.Handler()}
	go srv.Serve(l)
	t.Cleanup(func() { srv.Close() })
	return path, unixClient(path)
}

func TestSocketModesAndStaleRemoval(t *testing.T) {
	path := filepath.Join(t.TempDir(), "d", "chat.sock")
	os.MkdirAll(filepath.Dir(path), 0o700)
	old, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	old.(*net.UnixListener).SetUnlinkOnClose(false)
	old.Close() // leaves a stale socket file behind
	l, err := Listen(path)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	fi, _ := os.Stat(path)
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("socket mode %v", fi.Mode().Perm())
	}
	di, _ := os.Stat(filepath.Dir(path))
	if di.Mode().Perm() != 0o700 {
		t.Fatalf("dir mode %v", di.Mode().Perm())
	}
}

func TestSyncRoundTrip(t *testing.T) {
	h := NewHub(nil)
	_, c := serve(t, h)
	body := `{"paneId":"w1:p1","sessionId":"s","events":[{"type":"user_text","uuid":"u","text":"hi"}]}`
	res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 200 {
		t.Fatalf("status %d", res.StatusCode)
	}
	if !h.Live("w1:p1") {
		t.Fatal("sync should mark the pane live")
	}
	h.Send("w1:p1", "from phone")
	res, _ = c.Post("http://chat/sync", "application/json", strings.NewReader(`{"paneId":"w1:p1","sessionId":"s","events":[]}`))
	var got struct{ Messages []OutMsg }
	json.NewDecoder(res.Body).Decode(&got)
	if len(got.Messages) != 1 || got.Messages[0].Text != "from phone" {
		t.Fatalf("messages = %+v", got.Messages)
	}
}

// The mod sends v2 traffic (agentId, agent/tasks controls) only to a
// companion that announces it, so every /sync reply carries threads: true.
func TestSyncAnnouncesThreads(t *testing.T) {
	_, c := serve(t, NewHub(nil))
	res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(`{"paneId":"p","sessionId":"s","events":[]}`))
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.NewDecoder(res.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	if got["threads"] != true {
		t.Fatalf("/sync reply %v lacks threads:true", got)
	}
}

func TestSyncRejectsBadRequests(t *testing.T) {
	_, c := serve(t, NewHub(nil))
	for _, body := range []string{`not json`, `{"sessionId":"s","events":[]}`} {
		res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != 400 {
			t.Fatalf("body %q: status %d", body, res.StatusCode)
		}
	}
	res, _ := c.Get("http://chat/sync")
	if res.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("GET status %d", res.StatusCode)
	}
}

func TestSyncBodyLimit(t *testing.T) {
	_, c := serve(t, NewHub(nil))
	big := bytes.Repeat([]byte("a"), maxBody+1)
	body := `{"paneId":"p","sessionId":"s","events":[{"type":"user_text","uuid":"u","text":"` + string(big) + `"}]}`
	res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 400 {
		t.Fatalf("oversized body: status %d", res.StatusCode)
	}
}

func TestSocketPathRules(t *testing.T) {
	t.Setenv("HERDR_MOBILE_CHAT_SOCK", "/explicit.sock")
	if SocketPath() != "/explicit.sock" {
		t.Fatal(SocketPath())
	}
	t.Setenv("HERDR_MOBILE_CHAT_SOCK", "")
	t.Setenv("XDG_RUNTIME_DIR", "/run/user/7")
	if SocketPath() != "/run/user/7/herdr-mobile/chat.sock" {
		t.Fatal(SocketPath())
	}
	t.Setenv("XDG_RUNTIME_DIR", "")
	if SocketPath() != "" {
		t.Fatal(SocketPath())
	}
}
