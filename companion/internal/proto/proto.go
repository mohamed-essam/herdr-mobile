package proto

import (
	"encoding/json"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

type ClientMsg struct {
	T             string `json:"t"`
	Client        string `json:"client"`
	ClientVersion string `json:"clientVersion"`
	Endpoint      string `json:"endpoint"`
	ReqID         string `json:"reqId"`
	PaneID        string `json:"paneId"`
	AgentID       string `json:"agentId"` // chat thread (protocol 10); empty = main stream
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
	ID            string `json:"id"` // also the chat_image id
	Label         string `json:"label"`

	Epoch     int               `json:"epoch"`
	BeforeSeq int               `json:"beforeSeq"`
	Limit     int               `json:"limit"`
	ToolUseID string            `json:"toolUseId"`
	Answers   map[string]string `json:"answers"`

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
	return must(map[string]any{"t": "welcome", "herdrVersion": version, "herdrProtocol": protocol, "companionProtocol": 10})
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

// ChatSnapshot builds chat_snapshot. A thread snapshot (AgentID set) carries
// agentId and, when the thread is unknown, missing. A main snapshot carries
// agents (always an array) and tasks (omitted when nil); a protocol-9 app
// ignores both.
func ChatSnapshot(s chatbridge.Snapshot) []byte {
	events := s.Events
	if events == nil {
		events = []chatbridge.Entry{}
	}
	m := map[string]any{"t": "chat_snapshot", "paneId": s.PaneID, "epoch": s.Epoch, "state": s.State, "events": events, "hasMore": s.HasMore}
	if s.AgentID != "" {
		m["agentId"] = s.AgentID
	}
	if s.Missing {
		m["missing"] = true
	}
	if s.AgentID == "" {
		agents := s.Agents
		if agents == nil {
			agents = []json.RawMessage{}
		}
		m["agents"] = agents
		if s.Tasks != nil {
			m["tasks"] = s.Tasks
		}
	}
	return must(m)
}

func ChatEvent(paneID, agentID string, epoch int, e chatbridge.Entry) []byte {
	m := map[string]any{"t": "chat_event", "paneId": paneID, "epoch": epoch, "seq": e.Seq, "event": e.Event}
	if agentID != "" {
		m["agentId"] = agentID
	}
	return must(m)
}

// ChatAgent announces a new or changed agent summary on the main stream.
func ChatAgent(paneID string, agent json.RawMessage) []byte {
	return must(map[string]any{"t": "chat_agent", "paneId": paneID, "agent": agent})
}

// ChatAgentRemoved announces that an agent left the pane's list.
func ChatAgentRemoved(paneID, agentID string) []byte {
	return must(map[string]any{"t": "chat_agent", "paneId": paneID, "agentId": agentID, "removed": true})
}

// ChatTasks carries the pane's whole background-task list.
func ChatTasks(paneID string, tasks json.RawMessage) []byte {
	return must(map[string]any{"t": "chat_tasks", "paneId": paneID, "tasks": tasks})
}

func ChatState(paneID, st string) []byte {
	return must(map[string]any{"t": "chat_state", "paneId": paneID, "state": st})
}

func ChatSendResult(reqID string, ok bool, errCode string) []byte {
	m := map[string]any{"t": "chat_send_result", "reqId": reqID, "ok": ok}
	if errCode != "" {
		m["error"] = errCode
	}
	return must(m)
}

// ChatHistoryPage answers chat_history. events is never null; stale marks a
// request whose epoch is no longer the pane's current one. agentID is echoed
// when the page belongs to a thread.
func ChatHistoryPage(reqID, paneID, agentID string, epoch int, events []chatbridge.Entry, hasMore, stale bool) []byte {
	if events == nil {
		events = []chatbridge.Entry{}
	}
	m := map[string]any{"t": "chat_history_page", "reqId": reqID, "paneId": paneID, "epoch": epoch, "events": events, "hasMore": hasMore}
	if agentID != "" {
		m["agentId"] = agentID
	}
	if stale {
		m["stale"] = true
	}
	return must(m)
}

func ChatImageData(paneID, id, mediaType, data string) []byte {
	return must(map[string]any{"t": "chat_image_data", "paneId": paneID, "id": id, "mediaType": mediaType, "data": data})
}

func ChatImageMissing(paneID, id string) []byte {
	return must(map[string]any{"t": "chat_image_data", "paneId": paneID, "id": id, "missing": true})
}

func ChatAnswerResult(reqID string, ok bool, errCode string) []byte {
	m := map[string]any{"t": "chat_answer_result", "reqId": reqID, "ok": ok}
	if errCode != "" {
		m["error"] = errCode
	}
	return must(m)
}
