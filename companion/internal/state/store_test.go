package state

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
)

func infos(p ...herdr.PaneInfo) []herdr.PaneInfo { return p }

func TestApplyDetectsNewChangedRemoved(t *testing.T) {
	s := NewStore()

	ch, tr := s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}))
	if len(ch) != 1 || ch[0].Kind != "update" {
		t.Fatalf("first apply want 1 update, got %+v", ch)
	}
	if len(tr) != 0 {
		t.Fatalf("first apply should not report transitions (no prior state), got %+v", tr)
	}

	// same state again -> no changes
	ch, _ = s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}))
	if len(ch) != 0 {
		t.Fatalf("unchanged apply want 0 changes, got %+v", ch)
	}

	// status change working -> blocked
	ch, tr = s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked"}))
	if len(ch) != 1 || ch[0].Kind != "update" || ch[0].Pane.AgentStatus != "blocked" {
		t.Fatalf("want blocked update, got %+v", ch)
	}
	if len(tr) != 1 || tr[0].From != "working" || tr[0].To != "blocked" || tr[0].WorkspaceID != "w6" {
		t.Fatalf("want working->blocked transition, got %+v", tr)
	}

	// pane disappears -> removed
	ch, _ = s.Apply(infos())
	if len(ch) != 1 || ch[0].Kind != "removed" || ch[0].PaneID != "w6:p1" {
		t.Fatalf("want removed, got %+v", ch)
	}
	// The removal names the workspace the pane was in.
	if ch[0].Pane.WorkspaceID != "w6" {
		t.Fatalf("removed change should carry the old pane, got %+v", ch[0])
	}
}

func TestApplyReportsAgentExit(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked"}))
	_, tr := s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", AgentStatus: "blocked"}))
	if len(tr) != 1 || !tr[0].AgentGone || tr[0].PaneID != "w6:p1" {
		t.Fatalf("agent leaving the pane should be a transition with AgentGone, got %+v", tr)
	}
	// A pane that never had an agent reports nothing.
	_, tr = s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", AgentStatus: "unknown"}))
	for _, x := range tr {
		if x.AgentGone {
			t.Fatalf("no agent to lose, got %+v", tr)
		}
	}
}

func TestToPaneCarriesTerminalID(t *testing.T) {
	s := NewStore()
	s.Apply([]herdr.PaneInfo{{PaneID: "w7:p2", TerminalID: "term_abc"}})
	got := s.Snapshot()
	if len(got) != 1 || got[0].TerminalID != "term_abc" {
		t.Fatalf("terminalId not carried into state.Pane: %+v", got)
	}
}

func TestApplyWorkspacesAndTabsChangeDetection(t *testing.T) {
	s := NewStore()

	ws := []herdr.WorkspaceInfo{{WorkspaceID: "w7", Label: "omega3", Number: 4, AgentStatus: "idle", PaneCount: 2, TabCount: 2}}
	if !s.ApplyWorkspaces(ws) {
		t.Fatal("first ApplyWorkspaces should report changed")
	}
	if s.ApplyWorkspaces(ws) {
		t.Fatal("unchanged ApplyWorkspaces should report not-changed")
	}
	if got := s.Workspaces(); len(got) != 1 || got[0].Label != "omega3" {
		t.Fatalf("bad workspaces snapshot: %+v", got)
	}

	// worktree pointer is carried through only when linked
	ws2 := []herdr.WorkspaceInfo{{WorkspaceID: "w5", Label: "wt", Number: 2,
		Worktree: &herdr.WorktreeInfo{RepoName: "ops", IsLinkedWorktree: true}}}
	if !s.ApplyWorkspaces(ws2) {
		t.Fatal("changed workspace list should report changed")
	}
	if got := s.Workspaces(); got[0].Worktree == nil || got[0].Worktree.RepoName != "ops" {
		t.Fatalf("worktree not carried: %+v", got[0])
	}

	tabs := []herdr.TabInfo{{TabID: "w7:t1", Label: "1", Number: 1, WorkspaceID: "w7"}}
	if !s.ApplyTabs(tabs) {
		t.Fatal("first ApplyTabs should report changed")
	}
	if s.ApplyTabs(tabs) {
		t.Fatal("unchanged ApplyTabs should report not-changed")
	}
	if got := s.Tabs(); len(got) != 1 || got[0].TabID != "w7:t1" {
		t.Fatalf("bad tabs snapshot: %+v", got)
	}
}

func TestStoreConcurrentApplyAndSnapshot(t *testing.T) {
	s := NewStore()
	done := make(chan struct{})
	go func() {
		for i := 0; i < 1000; i++ {
			s.Apply([]herdr.PaneInfo{{PaneID: "w1:p1", WorkspaceID: "w1", AgentStatus: "working"}})
		}
		close(done)
	}()
	for {
		select {
		case <-done:
			return
		default:
			_ = s.Snapshot()
		}
	}
}

func TestLastActivityTracking(t *testing.T) {
	s := NewStore()
	clock := int64(1000)
	s.now = func() int64 { return clock }

	// created → bump
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}))
	s.ApplyWorkspaces([]herdr.WorkspaceInfo{{WorkspaceID: "w6", Label: "herdr-mobile", Number: 2}})
	if got := s.Workspaces()[0].LastActivity; got != 1000 {
		t.Fatalf("created should stamp lastActivity=1000, got %d", got)
	}

	// focus-only change → NO bump
	clock = 2000
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working", Focused: true}))
	s.ApplyWorkspaces([]herdr.WorkspaceInfo{{WorkspaceID: "w6", Label: "herdr-mobile", Number: 2}})
	if got := s.Workspaces()[0].LastActivity; got != 1000 {
		t.Fatalf("focus-only change must not bump, want 1000 got %d", got)
	}

	// agentStatus transition → bump
	clock = 3000
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "blocked", Focused: true}))
	s.ApplyWorkspaces([]herdr.WorkspaceInfo{{WorkspaceID: "w6", Label: "herdr-mobile", Number: 2}})
	if got := s.Workspaces()[0].LastActivity; got != 3000 {
		t.Fatalf("transition should bump to 3000, got %d", got)
	}

	// pane removed → bump
	clock = 4000
	s.Apply(infos()) // w6:p1 disappears
	if s.lastActivity["w6"] != 4000 {
		t.Fatalf("removal should bump to 4000, got %d", s.lastActivity["w6"])
	}
}

func TestApplyWorkspacesChangesWhenOnlyLastActivityChanges(t *testing.T) {
	s := NewStore()
	clock := int64(1000)
	s.now = func() int64 { return clock }
	ws := []herdr.WorkspaceInfo{{WorkspaceID: "w6", Label: "herdr-mobile", Number: 2}}

	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", AgentStatus: "working"}))
	if !s.ApplyWorkspaces(ws) {
		t.Fatal("first ApplyWorkspaces should report changed")
	}
	// bump activity via a transition, same workspace list from herdr
	clock = 5000
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", AgentStatus: "blocked"}))
	if !s.ApplyWorkspaces(ws) {
		t.Fatal("lastActivity change alone should report changed (so it rebroadcasts)")
	}
}

func TestLastActivityPrunedWhenWorkspaceGone(t *testing.T) {
	s := NewStore()
	s.now = func() int64 { return 1000 }
	s.Apply(infos(herdr.PaneInfo{PaneID: "w6:p1", WorkspaceID: "w6", AgentStatus: "working"}))
	s.ApplyWorkspaces([]herdr.WorkspaceInfo{{WorkspaceID: "w6", Number: 1}})
	if s.lastActivity["w6"] == 0 {
		t.Fatal("precondition: w6 should have recorded activity")
	}
	// w6 disappears from herdr's workspace list; its recency entry must be pruned.
	s.ApplyWorkspaces([]herdr.WorkspaceInfo{{WorkspaceID: "w9", Number: 2}})
	if _, ok := s.lastActivity["w6"]; ok {
		t.Fatal("lastActivity for a workspace no longer reported should be pruned")
	}
}

func TestSetChatFlagsKnownPane(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"}))
	p, changed := s.SetChat("w1:p1", true)
	if !changed || !p.Chat {
		t.Fatalf("SetChat on: changed=%v pane=%+v", changed, p)
	}
	if _, again := s.SetChat("w1:p1", true); again {
		t.Fatal("setting the same value should report no change")
	}
	// herdr's next poll must not wipe the flag
	ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"}))
	if len(ch) != 0 || !s.Snapshot()[0].Chat {
		t.Fatalf("poll lost chat flag: changes=%+v snap=%+v", ch, s.Snapshot())
	}
	if p, changed := s.SetChat("w1:p1", false); !changed || p.Chat {
		t.Fatalf("SetChat off: %v %+v", changed, p)
	}
}

func TestSetChatBeforePaneAppears(t *testing.T) {
	s := NewStore()
	if _, changed := s.SetChat("w2:p1", true); changed {
		t.Fatal("unknown pane: nothing to broadcast yet")
	}
	ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w2:p1", WorkspaceID: "w2"}))
	if len(ch) != 1 || !ch[0].Pane.Chat {
		t.Fatalf("new pane should carry chat=true: %+v", ch)
	}
}

func TestRemovedPaneForgetsChat(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1"}))
	s.SetChat("w1:p1", true)
	s.Apply(infos())
	ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1"}))
	if ch[0].Pane.Chat {
		t.Fatal("a re-created pane id must not inherit the old chat flag")
	}
}

func TestSetSummaryFlagsKnownPaneAndSurvivesPoll(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"}))
	act := &Activity{Kind: "tool", Tool: "Bash", Text: "npm run build", TS: 5}
	ask := &Ask{ToolUseID: "t1", Questions: json.RawMessage(`[{"question":"Q?"}]`)}
	p, changed := s.SetSummary("w1:p1", Summary{Activity: act, Ask: ask})
	if !changed || p.Activity == nil || p.Activity.Tool != "Bash" || p.Ask == nil || p.Ask.ToolUseID != "t1" {
		t.Fatalf("SetSummary: changed=%v pane=%+v", changed, p)
	}
	if _, again := s.SetSummary("w1:p1", Summary{Activity: &Activity{Kind: "tool", Tool: "Bash", Text: "npm run build", TS: 5}, Ask: &Ask{ToolUseID: "t1", Questions: json.RawMessage(`[{"question":"Q?"}]`)}}); again {
		t.Fatal("an equal summary should report no change")
	}
	ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"}))
	if len(ch) != 0 || s.Snapshot()[0].Activity == nil || s.Snapshot()[0].Ask == nil {
		t.Fatalf("poll lost summary: changes=%+v snap=%+v", ch, s.Snapshot())
	}
	if p, changed := s.SetSummary("w1:p1", Summary{}); !changed || p.Activity != nil || p.Ask != nil {
		t.Fatalf("clear: %v %+v", changed, p)
	}
}

func TestSummaryJSONOmittedWhenEmpty(t *testing.T) {
	b, _ := json.Marshal(Pane{PaneID: "p"})
	if strings.Contains(string(b), "activity") || strings.Contains(string(b), "ask") {
		t.Fatalf("empty summary should be omitted: %s", b)
	}
	b, _ = json.Marshal(Pane{PaneID: "p", Activity: &Activity{Kind: "tool", Tool: "Bash", Text: "ls", TS: 7},
		Ask: &Ask{ToolUseID: "t", Questions: json.RawMessage(`[{"question":"Q?"}]`)}})
	want := `"activity":{"kind":"tool","tool":"Bash","text":"ls","ts":7},"ask":{"toolUseId":"t","questions":[{"question":"Q?"}]}`
	if !strings.Contains(string(b), want) {
		t.Fatalf("json = %s", b)
	}
}

func TestSetSummaryBeforePaneAppearsAndRemovedForgets(t *testing.T) {
	s := NewStore()
	if _, changed := s.SetSummary("w2:p1", Summary{Activity: &Activity{Kind: "text", Text: "hi"}}); changed {
		t.Fatal("unknown pane: nothing to broadcast yet")
	}
	ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w2:p1", WorkspaceID: "w2"}))
	if len(ch) != 1 || ch[0].Pane.Activity == nil {
		t.Fatalf("new pane should carry the summary: %+v", ch)
	}
	s.Apply(infos())
	ch, _ = s.Apply(infos(herdr.PaneInfo{PaneID: "w2:p1", WorkspaceID: "w2"}))
	if ch[0].Pane.Activity != nil {
		t.Fatal("a re-created pane id must not inherit the old summary")
	}
}

func TestSetSummaryBgRunning(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1"}))
	p, changed := s.SetSummary("w1:p1", Summary{BgRunning: 2})
	if !changed || p.BgRunning != 2 {
		t.Fatalf("bgRunning 2: changed=%v pane=%+v", changed, p)
	}
	if _, again := s.SetSummary("w1:p1", Summary{BgRunning: 2}); again {
		t.Fatal("same bgRunning should report no change")
	}
	if ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1"})); len(ch) != 0 || s.Snapshot()[0].BgRunning != 2 {
		t.Fatalf("poll lost bgRunning: %+v", ch)
	}
	if p, changed := s.SetSummary("w1:p1", Summary{}); !changed || p.BgRunning != 0 {
		t.Fatalf("clear: %v %+v", changed, p)
	}
	b, _ := json.Marshal(Pane{PaneID: "p"})
	if strings.Contains(string(b), "bgRunning") {
		t.Fatalf("bgRunning omitted at 0: %s", b)
	}
	b, _ = json.Marshal(Pane{PaneID: "p", BgRunning: 3})
	if !strings.Contains(string(b), `"bgRunning":3`) {
		t.Fatalf("json = %s", b)
	}
}

func TestSetSummaryContext(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"}))
	p, changed := s.SetSummary("w1:p1", Summary{Context: &Context{Percent: 61, Tokens: 122000, Window: 200000}})
	if !changed || p.Context == nil || p.Context.Percent != 61 {
		t.Fatalf("set: changed=%v pane=%+v", changed, p)
	}
	if _, again := s.SetSummary("w1:p1", Summary{Context: &Context{Percent: 61, Tokens: 122000, Window: 200000}}); again {
		t.Fatal("an equal context should report no change")
	}
	if ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"})); len(ch) != 0 || s.Snapshot()[0].Context == nil {
		t.Fatalf("poll lost context: %+v", s.Snapshot())
	}
	p, changed = s.SetSummary("w1:p1", Summary{})
	if !changed || p.Context != nil {
		t.Fatalf("clear: %v %+v", changed, p)
	}
	b, _ := json.Marshal(p)
	if strings.Contains(string(b), `"context"`) {
		t.Fatalf("nil context must be omitted: %s", b)
	}
}
