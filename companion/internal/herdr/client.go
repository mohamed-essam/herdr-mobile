package herdr

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"strconv"
	"sync/atomic"
)

type Client struct {
	socketPath string
	seq        atomic.Uint64
}

func New(socketPath string) *Client { return &Client{socketPath: socketPath} }

func (c *Client) dial(ctx context.Context) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, "unix", c.socketPath)
}

func (c *Client) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	conn, err := c.dial(ctx)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if dl, ok := ctx.Deadline(); ok {
		conn.SetDeadline(dl)
	}
	id := "c" + strconv.FormatUint(c.seq.Add(1), 10)
	req := map[string]any{"id": id, "method": method, "params": params}
	if params == nil {
		req["params"] = map[string]any{}
	}
	b, _ := json.Marshal(req)
	if _, err := conn.Write(append(b, '\n')); err != nil {
		return nil, err
	}
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return nil, err
	}
	var resp Response
	if err := json.Unmarshal(line, &resp); err != nil {
		return nil, err
	}
	if resp.Error != nil {
		return nil, resp.Error
	}
	return resp.Result, nil
}

func (c *Client) ListPanes(ctx context.Context) ([]PaneInfo, error) {
	raw, err := c.Call(ctx, "pane.list", nil)
	if err != nil {
		return nil, err
	}
	var res paneListResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Panes, nil
}

func (c *Client) ListWorkspaces(ctx context.Context) ([]WorkspaceInfo, error) {
	raw, err := c.Call(ctx, "workspace.list", nil)
	if err != nil {
		return nil, err
	}
	var res workspaceListResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Workspaces, nil
}

func (c *Client) ListTabs(ctx context.Context) ([]TabInfo, error) {
	raw, err := c.Call(ctx, "tab.list", nil)
	if err != nil {
		return nil, err
	}
	var res tabListResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Tabs, nil
}

func (c *Client) ListWorktrees(ctx context.Context, workspaceID string) ([]WorktreeEntry, error) {
	params := map[string]any{}
	if workspaceID != "" {
		params["workspace_id"] = workspaceID
	}
	raw, err := c.Call(ctx, "worktree.list", params)
	if err != nil {
		return nil, err
	}
	var res worktreeListResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	return res.Worktrees, nil
}

func (c *Client) ReadPane(ctx context.Context, paneID, source string, lines int) (string, error) {
	raw, err := c.Call(ctx, "pane.read", map[string]any{"pane_id": paneID, "source": source, "lines": lines})
	if err != nil {
		return "", err
	}
	var res paneReadResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", err
	}
	return res.Read.Text, nil
}

func (c *Client) SendText(ctx context.Context, paneID, text string) error {
	_, err := c.Call(ctx, "pane.send_text", map[string]any{"pane_id": paneID, "text": text})
	return err
}

func (c *Client) SendKeys(ctx context.Context, paneID, keys string) error {
	// herdr's pane.send_keys expects `keys` as a sequence (array) of key-combo
	// strings, not a single string — a bare string is rejected with
	// "invalid type: string, expected a sequence". We send one combo per call.
	_, err := c.Call(ctx, "pane.send_keys", map[string]any{"pane_id": paneID, "keys": []string{keys}})
	return err
}

func (c *Client) RenameWorkspace(ctx context.Context, id, label string) error {
	_, err := c.Call(ctx, "workspace.rename", map[string]any{"workspace_id": id, "label": label})
	return err
}

func (c *Client) RenameTab(ctx context.Context, id, label string) error {
	_, err := c.Call(ctx, "tab.rename", map[string]any{"tab_id": id, "label": label})
	return err
}

func (c *Client) RenamePane(ctx context.Context, id, label string) error {
	_, err := c.Call(ctx, "pane.rename", map[string]any{"pane_id": id, "label": label})
	return err
}

func (c *Client) CloseWorkspace(ctx context.Context, id string) error {
	_, err := c.Call(ctx, "workspace.close", map[string]any{"workspace_id": id})
	return err
}

func (c *Client) CloseTab(ctx context.Context, id string) error {
	_, err := c.Call(ctx, "tab.close", map[string]any{"tab_id": id})
	return err
}

func (c *Client) ClosePane(ctx context.Context, id string) error {
	_, err := c.Call(ctx, "pane.close", map[string]any{"pane_id": id})
	return err
}

func (c *Client) CreateWorkspace(ctx context.Context) (string, string, error) {
	raw, err := c.Call(ctx, "workspace.create", map[string]any{"focus": true})
	if err != nil {
		return "", "", err
	}
	var res rootPaneResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", "", err
	}
	return res.RootPane.PaneID, res.RootPane.TerminalID, nil
}

func (c *Client) CreateTab(ctx context.Context, workspaceID string) (string, string, error) {
	params := map[string]any{"focus": true}
	if workspaceID != "" {
		params["workspace_id"] = workspaceID
	}
	raw, err := c.Call(ctx, "tab.create", params)
	if err != nil {
		return "", "", err
	}
	var res rootPaneResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", "", err
	}
	return res.RootPane.PaneID, res.RootPane.TerminalID, nil
}

func (c *Client) SplitPane(ctx context.Context, targetPaneID, workspaceID, direction string) (string, string, error) {
	params := map[string]any{"direction": direction, "focus": true}
	if targetPaneID != "" {
		params["target_pane_id"] = targetPaneID
	}
	if workspaceID != "" {
		params["workspace_id"] = workspaceID
	}
	raw, err := c.Call(ctx, "pane.split", params)
	if err != nil {
		return "", "", err
	}
	var res paneInfoResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", "", err
	}
	return res.Pane.PaneID, res.Pane.TerminalID, nil
}

func (c *Client) StartAgent(ctx context.Context, name string, argv []string, workspaceID, tabID, split string) (string, string, error) {
	params := map[string]any{"name": name, "argv": argv, "focus": true}
	if workspaceID != "" {
		params["workspace_id"] = workspaceID
	}
	if tabID != "" {
		params["tab_id"] = tabID
	}
	if split != "" {
		params["split"] = split
	}
	raw, err := c.Call(ctx, "agent.start", params)
	if err != nil {
		return "", "", err
	}
	var res agentStartedResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return "", "", err
	}
	return res.Agent.PaneID, res.Agent.TerminalID, nil
}

func (c *Client) MovePane(ctx context.Context, paneID, dest, tabID, direction string) error {
	var destination map[string]any
	switch dest {
	case "tab":
		d := direction
		if d == "" {
			d = "down"
		}
		destination = map[string]any{"type": "tab", "tab_id": tabID, "split": d}
	case "new_tab":
		destination = map[string]any{"type": "new_tab"}
	case "new_workspace":
		destination = map[string]any{"type": "new_workspace"}
	default:
		return &RPCError{Code: "bad_dest", Message: "unknown move destination: " + dest}
	}
	_, err := c.Call(ctx, "pane.move", map[string]any{"pane_id": paneID, "destination": destination, "focus": false})
	return err
}

func (c *Client) ListAgentNames(ctx context.Context) ([]string, error) {
	raw, err := c.Call(ctx, "server.agent_manifests", nil)
	if err != nil {
		return nil, err
	}
	var res agentManifestsResult
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, err
	}
	names := make([]string, 0, len(res.Manifests))
	for _, m := range res.Manifests {
		names = append(names, m.Agent)
	}
	return names, nil
}

func (c *Client) Subscribe(ctx context.Context, paneID, eventType string) (<-chan Event, error) {
	conn, err := c.dial(ctx)
	if err != nil {
		return nil, err
	}
	id := "s" + strconv.FormatUint(c.seq.Add(1), 10)
	req := map[string]any{"id": id, "method": "events.subscribe",
		"params": map[string]any{"subscriptions": []map[string]any{{"type": eventType, "pane_id": paneID}}}}
	b, _ := json.Marshal(req)
	if _, err := conn.Write(append(b, '\n')); err != nil {
		conn.Close()
		return nil, err
	}
	out := make(chan Event, 16)
	go func() {
		defer close(out)
		defer conn.Close()
		done := make(chan struct{})
		defer close(done)
		go func() {
			select {
			case <-ctx.Done():
				conn.Close()
			case <-done:
			}
		}()
		r := bufio.NewReader(conn)
		first := true
		for {
			line, err := r.ReadBytes('\n')
			if err != nil {
				return
			}
			if first {
				first = false // skip subscription_started
				continue
			}
			var e Event
			if json.Unmarshal(line, &e) == nil && e.Type != "" {
				select {
				case out <- e:
				case <-ctx.Done():
					return
				}
			}
		}
	}()
	return out, nil
}
