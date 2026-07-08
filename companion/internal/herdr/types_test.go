package herdr

import (
	"encoding/json"
	"testing"
)

func TestUnmarshalPaneListResult(t *testing.T) {
	raw := `{"id":"a","result":{"type":"pane_list","panes":[
	  {"pane_id":"w6:p1","workspace_id":"w6","tab_id":"w6:t1","focused":true,
	   "cwd":"/home/me/proj","agent":"claude","agent_status":"working","revision":0}]}}`
	var resp Response
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.Error != nil {
		t.Fatalf("unexpected error: %+v", resp.Error)
	}
	var res paneListResult
	if err := json.Unmarshal(resp.Result, &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Panes) != 1 {
		t.Fatalf("want 1 pane, got %d", len(res.Panes))
	}
	p := res.Panes[0]
	if p.PaneID != "w6:p1" || p.Agent != "claude" || p.AgentStatus != "working" || !p.Focused {
		t.Fatalf("bad pane: %+v", p)
	}
}

func TestPaneInfoParsesTerminalID(t *testing.T) {
	raw := `{"type":"pane_list","panes":[{"pane_id":"w7:p2","terminal_id":"term_abc","agent_status":"unknown"}]}`
	var res paneListResult
	if err := json.Unmarshal([]byte(raw), &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Panes) != 1 || res.Panes[0].TerminalID != "term_abc" {
		t.Fatalf("terminal_id not parsed: %+v", res.Panes)
	}
}

func TestUnmarshalRPCError(t *testing.T) {
	var resp Response
	if err := json.Unmarshal([]byte(`{"id":"a","error":{"code":"not_found","message":"pane not found"}}`), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.Error == nil || resp.Error.Code != "not_found" {
		t.Fatalf("want not_found error, got %+v", resp.Error)
	}
}
