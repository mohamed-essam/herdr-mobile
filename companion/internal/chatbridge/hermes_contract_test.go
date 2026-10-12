package chatbridge

import (
	"encoding/json"
	"net/http"
	"os"
	"strings"
	"testing"
)

// testdata/hermes_sync.json is the /sync body the herdr-hermes plugin's Loop
// emits for a first turn (hermes/herdr-hermes/tests/test_contract.py asserts
// the plugin still produces exactly this). The companion must accept it.
func TestHermesSyncBodyIsAccepted(t *testing.T) {
	body, err := os.ReadFile("testdata/hermes_sync.json")
	if err != nil {
		t.Fatal(err)
	}
	h := NewHub(nil)
	_, c := serve(t, h)
	res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(string(body)))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("status %d", res.StatusCode)
	}
	var reply map[string]json.RawMessage
	if err := json.NewDecoder(res.Body).Decode(&reply); err != nil {
		t.Fatal(err)
	}
	if _, ok := reply["resync"]; ok {
		t.Fatalf("unexpected resync in reply: %s", reply["resync"])
	}
	snap, _, cancel := h.Subscribe("w1:p1", "")
	defer cancel()
	if snap.Agent != "hermes" {
		t.Fatalf("agent = %q, want hermes", snap.Agent)
	}
	if snap.State != "working" {
		t.Fatalf("state = %q, want working", snap.State)
	}
	if len(snap.Events) != 1 {
		t.Fatalf("events = %+v, want one user_text", snap.Events)
	}
	var ev struct{ Type, Text string }
	if err := json.Unmarshal(snap.Events[0].Event, &ev); err != nil {
		t.Fatal(err)
	}
	if ev.Type != "user_text" || ev.Text != "hi" {
		t.Fatalf("event = %s", snap.Events[0].Event)
	}
}
