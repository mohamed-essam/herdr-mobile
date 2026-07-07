package pty

import (
	"bytes"
	"sync"
	"testing"
	"time"
)

// collect is a tiny thread-safe byte sink for onData.
type collect struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (c *collect) add(b []byte) { c.mu.Lock(); c.buf.Write(b); c.mu.Unlock() }
func (c *collect) string() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.buf.String()
}

func TestSessionEchoesInputAndData(t *testing.T) {
	got := &collect{}
	s, err := Start([]string{"cat"}, 80, 24, got.add, func(int) {})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.Write([]byte("hello\n")); err != nil {
		t.Fatal(err)
	}
	deadline := time.After(2 * time.Second)
	for !bytes.Contains([]byte(got.string()), []byte("hello")) {
		select {
		case <-deadline:
			t.Fatalf("never saw echoed input, got %q", got.string())
		case <-time.After(20 * time.Millisecond):
		}
	}
}

func TestSessionResizeReflectedInChild(t *testing.T) {
	got := &collect{}
	// `stty size` prints "<rows> <cols>" read from the controlling tty.
	s, err := Start([]string{"sh", "-c", "sleep 0.3; stty size"}, 100, 40, got.add, func(int) {})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.Resize(120, 50); err != nil {
		t.Fatal(err)
	}
	deadline := time.After(2 * time.Second)
	for !bytes.Contains([]byte(got.string()), []byte("50 120")) {
		select {
		case <-deadline:
			t.Fatalf("resize not reflected, got %q", got.string())
		case <-time.After(20 * time.Millisecond):
		}
	}
}

func TestSessionOnExitFires(t *testing.T) {
	code := make(chan int, 1)
	s, err := Start([]string{"sh", "-c", "exit 3"}, 80, 24, func([]byte) {}, func(c int) { code <- c })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	select {
	case c := <-code:
		if c != 3 {
			t.Fatalf("want exit code 3, got %d", c)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("onExit never fired")
	}
}
