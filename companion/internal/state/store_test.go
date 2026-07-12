package state

import (
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
