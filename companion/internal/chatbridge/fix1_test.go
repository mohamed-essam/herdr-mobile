package chatbridge

import (
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestLivenessLastDeliveredMatchesLive(t *testing.T) {
	for iter := 0; iter < 200; iter++ {
		c := &fakeClock{t: time.Unix(1000, 0)}
		h := NewHub(c.now)
		var mu sync.Mutex
		last := false
		h.SetOnLiveness(func(_ string, live bool) { mu.Lock(); last = live; mu.Unlock() })
		h.Sync("p", "s", nil)
		c.add(10 * time.Second)
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); h.Tick() }()
		go func() { defer wg.Done(); h.Sync("p", "s", nil) }()
		wg.Wait()
		h.Tick()
		mu.Lock()
		got := last
		mu.Unlock()
		if got != h.Live("p") {
			t.Fatalf("iter %d: last delivered %v but Live=%v", iter, got, h.Live("p"))
		}
	}
}

func TestLivenessNotifyReadsCurrentTruth(t *testing.T) {
	// A stale "dead" decision must not be delivered once the pane is live again.
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	var got []bool
	h.SetOnLiveness(func(_ string, live bool) { got = append(got, live) })
	h.Sync("p", "s", nil)
	c.add(10 * time.Second)
	h.mu.Lock()
	h.panes["p"].live = false // Tick decided dead...
	h.mu.Unlock()
	h.Sync("p", "s", nil) // ...but a Sync revived it first
	h.notify("p")         // Tick's late delivery
	if len(got) == 0 || got[len(got)-1] != true || !h.Live("p") {
		t.Fatalf("delivered %v, live=%v", got, h.Live("p"))
	}
}

func TestOutboxClearedWhenPaneGoesDead(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	h.Sync("p", "s", nil)
	if err := h.Send("p", "stale"); err != nil {
		t.Fatal(err)
	}
	c.add(6 * time.Second)
	h.Tick()
	if out := h.Sync("p", "s2", nil); len(out) != 0 {
		t.Fatalf("stale outbox delivered: %+v", out)
	}
}

func TestOutboxClearedOnSessionChange(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s1", []json.RawMessage{raw(`{"type":"hello","sessionId":"s1","cwd":"/x"}`)})
	h.Send("p", "for s1")
	out := h.Sync("p", "s2", []json.RawMessage{raw(`{"type":"hello","sessionId":"s2","cwd":"/x"}`)})
	if len(out) != 0 {
		t.Fatalf("message for old session delivered: %+v", out)
	}
	// Same session re-hello keeps the outbox; payload id wins over request id.
	h.Send("p", "for s2")
	out = h.Sync("p", "other", []json.RawMessage{raw(`{"type":"hello","sessionId":"s2"}`)})
	if len(out) != 1 {
		t.Fatalf("same-session hello dropped messages: %+v", out)
	}
	// Hello without payload id falls back to the request's.
	h.Send("p", "x")
	out = h.Sync("p", "s3", []json.RawMessage{raw(`{"type":"hello"}`)})
	if len(out) != 0 {
		t.Fatalf("request-id session change not detected: %+v", out)
	}
}

func TestListenExistingDirNotChmodded(t *testing.T) {
	dir := t.TempDir()
	os.Chmod(dir, 0o755)
	if l, err := Listen(filepath.Join(dir, "chat.sock")); err == nil {
		l.Close()
		t.Fatal("0755 dir must be rejected")
	}
	fi, _ := os.Stat(dir)
	if fi.Mode().Perm() != 0o755 {
		t.Fatalf("existing dir mode changed to %v", fi.Mode().Perm())
	}
	os.Chmod(dir, 0o700)
	l, err := Listen(filepath.Join(dir, "chat.sock"))
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	fi, _ = os.Stat(dir)
	if fi.Mode().Perm() != 0o700 {
		t.Fatalf("existing dir mode changed to %v", fi.Mode().Perm())
	}
}

func TestListenRejectsWritableDir(t *testing.T) {
	dir := t.TempDir()
	os.Chmod(dir, 0o777)
	if l, err := Listen(filepath.Join(dir, "chat.sock")); err == nil {
		l.Close()
		t.Fatal("expected error for 0777 dir")
	}
}

func TestListenCreatesDir0700(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "new")
	l, err := Listen(filepath.Join(dir, "chat.sock"))
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	fi, _ := os.Stat(dir)
	if fi.Mode().Perm() != 0o700 {
		t.Fatalf("new dir mode %v", fi.Mode().Perm())
	}
}

func TestListenReplacesStaleSocket(t *testing.T) {
	dir := t.TempDir()
	os.Chmod(dir, 0o700)
	path := filepath.Join(dir, "chat.sock")
	old, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	old.(*net.UnixListener).SetUnlinkOnClose(false)
	old.Close()
	if _, err := os.Lstat(path); err != nil {
		t.Fatal("stale socket should remain")
	}
	l, err := Listen(path)
	if err != nil {
		t.Fatal(err)
	}
	l.Close()
}

func TestListenRefusesRegularFile(t *testing.T) {
	dir0 := t.TempDir()
	os.Chmod(dir0, 0o700)
	path := filepath.Join(dir0, "chat.sock")
	os.WriteFile(path, []byte("precious"), 0o600)
	if l, err := Listen(path); err == nil {
		l.Close()
		t.Fatal("expected error")
	}
	if b, _ := os.ReadFile(path); string(b) != "precious" {
		t.Fatal("file was touched")
	}
}

func TestListenRefusesLiveListener(t *testing.T) {
	dir0 := t.TempDir()
	os.Chmod(dir0, 0o700)
	path := filepath.Join(dir0, "chat.sock")
	live, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer live.Close()
	if l, err := Listen(path); err == nil {
		l.Close()
		t.Fatal("expected error: another companion listening")
	}
}

func TestDropResetsLivenessNotification(t *testing.T) {
	h := NewHub(nil)
	var got []bool
	h.SetOnLiveness(func(_ string, live bool) { got = append(got, live) })
	h.Sync("p", "s", nil)
	h.Drop("p")
	h.Sync("p", "s", nil)
	if len(got) != 2 || !got[0] || !got[1] {
		t.Fatalf("callbacks = %v, want [true true]", got)
	}
}
