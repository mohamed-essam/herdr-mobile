// Package chatbridge relays Claude Code conversations from the herdr-chat mod
// (one per Claude session in a herdr pane) to the app's chat view, and carries
// messages typed on the phone back to the mod.
package chatbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"maps"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/limits"
)

const (
	RingCap    = 5000
	OutboxCap  = 20
	LiveWindow = 5 * time.Second
	subBuffer  = 256

	// SnapshotTail is how many of the newest entries a snapshot carries;
	// older ones are fetched with History, at most HistoryMax per page.
	SnapshotTail = 300
	HistoryMax   = 300
	// ImageCap and ImageBytesCap bound the per-pane image LRU (entries, and
	// len(data)+len(mediaType) summed).
	ImageCap      = 30
	ImageBytesCap = 40 << 20
	// QuestionTTL is how long a question stays pending without an /answer
	// poll for it.
	QuestionTTL = time.Hour
	// AnswerWait is how long the mod's /answer long-poll is held.
	AnswerWait = 25 * time.Second
	// SummaryMax caps an Activity's text, in runes.
	SummaryMax = 120
)

var (
	ErrNoMod      = errors.New("no_mod")
	ErrOutboxFull = errors.New("outbox_full")
	ErrEmpty      = errors.New("empty")
	ErrNoQuestion = errors.New("no_question")
)

type Entry struct {
	Seq   int             `json:"seq"`
	Event json.RawMessage `json:"event"`
}

// Snapshot is a pane's view: the newest SnapshotTail entries of the current
// epoch, of the main stream or (AgentID set) of one agent's thread. HasMore
// reports older entries reachable through History. Missing marks a thread
// the pane does not hold. Agents (sorted by ts) and Tasks are set on main
// snapshots only; Tasks is nil when the mod sent no list.
type Snapshot struct {
	PaneID  string
	AgentID string
	Epoch   int
	State   string
	Events  []Entry
	HasMore bool
	Missing bool
	Agents  []json.RawMessage
	Tasks   json.RawMessage
	// Commands is the session's slash commands (main snapshots only); nil
	// when the mod sent no list.
	Commands json.RawMessage
}

// Update is one change fanned out to a pane's subscribers. Main-stream
// subscribers get "event" | "state" | "snapshot" | "agent" (Agent is the
// merged summary) | "agent_removed" | "tasks" (Tasks is the list) |
// "commands" (Commands is the list); a
// thread's subscribers get only its "event" and "snapshot". AgentID names
// the thread or agent concerned.
type Update struct {
	Kind     string
	Epoch    int
	AgentID  string
	Entry    Entry
	State    string
	Snapshot Snapshot
	Agent    json.RawMessage
	Tasks    json.RawMessage
	Commands json.RawMessage
}

type OutMsg struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

// Image is one image the mod sent on the /sync side channel.
type Image struct {
	MediaType string `json:"mediaType"`
	Data      string `json:"data"`
}

// Activity is a one-line summary of a pane's latest chat event. Kind is
// "tool" | "text" | "user" | "question" | "notice"; Tool is set for "tool".
// TS is epoch milliseconds. Never mutated once built.
type Activity struct {
	Kind string `json:"kind"`
	Tool string `json:"tool,omitempty"`
	Text string `json:"text"`
	TS   int64  `json:"ts"`
}

// Ask is a pending AskUserQuestion: Questions is the question event's
// questions array, verbatim. Never mutated once built.
type Ask struct {
	ToolUseID string          `json:"toolUseId"`
	Questions json.RawMessage `json:"questions"`
}

// Context is a session's context-window fill as the mod reports it.
type Context struct {
	Percent int `json:"percent"`
	Tokens  int `json:"tokens,omitempty"`
	Window  int `json:"window"`
}

// Usage is a /sync body's `usage`: the session's context fill (nil before
// its first response) and the account's rate-limit windows.
type Usage struct {
	Context *Context        `json:"context"`
	Limits  []limits.Window `json:"limits"`
	// LimitsAt is when the mod measured Limits (epoch ms; 0 from an older mod).
	LimitsAt int64 `json:"limitsAt"`
}

// Summary is what the dashboard shows for a pane: its latest activity,
// newest pending question, number of running background tasks and context
// fill. All are zero while the pane's mod is not live.
type Summary struct {
	Activity  *Activity
	Ask       *Ask
	BgRunning int
	Context   *Context
}

func (s Summary) empty() bool {
	return s.Activity == nil && s.Ask == nil && s.BgRunning == 0 && s.Context == nil
}

func sameSummary(a, b Summary) bool {
	actEq := a.Activity == b.Activity || (a.Activity != nil && b.Activity != nil && *a.Activity == *b.Activity)
	askEq := a.Ask == b.Ask || (a.Ask != nil && b.Ask != nil && a.Ask.ToolUseID == b.Ask.ToolUseID && bytes.Equal(a.Ask.Questions, b.Ask.Questions))
	ctxEq := a.Context == b.Context || (a.Context != nil && b.Context != nil && *a.Context == *b.Context)
	return actEq && askEq && ctxEq && a.BgRunning == b.BgRunning
}

// question is a pending AskUserQuestion, keyed by its toolUseId. order ranks
// questions by arrival; questions is the event's questions array.
type question struct {
	added     time.Time
	answer    map[string]string
	order     int
	questions json.RawMessage
}

type pane struct {
	epoch, seq int
	sessionID  string
	state      string
	lastSeen   time.Time
	live       bool
	events     []Entry
	outbox     []OutMsg
	subs       map[int]chan Update

	// staging collects a chunked snapshot between snapshot_begin and
	// snapshot_end; nil when none is in progress.
	staging []json.RawMessage
	// images is an LRU; imageOrder lists its ids oldest first.
	images     map[string]Image
	imageOrder []string
	imageBytes int
	questions  map[string]*question
	qorder     int
	// activity is the latest activity-bearing chat event's summary.
	activity *Activity
	// context is the session's latest context fill (Usage.Context).
	context *Context
	// answered is closed (and cleared) when an answer is stored or the pane
	// is dropped, waking every WaitAnswer on the pane.
	answered chan struct{}

	// threads holds the agent threads (threads.go), agents the merged
	// summary object per agentId, tasks the latest background task list
	// (nil = none) and bgRunning its running count. All belong to the epoch.
	threads   map[string]*thread
	agents    map[string]json.RawMessage
	tasks     json.RawMessage
	bgRunning int

	// commands is the session's latest slash-command list (nil = none). It
	// belongs to the pane: a new epoch keeps it.
	commands json.RawMessage
}

type Hub struct {
	mu         sync.Mutex
	panes      map[string]*pane
	now        func() time.Time
	onLiveness func(paneID string, live bool)
	onSummary  func(paneID string, s Summary)
	onLimits   func([]limits.Window, int64)
	nextSub    int
	nextMsg    int
	answerWait time.Duration // the /answer hold; tests shorten it

	// cbMu serializes liveness and summary delivery; notified and summaries
	// are the last values delivered per pane. Callbacks must not call back
	// into the Hub.
	cbMu      sync.Mutex
	notified  map[string]bool
	summaries map[string]Summary
}

func NewHub(now func() time.Time) *Hub {
	if now == nil {
		now = time.Now
	}
	return &Hub{panes: map[string]*pane{}, now: now, onLiveness: func(string, bool) {}, onSummary: func(string, Summary) {}, onLimits: func([]limits.Window, int64) {},
		notified: map[string]bool{}, summaries: map[string]Summary{}, answerWait: AnswerWait}
}

// SetOnLiveness registers the callback for chat-capable flips. It is called
// without the hub's lock held. Set it before the hub is used.
func (h *Hub) SetOnLiveness(fn func(paneID string, live bool)) { h.onLiveness = fn }

// SetOnSummary registers the callback for summary changes (activity or
// pending question). It is called without the hub's lock held, serialized
// with liveness delivery, and only when the summary changed. A sync's whole
// batch yields at most one call. Set it before the hub is used.
func (h *Hub) SetOnSummary(fn func(paneID string, s Summary)) { h.onSummary = fn }

func (h *Hub) get(id string) *pane {
	p := h.panes[id]
	if p == nil {
		p = &pane{epoch: 1, state: "idle", subs: map[int]chan Update{}, images: map[string]Image{}, questions: map[string]*question{}}
		h.panes[id] = p
	}
	return p
}

// Sync applies the mod's queued events in order, records the heartbeat and
// drains the pane's outbox. The result is never nil.
func (h *Hub) Sync(paneID, sessionID string, events []json.RawMessage) []OutMsg {
	out, _ := h.SyncResync(paneID, sessionID, events)
	return out
}

// SyncResync is Sync that also reports whether the mod must resync (send
// hello + snapshot): the pane never received a hello (e.g. the companion
// restarted between two mod ticks), or the request's session id differs from
// the stored one and the batch carried no hello.
func (h *Hub) SyncResync(paneID, sessionID string, events []json.RawMessage) ([]OutMsg, bool) {
	return h.SyncBody(paneID, sessionID, events, nil)
}

// SyncBody is SyncResync for a whole /sync body. The events are applied
// first, then the images are stored. An image can arrive ahead of the
// snapshot chunk that references it, so no snapshot clears the store; the
// LRU evicts by age.
func (h *Hub) SyncBody(paneID, sessionID string, events []json.RawMessage, images map[string]Image) ([]OutMsg, bool) {
	h.mu.Lock()
	p := h.get(paneID)
	now := h.now()
	p.lastSeen = now
	p.live = true
	sawHello := false
	for _, ev := range events {
		if p.apply(paneID, sessionID, ev, now) {
			sawHello = true
		}
	}
	ids := make([]string, 0, len(images))
	for id := range images {
		ids = append(ids, id)
	}
	sort.Strings(ids) // deterministic eviction within one body
	for _, id := range ids {
		p.storeImage(id, images[id])
	}
	resync := p.sessionID == "" || (!sawHello && sessionID != p.sessionID)
	out := p.outbox
	p.outbox = nil
	h.mu.Unlock()
	h.notify(paneID) // liveness flip and/or summary change; deduped
	if out == nil {
		out = []OutMsg{}
	}
	return out, resync
}

func isChatEvent(t string) bool {
	switch t {
	case "user_text", "assistant_text", "tool_use", "tool_result", "task_notice", "question", "command_output":
		return true
	}
	return false
}

// apply applies one event and reports whether it was a hello.
func (p *pane) apply(paneID, sessionID string, raw json.RawMessage, now time.Time) bool {
	var head struct {
		Type      string            `json:"type"`
		SessionID string            `json:"sessionId"`
		State     string            `json:"state"`
		ToolUseID string            `json:"toolUseId"`
		Questions json.RawMessage   `json:"questions"`
		Events    []json.RawMessage `json:"events"`
		AgentID   string            `json:"agentId"`
		Agent     json.RawMessage   `json:"agent"`
		Tasks     json.RawMessage   `json:"tasks"`
		Commands  json.RawMessage   `json:"commands"`
	}
	if json.Unmarshal(raw, &head) != nil {
		return false
	}
	switch {
	case head.Type == "hello":
		if head.SessionID != "" {
			sessionID = head.SessionID
		}
		if p.sessionID != "" && p.sessionID != sessionID {
			p.outbox = nil // queued for the previous session
			p.setIdle()    // the old session's turn state does not carry over
			p.questions = map[string]*question{}
			p.dropStaging() // the old session's unfinished snapshots
		}
		p.sessionID = sessionID
		return true
	case head.AgentID != "" && (head.Type == "snapshot_begin" || head.Type == "snapshot_chunk" || head.Type == "snapshot_end"):
		p.applyThreadSnapshot(paneID, head.Type, head.AgentID, head.Events, now)
	case head.Type == "agent":
		p.applyAgent(head.Agent)
	case head.Type == "tasks":
		p.applyTasks(head.Tasks)
	case head.Type == "commands":
		p.applyCommands(head.Commands)
	case isChatEvent(head.Type) && head.AgentID != "":
		p.applyThreadEvent(head.AgentID, head.Type, raw, now)
	case head.Type == "snapshot" && head.AgentID != "":
		return false // a thread is only ever sent chunked: never a main swap
	case head.Type == "snapshot":
		p.staging = nil
		p.swap(paneID, head.Events, now)
	case head.Type == "snapshot_begin":
		p.staging = []json.RawMessage{} // discards an unfinished one
	case head.Type == "snapshot_chunk":
		if p.staging == nil {
			return false
		}
		p.staging = append(p.staging, head.Events...)
		if len(p.staging) > RingCap { // only the newest RingCap can survive the swap
			p.staging = append([]json.RawMessage(nil), p.staging[len(p.staging)-RingCap:]...)
		}
	case head.Type == "snapshot_end":
		if p.staging == nil {
			return false
		}
		evs := p.staging
		p.staging = nil
		p.swap(paneID, evs, now)
	case head.Type == "state":
		if head.State != "working" && head.State != "idle" {
			return false
		}
		p.state = head.State
		p.fan(Update{Kind: "state", Epoch: p.epoch, State: p.state})
	case isChatEvent(head.Type):
		p.observe(head.Type, head.ToolUseID, head.Questions, raw, now)
		e := p.push(raw)
		p.fan(Update{Kind: "event", Epoch: p.epoch, Entry: e})
	}
	return false
}

// swap starts a new epoch holding the chat events of evs (seqs from 1, the
// ring keeping the newest RingCap) and fans the snapshot. The old epoch's
// threads, agents and tasks go with it; the mod resends them after the main
// snapshot.
func (p *pane) swap(paneID string, evs []json.RawMessage, now time.Time) {
	p.epoch++
	p.resetAgents()
	p.seq = 0
	p.events = nil
	p.activity = nil // recomputed from the new history
	for _, ev := range evs {
		var inner struct {
			Type      string          `json:"type"`
			ToolUseID string          `json:"toolUseId"`
			Questions json.RawMessage `json:"questions"`
		}
		if json.Unmarshal(ev, &inner) == nil && isChatEvent(inner.Type) {
			p.observe(inner.Type, inner.ToolUseID, inner.Questions, ev, now)
			p.push(ev)
		}
	}
	p.fan(Update{Kind: "snapshot", Epoch: p.epoch, Snapshot: p.snapshot(paneID)})
}

// observe updates the pane's pending questions and latest activity for one
// chat event.
func (p *pane) observe(typ, toolUseID string, questions, raw json.RawMessage, now time.Time) {
	p.track(typ, toolUseID, questions, now)
	if a := activityOf(typ, raw, now); a != nil {
		p.activity = a
	}
}

// activityOf summarizes an activity-bearing chat event; nil for any other
// (tool_result) or a malformed one.
func activityOf(typ string, raw json.RawMessage, now time.Time) *Activity {
	var ev struct {
		Tool      string  `json:"tool"`
		Summary   string  `json:"summary"`
		Text      string  `json:"text"`
		Command   string  `json:"command"`
		TS        float64 `json:"ts"`
		Questions []struct {
			Question string `json:"question"`
		} `json:"questions"`
	}
	if json.Unmarshal(raw, &ev) != nil {
		return nil
	}
	a := &Activity{TS: int64(ev.TS)}
	if a.TS <= 0 {
		a.TS = now.UnixMilli()
	}
	switch typ {
	case "tool_use":
		a.Kind, a.Tool, a.Text = "tool", ev.Tool, oneLine(ev.Summary)
	case "assistant_text":
		a.Kind, a.Text = "text", oneLine(ev.Text)
	case "user_text":
		a.Kind, a.Text = "user", oneLine(ev.Text)
	case "task_notice":
		a.Kind, a.Text = "notice", oneLine(ev.Summary)
	case "command_output":
		a.Kind, a.Text = "user", oneLine(ev.Command)
	case "question":
		a.Kind = "question"
		if len(ev.Questions) > 0 {
			a.Text = oneLine(ev.Questions[0].Question)
		}
	default:
		return nil
	}
	return a
}

// oneLine returns s's first non-empty line, trimmed and capped at SummaryMax
// runes (an ellipsis marking a cut).
func oneLine(s string) string {
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if r := []rune(line); len(r) > SummaryMax {
			return string(r[:SummaryMax-1]) + "…"
		}
		return line
	}
	return ""
}

// summary is the pane's dashboard summary: empty unless the mod is live; Ask
// is the newest unanswered, unexpired question.
func (p *pane) summary(now time.Time) Summary {
	if p == nil || !p.live {
		return Summary{}
	}
	s := Summary{Activity: p.activity, BgRunning: p.bgRunning, Context: p.context}
	var best *question
	for id, q := range p.questions {
		if q.answer != nil || now.Sub(q.added) >= QuestionTTL || (best != nil && q.order < best.order) {
			continue
		}
		best = q
		qs := q.questions
		if len(qs) == 0 {
			qs = json.RawMessage("[]")
		}
		s.Ask = &Ask{ToolUseID: id, Questions: qs}
	}
	return s
}

// track maintains the pending questions: a question event adds its
// toolUseId (idempotent: a re-sent one keeps its age and stored answer), the
// tool_result for it removes it.
func (p *pane) track(typ, toolUseID string, questions json.RawMessage, now time.Time) {
	if toolUseID == "" {
		return
	}
	switch typ {
	case "question":
		if _, ok := p.questions[toolUseID]; !ok {
			p.qorder++
			p.questions[toolUseID] = &question{added: now, order: p.qorder, questions: append(json.RawMessage(nil), questions...)}
		}
	case "tool_result":
		delete(p.questions, toolUseID)
	}
}

// pending returns the unexpired pending question toolUseID, forgetting an
// expired one.
func (p *pane) pending(toolUseID string, now time.Time) *question {
	q := p.questions[toolUseID]
	if q != nil && now.Sub(q.added) >= QuestionTTL {
		delete(p.questions, toolUseID)
		return nil
	}
	return q
}

func imageSize(img Image) int { return len(img.Data) + len(img.MediaType) }

// storeImage adds img under id as the newest entry and evicts the oldest
// while the store holds more than ImageCap entries or ImageBytesCap bytes
// (an image alone over the byte cap is evicted at once).
func (p *pane) storeImage(id string, img Image) {
	if old, ok := p.images[id]; ok {
		p.imageBytes -= imageSize(old)
		p.touchImage(id)
	} else {
		p.imageOrder = append(p.imageOrder, id)
	}
	p.images[id] = img
	p.imageBytes += imageSize(img)
	for len(p.imageOrder) > ImageCap || (p.imageBytes > ImageBytesCap && len(p.imageOrder) > 0) {
		oldest := p.imageOrder[0]
		p.imageBytes -= imageSize(p.images[oldest])
		delete(p.images, oldest)
		p.imageOrder = p.imageOrder[1:]
	}
}

// touchImage moves id to the newest end of the LRU order.
func (p *pane) touchImage(id string) {
	for i, x := range p.imageOrder {
		if x == id {
			p.imageOrder = append(append(p.imageOrder[:i:i], p.imageOrder[i+1:]...), id)
			return
		}
	}
}

// setIdle resets a stale turn state (a pending message's timeout is paused
// while working, so a stuck "working" would pin it forever).
func (p *pane) setIdle() {
	if p.state != "idle" {
		p.state = "idle"
		p.fan(Update{Kind: "state", Epoch: p.epoch, State: p.state})
	}
}

func (p *pane) push(raw json.RawMessage) Entry {
	p.seq++
	e := Entry{Seq: p.seq, Event: append(json.RawMessage(nil), raw...)}
	p.events = appendCapped(p.events, e, RingCap)
	return e
}

func (p *pane) snapshot(paneID string) Snapshot {
	start := max(0, len(p.events)-SnapshotTail)
	ev := make([]Entry, len(p.events)-start)
	copy(ev, p.events[start:])
	return Snapshot{PaneID: paneID, Epoch: p.epoch, State: p.state, Events: ev, HasMore: start > 0, Agents: p.agentList(), Tasks: p.tasks, Commands: p.commands}
}

// fan delivers u to every main-stream subscriber without blocking.
func (p *pane) fan(u Update) { fanTo(p.subs, u) }

// Send queues text for the pane's mod. The pane must be chat-capable.
func (h *Hub) Send(paneID, text string) error {
	if strings.TrimSpace(text) == "" {
		return ErrEmpty
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	p := h.panes[paneID]
	if p == nil || !p.live {
		return ErrNoMod
	}
	if len(p.outbox) >= OutboxCap {
		return ErrOutboxFull
	}
	h.nextMsg++
	p.outbox = append(p.outbox, OutMsg{ID: "m" + strconv.Itoa(h.nextMsg), Text: text})
	return nil
}

// History returns up to min(limit, HistoryMax) entries of the pane's current
// epoch with seq < beforeSeq, ascending, and whether older ones remain: of the
// main stream, or of agentID's thread when set. ok is false when the pane or
// thread is unknown or epoch is not the pane's current one.
func (h *Hub) History(paneID, agentID string, epoch, beforeSeq, limit int) (events []Entry, hasMore bool, ok bool) {
	if limit <= 0 || limit > HistoryMax {
		limit = HistoryMax
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	p := h.panes[paneID]
	if p == nil || p.epoch != epoch {
		return nil, false, false
	}
	ring := p.events
	if agentID != "" {
		t := p.threads[agentID]
		if t == nil {
			return nil, false, false
		}
		ring = t.events
	}
	events, hasMore = page(ring, beforeSeq, limit)
	return events, hasMore, true
}

// Image returns a stored image of the pane and marks it recently used.
func (h *Hub) Image(paneID, id string) (mediaType, data string, ok bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	p := h.panes[paneID]
	if p == nil {
		return "", "", false
	}
	img, ok := p.images[id]
	if !ok {
		return "", "", false
	}
	p.touchImage(id)
	return img.MediaType, img.Data, true
}

// Answer stores the phone's answers (question text -> answer) to the pending
// question toolUseID and wakes the mod's waiting /answer poll.
func (h *Hub) Answer(paneID, toolUseID string, answers map[string]string) error {
	if len(answers) == 0 {
		return ErrEmpty
	}
	h.mu.Lock()
	p := h.panes[paneID]
	if p == nil || !p.live {
		h.mu.Unlock()
		return ErrNoMod
	}
	q := p.pending(toolUseID, h.now())
	if q == nil {
		h.mu.Unlock()
		return ErrNoQuestion
	}
	q.answer = maps.Clone(answers)
	if p.answered != nil {
		close(p.answered)
		p.answered = nil
	}
	h.mu.Unlock()
	h.notify(paneID) // an answered question is no longer asked
	return nil
}

// WaitAnswer refreshes the pending question's TTL and returns its stored
// answer, waiting up to timeout
// (or until ctx ends) for one. The question need not be known yet: the mod's
// poll can overtake the sync carrying its question event. The pane must
// exist (the /answer handler records a heartbeat first).
func (h *Hub) WaitAnswer(ctx context.Context, paneID, toolUseID string, timeout time.Duration) (map[string]string, bool) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		h.mu.Lock()
		p := h.panes[paneID]
		if p == nil {
			h.mu.Unlock()
			return nil, false
		}
		now := h.now()
		// A poll keeps its question alive: QuestionTTL counts from the last
		// poll, so a dialog left open for hours can still be answered.
		if q := p.questions[toolUseID]; q != nil {
			q.added = now
		}
		if q := p.pending(toolUseID, now); q != nil && q.answer != nil {
			a := maps.Clone(q.answer)
			h.mu.Unlock()
			return a, true
		}
		if p.answered == nil {
			p.answered = make(chan struct{})
		}
		wake := p.answered
		h.mu.Unlock()
		select {
		case <-wake:
		case <-timer.C:
			return nil, false
		case <-ctx.Done():
			return nil, false
		}
	}
}

// Heartbeat records a sign of life from the pane's mod (an /answer poll)
// without applying anything.
func (h *Hub) Heartbeat(paneID string) {
	h.mu.Lock()
	p := h.get(paneID)
	p.lastSeen = h.now()
	flipped := !p.live
	p.live = true
	h.mu.Unlock()
	if flipped {
		h.notify(paneID)
	}
}

func (h *Hub) Live(paneID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	p := h.panes[paneID]
	return p != nil && p.live
}

// Subscribe returns the pane's current snapshot and a channel of later
// updates: of the main stream, or of agentID's thread when set. A thread the
// pane does not hold yields Missing and a channel that receives nothing until
// cancel. A thread subscription's channel is closed when the thread is
// evicted or discarded by a main resync. cancel closes the channel; it is
// safe to call more than once and after Drop.
func (h *Hub) Subscribe(paneID, agentID string) (Snapshot, <-chan Update, func()) {
	h.mu.Lock()
	p := h.get(paneID)
	h.nextSub++
	id := h.nextSub
	ch := make(chan Update, subBuffer)
	registered := true
	var snap Snapshot
	switch t := p.threads[agentID]; {
	case agentID == "":
		p.subs[id] = ch
		snap = p.snapshot(paneID)
	case t != nil:
		t.subs[id] = ch
		snap = t.snapshot(p, paneID, agentID)
	default:
		registered = false
		snap = Snapshot{PaneID: paneID, AgentID: agentID, Epoch: p.epoch, State: p.state, Events: []Entry{}, Missing: true}
	}
	h.mu.Unlock()
	var once sync.Once
	cancel := func() {
		once.Do(func() {
			h.mu.Lock()
			defer h.mu.Unlock()
			if !registered {
				close(ch)
				return
			}
			subs := h.subsOf(paneID, agentID)
			if c, ok := subs[id]; ok { // absent once closed (evicted, resync, Drop)
				delete(subs, id)
				close(c)
			}
		})
	}
	return snap, ch, cancel
}

// subsOf returns the subscriber set of the pane's main stream (agentID "")
// or of one thread; nil when the pane or thread is gone. Caller holds mu.
func (h *Hub) subsOf(paneID, agentID string) map[int]chan Update {
	p := h.panes[paneID]
	switch {
	case p == nil:
		return nil
	case agentID == "":
		return p.subs
	case p.threads[agentID] != nil:
		return p.threads[agentID].subs
	}
	return nil
}

// Tick expires panes whose mod has not synced within LiveWindow and resets
// their turn state to idle, and forgets questions pending for QuestionTTL.
func (h *Hub) Tick() {
	now := h.now()
	var dead []string
	h.mu.Lock()
	for id, p := range h.panes {
		n := len(p.questions)
		for tid := range p.questions {
			p.pending(tid, now) // forgets an expired one
		}
		if p.live && now.Sub(p.lastSeen) >= LiveWindow {
			p.live = false
			p.outbox = nil  // never deliver stale messages to a later session
			p.setIdle()     // a dead mod is not working
			p.dropStaging() // it restarts its chunked snapshots after recovery
			dead = append(dead, id)
		} else if len(p.questions) != n {
			dead = append(dead, id) // still live, but its ask may have changed
		}
	}
	h.mu.Unlock()
	for _, id := range dead { // liveness and/or summary changed
		h.notify(id)
	}
}

// Drop forgets a pane that herdr no longer reports, closing its subscribers
// and ending its waiting /answer polls.
func (h *Hub) Drop(paneID string) {
	h.mu.Lock()
	p := h.panes[paneID]
	delete(h.panes, paneID)
	if p != nil && p.answered != nil {
		close(p.answered)
		p.answered = nil
	}
	h.mu.Unlock()
	// Forget the last delivered value so a later pane with this id notifies
	// afresh (and the map stays bounded). Taken after mu is released: notify
	// holds cbMu then takes mu.
	h.cbMu.Lock()
	delete(h.notified, paneID)
	if !h.summaries[paneID].empty() {
		h.onSummary(paneID, Summary{})
	}
	delete(h.summaries, paneID)
	h.cbMu.Unlock()
	if p == nil {
		return
	}
	closeSubs(p.subs)
	for _, t := range p.threads {
		closeSubs(t.subs)
	}
}

// notify delivers the pane's current liveness and summary to the observers,
// each only when it changed. Delivery is serialized and re-reads the truth
// under the delivery lock, so a stale value can never land after a newer one.
func (h *Hub) notify(paneID string) {
	h.cbMu.Lock()
	defer h.cbMu.Unlock()
	h.mu.Lock()
	p := h.panes[paneID]
	live := p != nil && p.live
	sum := p.summary(h.now())
	h.mu.Unlock()
	if h.notified[paneID] != live {
		h.notified[paneID] = live
		h.onLiveness(paneID, live)
	}
	if !sameSummary(h.summaries[paneID], sum) {
		if sum.empty() {
			delete(h.summaries, paneID)
		} else {
			h.summaries[paneID] = sum
		}
		h.onSummary(paneID, sum)
	}
}

// SetOnLimits registers the callback for a /sync's rate-limit windows and
// their measurement time (Usage.LimitsAt). It is called without the hub's
// lock held, once per /sync that carries any. Set it before the hub is used.
func (h *Hub) SetOnLimits(fn func([]limits.Window, int64)) { h.onLimits = fn }

// SetUsage records a /sync body's usage, ahead of SyncBody (whose summary
// delivery then carries the context). nil (an older mod) changes nothing;
// a usage without context clears the pane's.
func (h *Hub) SetUsage(paneID string, u *Usage) {
	if u == nil {
		return
	}
	h.mu.Lock()
	h.get(paneID).context = u.Context
	h.mu.Unlock()
	if len(u.Limits) > 0 {
		h.onLimits(u.Limits, u.LimitsAt)
	}
}
