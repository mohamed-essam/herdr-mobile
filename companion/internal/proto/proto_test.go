package proto

import (
	"encoding/json"
	"testing"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
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

func TestParseClientTermFields(t *testing.T) {
	m, err := ParseClient([]byte(`{"t":"term_open","reqId":"r9","target":"w6:p1","cols":80,"rows":24}`))
	if err != nil {
		t.Fatal(err)
	}
	if m.T != "term_open" || m.Target != "w6:p1" || m.Cols != 80 || m.Rows != 24 || m.ReqID != "r9" {
		t.Fatalf("bad term_open parse: %+v", m)
	}
	m2, _ := ParseClient([]byte(`{"t":"term_input","termId":"t1","data":"aGk="}`))
	if m2.TermID != "t1" || m2.Data != "aGk=" {
		t.Fatalf("bad term_input parse: %+v", m2)
	}
}

func TestTermFrames(t *testing.T) {
	var got map[string]any

	json.Unmarshal(TermOpened("r9", "t1"), &got)
	if got["t"] != "term_opened" || got["reqId"] != "r9" || got["termId"] != "t1" {
		t.Fatalf("bad term_opened: %v", got)
	}

	json.Unmarshal(TermData("t1", "aGk="), &got)
	if got["t"] != "term_data" || got["termId"] != "t1" || got["data"] != "aGk=" {
		t.Fatalf("bad term_data: %v", got)
	}

	json.Unmarshal(TermExit("t1", 3, "takeover"), &got)
	if got["t"] != "term_exit" || got["termId"] != "t1" || got["code"].(float64) != 3 || got["reason"] != "takeover" {
		t.Fatalf("bad term_exit: %v", got)
	}

	json.Unmarshal(TermError("r9", "t1", "boom"), &got)
	if got["t"] != "term_error" || got["message"] != "boom" {
		t.Fatalf("bad term_error: %v", got)
	}
}

func TestWelcomeAdvertisesProtocol7(t *testing.T) {
	var got map[string]any
	json.Unmarshal(Welcome("0.7.1", 14), &got)
	if got["companionProtocol"].(float64) != 7 {
		t.Fatalf("want companionProtocol 7, got %v", got["companionProtocol"])
	}
}
