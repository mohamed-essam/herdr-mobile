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
