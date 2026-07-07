package state

import "github.com/messam/herdr-mobile/companion/internal/herdr"

type Pane struct {
	PaneID      string `json:"paneId"`
	WorkspaceID string `json:"workspaceId"`
	TabID       string `json:"tabId"`
	CWD         string `json:"cwd"`
	Focused     bool   `json:"focused"`
	Agent       string `json:"agent"`
	AgentStatus string `json:"agentStatus"`
}

type Change struct {
	Kind   string `json:"-"` // "update" | "removed"
	Pane   Pane   `json:"pane,omitempty"`
	PaneID string `json:"paneId,omitempty"`
}

type Transition struct {
	PaneID, WorkspaceID, From, To string
}

type Store struct {
	panes map[string]Pane
}

func NewStore() *Store { return &Store{panes: map[string]Pane{}} }

func toPane(i herdr.PaneInfo) Pane {
	return Pane{PaneID: i.PaneID, WorkspaceID: i.WorkspaceID, TabID: i.TabID,
		CWD: i.CWD, Focused: i.Focused, Agent: i.Agent, AgentStatus: i.AgentStatus}
}

func (s *Store) Apply(infos []herdr.PaneInfo) ([]Change, []Transition) {
	var changes []Change
	var transitions []Transition
	seen := map[string]bool{}
	for _, i := range infos {
		np := toPane(i)
		seen[np.PaneID] = true
		old, existed := s.panes[np.PaneID]
		if !existed {
			s.panes[np.PaneID] = np
			changes = append(changes, Change{Kind: "update", Pane: np})
			continue
		}
		if old != np {
			s.panes[np.PaneID] = np
			changes = append(changes, Change{Kind: "update", Pane: np})
		}
		if old.AgentStatus != np.AgentStatus {
			transitions = append(transitions, Transition{PaneID: np.PaneID,
				WorkspaceID: np.WorkspaceID, From: old.AgentStatus, To: np.AgentStatus})
		}
	}
	for id := range s.panes {
		if !seen[id] {
			delete(s.panes, id)
			changes = append(changes, Change{Kind: "removed", PaneID: id})
		}
	}
	return changes, transitions
}

func (s *Store) Snapshot() []Pane {
	out := make([]Pane, 0, len(s.panes))
	for _, p := range s.panes {
		out = append(out, p)
	}
	return out
}
