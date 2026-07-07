package proto

import (
	"encoding/json"

	"github.com/messam/herdr-mobile/companion/internal/state"
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
}

func ParseClient(b []byte) (ClientMsg, error) {
	var m ClientMsg
	err := json.Unmarshal(b, &m)
	return m, err
}

func must(v any) []byte { b, _ := json.Marshal(v); return b }

func Welcome(version string, protocol int) []byte {
	return must(map[string]any{"t": "welcome", "herdrVersion": version, "herdrProtocol": protocol, "companionProtocol": 2})
}
func PanesSnapshot(p []state.Pane) []byte {
	return must(map[string]any{"t": "panes", "panes": p})
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
func Pong() []byte { return must(map[string]any{"t": "pong"}) }
func TermOpened(reqID, termID string) []byte {
	return must(map[string]any{"t": "term_opened", "reqId": reqID, "termId": termID})
}
func TermData(termID, dataB64 string) []byte {
	return must(map[string]any{"t": "term_data", "termId": termID, "data": dataB64})
}
func TermExit(termID string, code int) []byte {
	return must(map[string]any{"t": "term_exit", "termId": termID, "code": code})
}
func TermError(reqID, termID, message string) []byte {
	return must(map[string]any{"t": "term_error", "reqId": reqID, "termId": termID, "message": message})
}
