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

func TestListWorkspacesAndTabs(t *testing.T) {
	f := newFakeHerdr(t)
	f.SetWorkspaces([]WorkspaceInfo{
		{WorkspaceID: "w3", Label: "apollo", Number: 1, AgentStatus: "idle", PaneCount: 1, TabCount: 1},
		{WorkspaceID: "w5", Label: "wt-cost-dashboards", Number: 2, Focused: true, PaneCount: 1, TabCount: 1,
			Worktree: &WorktreeInfo{RepoName: "ops", IsLinkedWorktree: true}},
	})
	f.SetTabs([]TabInfo{
		{TabID: "w7:t1", Label: "1", Number: 1, WorkspaceID: "w7", AgentStatus: "idle", PaneCount: 1},
		{TabID: "w7:t2", Label: "2", Number: 2, WorkspaceID: "w7", AgentStatus: "unknown", PaneCount: 1},
	})
	c := New(f.SocketPath())

	ws, err := c.ListWorkspaces(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(ws) != 2 || ws[0].Label != "apollo" || ws[1].Worktree == nil || ws[1].Worktree.RepoName != "ops" {
		t.Fatalf("bad workspaces: %+v", ws)
	}

	tabs, err := c.ListTabs(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(tabs) != 2 || tabs[1].Label != "2" || tabs[1].WorkspaceID != "w7" {
		t.Fatalf("bad tabs: %+v", tabs)
	}
}

func TestClientRenameAndCloseReachHerdr(t *testing.T) {
	f := newFakeHerdr(t)
	c := New(f.SocketPath())
	ctx := context.Background()

	cases := []struct {
		call   func() error
		method string
		key    string // param key carrying the id
		id     string
		label  string // "" for close
	}{
		{func() error { return c.RenameWorkspace(ctx, "w7", "omega3") }, "workspace.rename", "workspace_id", "w7", "omega3"},
		{func() error { return c.RenameTab(ctx, "w7:t1", "build") }, "tab.rename", "tab_id", "w7:t1", "build"},
		{func() error { return c.RenamePane(ctx, "w7:p2", "logs") }, "pane.rename", "pane_id", "w7:p2", "logs"},
		{func() error { return c.CloseWorkspace(ctx, "w7") }, "workspace.close", "workspace_id", "w7", ""},
		{func() error { return c.CloseTab(ctx, "w7:t1") }, "tab.close", "tab_id", "w7:t1", ""},
		{func() error { return c.ClosePane(ctx, "w7:p2") }, "pane.close", "pane_id", "w7:p2", ""},
	}
	for _, tc := range cases {
		if err := tc.call(); err != nil {
			t.Fatalf("%s: %v", tc.method, err)
		}
		select {
		case rec := <-f.lastCall:
			if rec.Method != tc.method {
				t.Fatalf("want method %s, got %s", tc.method, rec.Method)
			}
			if rec.Params[tc.key] != tc.id {
				t.Fatalf("%s: want %s=%s, got %v", tc.method, tc.key, tc.id, rec.Params[tc.key])
			}
			if tc.label != "" && rec.Params["label"] != tc.label {
				t.Fatalf("%s: want label=%s, got %v", tc.method, tc.label, rec.Params["label"])
			}
		case <-time.After(time.Second):
			t.Fatalf("%s never reached herdr", tc.method)
		}
	}
}
