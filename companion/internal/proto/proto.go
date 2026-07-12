package proto

import (
	"encoding/json"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

type ClientMsg struct {
	T             string `json:"t"`
	Client        string `json:"client"`
	ClientVersion string `json:"clientVersion"`
	Endpoint      string `json:"endpoint"`
	ReqID         string `json:"reqId"`
	PaneID        string `json:"paneId"`
	Source        string `json:"source"`
	Lines         int    `json:"lines"`
	Text          string `json:"text"`
	Keys          string `json:"keys"`
	TermID        string `json:"termId"`
	Target        string `json:"target"`
	Cols          int    `json:"cols"`
	Rows          int    `json:"rows"`
	Data          string `json:"data"`
	Op            string `json:"op"`
	Kind          string `json:"kind"`
	ID            string `json:"id"`
	Label         string `json:"label"`

	What        string   `json:"what"`
	AgentName   string   `json:"agentName"`
	Argv        []string `json:"argv"`
	Direction   string   `json:"direction"`
	Dest        string   `json:"dest"`
	WorkspaceID string   `json:"workspaceId"`
	TabID       string   `json:"tabId"`
}

func ParseClient(b []byte) (ClientMsg, error) {
	var m ClientMsg
	err := json.Unmarshal(b, &m)
	return m, err
}

func must(v any) []byte { b, _ := json.Marshal(v); return b }

func Welcome(version string, protocol int) []byte {
	return must(map[string]any{"t": "welcome", "herdrVersion": version, "herdrProtocol": protocol, "companionProtocol": 7})
}
func PanesSnapshot(p []state.Pane) []byte {
	return must(map[string]any{"t": "panes", "panes": p})
}
func WorkspacesSnapshot(w []state.Workspace) []byte {
	return must(map[string]any{"t": "workspaces", "workspaces": w})
}
func TabsSnapshot(tabs []state.Tab) []byte {
	return must(map[string]any{"t": "tabs", "tabs": tabs})
}
func PaneUpdate(p state.Pane) []byte { return must(map[string]any{"t": "pane_update", "pane": p}) }
func PaneRemoved(id string) []byte   { return must(map[string]any{"t": "pane_removed", "paneId": id}) }
func PaneRead(reqID, paneID, source, text string) []byte {
	return must(map[string]any{"t": "pane_read", "reqId": reqID, "paneId": paneID, "source": source, "text": text})
}
func Ack(reqID string) []byte { return must(map[string]any{"t": "ack", "reqId": reqID}) }
func ErrorFrame(reqID, code, message string) []byte {
	return must(map[string]any{"t": "error", "reqId": reqID, "code": code, "message": message})
}
func ActionResult(reqID string, ok bool, message string) []byte {
	m := map[string]any{"t": "action_result", "reqId": reqID, "ok": ok}
	if message != "" {
		m["error"] = message
	}
	return must(m)
}
func Created(reqID string, ok bool, paneID, terminalID, message string) []byte {
	m := map[string]any{"t": "created", "reqId": reqID, "ok": ok}
	if paneID != "" {
		m["paneId"] = paneID
	}
	if terminalID != "" {
		m["terminalId"] = terminalID
	}
	if message != "" {
		m["error"] = message
	}
	return must(m)
}

func Agents(reqID string, names []string) []byte {
	return must(map[string]any{"t": "agents", "reqId": reqID, "agents": names})
}

type AlsoClose struct {
	WorkspaceID string `json:"workspaceId"`
	Label       string `json:"label"`
}

// CloseImpact reports which sibling workspaces herdr will also close when the
// target workspace is closed. alsoCloses is always a present array (never null).
func CloseImpact(reqID, workspaceID string, alsoCloses []AlsoClose) []byte {
	if alsoCloses == nil {
		alsoCloses = []AlsoClose{}
	}
	return must(map[string]any{"t": "close_impact", "reqId": reqID, "workspaceId": workspaceID, "alsoCloses": alsoCloses})
}

func Pong() []byte { return must(map[string]any{"t": "pong"}) }
func TermOpened(reqID, termID string) []byte {
	return must(map[string]any{"t": "term_opened", "reqId": reqID, "termId": termID})
}
func TermData(termID, dataB64 string) []byte {
	return must(map[string]any{"t": "term_data", "termId": termID, "data": dataB64})
}
func TermExit(termID string, code int, reason string) []byte {
	return must(map[string]any{"t": "term_exit", "termId": termID, "code": code, "reason": reason})
}
func TermError(reqID, termID, message string) []byte {
	return must(map[string]any{"t": "term_error", "reqId": reqID, "termId": termID, "message": message})
}
