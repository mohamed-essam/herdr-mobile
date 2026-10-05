package proto

import (
	"encoding/json"
	"testing"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

func TestParseClientMsg(t *testing.T) {
	m, err := ParseClient([]byte(`{"t":"send_text","reqId":"r2","paneId":"w6:p1","text":"y"}`))
	if err != nil {
		t.Fatal(err)
	}
	if m.T != "send_text" || m.PaneID != "w6:p1" || m.Text != "y" || m.ReqID != "r2" {
		t.Fatalf("bad parse: %+v", m)
	}
}

func TestPanesSnapshotFrame(t *testing.T) {
	b := PanesSnapshot([]state.Pane{{PaneID: "w6:p1", AgentStatus: "working"}})
	var got map[string]any
	json.Unmarshal(b, &got)
	if got["t"] != "panes" {
		t.Fatalf("want t=panes, got %v", got["t"])
	}
	panes := got["panes"].([]any)
	if len(panes) != 1 {
		t.Fatalf("want 1 pane in snapshot, got %d", len(panes))
	}
}

func TestErrorFrameCarriesReqID(t *testing.T) {
	b := ErrorFrame("r2", "not_found", "pane not found")
	var got map[string]any
	json.Unmarshal(b, &got)
	if got["t"] != "error" || got["reqId"] != "r2" || got["code"] != "not_found" {
		t.Fatalf("bad error frame: %v", got)
	}
}

func TestParseClientTermFields(t *testing.T) {
	m, err := ParseClient([]byte(`{"t":"term_open","reqId":"r9","target":"w6:p1","cols":80,"rows":24}`))
	if err != nil {
		t.Fatal(err)
	}
	if m.T != "term_open" || m.Target != "w6:p1" || m.Cols != 80 || m.Rows != 24 || m.ReqID != "r9" {
		t.Fatalf("bad term_open parse: %+v", m)
	}
	m2, _ := ParseClient([]byte(`{"t":"term_input","termId":"t1","data":"aGk="}`))
	if m2.TermID != "t1" || m2.Data != "aGk=" {
		t.Fatalf("bad term_input parse: %+v", m2)
	}
}

func TestTermFrames(t *testing.T) {
	var got map[string]any

	json.Unmarshal(TermOpened("r9", "t1"), &got)
	if got["t"] != "term_opened" || got["reqId"] != "r9" || got["termId"] != "t1" {
		t.Fatalf("bad term_opened: %v", got)
	}

	json.Unmarshal(TermData("t1", "aGk="), &got)
	if got["t"] != "term_data" || got["termId"] != "t1" || got["data"] != "aGk=" {
		t.Fatalf("bad term_data: %v", got)
	}

	json.Unmarshal(TermExit("t1", 3, "takeover"), &got)
	if got["t"] != "term_exit" || got["termId"] != "t1" || got["code"].(float64) != 3 || got["reason"] != "takeover" {
		t.Fatalf("bad term_exit: %v", got)
	}

	json.Unmarshal(TermError("r9", "t1", "boom"), &got)
	if got["t"] != "term_error" || got["message"] != "boom" {
		t.Fatalf("bad term_error: %v", got)
	}
}

func TestWelcomeAdvertisesProtocol10(t *testing.T) {
	var got map[string]any
	json.Unmarshal(Welcome("0.7.1", 14), &got)
	if got["companionProtocol"].(float64) != 10 {
		t.Fatalf("want companionProtocol 10, got %v", got["companionProtocol"])
	}
}

func TestChatFrames(t *testing.T) {
	var m map[string]any
	json.Unmarshal(ChatSnapshot(chatbridge.Snapshot{PaneID: "w1:p1", Epoch: 3, State: "idle"}), &m)
	if m["t"] != "chat_snapshot" || m["epoch"].(float64) != 3 || m["events"] == nil {
		t.Fatalf("snapshot: %v", m)
	}
	json.Unmarshal(ChatEvent("w1:p1", "", 3, chatbridge.Entry{Seq: 7, Event: json.RawMessage(`{"type":"user_text","uuid":"u","text":"hi"}`)}), &m)
	ev := m["event"].(map[string]any)
	if m["t"] != "chat_event" || m["seq"].(float64) != 7 || ev["text"] != "hi" {
		t.Fatalf("event: %v", m)
	}
	m = nil
	json.Unmarshal(ChatSendResult("c1", false, "no_mod"), &m)
	if m["ok"] != false || m["error"] != "no_mod" {
		t.Fatalf("send result: %v", m)
	}
	m = nil
	json.Unmarshal(ChatSendResult("c2", true, ""), &m)
	if _, has := m["error"]; has {
		t.Fatalf("ok result must omit error: %v", m)
	}
}

func TestChatSnapshotCarriesHasMore(t *testing.T) {
	var got map[string]any
	if err := json.Unmarshal(ChatSnapshot(chatbridge.Snapshot{PaneID: "p", HasMore: true}), &got); err != nil {
		t.Fatal(err)
	}
	if got["hasMore"] != true {
		t.Fatalf("hasMore: %v", got)
	}
}

func TestChatHistoryPageNeverNullEvents(t *testing.T) {
	var got map[string]any
	json.Unmarshal(ChatHistoryPage("r", "p", "", 2, nil, false, true), &got)
	if got["t"] != "chat_history_page" || got["stale"] != true || got["hasMore"] != false {
		t.Fatalf("%v", got)
	}
	if evs, ok := got["events"].([]any); !ok || len(evs) != 0 {
		t.Fatalf("events: %v", got["events"])
	}
	got = nil
	json.Unmarshal(ChatHistoryPage("r", "p", "", 2, nil, true, false), &got)
	if _, has := got["stale"]; has {
		t.Fatalf("stale must be omitted when false: %v", got)
	}
}

func TestChatImageAndAnswerFrames(t *testing.T) {
	var got map[string]any
	json.Unmarshal(ChatImageData("p", "i#0", "image/png", "QQ=="), &got)
	if got["t"] != "chat_image_data" || got["mediaType"] != "image/png" || got["data"] != "QQ==" {
		t.Fatalf("%v", got)
	}
	got = nil
	json.Unmarshal(ChatImageMissing("p", "i#0"), &got)
	if got["missing"] != true || got["id"] != "i#0" {
		t.Fatalf("%v", got)
	}
	got = nil
	json.Unmarshal(ChatAnswerResult("r", false, "empty"), &got)
	if got["t"] != "chat_answer_result" || got["ok"] != false || got["error"] != "empty" {
		t.Fatalf("%v", got)
	}
}

func TestChatSnapshotProtocol10Shapes(t *testing.T) {
	var m map[string]any
	json.Unmarshal(ChatSnapshot(chatbridge.Snapshot{PaneID: "p"}), &m)
	if a, ok := m["agents"].([]any); !ok || len(a) != 0 {
		t.Fatalf("main snapshot agents must be []: %v", m)
	}
	if tk, ok := m["tasks"].([]any); !ok || len(tk) != 0 {
		t.Fatalf("main snapshot tasks must always be present, [] when nil: %v", m)
	}
	if _, has := m["agentId"]; has {
		t.Fatalf("main snapshot has no agentId: %v", m)
	}
	m = nil
	json.Unmarshal(ChatSnapshot(chatbridge.Snapshot{PaneID: "p",
		Agents: []json.RawMessage{json.RawMessage(`{"id":"aa1"}`)}, Tasks: json.RawMessage(`[{"id":"t1"}]`)}), &m)
	if len(m["agents"].([]any)) != 1 || len(m["tasks"].([]any)) != 1 {
		t.Fatalf("agents/tasks: %v", m)
	}
	m = nil
	json.Unmarshal(ChatSnapshot(chatbridge.Snapshot{PaneID: "p", AgentID: "aa1", Agents: []json.RawMessage{json.RawMessage(`{}`)}, Tasks: json.RawMessage(`[]`)}), &m)
	if m["agentId"] != "aa1" {
		t.Fatalf("thread snapshot agentId: %v", m)
	}
	if _, has := m["agents"]; has {
		t.Fatalf("thread snapshot has no agents: %v", m)
	}
	if _, has := m["tasks"]; has {
		t.Fatalf("thread snapshot has no tasks: %v", m)
	}
	if _, has := m["missing"]; has {
		t.Fatalf("missing only when set: %v", m)
	}
	m = nil
	json.Unmarshal(ChatSnapshot(chatbridge.Snapshot{PaneID: "p", AgentID: "zz", Missing: true}), &m)
	if m["missing"] != true || m["agentId"] != "zz" {
		t.Fatalf("missing: %v", m)
	}
}

func TestChatEventAgentID(t *testing.T) {
	e := chatbridge.Entry{Seq: 1, Event: json.RawMessage(`{}`)}
	var m map[string]any
	json.Unmarshal(ChatEvent("p", "", 1, e), &m)
	if _, has := m["agentId"]; has {
		t.Fatalf("main event has no agentId: %v", m)
	}
	m = nil
	json.Unmarshal(ChatEvent("p", "aa1", 1, e), &m)
	if m["agentId"] != "aa1" {
		t.Fatalf("thread event: %v", m)
	}
}

func TestChatAgentAndTasksFrames(t *testing.T) {
	var m map[string]any
	json.Unmarshal(ChatAgent("p", json.RawMessage(`{"id":"aa1","status":"running"}`)), &m)
	if m["t"] != "chat_agent" || m["paneId"] != "p" || m["agent"].(map[string]any)["id"] != "aa1" {
		t.Fatalf("chat_agent: %v", m)
	}
	if _, has := m["removed"]; has {
		t.Fatalf("chat_agent update is not a removal: %v", m)
	}
	m = nil
	json.Unmarshal(ChatAgentRemoved("p", "aa1"), &m)
	if m["t"] != "chat_agent" || m["agentId"] != "aa1" || m["removed"] != true {
		t.Fatalf("removed: %v", m)
	}
	m = nil
	json.Unmarshal(ChatTasks("p", json.RawMessage(`[{"id":"t1"}]`)), &m)
	if m["t"] != "chat_tasks" || m["paneId"] != "p" || len(m["tasks"].([]any)) != 1 {
		t.Fatalf("chat_tasks: %v", m)
	}
}

func TestChatHistoryPageEchoesAgentID(t *testing.T) {
	var m map[string]any
	json.Unmarshal(ChatHistoryPage("r", "p", "aa1", 2, nil, false, false), &m)
	if m["agentId"] != "aa1" {
		t.Fatalf("agentId: %v", m)
	}
	m = nil
	json.Unmarshal(ChatHistoryPage("r", "p", "", 2, nil, false, false), &m)
	if _, has := m["agentId"]; has {
		t.Fatalf("main page has no agentId: %v", m)
	}
}

func TestParseClientAgentID(t *testing.T) {
	m, err := ParseClient([]byte(`{"t":"chat_open","paneId":"p","agentId":"aa1"}`))
	if err != nil || m.AgentID != "aa1" {
		t.Fatalf("%+v %v", m, err)
	}
}
