package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/messam/herdr-mobile/companion/internal/state"
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

// ShouldNotify encodes the two v1 triggers. lastBody is the last non-empty
// output line for the pane (used as the blocked notification body).
func ShouldNotify(tr state.Transition, lastBody string) (Push, bool) {
	switch {
	case tr.To == "blocked":
		return Push{Kind: "blocked", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID,
			Title: tr.WorkspaceID + " needs you", Body: lastBody}, true
	case tr.From == "working" && (tr.To == "idle" || tr.To == "done"):
		return Push{Kind: "finished", PaneID: tr.PaneID, WorkspaceID: tr.WorkspaceID,
			Title: tr.WorkspaceID + " finished", Body: ""}, true
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
