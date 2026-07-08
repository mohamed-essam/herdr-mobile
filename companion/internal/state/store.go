package state

import (
	"reflect"
	"sync"

	"github.com/messam/herdr-mobile/companion/internal/herdr"
)

type Pane struct {
	PaneID      string `json:"paneId"`
	WorkspaceID string `json:"workspaceId"`
	TabID       string `json:"tabId"`
	CWD         string `json:"cwd"`
	Focused     bool   `json:"focused"`
	// omitempty so non-agent panes send no agent/agentStatus at all; the app
	// then decodes them as null and renders "—" rather than a blank cell.
	Agent       string `json:"agent,omitempty"`
	AgentStatus string `json:"agentStatus,omitempty"`
}

type Worktree struct {
	RepoName         string `json:"repoName,omitempty"`
	IsLinkedWorktree bool   `json:"isLinkedWorktree,omitempty"`
}

type Workspace struct {
	WorkspaceID string    `json:"workspaceId"`
	Label       string    `json:"label"`
	Number      int       `json:"number"`
	AgentStatus string    `json:"agentStatus,omitempty"`
	Focused     bool      `json:"focused"`
	PaneCount   int       `json:"paneCount"`
	TabCount    int       `json:"tabCount"`
	Worktree    *Worktree `json:"worktree,omitempty"`
}

type Tab struct {
	TabID       string `json:"tabId"`
	Label       string `json:"label"`
	Number      int    `json:"number"`
	WorkspaceID string `json:"workspaceId"`
	AgentStatus string `json:"agentStatus,omitempty"`
	Focused     bool   `json:"focused"`
	PaneCount   int    `json:"paneCount"`
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
	mu    sync.Mutex
	panes map[string]Pane

	workspaces []Workspace
	tabs       []Tab
}

func NewStore() *Store { return &Store{panes: map[string]Pane{}} }

func toPane(i herdr.PaneInfo) Pane {
	return Pane{PaneID: i.PaneID, WorkspaceID: i.WorkspaceID, TabID: i.TabID,
		CWD: i.CWD, Focused: i.Focused, Agent: i.Agent, AgentStatus: i.AgentStatus}
}

func (s *Store) Apply(infos []herdr.PaneInfo) ([]Change, []Transition) {
	s.mu.Lock()
	defer s.mu.Unlock()
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
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Pane, 0, len(s.panes))
	for _, p := range s.panes {
		out = append(out, p)
	}
	return out
}

func toWorkspace(i herdr.WorkspaceInfo) Workspace {
	var wt *Worktree
	if i.Worktree != nil && i.Worktree.IsLinkedWorktree {
		wt = &Worktree{RepoName: i.Worktree.RepoName, IsLinkedWorktree: true}
	}
	return Workspace{WorkspaceID: i.WorkspaceID, Label: i.Label, Number: i.Number,
		AgentStatus: i.AgentStatus, Focused: i.Focused, PaneCount: i.PaneCount,
		TabCount: i.TabCount, Worktree: wt}
}

func toTab(i herdr.TabInfo) Tab {
	return Tab{TabID: i.TabID, Label: i.Label, Number: i.Number, WorkspaceID: i.WorkspaceID,
		AgentStatus: i.AgentStatus, Focused: i.Focused, PaneCount: i.PaneCount}
}

// ApplyWorkspaces stores the list and reports whether it changed from the prior one.
func (s *Store) ApplyWorkspaces(infos []herdr.WorkspaceInfo) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := make([]Workspace, 0, len(infos))
	for _, i := range infos {
		next = append(next, toWorkspace(i))
	}
	if reflect.DeepEqual(s.workspaces, next) {
		return false
	}
	s.workspaces = next
	return true
}

func (s *Store) ApplyTabs(infos []herdr.TabInfo) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := make([]Tab, 0, len(infos))
	for _, i := range infos {
		next = append(next, toTab(i))
	}
	if reflect.DeepEqual(s.tabs, next) {
		return false
	}
	s.tabs = next
	return true
}

func (s *Store) Workspaces() []Workspace {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Workspace, len(s.workspaces))
	copy(out, s.workspaces)
	return out
}

func (s *Store) Tabs() []Tab {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Tab, len(s.tabs))
	copy(out, s.tabs)
	return out
}
