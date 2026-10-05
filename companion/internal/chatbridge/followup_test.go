package chatbridge

import (
	"testing"
	"time"
)

// L2: background-task notifications are chat events.
func TestTaskNoticeIsAcceptedAndFanned(t *testing.T) {
	h := NewHub(nil)
	notice := `{"type":"task_notice","uuid":"d1#0","status":"completed","summary":"done"}`
	h.Sync("p", "s", raws(`{"type":"hello","sessionId":"s","cwd":"/x"}`, `{"type":"snapshot","events":[`+notice+`]}`))
	snap, ch, cancel := h.Subscribe("p", "")
	defer cancel()
	if len(snap.Events) != 1 || string(snap.Events[0].Event) != notice {
		t.Fatalf("snapshot dropped task_notice: %+v", snap.Events)
	}
	h.Sync("p", "s", raws(notice))
	u := <-ch
	if u.Kind != "event" || u.Entry.Seq != 2 || string(u.Entry.Event) != notice {
		t.Fatalf("live task_notice not fanned: %+v", u)
	}
}

func drainState(t *testing.T, ch <-chan Update) Update {
	t.Helper()
	select {
	case u := <-ch:
		return u
	case <-time.After(time.Second):
		t.Fatal("no update")
	}
	return Update{}
}

// P1: a pane that stops syncing while working goes back to idle.
func TestTickResetsWorkingToIdle(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	h.Sync("p", "s", raws(`{"type":"hello","sessionId":"s","cwd":"/x"}`, `{"type":"state","state":"working"}`))
	_, ch, cancel := h.Subscribe("p", "")
	defer cancel()
	c.add(LiveWindow)
	h.Tick()
	u := drainState(t, ch)
	if u.Kind != "state" || u.State != "idle" {
		t.Fatalf("want idle state update, got %+v", u)
	}
	snap, _, cancel2 := h.Subscribe("p", "")
	defer cancel2()
	if snap.State != "idle" {
		t.Fatalf("state = %q", snap.State)
	}
}

func TestTickOnIdlePaneFansNothing(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	h.Sync("p", "s", raws(`{"type":"hello","sessionId":"s","cwd":"/x"}`))
	_, ch, cancel := h.Subscribe("p", "")
	defer cancel()
	c.add(LiveWindow)
	h.Tick()
	select {
	case u := <-ch:
		t.Fatalf("unexpected update %+v", u)
	default:
	}
}

// P1: a new session in the pane starts idle.
func TestHelloWithNewSessionResetsState(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s1", raws(`{"type":"hello","sessionId":"s1","cwd":"/x"}`, `{"type":"state","state":"working"}`))
	_, ch, cancel := h.Subscribe("p", "")
	defer cancel()
	h.Sync("p", "s2", raws(`{"type":"hello","sessionId":"s2","cwd":"/x"}`))
	u := drainState(t, ch)
	if u.Kind != "state" || u.State != "idle" {
		t.Fatalf("want idle state update, got %+v", u)
	}
	// Same session again: no reset.
	h.Sync("p", "s2", raws(`{"type":"state","state":"working"}`, `{"type":"hello","sessionId":"s2","cwd":"/x"}`))
	if u := drainState(t, ch); u.State != "working" {
		t.Fatalf("got %+v", u)
	}
	select {
	case u := <-ch:
		t.Fatalf("same-session hello fanned %+v", u)
	default:
	}
}
