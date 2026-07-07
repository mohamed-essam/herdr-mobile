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
}

type paneListResult struct {
	Type  string     `json:"type"`
	Panes []PaneInfo `json:"panes"`
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
