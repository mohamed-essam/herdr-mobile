package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

type Push struct {
	Kind        string `json:"kind"`
	PaneID      string `json:"paneId"`
	WorkspaceID string `json:"workspaceId"`
	Title       string `json:"title"`
	Body        string `json:"body"`
}

type Notifier interface {
	Notify(ctx context.Context, p Push) error
}

// ShouldNotify encodes the two v1 triggers. displayName is the friendly pane
// name shown in the title (the project/cwd basename); it falls back to the
// workspace id when empty. lastBody is the last non-empty output line for the
// pane (used as the blocked notification body).
func ShouldNotify(tr state.Transition, displayName, lastBody string) (Push, bool) {
	name := displayName
	if name == "" {
		name = tr.WorkspaceID
	}
	switch {
	case tr.To == "blocked":
		return Push{Kind: "blocked", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID,
			Title: name + " needs you", Body: lastBody}, true
	case tr.From == "working" && (tr.To == "idle" || tr.To == "done"):
		return Push{Kind: "finished", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID,
			Title: name + " finished", Body: ""}, true
	case tr.To == "working":
		return Push{Kind: "clear", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID}, true
	default:
		return Push{}, false
	}
}

type HTTPNotifier struct {
	endpoint string
	hc       *http.Client
}

func NewHTTPNotifier(endpoint string, hc *http.Client) *HTTPNotifier {
	if hc == nil {
		hc = http.DefaultClient
	}
	return &HTTPNotifier{endpoint: endpoint, hc: hc}
}

func (n *HTTPNotifier) Notify(ctx context.Context, p Push) error {
	if n.endpoint == "" {
		return nil // no endpoint registered yet
	}
	b, _ := json.Marshal(p)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, n.endpoint, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := n.hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("push endpoint returned %d", resp.StatusCode)
	}
	return nil
}
