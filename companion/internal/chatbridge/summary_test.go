package chatbridge

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"
)

type summaryLog struct {
	mu  sync.Mutex
	got []Summary
}

func (l *summaryLog) record(h *Hub) {
	h.SetOnSummary(func(_ string, s Summary) { l.mu.Lock(); l.got = append(l.got, s); l.mu.Unlock() })
}

func (l *summaryLog) n() int { l.mu.Lock(); defer l.mu.Unlock(); return len(l.got) }

func (l *summaryLog) last(t *testing.T) Summary {
	t.Helper()
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.got) == 0 {
		t.Fatal("no summary delivered")
	}
	return l.got[len(l.got)-1]
}

func toolUse(id, tool, summary string, ts int64) json.RawMessage {
	b, _ := json.Marshal(map[string]any{"type": "tool_use", "uuid": id, "toolUseId": id, "tool": tool, "summary": summary, "ts": ts})
	return b
}

func assistantText(text string, ts int64) json.RawMessage {
	b, _ := json.Marshal(map[string]any{"type": "assistant_text", "uuid": "a", "text": text, "ts": ts})
	return b
}

func TestSummaryToolUseActivity(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), toolUse("t1", "Bash", "npm run build", 42)})
	s := l.last(t)
	if s.Activity == nil || *s.Activity != (Activity{Kind: "tool", Tool: "Bash", Text: "npm run build", TS: 42}) || s.Ask != nil {
		t.Fatalf("summary = %+v / %+v", s.Activity, s.Ask)
	}
}

func TestSummaryTextIsFirstNonEmptyLineCapped(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{assistantText("\n\n  Done building.  \nsecond line", 7)})
	if a := l.last(t).Activity; a == nil || a.Kind != "text" || a.Text != "Done building." || a.TS != 7 {
		t.Fatalf("activity = %+v", a)
	}
	long := strings.Repeat("é", 300)
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"user_text","uuid":"u","text":"` + long + `"}`)})
	a := l.last(t).Activity
	if a == nil || a.Kind != "user" || len([]rune(a.Text)) > SummaryMax || !strings.HasSuffix(a.Text, "…") {
		t.Fatalf("activity = %+v (runes %d)", a, len([]rune(a.Text)))
	}
	if a.TS == 0 {
		t.Fatal("an event without ts should be stamped with the receive time")
	}
}

func TestSummaryNoticeAndToolResultIsNotActivity(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"task_notice","uuid":"n","status":"completed","summary":"agent done","ts":3}`)})
	if a := l.last(t).Activity; a == nil || a.Kind != "notice" || a.Text != "agent done" {
		t.Fatalf("activity = %+v", a)
	}
	n := l.n()
	h.Sync("p", "s", []json.RawMessage{toolResult("x")})
	if l.n() != n {
		t.Fatalf("tool_result changed the summary: %+v", l.last(t).Activity)
	}
}

func TestSummaryQuestionAskLifecycle(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("q1")})
	s := l.last(t)
	if s.Activity == nil || s.Activity.Kind != "question" || s.Activity.Text != "Color?" {
		t.Fatalf("activity = %+v", s.Activity)
	}
	if s.Ask == nil || s.Ask.ToolUseID != "q1" || string(s.Ask.Questions) != `[{"question":"Color?","options":[{"label":"Blue"}]}]` {
		t.Fatalf("ask = %+v", s.Ask)
	}
	// tool_result for it clears the ask.
	h.Sync("p", "s", []json.RawMessage{toolResult("q1")})
	if s := l.last(t); s.Ask != nil || s.Activity == nil || s.Activity.Kind != "question" {
		t.Fatalf("after tool_result: %+v / %+v", s.Activity, s.Ask)
	}
}

func TestSummaryAskNewestPendingAndAnswerClears(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("q1"), questionEv("q2")})
	if a := l.last(t).Ask; a == nil || a.ToolUseID != "q2" {
		t.Fatalf("ask = %+v, want newest q2", a)
	}
	if err := h.Answer("p", "q2", map[string]string{"Color?": "Blue"}); err != nil {
		t.Fatal(err)
	}
	if a := l.last(t).Ask; a == nil || a.ToolUseID != "q1" {
		t.Fatalf("after answering q2: ask = %+v, want q1", a)
	}
	if err := h.Answer("p", "q1", map[string]string{"Color?": "Blue"}); err != nil {
		t.Fatal(err)
	}
	if a := l.last(t).Ask; a != nil {
		t.Fatalf("all answered: ask = %+v", a)
	}
}

func TestSummaryAskExpires(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("q1")})
	for i := 0; i < int(QuestionTTL/(LiveWindow/2))+1; i++ {
		c.add(LiveWindow / 2)
		h.Heartbeat("p") // keep the mod live
		h.Tick()
	}
	if a := l.last(t).Ask; a != nil {
		t.Fatalf("expired question still asked: %+v", a)
	}
	if l.last(t).Activity == nil {
		t.Fatal("expiry should keep the activity")
	}
}

func TestSummaryClearedWhenModGoesAwayAndOnDrop(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("q1")})
	c.add(LiveWindow)
	h.Tick()
	if s := l.last(t); s.Activity != nil || s.Ask != nil {
		t.Fatalf("dead mod: %+v / %+v", s.Activity, s.Ask)
	}
	// The mod comes back: the summary returns.
	h.Sync("p", "s", nil)
	if s := l.last(t); s.Activity == nil || s.Ask == nil {
		t.Fatalf("revived mod: %+v / %+v", s.Activity, s.Ask)
	}
	h.Drop("p")
	if s := l.last(t); s.Activity != nil || s.Ask != nil {
		t.Fatalf("dropped pane: %+v / %+v", s.Activity, s.Ask)
	}
	// A new pane with the same id notifies afresh.
	h.Sync("p", "s", []json.RawMessage{hello("s"), toolUse("t", "Read", "a.go", 1)})
	if s := l.last(t); s.Activity == nil || s.Activity.Tool != "Read" {
		t.Fatalf("re-created pane: %+v", s.Activity)
	}
}

func TestSummaryDedupedAndCoalescedPerSync(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), toolUse("a", "Bash", "ls", 1), toolUse("b", "Read", "x", 2), toolUse("c", "Grep", "y", 3)})
	if l.n() != 1 || l.last(t).Activity.Tool != "Grep" {
		t.Fatalf("one sync should deliver one summary: n=%d %+v", l.n(), l.last(t).Activity)
	}
	h.Sync("p", "s", nil)
	h.Tick()
	if l.n() != 1 {
		t.Fatalf("unchanged summary delivered again: n=%d", l.n())
	}
}

func TestSummaryRecomputedOnSnapshot(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), toolUse("a", "Bash", "ls", 1), questionEv("q0")})
	// A full snapshot replaces the history: its last activity wins and its
	// pending question is asked.
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot","events":[` + string(questionEv("q1")) + `,` + string(assistantText("hi", 9)) + `]}`)})
	s := l.last(t)
	if s.Activity == nil || s.Activity.Kind != "text" || s.Activity.Text != "hi" {
		t.Fatalf("snapshot activity = %+v", s.Activity)
	}
	if s.Ask == nil {
		t.Fatal("snapshot question should be asked")
	}
	// A chunked snapshot with only tool_use events.
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":1}`), chunk(toolUse("z", "Edit", "f.go", 5)), raw(`{"type":"snapshot_end"}`)})
	if a := l.last(t).Activity; a == nil || a.Tool != "Edit" {
		t.Fatalf("chunked snapshot activity = %+v", a)
	}
	// An empty snapshot clears the activity.
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot","events":[]}`)})
	if a := l.last(t).Activity; a != nil {
		t.Fatalf("empty snapshot activity = %+v", a)
	}
}
