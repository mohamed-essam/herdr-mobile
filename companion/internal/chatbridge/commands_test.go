package chatbridge

import (
	"encoding/json"
	"testing"
	"time"
)

func TestCommandsControl(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s")})
	_, ch, c := h.Subscribe("p", "")
	defer c()
	cmds := `[{"name":"compact","description":"Clear history","source":"builtin"}]`
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"commands","commands":` + cmds + `}`)})
	us := drain(ch)
	if len(us) != 1 || us[0].Kind != "commands" || string(us[0].Commands) != cmds {
		t.Fatalf("commands updates: %+v", us)
	}
	snap, _, c2 := h.Subscribe("p", "")
	c2()
	if string(snap.Commands) != cmds {
		t.Fatalf("snapshot commands %s", snap.Commands)
	}
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"commands","commands":` + cmds + `}`), raw(`{"type":"commands","commands":{"name":"x"}}`), raw(`{"type":"commands"}`)})
	if us := drain(ch); len(us) != 0 {
		t.Fatalf("identical or non-array commands fanned: %+v", us)
	}
	// A new history keeps the list: it belongs to the pane, not the epoch.
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot","events":[]}`)})
	snap, _, c3 := h.Subscribe("p", "")
	c3()
	if string(snap.Commands) != cmds {
		t.Fatalf("commands after a swap %s", snap.Commands)
	}
}

func TestCommandOutputIsAChatEvent(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s")})
	_, ch, c := h.Subscribe("p", "")
	defer c()
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"command_output","uuid":"cmd-m1","command":"/compact","text":"Compacted","ts":5}`)})
	us := drain(ch)
	if len(us) != 1 || us[0].Kind != "event" {
		t.Fatalf("updates: %+v", us)
	}
	if a := activityOf("command_output", raw(`{"command":"/compact","text":"Compacted","ts":5}`), time.Unix(1000, 0)); a == nil || a.Kind != "user" || a.Text != "/compact" {
		t.Fatalf("activity %+v", a)
	}
}
