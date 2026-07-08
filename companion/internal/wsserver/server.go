package wsserver

import (
	"context"
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/coder/websocket"
	"github.com/messam/herdr-mobile/companion/internal/proto"
	"github.com/messam/herdr-mobile/companion/internal/pty"
	"github.com/messam/herdr-mobile/companion/internal/state"
)

type HerdrRPC interface {
	ReadPane(ctx context.Context, paneID, source string, lines int) (string, error)
	SendText(ctx context.Context, paneID, text string) error
	SendKeys(ctx context.Context, paneID, keys string) error
}

type Server struct {
	auth        Authorizer
	rpc         HerdrRPC
	snapshot    func() []state.Pane
	wsSnapshot  func() []state.Workspace
	tabSnapshot func() []state.Tab
	onPush      func(endpoint string)
	herdrVer    string
	herdrProt   int

	termSeq    atomic.Uint64
	attachArgv func(target string) []string

	mu      sync.Mutex
	clients map[*client]struct{}
}

type client struct {
	conn     *websocket.Conn
	send     chan []byte
	sessions map[string]*pty.Session
	smu      sync.Mutex
}

func NewServer(auth Authorizer, rpc HerdrRPC) *Server {
	srv := &Server{auth: auth, rpc: rpc, clients: map[*client]struct{}{},
		snapshot: func() []state.Pane { return nil }, onPush: func(string) {},
		wsSnapshot: func() []state.Workspace { return nil }, tabSnapshot: func() []state.Tab { return nil },
		herdrVer: "unknown", herdrProt: 0}
	// --takeover: the phone seizes the pane's attachment even if a client (e.g. the
	// desktop herdr TUI or a stale attach) already holds it. --takeover is a fixed
	// literal we control, not client input, so it can't be a flag-injection vector.
	srv.attachArgv = func(target string) []string {
		return []string{"herdr", "agent", "attach", target, "--takeover"}
	}
	return srv
}

func (s *Server) SetInitialSnapshot(fn func() []state.Pane)        { s.snapshot = fn }
func (s *Server) SetWorkspaceSnapshot(fn func() []state.Workspace) { s.wsSnapshot = fn }
func (s *Server) SetTabSnapshot(fn func() []state.Tab)             { s.tabSnapshot = fn }
func (s *Server) SetPushEndpoint(fn func(string))                  { s.onPush = fn }
func (s *Server) SetHerdrInfo(ver string, prot int)                { s.herdrVer, s.herdrProt = ver, prot }

func (s *Server) Broadcast(frame []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for c := range s.clients {
		select {
		case c.send <- frame:
		default: // drop for a slow client
		}
	}
}

func (s *Server) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := s.auth.Authorize(r); err != nil {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		// NOTE: coder/websocket's InsecureSkipVerify skips the ORIGIN-header
		// check, NOT TLS verification. A native app sends no Origin header, so
		// this is required and safe. Transport confidentiality comes from
		// Tailscale (WireGuard) — v1 uses ws:// over the tailnet, not wss://.
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		c := &client{conn: conn, send: make(chan []byte, 64), sessions: map[string]*pty.Session{}}
		// enqueue welcome + snapshot BEFORE the client is visible to Broadcast
		c.send <- proto.Welcome(s.herdrVer, s.herdrProt)
		c.send <- proto.PanesSnapshot(s.snapshot())
		c.send <- proto.WorkspacesSnapshot(s.wsSnapshot())
		c.send <- proto.TabsSnapshot(s.tabSnapshot())
		s.add(c)
		defer func() { c.closeAll(); s.remove(c) }()

		ctx := r.Context()
		go s.writeLoop(ctx, c)
		s.readLoop(ctx, c)
	})
}

func (s *Server) add(c *client) { s.mu.Lock(); s.clients[c] = struct{}{}; s.mu.Unlock() }
func (s *Server) remove(c *client) {
	s.mu.Lock()
	delete(s.clients, c)
	s.mu.Unlock()
	c.conn.Close(websocket.StatusNormalClosure, "")
}

func (s *Server) writeLoop(ctx context.Context, c *client) {
	for {
		select {
		case <-ctx.Done():
			return
		case frame := <-c.send:
			if err := c.conn.Write(ctx, websocket.MessageText, frame); err != nil {
				return
			}
		}
	}
}

func (s *Server) readLoop(ctx context.Context, c *client) {
	for {
		_, b, err := c.conn.Read(ctx)
		if err != nil {
			return
		}
		m, err := proto.ParseClient(b)
		if err != nil {
			continue
		}
		switch m.T {
		case "hello", "ping":
			c.send <- proto.Pong()
		case "register_push":
			s.onPush(m.Endpoint)
			c.send <- proto.Ack(m.ReqID)
		case "read_pane":
			src := m.Source
			if src == "" {
				src = "detection"
			}
			lines := m.Lines
			if lines == 0 {
				lines = 40
			}
			txt, err := s.rpc.ReadPane(ctx, m.PaneID, src, lines)
			if err != nil {
				c.send <- proto.ErrorFrame(m.ReqID, "read_failed", err.Error())
				continue
			}
			c.send <- proto.PaneRead(m.ReqID, m.PaneID, src, txt)
		case "send_text":
			if err := s.rpc.SendText(ctx, m.PaneID, m.Text); err != nil {
				c.send <- proto.ErrorFrame(m.ReqID, "send_failed", err.Error())
				continue
			}
			c.send <- proto.Ack(m.ReqID)
		case "send_keys":
			if err := s.rpc.SendKeys(ctx, m.PaneID, m.Keys); err != nil {
				c.send <- proto.ErrorFrame(m.ReqID, "send_failed", err.Error())
				continue
			}
			c.send <- proto.Ack(m.ReqID)
		case "term_open":
			s.openTerm(ctx, c, m.ReqID, m.Target, m.Cols, m.Rows)
		case "term_input":
			if sess := c.get(m.TermID); sess != nil {
				if data, err := base64.StdEncoding.DecodeString(m.Data); err == nil {
					_ = sess.Write(data)
				}
			}
		case "term_resize":
			if sess := c.get(m.TermID); sess != nil {
				_ = sess.Resize(uint16(m.Cols), uint16(m.Rows))
			}
		case "term_close":
			c.closeTerm(m.TermID)
		}
	}
}

// sendBlocking enqueues frame on c.send, blocking until it fits (or ctx is
// done). Terminal data must never be silently dropped the way pane
// broadcasts are, so this backpressures the PTY read loop instead.
func sendBlocking(ctx context.Context, c *client, frame []byte) {
	select {
	case c.send <- frame:
	case <-ctx.Done():
	}
}

const maxTerms = 8

func (c *client) get(id string) *pty.Session {
	c.smu.Lock()
	defer c.smu.Unlock()
	return c.sessions[id]
}

func (c *client) closeTerm(id string) {
	c.smu.Lock()
	sess := c.sessions[id]
	delete(c.sessions, id)
	c.smu.Unlock()
	if sess != nil {
		// sess.Close() kills the child, which makes its PTY read loop exit and
		// still fire onExit -> term_exit; that's intentional, not a double-signal
		// bug — an explicit term_close is expected to be followed by term_exit.
		_ = sess.Close()
	}
}

func (c *client) closeAll() {
	c.smu.Lock()
	all := c.sessions
	c.sessions = map[string]*pty.Session{}
	c.smu.Unlock()
	for _, s := range all {
		_ = s.Close()
	}
}

func (s *Server) openTerm(ctx context.Context, c *client, reqID, target string, cols, rows int) {
	// target flows unauthenticated-WS-client -> argv for `herdr agent attach`;
	// reject anything that could be smuggled in as a flag rather than a pane/agent id.
	if target == "" || strings.HasPrefix(target, "-") {
		c.send <- proto.TermError(reqID, "", "invalid target")
		return
	}
	c.smu.Lock()
	over := len(c.sessions) >= maxTerms
	c.smu.Unlock()
	if over {
		c.send <- proto.TermError(reqID, "", "too many terminals")
		return
	}
	if cols <= 0 {
		cols = 80
	}
	if rows <= 0 {
		rows = 24
	}
	termID := "t" + strconv.FormatUint(s.termSeq.Add(1), 10)
	sess, err := pty.Start(s.attachArgv(target), uint16(cols), uint16(rows),
		func(b []byte) {
			sendBlocking(ctx, c, proto.TermData(termID, base64.StdEncoding.EncodeToString(b)))
		},
		func(code int) {
			c.closeTerm(termID)
			sendBlocking(ctx, c, proto.TermExit(termID, code))
		},
	)
	if err != nil {
		c.send <- proto.TermError(reqID, "", err.Error())
		return
	}
	c.smu.Lock()
	c.sessions[termID] = sess
	c.smu.Unlock()
	c.send <- proto.TermOpened(reqID, termID)
}
