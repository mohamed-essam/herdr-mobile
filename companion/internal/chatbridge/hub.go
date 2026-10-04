// Package chatbridge relays Claude Code conversations from the herdr-chat mod
// (one per Claude session in a herdr pane) to the app's chat view, and carries
// messages typed on the phone back to the mod.
package chatbridge

import (
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	RingCap    = 500
	OutboxCap  = 20
	LiveWindow = 5 * time.Second
	subBuffer  = 256
)

var (
	ErrNoMod      = errors.New("no_mod")
	ErrOutboxFull = errors.New("outbox_full")
	ErrEmpty      = errors.New("empty")
)

type Entry struct {
	Seq   int             `json:"seq"`
	Event json.RawMessage `json:"event"`
}

type Snapshot struct {
	PaneID string
	Epoch  int
	State  string
	Events []Entry
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

type pane struct {
	epoch, seq int
	sessionID  string
	state      string
	lastSeen   time.Time
	live       bool
	events     []Entry
	outbox     []OutMsg
	subs       map[int]chan Update
}

type Hub struct {
	mu         sync.Mutex
	panes      map[string]*pane
	now        func() time.Time
	onLiveness func(paneID string, live bool)
	nextSub    int
	nextMsg    int

	// cbMu serializes liveness delivery; notified is the last value delivered
	// per pane. Callbacks must not call back into the Hub.
	cbMu     sync.Mutex
	notified map[string]bool
}

func NewHub(now func() time.Time) *Hub {
	if now == nil {
		now = time.Now
	}
	return &Hub{panes: map[string]*pane{}, now: now, onLiveness: func(string, bool) {}, notified: map[string]bool{}}
}

// SetOnLiveness registers the callback for chat-capable flips. It is called
// without the hub's lock held. Set it before the hub is used.
func (h *Hub) SetOnLiveness(fn func(paneID string, live bool)) { h.onLiveness = fn }

func (h *Hub) get(id string) *pane {
	p := h.panes[id]
	if p == nil {
		p = &pane{epoch: 1, state: "idle", subs: map[int]chan Update{}}
		h.panes[id] = p
	}
	return p
}

// Sync applies the mod's queued events in order, records the heartbeat and
// drains the pane's outbox. The result is never nil.
func (h *Hub) Sync(paneID, sessionID string, events []json.RawMessage) []OutMsg {
	h.mu.Lock()
	p := h.get(paneID)
	p.lastSeen = h.now()
	flipped := !p.live
	p.live = true
	for _, ev := range events {
		p.apply(paneID, sessionID, ev)
	}
	out := p.outbox
	p.outbox = nil
	h.mu.Unlock()
	if flipped {
		h.notify(paneID)
	}
	if out == nil {
		out = []OutMsg{}
	}
	return out
}

func isChatEvent(t string) bool {
	switch t {
	case "user_text", "assistant_text", "tool_use", "tool_result":
		return true
	}
	return false
}

func (p *pane) apply(paneID, sessionID string, raw json.RawMessage) {
	var head struct {
		Type      string            `json:"type"`
		SessionID string            `json:"sessionId"`
		State     string            `json:"state"`
		Events    []json.RawMessage `json:"events"`
	}
	if json.Unmarshal(raw, &head) != nil {
		return
	}
	switch {
	case head.Type == "hello":
		if head.SessionID != "" {
			sessionID = head.SessionID
		}
		if p.sessionID != "" && p.sessionID != sessionID {
			p.outbox = nil // queued for the previous session
		}
		p.sessionID = sessionID
	case head.Type == "snapshot":
		p.epoch++
		p.seq = 0
		p.events = nil
		for _, ev := range head.Events {
			var inner struct {
				Type string `json:"type"`
			}
			if json.Unmarshal(ev, &inner) == nil && isChatEvent(inner.Type) {
				p.push(ev)
			}
		}
		p.fan(Update{Kind: "snapshot", Epoch: p.epoch, Snapshot: p.snapshot(paneID)})
	case head.Type == "state":
		if head.State != "working" && head.State != "idle" {
			return
		}
		p.state = head.State
		p.fan(Update{Kind: "state", Epoch: p.epoch, State: p.state})
	case isChatEvent(head.Type):
		e := p.push(raw)
		p.fan(Update{Kind: "event", Epoch: p.epoch, Entry: e})
	}
}

func (p *pane) push(raw json.RawMessage) Entry {
	p.seq++
	e := Entry{Seq: p.seq, Event: append(json.RawMessage(nil), raw...)}
	p.events = append(p.events, e)
	if len(p.events) > RingCap {
		p.events = append([]Entry(nil), p.events[len(p.events)-RingCap:]...)
	}
	return e
}

func (p *pane) snapshot(paneID string) Snapshot {
	ev := make([]Entry, len(p.events))
	copy(ev, p.events)
	return Snapshot{PaneID: paneID, Epoch: p.epoch, State: p.state, Events: ev}
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

// Tick expires panes whose mod has not synced within LiveWindow.
func (h *Hub) Tick() {
	now := h.now()
	var dead []string
	h.mu.Lock()
	for id, p := range h.panes {
		if p.live && now.Sub(p.lastSeen) >= LiveWindow {
			p.live = false
			p.outbox = nil // never deliver stale messages to a later session
			dead = append(dead, id)
		}
	}
	h.mu.Unlock()
	for _, id := range dead {
		h.notify(id)
	}
}

// Drop forgets a pane that herdr no longer reports, closing its subscribers.
func (h *Hub) Drop(paneID string) {
	h.mu.Lock()
	p := h.panes[paneID]
	delete(h.panes, paneID)
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
