package engine

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

func paneByID(t *testing.T, e *Engine, id string) state.Pane {
	t.Helper()
	for _, p := range e.store.Snapshot() {
		if p.PaneID == id {
			return p
		}
	}
	t.Fatalf("pane %s not in store", id)
	return state.Pane{}
}

func TestChatSummaryFlowsIntoPaneStateAndSurvivesPoll(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetPanes([]herdr.PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}})
	e := New(Config{SocketPath: f.SocketPath(), ListenAddr: "127.0.0.1:0"})
	e.pollOnce(context.Background())

	e.hub.Sync("w6:p1", "s", []json.RawMessage{
		json.RawMessage(`{"type":"hello","sessionId":"s"}`),
		json.RawMessage(`{"type":"tool_use","uuid":"t","toolUseId":"t","tool":"Bash","summary":"npm run build","ts":5}`),
		json.RawMessage(`{"type":"question","uuid":"q","toolUseId":"q","questions":[{"question":"Layout?"}],"ts":6}`),
	})
	p := paneByID(t, e, "w6:p1")
	if !p.Chat || p.Activity == nil || p.Activity.Kind != "question" || p.Activity.Text != "Layout?" || p.Ask == nil || p.Ask.ToolUseID != "q" {
		t.Fatalf("pane = %+v act=%+v ask=%+v", p, p.Activity, p.Ask)
	}

	e.pollOnce(context.Background()) // herdr's refresh must keep the summary
	if p := paneByID(t, e, "w6:p1"); p.Activity == nil || p.Ask == nil {
		t.Fatalf("poll wiped the summary: %+v", p)
	}

	e.hub.Sync("w6:p1", "s", []json.RawMessage{json.RawMessage(`{"type":"tool_result","toolUseId":"q","isError":false,"preview":"ok"}`)})
	if p := paneByID(t, e, "w6:p1"); p.Ask != nil || p.Activity == nil {
		t.Fatalf("answered question still asked: %+v", p)
	}
}
