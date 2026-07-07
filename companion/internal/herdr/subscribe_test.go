package herdr

import (
	"context"
	"testing"
	"time"
)

func TestSubscribeStreamsEvents(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ch, err := c.Subscribe(ctx, "w6:p1", "pane.agent_status_changed")
	if err != nil {
		t.Fatal(err)
	}
	// give the subscription time to register
	time.Sleep(50 * time.Millisecond)
	f.PushEvent(Event{Type: "pane.agent_status_changed", PaneID: "w6:p1", AgentStatus: "blocked"})
	select {
	case e := <-ch:
		if e.AgentStatus != "blocked" || e.PaneID != "w6:p1" {
			t.Fatalf("bad event: %+v", e)
		}
	case <-time.After(time.Second):
		t.Fatal("no event received")
	}
}

func TestSubscribeClosesChannelOnCancel(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	ctx, cancel := context.WithCancel(context.Background())
	ch, err := c.Subscribe(ctx, "w6:p1", "pane.agent_status_changed")
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(50 * time.Millisecond)
	cancel()
	select {
	case _, ok := <-ch:
		// draining then closed is fine; we just require it eventually closes
		for ok {
			_, ok = <-ch
		}
	case <-time.After(2 * time.Second):
		t.Fatal("channel not closed within 2s of ctx cancel")
	}
}
