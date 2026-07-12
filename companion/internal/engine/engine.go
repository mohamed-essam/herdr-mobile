package engine

import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"time"

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
}

type Engine struct {
	cfg    Config
	client *herdr.Client
	store  *state.Store
	srv    *wsserver.Server

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
			e.srv.Broadcast(proto.PaneRemoved(ch.PaneID))
		} else {
			e.srv.Broadcast(proto.PaneUpdate(ch.Pane))
		}
	}
	for _, tr := range transitions {
		e.handleTransition(ctx, tr)
	}

	if ws, err := e.client.ListWorkspaces(ctx); err == nil {
		if e.store.ApplyWorkspaces(ws) {
			e.srv.Broadcast(proto.WorkspacesSnapshot(e.store.Workspaces()))
		}
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

// displayName returns the friendly pane name for notification titles: the cwd
// basename (the project folder, e.g. "omega3") if present, else the workspace
// id. Read from the store snapshot since a Transition carries only the id.
func (e *Engine) displayName(paneID string) string {
	for _, p := range e.store.Snapshot() {
		if p.PaneID == paneID {
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
