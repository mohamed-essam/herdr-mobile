package chatbridge

import "testing"

func TestHelloAgentReachesSnapshot(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", raws(`{"type":"hello","sessionId":"s","cwd":"/x","agent":"hermes"}`))
	snap, _, cancel := h.Subscribe("p", "")
	defer cancel()
	if snap.Agent != "hermes" {
		t.Fatalf("want agent hermes, got %q", snap.Agent)
	}
}

func TestHelloWithoutAgentIsClaude(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", raws(`{"type":"hello","sessionId":"s","cwd":"/x"}`))
	snap, _, cancel := h.Subscribe("p", "")
	defer cancel()
	if snap.Agent != "" {
		t.Fatalf("want empty agent, got %q", snap.Agent)
	}
}

func TestHelloAgentResetsOnNewSession(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s1", raws(`{"type":"hello","sessionId":"s1","agent":"hermes"}`))
	h.Sync("p", "s2", raws(`{"type":"hello","sessionId":"s2"}`))
	snap, _, cancel := h.Subscribe("p", "")
	defer cancel()
	if snap.Agent != "" {
		t.Fatalf("new session without agent must clear it, got %q", snap.Agent)
	}
}
