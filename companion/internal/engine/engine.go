package engine

import (
	"context"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/notify"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/proto"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/wsserver"
)

type Config struct {
	SocketPath       string
	ListenAddr       string
	PollInterval     time.Duration
	DebounceFinished time.Duration
	// ChatSocket is the Unix socket the herdr-chat mod syncs over; empty
	// disables the chat view.
	ChatSocket string
}

type Engine struct {
	cfg    Config
	client *herdr.Client
	store  *state.Store
	srv    *wsserver.Server
	hub    *chatbridge.Hub

	mu       sync.Mutex
	endpoint string

	trigger chan struct{}
	subs    map[string]context.CancelFunc
}

func New(cfg Config) *Engine {
	if cfg.PollInterval == 0 {
		cfg.PollInterval = 1500 * time.Millisecond
	}
	if cfg.DebounceFinished == 0 {
		cfg.DebounceFinished = 4 * time.Second
	}
	c := herdr.New(cfg.SocketPath)
	e := &Engine{cfg: cfg, client: c, store: state.NewStore()}
	e.srv = wsserver.NewServer(wsserver.AllowAll{}, c)
	e.hub = chatbridge.NewHub(nil)
	// Liveness callbacks run serialized under the hub's lock while a mod sync
	// may be waiting: this must stay fast and non-blocking, and must never call
	// back into the hub (deadlock). SetChat + Broadcast (non-blocking) is safe.
	e.hub.SetOnLiveness(func(paneID string, live bool) {
		if p, changed := e.store.SetChat(paneID, live); changed {
			e.srv.Broadcast(proto.PaneUpdate(p))
		}
	})
	// Summary callbacks follow the same rules as liveness (serialized with
	// it, never call back into the hub). The hub delivers at most one per mod
	// sync (about once a second per pane) and only on a change.
	e.hub.SetOnSummary(func(paneID string, s chatbridge.Summary) {
		sum := state.Summary{Activity: (*state.Activity)(s.Activity), Ask: (*state.Ask)(s.Ask), BgRunning: s.BgRunning, Context: (*state.Context)(s.Context)}
		if p, changed := e.store.SetSummary(paneID, sum); changed {
			e.srv.Broadcast(proto.PaneUpdate(p))
		}
	})
	e.srv.SetChat(e.hub)
	e.srv.SetInitialSnapshot(e.store.Snapshot)
	e.srv.SetWorkspaceSnapshot(e.store.Workspaces)
	e.srv.SetTabSnapshot(e.store.Tabs)
	e.srv.SetPushEndpoint(e.setEndpoint)
	e.srv.SetPoke(e.Poke)
	e.trigger = make(chan struct{}, 1)
	e.subs = map[string]context.CancelFunc{}
	return e
}

func (e *Engine) setEndpoint(ep string) {
	e.mu.Lock()
	e.endpoint = ep
	e.mu.Unlock()
}

// Poke requests an immediate poll (coalesced with the ticker). Used by the
// wsserver after a successful structural action so the tree refreshes fast.
func (e *Engine) Poke() {
	select {
	case e.trigger <- struct{}{}:
	default:
	}
}

func (e *Engine) Run(ctx context.Context) error {
	// probe herdr version for the welcome frame (best-effort)
	if raw, err := e.client.Call(ctx, "ping", nil); err == nil {
		var pong struct {
			Version  string `json:"version"`
			Protocol int    `json:"protocol"`
		}
		_ = json.Unmarshal(raw, &pong)
		e.srv.SetHerdrInfo(pong.Version, pong.Protocol)
	}

	go e.pollLoop(ctx)

	if e.cfg.ChatSocket == "" {
		log.Printf("chat bridge disabled: no private socket path (set --chat-socket, HERDR_MOBILE_CHAT_SOCK or XDG_RUNTIME_DIR)")
	} else if l, err := chatbridge.Listen(e.cfg.ChatSocket); err != nil {
		log.Printf("chat bridge disabled: cannot listen on %s: %v", e.cfg.ChatSocket, err)
	} else {
		go e.serveChat(ctx, l)
	}

	httpSrv := &http.Server{Addr: e.cfg.ListenAddr, Handler: e.srv.Handler()}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		httpSrv.Shutdown(shutdownCtx)
	}()
	err := httpSrv.ListenAndServe()
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}

// serveChat serves the herdr-chat mods' /sync and /answer endpoints and
// expires mods that stop syncing, until ctx ends.
func (e *Engine) serveChat(ctx context.Context, l net.Listener) {
	srv := &http.Server{Handler: e.hub.Handler()}
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				srv.Close()
				return
			case <-t.C:
				e.hub.Tick()
			}
		}
	}()
	_ = srv.Serve(l)
}

func (e *Engine) pollLoop(ctx context.Context) {
	t := time.NewTicker(e.cfg.PollInterval)
	defer t.Stop()
	defer e.cancelAllSubs()
	// immediate first poll + subscribe so we don't wait a full interval
	e.pollOnce(ctx)
	e.reconcileSubs(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			e.pollOnce(ctx)
			e.reconcileSubs(ctx)
		case <-e.trigger:
			e.pollOnce(ctx)
			e.reconcileSubs(ctx)
		}
	}
}

// reconcileSubs opens a per-pane agent_status_changed subscription for every
// agent-bearing pane and cancels subscriptions for panes that are gone. Runs
// only on the poll goroutine, so e.subs needs no lock. Subscription events
// poke e.trigger (coalesced), causing an immediate pollOnce — the store stays
// the single transition source, so notifications never double-fire.
func (e *Engine) reconcileSubs(ctx context.Context) {
	desired := map[string]bool{}
	for _, p := range e.store.Snapshot() {
		if p.Agent != "" {
			desired[p.PaneID] = true
		}
	}
	for id := range desired {
		if _, ok := e.subs[id]; ok {
			continue
		}
		cctx, cancel := context.WithCancel(ctx)
		ch, err := e.client.Subscribe(cctx, id, "pane.agent_status_changed")
		if err != nil {
			cancel()
			continue
		}
		e.subs[id] = cancel
		go e.drainSub(ch)
	}
	for id, cancel := range e.subs {
		if !desired[id] {
			cancel()
			delete(e.subs, id)
		}
	}
}

func (e *Engine) drainSub(ch <-chan herdr.Event) {
	for range ch {
		select {
		case e.trigger <- struct{}{}:
		default:
		}
	}
}

func (e *Engine) cancelAllSubs() {
	for id, cancel := range e.subs {
		cancel()
		delete(e.subs, id)
	}
}

func (e *Engine) pollOnce(ctx context.Context) {
	panes, err := e.client.ListPanes(ctx)
	if err != nil {
		return
	}
	changes, transitions := e.store.Apply(panes)
	for _, ch := range changes {
		if ch.Kind == "removed" {
			e.hub.Drop(ch.PaneID)
			e.srv.Broadcast(proto.PaneRemoved(ch.PaneID))
		} else {
			e.srv.Broadcast(proto.PaneUpdate(ch.Pane))
		}
	}
	// Workspaces before transitions, so a notification title uses this poll's
	// workspace label.
	if ws, err := e.client.ListWorkspaces(ctx); err == nil {
		if e.store.ApplyWorkspaces(ws) {
			e.srv.Broadcast(proto.WorkspacesSnapshot(e.store.Workspaces()))
		}
	}
	for _, tr := range transitions {
		e.handleTransition(ctx, tr)
	}
	if tabs, err := e.client.ListTabs(ctx); err == nil {
		if e.store.ApplyTabs(tabs) {
			e.srv.Broadcast(proto.TabsSnapshot(e.store.Tabs()))
		}
	}
}

func (e *Engine) handleTransition(ctx context.Context, tr state.Transition) {
	body := ""
	if tr.To == "blocked" {
		if txt, err := e.client.ReadPane(ctx, tr.PaneID, "detection", 40); err == nil {
			body = lastNonEmptyLine(txt)
		}
	}
	push, ok := notify.ShouldNotify(tr, e.displayName(tr.PaneID), body)
	if !ok {
		return
	}
	if push.Kind == "finished" {
		// debounce: only fire if still not working after the window
		go func() {
			select {
			case <-ctx.Done():
			case <-time.After(e.cfg.DebounceFinished):
				for _, p := range e.store.Snapshot() {
					if p.PaneID == tr.PaneID && p.AgentStatus == "working" {
						return // resumed; suppress
					}
				}
				e.fire(ctx, push)
			}
		}()
		return
	}
	e.fire(ctx, push)
}

// displayName returns the friendly pane name for notification titles: the
// pane's workspace label, else the cwd basename (the project folder, e.g.
// "omega3"), else the workspace id. Read from the store since a Transition
// carries only the ids.
func (e *Engine) displayName(paneID string) string {
	for _, p := range e.store.Snapshot() {
		if p.PaneID == paneID {
			for _, w := range e.store.Workspaces() {
				if w.WorkspaceID == p.WorkspaceID && w.Label != "" {
					return w.Label
				}
			}
			if b := filepath.Base(p.CWD); b != "" && b != "." && b != string(filepath.Separator) {
				return b
			}
			return p.WorkspaceID
		}
	}
	return ""
}

func (e *Engine) fire(ctx context.Context, p notify.Push) {
	e.mu.Lock()
	ep := e.endpoint
	e.mu.Unlock()
	if ep == "" {
		return
	}
	n := notify.NewHTTPNotifier(ep, http.DefaultClient)
	_ = n.Notify(ctx, p)
}

func lastNonEmptyLine(s string) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if strings.TrimSpace(lines[i]) != "" {
			return strings.TrimSpace(lines[i])
		}
	}
	return ""
}
