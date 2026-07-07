package proto

import (
	"encoding/json"
	"testing"

	"github.com/messam/herdr-mobile/companion/internal/state"
)

func TestParseClientMsg(t *testing.T) {
	m, err := ParseClient([]byte(`{"t":"send_text","reqId":"r2","paneId":"w6:p1","text":"y"}`))
	if err != nil {
		t.Fatal(err)
	}
	if m.T != "send_text" || m.PaneID != "w6:p1" || m.Text != "y" || m.ReqID != "r2" {
		t.Fatalf("bad parse: %+v", m)
	}
}

func TestPanesSnapshotFrame(t *testing.T) {
	b := PanesSnapshot([]state.Pane{{PaneID: "w6:p1", AgentStatus: "working"}})
	var got map[string]any
	json.Unmarshal(b, &got)
	if got["t"] != "panes" {
		t.Fatalf("want t=panes, got %v", got["t"])
	}
	panes := got["panes"].([]any)
	if len(panes) != 1 {
		t.Fatalf("want 1 pane in snapshot, got %d", len(panes))
	}
}

func TestErrorFrameCarriesReqID(t *testing.T) {
	b := ErrorFrame("r2", "not_found", "pane not found")
	var got map[string]any
	json.Unmarshal(b, &got)
	if got["t"] != "error" || got["reqId"] != "r2" || got["code"] != "not_found" {
		t.Fatalf("bad error frame: %v", got)
	}
}
