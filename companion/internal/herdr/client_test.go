package herdr

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestClientListPanes(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetPanes([]PaneInfo{{PaneID: "w6:p1", WorkspaceID: "w6", Agent: "claude", AgentStatus: "working"}})
	c := New(f.SocketPath())
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	panes, err := c.ListPanes(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(panes) != 1 || panes[0].PaneID != "w6:p1" {
		t.Fatalf("bad panes: %+v", panes)
	}
}

func TestClientSendTextReachesHerdr(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	if err := c.SendText(context.Background(), "w6:p1", "y"); err != nil {
		t.Fatal(err)
	}
	select {
	case params := <-f.lastSend:
		if params["pane_id"] != "w6:p1" || params["text"] != "y" {
			t.Fatalf("bad send params: %+v", params)
		}
	case <-time.After(time.Second):
		t.Fatal("send_text never reached herdr")
	}
}

func TestClientSendKeysSendsArray(t *testing.T) {
	// herdr requires `keys` to be a sequence (array); a bare string is rejected.
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	if err := c.SendKeys(context.Background(), "w6:p1", "enter"); err != nil {
		t.Fatal(err)
	}
	select {
	case params := <-f.lastSend:
		keys, ok := params["keys"].([]any)
		if !ok || len(keys) != 1 || keys[0] != "enter" {
			t.Fatalf("keys must be a 1-element array [\"enter\"], got %#v", params["keys"])
		}
	case <-time.After(time.Second):
		t.Fatal("send_keys never reached herdr")
	}
}

func TestClientCallPropagatesRPCError(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	_, err := c.Call(context.Background(), "bogus.method", nil)
	if err == nil {
		t.Fatal("expected error for unknown method")
	}
	var rpcErr *RPCError
	if !errors.As(err, &rpcErr) {
		t.Fatalf("expected *RPCError, got %T: %v", err, err)
	}
	if rpcErr.Code != "unknown_method" {
		t.Fatalf("want code unknown_method, got %q", rpcErr.Code)
	}
}
