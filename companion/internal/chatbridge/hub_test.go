package chatbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"
)

func raw(s string) json.RawMessage { return json.RawMessage(s) }

func userText(n int) json.RawMessage {
	return raw(fmt.Sprintf(`{"type":"user_text","uuid":"u%d","text":"m%d"}`, n, n))
}

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) now() time.Time      { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *fakeClock) add(d time.Duration) { c.mu.Lock(); c.t = c.t.Add(d); c.mu.Unlock() }

func TestSnapshotResetsEpochAndSeq(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s1", []json.RawMessage{raw(`{"type":"hello","sessionId":"s1","cwd":"/x"}`), raw(`{"type":"snapshot","events":[` + string(userText(1)) + `,{"type":"bogus"}]}`)})
	snap, _, cancel := h.Subscribe("p")
	defer cancel()
	if snap.Epoch != 2 || len(snap.Events) != 1 || snap.Events[0].Seq != 1 {
		t.Fatalf("after first snapshot: %+v", snap)
	}
	h.Sync("p", "s1", []json.RawMessage{userText(2)})
	h.Sync("p", "s2", []json.RawMessage{raw(`{"type":"snapshot","events":[]}`)})
	snap2, _, cancel2 := h.Subscribe("p")
	defer cancel2()
	if snap2.Epoch != 3 || len(snap2.Events) != 0 {
		t.Fatalf("after second snapshot: %+v", snap2)
	}
}

func TestEventsGetMonotonicSeqAndFanOut(t *testing.T) {
	h := NewHub(nil)
	_, ch, cancel := h.Subscribe("p")
	defer cancel()
	h.Sync("p", "s", []json.RawMessage{userText(1), userText(2), raw(`{"type":"state","state":"working"}`)})
	u1, u2, u3 := <-ch, <-ch, <-ch
	if u1.Kind != "event" || u1.Entry.Seq != 1 || u2.Entry.Seq != 2 {
		t.Fatalf("bad event updates: %+v %+v", u1, u2)
	}
	if u3.Kind != "state" || u3.State != "working" {
		t.Fatalf("bad state update: %+v", u3)
	}
}

func TestInvalidStateIgnored(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"state","state":"exploded"}`), raw(`not json`)})
	snap, _, cancel := h.Subscribe("p")
	defer cancel()
	if snap.State != "idle" {
		t.Fatalf("state = %q, want idle", snap.State)
	}
}

func TestRingCap(t *testing.T) {
	h := NewHub(nil)
	evs := make([]json.RawMessage, 0, RingCap+10)
	for i := 1; i <= RingCap+10; i++ {
		evs = append(evs, userText(i))
	}
	h.Sync("p", "s", evs)
	snap, _, cancel := h.Subscribe("p")
	defer cancel()
	if len(snap.Events) != RingCap || snap.Events[0].Seq != 11 {
		t.Fatalf("ring: len=%d first=%d", len(snap.Events), snap.Events[0].Seq)
	}
}

func TestSendRequiresLivePaneAndDrains(t *testing.T) {
	h := NewHub(nil)
	if err := h.Send("p", "hi"); !errors.Is(err, ErrNoMod) {
		t.Fatalf("send to unknown pane: %v", err)
	}
	h.Sync("p", "s", nil)
	if err := h.Send("p", "  "); !errors.Is(err, ErrEmpty) {
		t.Fatalf("blank send: %v", err)
	}
	if err := h.Send("p", "hi"); err != nil {
		t.Fatal(err)
	}
	out := h.Sync("p", "s", nil)
	if len(out) != 1 || out[0].Text != "hi" || out[0].ID == "" {
		t.Fatalf("drain: %+v", out)
	}
	if again := h.Sync("p", "s", nil); len(again) != 0 || again == nil {
		t.Fatalf("second drain should be empty non-nil, got %#v", again)
	}
}

func TestOutboxCap(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", nil)
	for i := 0; i < OutboxCap; i++ {
		if err := h.Send("p", "x"); err != nil {
			t.Fatal(err)
		}
	}
	if err := h.Send("p", "x"); !errors.Is(err, ErrOutboxFull) {
		t.Fatalf("over cap: %v", err)
	}
}

func TestLivenessFlips(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	var mu sync.Mutex
	var flips []string
	h.SetOnLiveness(func(id string, live bool) {
		mu.Lock()
		flips = append(flips, fmt.Sprintf("%s=%v", id, live))
		mu.Unlock()
	})
	h.Sync("p", "s", nil)
	h.Sync("p", "s", nil) // already live: no second flip
	c.add(4 * time.Second)
	h.Tick()
	if !h.Live("p") {
		t.Fatal("should still be live at 4s")
	}
	c.add(time.Second)
	h.Tick()
	if h.Live("p") {
		t.Fatal("should be dead at 5s")
	}
	mu.Lock()
	defer mu.Unlock()
	if fmt.Sprint(flips) != "[p=true p=false]" {
		t.Fatalf("flips = %v", flips)
	}
}

func TestFullSubscriberDoesNotBlockSync(t *testing.T) {
	h := NewHub(nil)
	_, _, cancel := h.Subscribe("p") // never read
	defer cancel()
	done := make(chan struct{})
	go func() {
		for i := 0; i < subBuffer*3; i++ {
			h.Sync("p", "s", []json.RawMessage{userText(i)})
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Sync blocked on a full subscriber")
	}
}

func TestDropClosesSubscribersAndCancelAfterDropIsSafe(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", nil)
	_, ch, cancel := h.Subscribe("p")
	h.Drop("p")
	if _, ok := <-ch; ok {
		t.Fatal("channel should be closed after Drop")
	}
	cancel() // must not panic (double close)
	if h.Live("p") {
		t.Fatal("dropped pane should not be live")
	}
}

func TestSnapshotUpdateFansOut(t *testing.T) {
	h := NewHub(nil)
	_, ch, cancel := h.Subscribe("p")
	defer cancel()
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot","events":[` + string(userText(1)) + `]}`)})
	u := <-ch
	if u.Kind != "snapshot" || u.Snapshot.PaneID != "p" || len(u.Snapshot.Events) != 1 {
		t.Fatalf("snapshot update: %+v", u)
	}
}
