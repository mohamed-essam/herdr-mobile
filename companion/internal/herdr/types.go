package herdr

import "encoding/json"

type RPCError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *RPCError) Error() string { return e.Code + ": " + e.Message }

type Response struct {
	ID     string          `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  *RPCError       `json:"error"`
}

type PaneInfo struct {
	PaneID      string `json:"pane_id"`
	WorkspaceID string `json:"workspace_id"`
	TabID       string `json:"tab_id"`
	CWD         string `json:"cwd"`
	Focused     bool   `json:"focused"`
	Agent       string `json:"agent"`
	AgentStatus string `json:"agent_status"`
	TerminalID  string `json:"terminal_id"`
}

type paneListResult struct {
	Type  string     `json:"type"`
	Panes []PaneInfo `json:"panes"`
}

type WorktreeInfo struct {
	RepoName         string `json:"repo_name"`
	RepoRoot         string `json:"repo_root"`
	CheckoutPath     string `json:"checkout_path"`
	IsLinkedWorktree bool   `json:"is_linked_worktree"`
}

type WorkspaceInfo struct {
	WorkspaceID string        `json:"workspace_id"`
	Label       string        `json:"label"`
	Number      int           `json:"number"`
	ActiveTabID string        `json:"active_tab_id"`
	AgentStatus string        `json:"agent_status"`
	Focused     bool          `json:"focused"`
	PaneCount   int           `json:"pane_count"`
	TabCount    int           `json:"tab_count"`
	Worktree    *WorktreeInfo `json:"worktree,omitempty"`
}

type workspaceListResult struct {
	Type       string          `json:"type"`
	Workspaces []WorkspaceInfo `json:"workspaces"`
}

type TabInfo struct {
	TabID       string `json:"tab_id"`
	Label       string `json:"label"`
	Number      int    `json:"number"`
	WorkspaceID string `json:"workspace_id"`
	AgentStatus string `json:"agent_status"`
	Focused     bool   `json:"focused"`
	PaneCount   int    `json:"pane_count"`
}

type tabListResult struct {
	Type string    `json:"type"`
	Tabs []TabInfo `json:"tabs"`
}

type paneReadResult struct {
	Type string `json:"type"`
	Read struct {
		PaneID string `json:"pane_id"`
		Source string `json:"source"`
		Text   string `json:"text"`
	} `json:"read"`
}

// Event is a frame pushed on a subscription connection.
type Event struct {
	Type        string `json:"type"`
	PaneID      string `json:"pane_id"`
	AgentStatus string `json:"agent_status"`
}

// paneRef is the {pane_id, terminal_id} carried by create/split/start results.
type paneRef struct {
	PaneID     string `json:"pane_id"`
	TerminalID string `json:"terminal_id"`
}
type paneInfoResult struct {
	Pane paneRef `json:"pane"`
}
type rootPaneResult struct {
	RootPane paneRef `json:"root_pane"`
}
type agentStartedResult struct {
	Agent paneRef `json:"agent"`
}
type agentManifestsResult struct {
	Manifests []struct {
		Agent string `json:"agent"`
	} `json:"manifests"`
}

// WorktreeEntry is one entry from herdr's worktree.list (scoped to one repo).
// Branch and OpenWorkspaceID are Option in herdr and arrive absent (→ "")
// when None. Label is the repo name (same for every entry), so it is NOT a
// per-sibling label — resolve sibling labels from the workspace snapshot.
type WorktreeEntry struct {
	Path             string `json:"path"`
	Branch           string `json:"branch"`
	IsLinkedWorktree bool   `json:"is_linked_worktree"`
	OpenWorkspaceID  string `json:"open_workspace_id"`
	Label            string `json:"label"`
}

type worktreeListResult struct {
	Type      string          `json:"type"`
	Worktrees []WorktreeEntry `json:"worktrees"`
}
