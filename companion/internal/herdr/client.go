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
	_, err := c.Call(ctx, "pane.send_keys", map[string]any{"pane_id": paneID, "keys": keys})
	return err
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
