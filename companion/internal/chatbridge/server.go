package chatbridge

import (
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
)

const maxBody = 8 << 20

// SocketPath is where the companion listens and the mod connects:
// HERDR_MOBILE_CHAT_SOCK, else $XDG_RUNTIME_DIR/herdr-mobile/chat.sock,
// else "" meaning chat is disabled. There is deliberately no /tmp fallback: a
// predictable shared path would let another local user pre-create it, read
// conversations and inject prompts. The mod uses the same rule.
func SocketPath() string {
	if p := os.Getenv("HERDR_MOBILE_CHAT_SOCK"); p != "" {
		return p
	}
	if d := os.Getenv("XDG_RUNTIME_DIR"); d != "" {
		return filepath.Join(d, "herdr-mobile", "chat.sock")
	}
	return ""
}

// Listen creates the socket's directory (0700), removes a stale socket and
// listens with the socket restricted to the user (0600).
func Listen(path string) (net.Listener, error) {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return nil, err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	l, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o600); err != nil {
		l.Close()
		return nil, err
	}
	return l, nil
}

type syncReq struct {
	PaneID    string            `json:"paneId"`
	SessionID string            `json:"sessionId"`
	Events    []json.RawMessage `json:"events"`
}

// Handler serves the mod's one endpoint, POST /sync.
func (h *Hub) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /sync", func(w http.ResponseWriter, r *http.Request) {
		var req syncReq
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody)).Decode(&req); err != nil || req.PaneID == "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		msgs := h.Sync(req.PaneID, req.SessionID, req.Events)
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"messages": msgs})
	})
	return mux
}
