package chatbridge

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"
)

// threadTool is a tool_use row of agent agentID.
func threadTool(agentID, id, tool, summary string, ts int64) json.RawMessage {
	b, _ := json.Marshal(map[string]any{"type": "tool_use", "agentId": agentID, "uuid": id, "toolUseId": id, "tool": tool, "summary": summary, "ts": ts})
	return b
}

func threadText(agentID string, n int) json.RawMessage {
	return raw(fmt.Sprintf(`{"type":"assistant_text","agentId":%q,"uuid":"a%d","text":"t%d","ts":%d}`, agentID, n, n, n))
}

func agentCtl(agentID, status string, ts int64) json.RawMessage {
	return raw(fmt.Sprintf(`{"type":"agent","agent":{"agentId":%q,"parentToolUseId":"tu_%s","kind":"subagent","label":"L %s","status":%q,"ts":%d}}`, agentID, agentID, agentID, status, ts))
}

func threadChunk(agentID string, evs ...json.RawMessage) json.RawMessage {
	parts := make([]string, len(evs))
	for i, e := range evs {
		parts[i] = string(e)
	}
	return raw(`{"type":"snapshot_chunk","agentId":"` + agentID + `","events":[` + strings.Join(parts, ",") + `]}`)
}

func drain(ch <-chan Update) []Update {
	var out []Update
	for {
		select {
		case u, ok := <-ch:
			if !ok {
				return out
			}
			out = append(out, u)
		default:
			return out
		}
	}
}

func kinds(us []Update, kind string) []Update {
	var out []Update
	for _, u := range us {
		if u.Kind == kind {
			out = append(out, u)
		}
	}
	return out
}

func agentField(t *testing.T, a json.RawMessage) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(a, &m); err != nil {
		t.Fatalf("agent %s: %v", a, err)
	}
	return m
}

func TestThreadEventStaysOutOfMain(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), toolUse("m1", "Bash", "main", 5)})
	before := l.last(t).Activity
	h.Sync("p", "s", []json.RawMessage{threadTool("aa1", "t1", "Read", "x.go", 9)})
	if a := l.last(t).Activity; *a != *before {
		t.Fatalf("thread event changed the pane activity: %+v", a)
	}
	snap, _, c := h.Subscribe("p", "")
	c()
	if len(snap.Events) != 1 || strings.Contains(string(snap.Events[0].Event), "aa1") {
		t.Fatalf("main snapshot: %+v", snap.Events)
	}
	ts, _, c2 := h.Subscribe("p", "aa1")
	defer c2()
	if ts.Missing || ts.AgentID != "aa1" || len(ts.Events) != 1 || ts.Events[0].Seq != 1 || !strings.Contains(string(ts.Events[0].Event), `"t1"`) {
		t.Fatalf("thread snapshot: %+v", ts)
	}
}

func TestThreadLiveEventFansToThreadOnly(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), threadText("aa1", 1)})
	_, mainCh, c1 := h.Subscribe("p", "")
	defer c1()
	_, thCh, c2 := h.Subscribe("p", "aa1")
	defer c2()
	h.Sync("p", "s", []json.RawMessage{threadText("aa1", 2)})
	if us := drain(mainCh); len(us) != 0 {
		t.Fatalf("main got thread updates: %+v", us)
	}
	us := drain(thCh)
	if len(us) != 1 || us[0].Kind != "event" || us[0].AgentID != "aa1" || us[0].Entry.Seq != 2 {
		t.Fatalf("thread updates: %+v", us)
	}
}

func TestThreadCap(t *testing.T) {
	if ThreadCap != 1000 || ThreadsMax != 20 {
		t.Fatalf("caps %d %d", ThreadCap, ThreadsMax)
	}
	h := NewHub(nil)
	evs := make([]json.RawMessage, 0, 1500)
	for i := 1; i <= 1500; i++ {
		evs = append(evs, threadText("aa1", i))
	}
	h.Sync("p", "s", evs)
	snap, _, c := h.Subscribe("p", "")
	c()
	if len(snap.Events) != 0 {
		t.Fatalf("main ring got %d", len(snap.Events))
	}
	ts, _, c2 := h.Subscribe("p", "aa1")
	c2()
	if len(ts.Events) != SnapshotTail || !ts.HasMore || ts.Events[len(ts.Events)-1].Seq != 1500 {
		t.Fatalf("thread tail: len=%d more=%v", len(ts.Events), ts.HasMore)
	}
	all, _, ok := h.History("p", "aa1", ts.Epoch, ts.Events[0].Seq, HistoryMax)
	if !ok || len(all) != HistoryMax {
		t.Fatalf("history ok=%v len=%d", ok, len(all))
	}
	// Walk back to the oldest kept entry.
	oldest := all[0].Seq
	for {
		page, more, _ := h.History("p", "aa1", ts.Epoch, oldest, HistoryMax)
		if len(page) == 0 {
			break
		}
		oldest = page[0].Seq
		if !more {
			break
		}
	}
	if oldest != 501 {
		t.Fatalf("oldest kept seq %d, want 501", oldest)
	}
}

func TestAgentControlMergesActivity(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), threadTool("aa1", "t1", "Read", "x.go", 9), agentCtl("aa1", "running", 100)})
	snap, ch, c := h.Subscribe("p", "")
	defer c()
	if len(snap.Agents) != 1 {
		t.Fatalf("agents: %s", snap.Agents)
	}
	m := agentField(t, snap.Agents[0])
	act, _ := m["activity"].(map[string]any)
	if m["agentId"] != "aa1" || m["label"] != "L aa1" || act == nil || act["tool"] != "Read" || act["text"] != "x.go" || act["kind"] != "tool" {
		t.Fatalf("merged agent: %s", snap.Agents[0])
	}
	h.Sync("p", "s", []json.RawMessage{threadTool("aa1", "t2", "Bash", "go test", 11)})
	ags := kinds(drain(ch), "agent")
	if len(ags) != 1 || ags[0].AgentID != "aa1" {
		t.Fatalf("agent updates: %+v", ags)
	}
	act, _ = agentField(t, ags[0].Agent)["activity"].(map[string]any)
	if act["tool"] != "Bash" || act["text"] != "go test" {
		t.Fatalf("activity not updated: %s", ags[0].Agent)
	}
	// An identical control fans nothing; a tool_result (no activity) neither.
	h.Sync("p", "s", []json.RawMessage{agentCtl("aa1", "running", 100), raw(`{"type":"tool_result","agentId":"aa1","toolUseId":"t2","isError":false}`)})
	if us := drain(ch); len(us) != 0 {
		t.Fatalf("expected no updates, got %+v", us)
	}
	// A changed control fans one.
	h.Sync("p", "s", []json.RawMessage{agentCtl("aa1", "done", 120)})
	us := drain(ch)
	if len(us) != 1 || us[0].Kind != "agent" || agentField(t, us[0].Agent)["status"] != "done" {
		t.Fatalf("changed control: %+v", us)
	}
	if act, _ := agentField(t, us[0].Agent)["activity"].(map[string]any); act["tool"] != "Bash" {
		t.Fatalf("activity lost on a new control: %s", us[0].Agent)
	}
	// Without agentId: ignored.
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"agent","agent":{"status":"running"}}`), raw(`{"type":"agent"}`)})
	if us := drain(ch); len(us) != 0 {
		t.Fatalf("bad control fanned %+v", us)
	}
}

func TestAgentsSortedByTs(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{agentCtl("bb", "running", 300), agentCtl("aa", "running", 100), agentCtl("cc", "done", 200)})
	snap, _, c := h.Subscribe("p", "")
	c()
	var ids []string
	for _, a := range snap.Agents {
		ids = append(ids, agentField(t, a)["agentId"].(string))
	}
	if strings.Join(ids, ",") != "aa,cc,bb" {
		t.Fatalf("order %v", ids)
	}
}

func TestTasksControl(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s")})
	_, ch, c := h.Subscribe("p", "")
	defer c()
	tasks := `[{"id":"b1","kind":"shell","label":"sleep","toolUseId":"t1","status":"running","startedAt":1},` +
		`{"id":"a1","kind":"subagent","label":"x","toolUseId":"t2","status":"running","startedAt":2},` +
		`{"id":"w1","kind":"workflow","label":"y","toolUseId":"t3","status":"done","startedAt":3,"endedAt":4}]`
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"tasks","tasks":` + tasks + `}`)})
	if s := l.last(t); s.BgRunning != 2 {
		t.Fatalf("summary BgRunning %d", s.BgRunning)
	}
	us := drain(ch)
	if len(us) != 1 || us[0].Kind != "tasks" || string(us[0].Tasks) != tasks {
		t.Fatalf("tasks updates: %+v", us)
	}
	snap, _, c2 := h.Subscribe("p", "")
	c2()
	if string(snap.Tasks) != tasks {
		t.Fatalf("snapshot tasks %s", snap.Tasks)
	}
	n := l.n()
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"tasks","tasks":` + tasks + `}`), raw(`{"type":"tasks","tasks":{"id":"x"}}`), raw(`{"type":"tasks","tasks":"no"}`), raw(`{"type":"tasks"}`)})
	if us := drain(ch); len(us) != 0 {
		t.Fatalf("identical or non-array tasks fanned: %+v", us)
	}
	if l.n() != n {
		t.Fatal("summary re-delivered without a change")
	}
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"tasks","tasks":[]}`)})
	if s := l.last(t); s.BgRunning != 0 {
		t.Fatalf("BgRunning after empty list %d", s.BgRunning)
	}
}

func TestThreadEviction(t *testing.T) {
	now := time.Unix(1000, 0)
	h := NewHub(func() time.Time { return now })
	h.Sync("p", "s", []json.RawMessage{hello("s")})
	for i := 1; i <= ThreadsMax; i++ {
		id := fmt.Sprintf("a%02d", i)
		status := "running"
		if i == 3 || i == 5 {
			status = "done"
		}
		now = now.Add(time.Second)
		h.Sync("p", "s", []json.RawMessage{threadText(id, i), agentCtl(id, status, int64(i))})
	}
	// a01 is the oldest but running; a03 is the oldest non-running.
	_, victim, cv := h.Subscribe("p", "a03")
	defer cv()
	_, keep, ck := h.Subscribe("p", "a01")
	defer ck()
	_, mainCh, cm := h.Subscribe("p", "")
	defer cm()
	now = now.Add(time.Second)
	h.Sync("p", "s", []json.RawMessage{threadText("a21", 21)})
	select {
	case _, ok := <-victim:
		if ok {
			t.Fatal("victim got an update instead of a close")
		}
	default:
		t.Fatal("victim subscriber not closed")
	}
	select {
	case _, ok := <-keep:
		if !ok {
			t.Fatal("running thread evicted")
		}
	default:
	}
	rem := kinds(drain(mainCh), "agent_removed")
	if len(rem) != 1 || rem[0].AgentID != "a03" {
		t.Fatalf("agent_removed: %+v", rem)
	}
	snap, _, c := h.Subscribe("p", "")
	c()
	if len(snap.Agents) != ThreadsMax-1 {
		t.Fatalf("agents after eviction %d", len(snap.Agents))
	}
	if ms, _, c := h.Subscribe("p", "a03"); !ms.Missing {
		t.Fatalf("evicted thread still present: %+v", ms)
	} else {
		c()
	}
	// All running: the oldest overall goes.
	for i := 22; i <= 24; i++ {
		id := fmt.Sprintf("a%02d", i)
		now = now.Add(time.Second)
		h.Sync("p", "s", []json.RawMessage{agentCtl(id, "running", int64(i))})
	}
	h.Sync("p", "s", []json.RawMessage{agentCtl("a05", "running", 5), agentCtl("a21", "running", 21)})
	now = now.Add(time.Second)
	h.Sync("p", "s", []json.RawMessage{threadText("a22", 22)})
	if ms, _, c := h.Subscribe("p", "a01"); !ms.Missing {
		t.Fatal("oldest running thread should be evicted when all run")
	} else {
		c()
	}
}

func TestThreadChunkedSnapshot(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), userText(1), threadText("aa1", 1), threadText("bb2", 1), threadText("bb2", 2)})
	_, mainCh, cm := h.Subscribe("p", "")
	defer cm()
	_, aCh, ca := h.Subscribe("p", "aa1")
	defer ca()
	_, bCh, cb := h.Subscribe("p", "bb2")
	defer cb()
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":3,"agentId":"aa1"}`), threadChunk("aa1", threadText("aa1", 10), threadText("aa1", 11))})
	if us := drain(aCh); len(us) != 0 {
		t.Fatalf("update while staging: %+v", us)
	}
	h.Sync("p", "s", []json.RawMessage{threadChunk("aa1", threadText("aa1", 12), raw(`{"type":"bogus"}`)), raw(`{"type":"snapshot_end","agentId":"aa1"}`)})
	us := drain(aCh)
	if len(us) != 1 || us[0].Kind != "snapshot" || us[0].AgentID != "aa1" || us[0].Snapshot.AgentID != "aa1" || len(us[0].Snapshot.Events) != 3 || us[0].Snapshot.Events[0].Seq != 1 || !strings.Contains(string(us[0].Snapshot.Events[0].Event), `"a10"`) {
		t.Fatalf("thread swap: %+v", us)
	}
	if us := drain(bCh); len(us) != 0 {
		t.Fatalf("other thread touched: %+v", us)
	}
	if us := drain(mainCh); len(us) != 0 {
		t.Fatalf("main touched: %+v", us)
	}
	snap, _, c := h.Subscribe("p", "")
	c()
	if snap.Epoch != 1 || len(snap.Events) != 1 {
		t.Fatalf("main changed: %+v", snap)
	}
	bs, _, c2 := h.Subscribe("p", "bb2")
	c2()
	if len(bs.Events) != 2 {
		t.Fatalf("bb2 changed: %+v", bs)
	}
	// A thread snapshot for a new agent creates its thread.
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":1,"agentId":"cc3"}`), threadChunk("cc3", threadText("cc3", 1)), raw(`{"type":"snapshot_end","agentId":"cc3"}`)})
	cs, _, c3 := h.Subscribe("p", "cc3")
	c3()
	if cs.Missing || len(cs.Events) != 1 {
		t.Fatalf("cc3: %+v", cs)
	}
}

func TestMainResyncClearsThreads(t *testing.T) {
	h := NewHub(nil)
	var l summaryLog
	l.record(h)
	h.Sync("p", "s", []json.RawMessage{hello("s"), threadText("aa1", 1), agentCtl("aa1", "running", 1),
		raw(`{"type":"tasks","tasks":[{"id":"b1","kind":"shell","label":"x","toolUseId":"t","status":"running","startedAt":1}]}`)})
	if l.last(t).BgRunning != 1 {
		t.Fatal("BgRunning not set")
	}
	_, th, ct := h.Subscribe("p", "aa1")
	defer ct()
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":1}`), chunk(userText(1)), raw(`{"type":"snapshot_end"}`)})
	select {
	case _, ok := <-th:
		if ok {
			t.Fatal("thread subscriber got an update instead of a close")
		}
	default:
		t.Fatal("thread subscriber not closed")
	}
	snap, _, c := h.Subscribe("p", "")
	c()
	if len(snap.Agents) != 0 || snap.Tasks != nil {
		t.Fatalf("not cleared: agents=%s tasks=%s", snap.Agents, snap.Tasks)
	}
	if l.last(t).BgRunning != 0 {
		t.Fatal("BgRunning not reset")
	}
	if ms, _, c := h.Subscribe("p", "aa1"); !ms.Missing {
		t.Fatal("thread survived the resync")
	} else {
		c()
	}
	ct() // cancelling an already-closed subscription is safe
}

func TestMissingThreadAndHistory(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s")})
	snap, ch, cancel := h.Subscribe("p", "zz9")
	if !snap.Missing || snap.AgentID != "zz9" || len(snap.Events) != 0 {
		t.Fatalf("missing: %+v", snap)
	}
	h.Sync("p", "s", []json.RawMessage{threadText("zz9", 1), userText(1)})
	select {
	case u := <-ch:
		t.Fatalf("missing subscription received %+v", u)
	default:
	}
	cancel()
	cancel()
	if _, ok := <-ch; ok {
		t.Fatal("channel not closed by cancel")
	}
	if _, _, ok := h.History("p", "nope", snap.Epoch, 1<<30, 10); ok {
		t.Fatal("history of an unknown thread ok")
	}
	if _, _, ok := h.History("p", "zz9", snap.Epoch+1, 1<<30, 10); ok {
		t.Fatal("history of a wrong epoch ok")
	}
	evs := make([]json.RawMessage, 0, 10)
	for i := 2; i <= 11; i++ {
		evs = append(evs, threadText("zz9", i))
	}
	h.Sync("p", "s", evs)
	page, more, ok := h.History("p", "zz9", snap.Epoch, 8, 3)
	if !ok || !more || len(page) != 3 || page[0].Seq != 5 || page[2].Seq != 7 {
		t.Fatalf("page ok=%v more=%v %+v", ok, more, page)
	}
	page, more, _ = h.History("p", "zz9", snap.Epoch, 3, 10)
	if more || len(page) != 2 || page[0].Seq != 1 {
		t.Fatalf("first page more=%v %+v", more, page)
	}
	// Main history is unaffected by the thread.
	mainPage, _, ok := h.History("p", "", snap.Epoch, 1<<30, 10)
	if !ok || len(mainPage) != 1 {
		t.Fatalf("main history %+v", mainPage)
	}
}

func TestThreadEventImagesStored(t *testing.T) {
	h := NewHub(nil)
	ev := raw(`{"type":"user_text","agentId":"aa1","uuid":"u","text":"see","images":[{"id":"img1"}]}`)
	h.SyncBody("p", "s", []json.RawMessage{hello("s"), ev}, map[string]Image{"img1": {MediaType: "image/png", Data: "AAAA"}})
	if mt, data, ok := h.Image("p", "img1"); !ok || mt != "image/png" || data != "AAAA" {
		t.Fatalf("image not stored: %v %q %q", ok, mt, data)
	}
	ts, _, c := h.Subscribe("p", "aa1")
	c()
	if len(ts.Events) != 1 {
		t.Fatalf("thread event: %+v", ts)
	}
}

func TestDropClosesThreadSubscribers(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{threadText("aa1", 1)})
	_, ch, cancel := h.Subscribe("p", "aa1")
	h.Drop("p")
	if _, ok := <-ch; ok {
		t.Fatal("thread subscriber not closed by Drop")
	}
	cancel()
}

// A non-chunked snapshot naming an agent is not a main swap: it is ignored.
func TestAgentTaggedPlainSnapshotIsIgnored(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), raw(`{"type":"snapshot","events":[` + string(userText(1)) + `]}`)})
	before, _, c := h.Subscribe("p", "")
	c()
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot","agentId":"aa1","events":[]}`)})
	after, _, c2 := h.Subscribe("p", "")
	c2()
	if after.Epoch != before.Epoch || len(after.Events) != 1 {
		t.Fatalf("agent-tagged snapshot swapped the main stream: before %+v after %+v", before, after)
	}
}
