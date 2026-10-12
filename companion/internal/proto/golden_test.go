package proto

import (
	"encoding/json"
	"flag"
	"os"
	"testing"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/limits"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

var update = flag.Bool("update", false, "rewrite testdata/frames.json")

// TestEveryFrameGolden keeps testdata/frames.json equal to one frame from each
// server-frame builder, with chat events as the herdr-chat mod sends them. The
// iOS tests (ios/HerdrKit) decode every frame in it. Add a frame here when you
// add a builder. Refresh with: go test ./internal/proto -run TestEveryFrameGolden -update
func TestEveryFrameGolden(t *testing.T) {
	raw := func(s string) json.RawMessage { return json.RawMessage(s) }
	questions := raw(`[{"question":"Which database?","header":"DB","options":[{"label":"Postgres","description":"relational"},{"label":"SQLite"}],"multiSelect":false},` +
		`{"question":"How many workers?","header":"Workers","kind":"number","min":1,"max":8,"step":1,"unit":"workers"},` +
		`{"question":"Branch name?","header":"Branch","kind":"text","placeholder":"feat/…"}]`)
	pane := state.Pane{PaneID: "w1:p1", WorkspaceID: "w1", TabID: "w1:t1", TerminalID: "term_1", CWD: "/Users/dev/herdr", Focused: true,
		Agent: "claude", AgentStatus: "blocked", Chat: true, BgRunning: 2,
		Activity: &state.Activity{Kind: "tool", Tool: "Bash", Text: "Bash: go test ./...", TS: 1791279000000},
		Ask:      &state.Ask{ToolUseID: "toolu_ask", Questions: questions},
		Context:  &state.Context{Percent: 61, Tokens: 122000, Window: 200000}}
	shell := state.Pane{PaneID: "w2:p1", WorkspaceID: "w2", TabID: "w2:t1", TerminalID: "term_2", CWD: "/tmp"}
	events := []chatbridge.Entry{
		{Seq: 1, Event: raw(`{"type":"user_text","uuid":"u1","text":"look at this","ts":1791279000000,"images":["img1"],"imageSizes":{"img1":[640,480]}}`)},
		{Seq: 2, Event: raw(`{"type":"assistant_text","uuid":"u2","text":"On it.","ts":1791279001000}`)},
		{Seq: 3, Event: raw(`{"type":"tool_use","uuid":"u3","toolUseId":"toolu_1","tool":"Bash","summary":"Bash: go test ./...","ts":1791279002000}`)},
		{Seq: 4, Event: raw(`{"type":"tool_result","toolUseId":"toolu_1","isError":true,"preview":"FAIL","images":["img2"],"imageSizes":{"img2":[100,50]},"ts":1791279003000}`)},
		{Seq: 5, Event: raw(`{"type":"task_notice","uuid":"u5","taskId":"bg1","toolUseId":"toolu_2","status":"completed","summary":"Agent \"review\" finished","ts":1791279004000}`)},
		{Seq: 6, Event: raw(`{"type":"question","uuid":"toolu_ask","toolUseId":"toolu_ask","questions":` + string(questions) + `,"ts":1791279005000}`)},
		{Seq: 7, Event: raw(`{"type":"from_a_newer_mod","uuid":"u7"}`)},
		{Seq: 8, Event: raw(`{"type":"command_output","uuid":"cmd-c9","command":"/context","text":"61% of 200k tokens","ts":1791279008000}`)},
		{Seq: 9, Event: raw(`{"type":"command_output","uuid":"cmd-c10","command":"/nope","text":"Unknown command: /nope","isError":true,"ts":1791279009000}`)},
	}
	agent := raw(`{"agentId":"ag1","parentToolUseId":"toolu_2","kind":"subagent","label":"review","type":"code-reviewer","status":"running","activity":{"kind":"tool","tool":"Read","text":"Read: main.go","ts":1791279006000},"ts":1791279004000}`)
	tasks := raw(`[{"id":"bg1","kind":"subagent","label":"review","toolUseId":"toolu_2","status":"running","startedAt":1791279004000},` +
		`{"id":"bg2","kind":"shell","label":"npm run dev","toolUseId":"toolu_3","status":"done","startedAt":1791279000000,"endedAt":1791279007000,` +
		`"detail":"npm run dev \\\n  --port 3000","description":"Start the dev server"}]`)
	commands := raw(`[{"name":"clear","description":"Clear conversation history","source":"builtin"},{"name":"review","description":"Review a pull request","source":"project"}]`)

	frames := []json.RawMessage{
		Welcome("0.9.1", 3),
		PanesSnapshot([]state.Pane{pane, shell}),
		WorkspacesSnapshot([]state.Workspace{
			{WorkspaceID: "w1", Label: "herdr", Number: 1, AgentStatus: "blocked", Focused: true, PaneCount: 1, TabCount: 1, LastActivity: 1791279000000,
				Worktree: &state.Worktree{RepoName: "herdr", IsLinkedWorktree: true}},
			{WorkspaceID: "w2", Label: "scratch", Number: 2, PaneCount: 1, TabCount: 1},
		}),
		TabsSnapshot([]state.Tab{{TabID: "w1:t1", Label: "1", Number: 1, WorkspaceID: "w1", AgentStatus: "blocked", Focused: true, PaneCount: 1}}),
		PaneUpdate(pane),
		PaneRemoved("w2:p1"),
		PaneRead("r1", "w1:p1", "detection", "$ go test ./...\nok"),
		Ack("r2"),
		ErrorFrame("r3", "read_failed", "pane not found"),
		ActionResult("a4", true, ""),
		ActionResult("a5", false, "unknown kind: window"),
		Created("n6", true, "w1:p2", "term_3", ""),
		Created("n7", false, "", "", "unknown what: window"),
		Agents("g8", []string{"claude", "codex"}),
		CloseImpact("i9", "w1", []AlsoClose{{WorkspaceID: "w3", Label: "herdr-feature"}}),
		Pong(),
		TermOpened("r10", "t1"),
		TermData("t1", "aGVsbG8="),
		TermExit("t1", 0, "ended"),
		TermError("r11", "", "invalid target"),
		ChatSnapshot(chatbridge.Snapshot{PaneID: "w1:p1", Epoch: 2, State: "working", Events: events, HasMore: true,
			Agents: []json.RawMessage{agent}, Tasks: tasks, Commands: commands}),
		ChatSnapshot(chatbridge.Snapshot{PaneID: "w1:p1", AgentID: "ag1", Epoch: 2, State: "idle", Events: events[1:2]}),
		ChatSnapshot(chatbridge.Snapshot{PaneID: "w1:p1", AgentID: "ag9", Epoch: 2, Missing: true}),
		ChatEvent("w1:p1", "", 2, events[1]),
		ChatEvent("w1:p1", "ag1", 2, events[2]),
		ChatEvent("w1:p1", "", 2, events[6]),
		ChatEvent("w1:p1", "", 2, events[7]),
		ChatAgent("w1:p1", agent),
		ChatAgentRemoved("w1:p1", "ag1"),
		ChatTasks("w1:p1", tasks),
		ChatCommands("w1:p1", commands),
		ChatState("w1:p1", "idle"),
		ChatSendResult("c12", true, ""),
		ChatSendResult("c13", false, "no_mod"),
		ChatHistoryPage("h14", "w1:p1", "", 2, events[:2], true, false),
		ChatHistoryPage("h15", "w1:p1", "ag1", 1, nil, false, true),
		ChatImageData("w1:p1", "img1", "image/png", "iVBORw0KGgo="),
		ChatImageMissing("w1:p1", "img2"),
		ChatAnswerResult("q16", true, ""),
		ChatAnswerResult("q17", false, "no_question"),
		Limits(limits.Limits{ObservedAt: 1791279010000, Windows: []limits.Window{
			{Kind: "five_hour", PercentUsed: 42.5, ResetsAt: "2026-10-01T14:00:00Z"},
			{Kind: "seven_day", PercentUsed: 18, ResetsAt: "2026-10-04T09:30:00+02:00"},
		}}),
	}
	got, err := json.MarshalIndent(frames, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	got = append(got, '\n')
	if *update {
		if err := os.WriteFile("testdata/frames.json", got, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile("testdata/frames.json")
	if err != nil || string(want) != string(got) {
		t.Fatalf("testdata/frames.json is stale (%v); rerun with -update", err)
	}
}
