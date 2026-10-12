package engine

import (
	"os"
	"path/filepath"
	"testing"
)

func TestEndpointSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	e := New(Config{SocketPath: filepath.Join(dir, "none.sock"), StateDir: dir})
	e.setEndpoint("https://push.example/abc")

	fi, err := os.Stat(filepath.Join(dir, "push-endpoint.json"))
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode = %v, want 0600", fi.Mode().Perm())
	}

	e2 := New(Config{SocketPath: filepath.Join(dir, "none.sock"), StateDir: dir})
	if e2.endpoint != "https://push.example/abc" {
		t.Fatalf("endpoint after restart = %q", e2.endpoint)
	}
}

func TestMalformedEndpointFileStartsEmpty(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "push-endpoint.json"), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	e := New(Config{SocketPath: filepath.Join(dir, "none.sock"), StateDir: dir})
	if e.endpoint != "" {
		t.Fatalf("endpoint = %q, want empty", e.endpoint)
	}
}

func TestNoStateDirKeepsEndpointInMemory(t *testing.T) {
	e := New(Config{SocketPath: filepath.Join(t.TempDir(), "none.sock")})
	e.setEndpoint("https://push.example/abc")
	if e.endpoint != "https://push.example/abc" {
		t.Fatalf("endpoint = %q", e.endpoint)
	}
}
