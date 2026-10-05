package chatbridge

import (
	"bytes"
	"encoding/json"
	"sort"
	"time"
)

const (
	// ThreadCap bounds one agent thread's ring; ThreadsMax bounds the threads
	// a pane keeps (the 21st evicts one, see evictThread).
	ThreadCap  = 1000
	ThreadsMax = 20
)

// thread is one subagent's or workflow agent's chat, fed by the mod's rows
// tagged with its agentId. It lives under the pane's epoch: a main resync
// discards every thread.
type thread struct {
	events []Entry
	seq    int
	subs   map[int]chan Update
	// staging collects a per-thread chunked snapshot; nil when none is in
	// progress.
	staging    []json.RawMessage
	lastActive time.Time
	// activity is the latest activity-bearing event's summary, merged into
	// the agent's summary object (the mod sends none).
	activity *Activity
}

// push appends raw as the thread's next entry, keeping the newest ThreadCap.
func (t *thread) push(raw json.RawMessage) Entry {
	t.seq++
	e := Entry{Seq: t.seq, Event: append(json.RawMessage(nil), raw...)}
	t.events = appendCapped(t.events, e, ThreadCap)
	return e
}

func (t *thread) snapshot(p *pane, paneID, agentID string) Snapshot {
	start := max(0, len(t.events)-SnapshotTail)
	ev := make([]Entry, len(t.events)-start)
	copy(ev, t.events[start:])
	return Snapshot{PaneID: paneID, AgentID: agentID, Epoch: p.epoch, State: p.state, Events: ev, HasMore: start > 0}
}

// appendCapped appends e and keeps the newest limit entries. It reslices
// instead of copying the ring on every push: the backing array's next growth
// copies only the live window. The dropped slot is cleared so its event can
// be collected.
func appendCapped(events []Entry, e Entry, limit int) []Entry {
	events = append(events, e)
	if len(events) > limit {
		events[0] = Entry{}
		events = events[len(events)-limit:]
	}
	return events
}

// page returns up to limit entries with seq < beforeSeq, ascending, and
// whether older ones remain.
func page(events []Entry, beforeSeq, limit int) ([]Entry, bool) {
	end := sort.Search(len(events), func(i int) bool { return events[i].Seq >= beforeSeq })
	start := max(0, end-limit)
	out := make([]Entry, end-start)
	copy(out, events[start:end])
	return out, start > 0
}

// fanTo delivers u to every subscriber in subs without blocking: a
// subscriber whose buffer is full misses the update rather than stalling the
// mod's heartbeat.
func fanTo(subs map[int]chan Update, u Update) {
	for _, ch := range subs {
		select {
		case ch <- u:
		default:
		}
	}
}

func closeSubs(subs map[int]chan Update) {
	for id, ch := range subs {
		delete(subs, id)
		close(ch)
	}
}

// thread returns agentID's thread, creating it (and evicting one when the
// pane already holds ThreadsMax) on first use.
func (p *pane) thread(agentID string, now time.Time) *thread {
	if t := p.threads[agentID]; t != nil {
		return t
	}
	if p.threads == nil {
		p.threads = map[string]*thread{}
	}
	if len(p.threads) >= ThreadsMax {
		p.evictThread()
	}
	t := &thread{subs: map[int]chan Update{}, lastActive: now}
	p.threads[agentID] = t
	return t
}

// evictThread drops the least recently active thread whose agent is not
// running (else the least recently active overall), closing its subscribers
// and forgetting its summary. Ties go to the smaller id, for determinism.
func (p *pane) evictThread() {
	victim, victimRunning := "", false
	for id, t := range p.threads {
		running := agentStatus(p.agents[id]) == "running"
		if victim == "" || (victimRunning && !running) {
			victim, victimRunning = id, running
			continue
		}
		if running != victimRunning {
			continue
		}
		v := p.threads[victim]
		if t.lastActive.Before(v.lastActive) || (t.lastActive.Equal(v.lastActive) && id < victim) {
			victim = id
		}
	}
	if victim == "" {
		return
	}
	closeSubs(p.threads[victim].subs)
	delete(p.threads, victim)
	if _, ok := p.agents[victim]; ok {
		delete(p.agents, victim)
		p.fan(Update{Kind: "agent_removed", Epoch: p.epoch, AgentID: victim})
	}
}

// resetAgents forgets every thread (closing their subscribers so the app
// re-opens the rebuilt one), agent summary and task: they belong to the
// epoch a main resync replaces.
func (p *pane) resetAgents() {
	for _, t := range p.threads {
		closeSubs(t.subs)
	}
	p.threads = nil
	p.agents = nil
	p.tasks = nil
	p.bgRunning = 0
}

// dropStaging discards every unfinished chunked snapshot, main and threads.
func (p *pane) dropStaging() {
	p.staging = nil
	for _, t := range p.threads {
		t.staging = nil
	}
}

// applyThreadEvent appends a chat event tagged with agentID to its thread.
// It never touches the main ring, the pane's activity or its questions.
func (p *pane) applyThreadEvent(agentID, typ string, raw json.RawMessage, now time.Time) {
	t := p.thread(agentID, now)
	t.lastActive = now
	if a := activityOf(typ, raw, now); a != nil {
		t.activity = a
	}
	e := t.push(raw)
	fanTo(t.subs, Update{Kind: "event", Epoch: p.epoch, AgentID: agentID, Entry: e})
	p.remerge(agentID)
}

// applyThreadSnapshot stages a per-thread chunked snapshot (typ is
// snapshot_begin, snapshot_chunk or snapshot_end) and swaps the thread at its
// end, under the pane's current epoch.
func (p *pane) applyThreadSnapshot(paneID, typ, agentID string, events []json.RawMessage, now time.Time) {
	if typ == "snapshot_begin" {
		p.thread(agentID, now).staging = []json.RawMessage{} // discards an unfinished one
		return
	}
	t := p.threads[agentID]
	if t == nil || t.staging == nil {
		return
	}
	if typ == "snapshot_chunk" {
		t.staging = append(t.staging, events...)
		if len(t.staging) > ThreadCap { // only the newest ThreadCap can survive the swap
			t.staging = append([]json.RawMessage(nil), t.staging[len(t.staging)-ThreadCap:]...)
		}
		return
	}
	evs := t.staging
	t.staging = nil
	t.seq = 0
	t.events = nil
	t.activity = nil
	t.lastActive = now
	for _, ev := range evs {
		var inner struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(ev, &inner) == nil && isChatEvent(inner.Type) {
			if a := activityOf(inner.Type, ev, now); a != nil {
				t.activity = a
			}
			t.push(ev)
		}
	}
	fanTo(t.subs, Update{Kind: "snapshot", Epoch: p.epoch, AgentID: agentID, Snapshot: t.snapshot(p, paneID, agentID)})
	p.remerge(agentID)
}

// applyAgent stores the mod's agent summary (which must carry an agentId)
// with the thread's activity merged in, and fans it to the main subscribers
// when it changed.
func (p *pane) applyAgent(obj json.RawMessage) {
	id := agentIDOf(obj)
	if id == "" {
		return
	}
	var act *Activity
	if t := p.threads[id]; t != nil {
		act = t.activity
	}
	p.storeAgent(id, obj, act)
}

// remerge refreshes agentID's stored summary with its thread's latest
// activity, if it has a summary and an activity.
func (p *pane) remerge(agentID string) {
	old, ok := p.agents[agentID]
	t := p.threads[agentID]
	if !ok || t == nil || t.activity == nil {
		return
	}
	p.storeAgent(agentID, old, t.activity)
}

func (p *pane) storeAgent(id string, obj json.RawMessage, act *Activity) {
	merged := mergeAgent(obj, act)
	if merged == nil || bytes.Equal(p.agents[id], merged) {
		return
	}
	if p.agents == nil {
		p.agents = map[string]json.RawMessage{}
	}
	p.agents[id] = merged
	p.fan(Update{Kind: "agent", Epoch: p.epoch, AgentID: id, Agent: merged})
}

// mergeAgent returns obj re-encoded with activity set to act (kept as sent
// when act is nil); nil when obj is not a JSON object. Re-encoding sorts the
// keys, so equal summaries compare byte-equal.
func mergeAgent(obj json.RawMessage, act *Activity) json.RawMessage {
	var m map[string]json.RawMessage
	if json.Unmarshal(obj, &m) != nil || m == nil {
		return nil
	}
	if act != nil {
		b, err := json.Marshal(act)
		if err != nil {
			return nil
		}
		m["activity"] = b
	}
	out, err := json.Marshal(m)
	if err != nil {
		return nil
	}
	return out
}

// agentIDOf returns an agent object's agentId, "" when missing or malformed.
func agentIDOf(obj json.RawMessage) string {
	var a struct {
		AgentID string `json:"agentId"`
	}
	if json.Unmarshal(obj, &a) != nil {
		return ""
	}
	return a.AgentID
}

// agentStatus returns a stored summary's status, "" when unknown.
func agentStatus(obj json.RawMessage) string {
	var a struct {
		Status string `json:"status"`
	}
	if obj == nil || json.Unmarshal(obj, &a) != nil {
		return ""
	}
	return a.Status
}

// applyTasks stores the mod's background task list (which must be a JSON
// array), counts its running entries into bgRunning, and fans it to the main
// subscribers when it changed.
func (p *pane) applyTasks(tasks json.RawMessage) {
	var list []json.RawMessage
	if json.Unmarshal(tasks, &list) != nil || list == nil {
		return
	}
	var buf bytes.Buffer
	if json.Compact(&buf, tasks) != nil {
		return
	}
	if bytes.Equal(p.tasks, buf.Bytes()) {
		return
	}
	running := 0
	for _, el := range list {
		var t struct {
			Status string `json:"status"`
		}
		if json.Unmarshal(el, &t) == nil && t.Status == "running" {
			running++
		}
	}
	p.tasks = buf.Bytes()
	p.bgRunning = running
	p.fan(Update{Kind: "tasks", Epoch: p.epoch, Tasks: p.tasks})
}

// agentList returns the stored summaries sorted by their ts ascending (ties
// by agentId).
func (p *pane) agentList() []json.RawMessage {
	if len(p.agents) == 0 {
		return nil
	}
	type keyed struct {
		id  string
		ts  float64
		raw json.RawMessage
	}
	ks := make([]keyed, 0, len(p.agents))
	for id, raw := range p.agents {
		var a struct {
			TS float64 `json:"ts"`
		}
		_ = json.Unmarshal(raw, &a)
		ks = append(ks, keyed{id, a.TS, raw})
	}
	sort.Slice(ks, func(i, j int) bool {
		if ks[i].ts != ks[j].ts {
			return ks[i].ts < ks[j].ts
		}
		return ks[i].id < ks[j].id
	})
	out := make([]json.RawMessage, len(ks))
	for i, k := range ks {
		out[i] = k.raw
	}
	return out
}
