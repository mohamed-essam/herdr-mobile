// Package chatbridge relays Claude Code conversations from the herdr-chat mod
// (one per Claude session in a herdr pane) to the app's chat view, and carries
// messages typed on the phone back to the mod.
package chatbridge

import (
	"context"
	"encoding/json"
	"errors"
	"maps"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
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
	// ImageCap is the per-pane image LRU size.
	ImageCap = 30
	// QuestionTTL is how long an unanswered question stays pending.
	QuestionTTL = time.Hour
	// AnswerWait is how long the mod's /answer long-poll is held.
	AnswerWait = 25 * time.Second
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
// epoch. HasMore reports older entries reachable through History.
type Snapshot struct {
	PaneID  string
	Epoch   int
	State   string
	Events  []Entry
	HasMore bool
}

// Update is one change fanned out to a pane's subscribers.
type Update struct {
	Kind     string // "event" | "state" | "snapshot"
	Epoch    int
	Entry    Entry
	State    string
	Snapshot Snapshot
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

// question is a pending AskUserQuestion, keyed by its toolUseId.
type question struct {
	added  time.Time
	answer map[string]string
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
	questions  map[string]*question
	// answered is closed (and cleared) when an answer is stored or the pane
	// is dropped, waking every WaitAnswer on the pane.
	answered chan struct{}
}

type Hub struct {
	mu         sync.Mutex
	panes      map[string]*pane
	now        func() time.Time
	onLiveness func(paneID string, live bool)
	nextSub    int
	nextMsg    int
	answerWait time.Duration // the /answer hold; tests shorten it

	// cbMu serializes liveness delivery; notified is the last value delivered
	// per pane. Callbacks must not call back into the Hub.
	cbMu     sync.Mutex
	notified map[string]bool
}

func NewHub(now func() time.Time) *Hub {
	if now == nil {
		now = time.Now
	}
	return &Hub{panes: map[string]*pane{}, now: now, onLiveness: func(string, bool) {}, notified: map[string]bool{}, answerWait: AnswerWait}
}

// SetOnLiveness registers the callback for chat-capable flips. It is called
// without the hub's lock held. Set it before the hub is used.
func (h *Hub) SetOnLiveness(fn func(paneID string, live bool)) { h.onLiveness = fn }

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
	flipped := !p.live
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
	if flipped {
		h.notify(paneID)
	}
	if out == nil {
		out = []OutMsg{}
	}
	return out, resync
}

func isChatEvent(t string) bool {
	switch t {
	case "user_text", "assistant_text", "tool_use", "tool_result", "task_notice", "question":
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
		Events    []json.RawMessage `json:"events"`
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
		}
		p.sessionID = sessionID
		return true
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
		p.track(head.Type, head.ToolUseID, now)
		e := p.push(raw)
		p.fan(Update{Kind: "event", Epoch: p.epoch, Entry: e})
	}
	return false
}

// swap starts a new epoch holding the chat events of evs (seqs from 1, the
// ring keeping the newest RingCap) and fans the snapshot.
func (p *pane) swap(paneID string, evs []json.RawMessage, now time.Time) {
	p.epoch++
	p.seq = 0
	p.events = nil
	for _, ev := range evs {
		var inner struct {
			Type      string `json:"type"`
			ToolUseID string `json:"toolUseId"`
		}
		if json.Unmarshal(ev, &inner) == nil && isChatEvent(inner.Type) {
			p.track(inner.Type, inner.ToolUseID, now)
			p.push(ev)
		}
	}
	p.fan(Update{Kind: "snapshot", Epoch: p.epoch, Snapshot: p.snapshot(paneID)})
}

// track maintains the pending questions: a question event adds its
// toolUseId (idempotent: a re-sent one keeps its age and stored answer), the
// tool_result for it removes it.
func (p *pane) track(typ, toolUseID string, now time.Time) {
	if toolUseID == "" {
		return
	}
	switch typ {
	case "question":
		if _, ok := p.questions[toolUseID]; !ok {
			p.questions[toolUseID] = &question{added: now}
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

func (p *pane) storeImage(id string, img Image) {
	if _, ok := p.images[id]; ok {
		p.touchImage(id)
	} else {
		p.imageOrder = append(p.imageOrder, id)
	}
	p.images[id] = img
	for len(p.imageOrder) > ImageCap {
		delete(p.images, p.imageOrder[0])
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
	p.events = append(p.events, e)
	if len(p.events) > RingCap {
		// Reslice instead of copying the ring on every push: the backing
		// array's next growth copies only the live window. The dropped slot
		// is cleared so its event can be collected.
		p.events[0] = Entry{}
		p.events = p.events[len(p.events)-RingCap:]
	}
	return e
}

func (p *pane) snapshot(paneID string) Snapshot {
	start := max(0, len(p.events)-SnapshotTail)
	ev := make([]Entry, len(p.events)-start)
	copy(ev, p.events[start:])
	return Snapshot{PaneID: paneID, Epoch: p.epoch, State: p.state, Events: ev, HasMore: start > 0}
}

// fan delivers u to every subscriber without blocking: a subscriber whose
// buffer is full misses the update rather than stalling the mod's heartbeat.
func (p *pane) fan(u Update) {
	for _, ch := range p.subs {
		select {
		case ch <- u:
		default:
		}
	}
}

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
// epoch with seq < beforeSeq, ascending, and whether older ones remain. ok is
// false when the pane is unknown or epoch is not its current one.
func (h *Hub) History(paneID string, epoch, beforeSeq, limit int) (events []Entry, hasMore bool, ok bool) {
	if limit <= 0 || limit > HistoryMax {
		limit = HistoryMax
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	p := h.panes[paneID]
	if p == nil || p.epoch != epoch {
		return nil, false, false
	}
	end := sort.Search(len(p.events), func(i int) bool { return p.events[i].Seq >= beforeSeq })
	start := max(0, end-limit)
	events = make([]Entry, end-start)
	copy(events, p.events[start:end])
	return events, start > 0, true
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
	h.mu.Lock()
	defer h.mu.Unlock()
	p := h.panes[paneID]
	if p == nil || !p.live {
		return ErrNoMod
	}
	q := p.pending(toolUseID, h.now())
	if q == nil {
		return ErrNoQuestion
	}
	q.answer = maps.Clone(answers)
	if q.answer == nil {
		q.answer = map[string]string{}
	}
	if p.answered != nil {
		close(p.answered)
		p.answered = nil
	}
	return nil
}

// WaitAnswer returns the stored answer to toolUseID, waiting up to timeout
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
		if q := p.pending(toolUseID, h.now()); q != nil && q.answer != nil {
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
// updates. cancel closes the channel; it is safe to call more than once and
// after Drop.
func (h *Hub) Subscribe(paneID string) (Snapshot, <-chan Update, func()) {
	h.mu.Lock()
	p := h.get(paneID)
	h.nextSub++
	id := h.nextSub
	ch := make(chan Update, subBuffer)
	p.subs[id] = ch
	snap := p.snapshot(paneID)
	h.mu.Unlock()
	var once sync.Once
	cancel := func() {
		once.Do(func() {
			h.mu.Lock()
			defer h.mu.Unlock()
			if q := h.panes[paneID]; q != nil {
				if c, ok := q.subs[id]; ok {
					delete(q.subs, id)
					close(c)
				}
			}
		})
	}
	return snap, ch, cancel
}

// Tick expires panes whose mod has not synced within LiveWindow and resets
// their turn state to idle, and forgets questions pending for QuestionTTL.
func (h *Hub) Tick() {
	now := h.now()
	var dead []string
	h.mu.Lock()
	for id, p := range h.panes {
		for tid := range p.questions {
			p.pending(tid, now) // forgets an expired one
		}
		if p.live && now.Sub(p.lastSeen) >= LiveWindow {
			p.live = false
			p.outbox = nil // never deliver stale messages to a later session
			p.setIdle()    // a dead mod is not working
			dead = append(dead, id)
		}
	}
	h.mu.Unlock()
	for _, id := range dead {
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
	h.cbMu.Unlock()
	if p == nil {
		return
	}
	for id, ch := range p.subs {
		delete(p.subs, id)
		close(ch)
	}
}

// notify delivers the pane's current liveness to the observer. Delivery is
// serialized and re-reads the truth under the delivery lock, so a stale flip
// can never land after a newer one.
func (h *Hub) notify(paneID string) {
	h.cbMu.Lock()
	defer h.cbMu.Unlock()
	live := h.Live(paneID)
	if h.notified[paneID] == live {
		return
	}
	h.notified[paneID] = live
	h.onLiveness(paneID, live)
}
