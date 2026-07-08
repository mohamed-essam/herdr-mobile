package state

import (
	"testing"

	"github.com/messam/herdr-mobile/companion/internal/herdr"
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
