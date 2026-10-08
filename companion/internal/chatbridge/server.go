package chatbridge

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"syscall"
	"time"
)

const maxBody = 8 << 20

// SocketPath is where the companion listens and the mod connects:
// HERDR_MOBILE_CHAT_SOCK, else $XDG_RUNTIME_DIR/herdr-mobile/chat.sock,
// else "" meaning chat is disabled. There is deliberately no /tmp fallback: a
// predictable shared path would let another local user pre-create it, read
// conversations and inject prompts. The mod uses the same rule. A custom
// HERDR_MOBILE_CHAT_SOCK must live in a directory private to the user.
func SocketPath() string {
	if p := os.Getenv("HERDR_MOBILE_CHAT_SOCK"); p != "" {
		return p
	}
	if d := os.Getenv("XDG_RUNTIME_DIR"); d != "" {
		return filepath.Join(d, "herdr-mobile", "chat.sock")
	}
	return ""
}

// Listen prepares the socket's directory and listens with the socket
// restricted to the user (0600). A directory Listen creates is 0700. A
// pre-existing directory is never chmod'ed: it must be owned by the current
// user and grant nothing to group/other (no 0077 bits), so a custom
// HERDR_MOBILE_CHAT_SOCK must live in a directory private to the user. A stale socket is replaced; a live
// listener or a non-socket file at path is an error.
func Listen(path string) (net.Listener, error) {
	dir := filepath.Dir(path)
	fi, err := os.Stat(dir)
	switch {
	case os.IsNotExist(err):
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return nil, err
		}
	case err != nil:
		return nil, err
	default:
		if !fi.IsDir() {
			return nil, fmt.Errorf("%s is not a directory", dir)
		}
		if st, ok := fi.Sys().(*syscall.Stat_t); !ok || int(st.Uid) != os.Getuid() {
			return nil, fmt.Errorf("%s is not owned by the current user", dir)
		}
		if fi.Mode().Perm()&0o077 != 0 {
			return nil, fmt.Errorf("%s grants access to group/other; use a private (0700) directory", dir)
		}
	}
	if li, err := os.Lstat(path); err == nil {
		if li.Mode()&os.ModeSocket == 0 {
			return nil, fmt.Errorf("%s exists and is not a socket", path)
		}
		if c, err := net.DialTimeout("unix", path, time.Second); err == nil {
			c.Close()
			return nil, fmt.Errorf("another companion is listening on %s", path)
		}
		if err := os.Remove(path); err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	// Create the socket under a restrictive umask so it is never briefly
	// accessible to others. Listen runs once at startup, so the process-wide
	// umask change is acceptable.
	old := syscall.Umask(0o177)
	l, err := net.Listen("unix", path)
	syscall.Umask(old)
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
	Images    map[string]Image  `json:"images"`
	Usage     *Usage            `json:"usage"`
}

type answerReq struct {
	PaneID    string `json:"paneId"`
	ToolUseID string `json:"toolUseId"`
}

// answerRes is the /answer reply: the phone's answers, or null when none
// came within the hold.
type answerRes struct {
	Answer map[string]string `json:"answer"`
}

// syncRes is the /sync answer. Resync asks the mod to send hello + snapshot
// on its next tick (omitted when false; older mods ignore it). Threads is
// always true: it tells the mod this companion understands agent threads,
// agent and tasks controls, so a newer mod never sends them to an older
// companion (which would mix agent rows into the main stream).
type syncRes struct {
	Messages []OutMsg `json:"messages"`
	Resync   bool     `json:"resync,omitempty"`
	Threads  bool     `json:"threads"`
}

// Handler serves the mod's endpoints: POST /sync, and POST /answer, a
// long-poll for the phone's answer to an AskUserQuestion.
func (h *Hub) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /sync", func(w http.ResponseWriter, r *http.Request) {
		var req syncReq
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody)).Decode(&req); err != nil || req.PaneID == "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		h.SetUsage(req.PaneID, req.Usage)
		msgs, resync := h.SyncBody(req.PaneID, req.SessionID, req.Events, req.Images)
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(syncRes{Messages: msgs, Resync: resync, Threads: true})
	})
	mux.HandleFunc("POST /answer", func(w http.ResponseWriter, r *http.Request) {
		var req answerReq
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req); err != nil || req.PaneID == "" || req.ToolUseID == "" {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		h.Heartbeat(req.PaneID)
		ans, _ := h.WaitAnswer(r.Context(), req.PaneID, req.ToolUseID, h.answerWait)
		w.Header().Set("content-type", "application/json")
		_ = json.NewEncoder(w).Encode(answerRes{Answer: ans})
	})
	return mux
}
