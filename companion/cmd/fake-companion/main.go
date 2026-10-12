// Command fake-companion serves the companion WebSocket protocol from scenario
// files in e2e/scenarios, for the iOS end-to-end flows. It runs the real
// wsserver.Server (orchestrator default, CPE-2091) over a scenario's state,
// with a herdr and a chat hub that only record what the app asked for, so the
// handshake, replies and frames can't drift from the real companion.
//
// A localhost control API lets a flow drive it:
//
//	POST /scenario {"name":"one-idle-pane"}  load a scenario, (re)start the WS side, clear /received,
//	                                         and send its state (and limits reading) to every app
//	POST /emit     <one JSON frame>          send a frame to every connected app
//	GET  /received                           JSON array of the companion calls the app caused,
//	                                         e.g. {"call":"RenamePane","args":["w1:p1","build"]}
//	POST /play     {"name":"live"}           send the scenario's named frames to every connected app
//	POST /stop                               drop every app and refuse new WS connections (503)
//	GET  /fetch/*                            404, recorded as a Fetch call: a request the app should never make
//	POST /term-exit {"code":3}               end every live terminal with this exit code (or the next one to open)
//
// term_open never runs herdr: it runs this binary's attach program (attach.go),
// recorded as TermOpen, which reports each read of the app's typing as TermInput.
// The attach program itself calls POST /attach/input and GET /attach/wait.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/limits"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/proto"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/wsserver"
)

type scenario struct {
	Workspaces []state.Workspace `json:"workspaces"`
	Tabs       []state.Tab       `json:"tabs"`
	Panes      []state.Pane      `json:"panes"`
	// What herdr answers for agent.list and, by workspace id, worktree.list.
	Agents    []string                         `json:"agents"`
	Worktrees map[string][]herdr.WorktreeEntry `json:"worktrees"`
	// Chats by "paneId" (the main stream) or "paneId/agentId" (a thread); images by id;
	// frames a flow sends with POST /play, by name.
	Chats  map[string]chatScenario      `json:"chats"`
	Images map[string]chatbridge.Image  `json:"images"`
	Frames map[string][]json.RawMessage `json:"frames"`
	// The account's rate-limit reading, sent after the state on connect and on
	// POST /scenario; none when nil.
	Limits *limits.Limits `json:"limits"`
	// The pane every create answers with, so a flow can auto-open it.
	Created struct {
		PaneID     string `json:"paneId"`
		TerminalID string `json:"terminalId"`
	} `json:"created"`
}

// chatScenario is one chat stream: Events make its snapshot, Older is reachable
// through chat_history, and chat_send fails with SendError when it is set.
type chatScenario struct {
	Epoch     int                `json:"epoch"`
	State     string             `json:"state"`
	Events    []chatbridge.Entry `json:"events"`
	Older     []chatbridge.Entry `json:"older"`
	Agents    []json.RawMessage  `json:"agents"`
	Tasks     json.RawMessage    `json:"tasks"`
	Commands  json.RawMessage    `json:"commands"`
	SendError string             `json:"sendError"`
}

// epoch is the chat's epoch, 1 unless the scenario says otherwise.
func (c chatScenario) epoch() int { return max(c.Epoch, 1) }

// chat is the scenario's stream for a pane (agentID "") or one of its threads.
func (s scenario) chat(paneID, agentID string) (chatScenario, bool) {
	key := paneID
	if agentID != "" {
		key += "/" + agentID
	}
	c, ok := s.Chats[key]
	return c, ok
}

func loadScenario(dir, name string) (scenario, error) {
	var s scenario
	if !filepath.IsLocal(name) {
		return s, fmt.Errorf("bad scenario name %q", name)
	}
	b, err := os.ReadFile(filepath.Join(dir, name+".json"))
	if err != nil {
		return s, err
	}
	err = json.Unmarshal(b, &s)
	return s, err
}

type fake struct {
	dir string
	srv *wsserver.Server
	rec *recorder

	mu       sync.Mutex
	scenario scenario
	drop     context.CancelFunc // ends every live app connection; /stop until the next /scenario
	dropped  context.Context

	exe         string // this binary, run as the attach program
	control     string // the control API's URL, for the attach program
	exitWaiters map[chan int]struct{}
	pendingExit *int // a /term-exit no attach was live for
}

func newFake(dir, name string) (*fake, error) {
	s, err := loadScenario(dir, name)
	if err != nil {
		return nil, err
	}
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	f := &fake{dir: dir, scenario: s, exe: exe, exitWaiters: map[chan int]struct{}{}}
	f.rec = &recorder{scenario: f.current}
	f.dropped, f.drop = context.WithCancel(context.Background())
	f.srv = wsserver.NewServer(wsserver.AllowAll{}, f.rec)
	f.srv.SetHerdrInfo("fake", 0)
	f.srv.SetInitialSnapshot(func() []state.Pane { return f.current().Panes })
	f.srv.SetWorkspaceSnapshot(func() []state.Workspace { return f.current().Workspaces })
	f.srv.SetTabSnapshot(func() []state.Tab { return f.current().Tabs })
	f.srv.SetLimitsSnapshot(func() []byte { return f.current().limitsFrame() })
	f.srv.SetPushEndpoint(func(endpoint string) { f.rec.record("RegisterPush", endpoint) })
	f.srv.SetChat(f.rec)
	f.srv.SetAttachArgv(f.attachArgv)
	return f, nil
}

// limitsFrame is the scenario's limits frame, nil when it has no reading.
func (s scenario) limitsFrame() []byte {
	if s.Limits == nil {
		return nil
	}
	return proto.Limits(*s.Limits)
}

func (f *fake) current() scenario {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.scenario
}

// wsHandler is wsserver's handler, refusing apps while stopped. Each app's
// request context ends on /stop, which makes wsserver close its socket.
func (f *fake) wsHandler() http.Handler {
	h := f.srv.Handler()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		dropped := f.dropped
		f.mu.Unlock()
		if dropped.Err() != nil {
			http.Error(w, "companion stopped", http.StatusServiceUnavailable)
			return
		}
		ctx, cancel := context.WithCancel(r.Context())
		defer cancel()
		defer context.AfterFunc(dropped, cancel)()
		log.Printf("app connected from %s", r.RemoteAddr)
		h.ServeHTTP(w, r.WithContext(ctx))
		log.Printf("app disconnected")
	})
}

func (f *fake) controlHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /scenario", func(w http.ResponseWriter, r *http.Request) {
		var req struct{ Name string }
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		s, err := loadScenario(f.dir, req.Name)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		f.mu.Lock()
		f.scenario = s
		f.pendingExit = nil
		if f.dropped.Err() != nil {
			f.dropped, f.drop = context.WithCancel(context.Background())
		}
		f.mu.Unlock()
		f.rec.reset()
		f.srv.Broadcast(proto.PanesSnapshot(s.Panes))
		f.srv.Broadcast(proto.WorkspacesSnapshot(s.Workspaces))
		f.srv.Broadcast(proto.TabsSnapshot(s.Tabs))
		if l := s.limitsFrame(); l != nil {
			f.srv.Broadcast(l)
		}
		log.Printf("scenario %s", req.Name)
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("POST /emit", func(w http.ResponseWriter, r *http.Request) {
		b, err := io.ReadAll(r.Body)
		if err != nil || !json.Valid(b) {
			http.Error(w, "body must be one JSON frame", http.StatusBadRequest)
			return
		}
		f.srv.Broadcast(b)
		log.Printf("emit %s", b)
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("POST /play", func(w http.ResponseWriter, r *http.Request) {
		var req struct{ Name string }
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		frames, ok := f.current().Frames[req.Name]
		if !ok {
			http.Error(w, "no frames named "+req.Name, http.StatusBadRequest)
			return
		}
		for _, b := range frames {
			f.srv.Broadcast(b)
		}
		log.Printf("play %s", req.Name)
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("GET /fetch/", func(w http.ResponseWriter, r *http.Request) {
		f.rec.record("Fetch", r.URL.Path)
		log.Printf("fetch %s", r.URL.Path)
		http.NotFound(w, r)
	})
	mux.HandleFunc("GET /received", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(f.rec.all())
	})
	mux.HandleFunc("POST /term-exit", func(w http.ResponseWriter, r *http.Request) {
		var req struct{ Code int }
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		f.endAttaches(req.Code)
		log.Printf("term-exit %d", req.Code)
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("POST /attach/input", func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		f.rec.record("TermInput", r.URL.Query().Get("target"), string(b))
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("GET /attach/wait", f.waitExit)
	mux.HandleFunc("POST /stop", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.drop()
		f.mu.Unlock()
		log.Printf("stopped")
		w.WriteHeader(http.StatusNoContent)
	})
	return mux
}

func main() {
	if target, control, ok := attachArgs(os.Args[1:]); ok {
		runAttach(target, control)
		return
	}
	listen := flag.String("listen", "127.0.0.1:18787", "WS listen address (the app connects here)")
	control := flag.String("control", "127.0.0.1:18788", "control API listen address")
	dir := flag.String("scenarios", "e2e/scenarios", "directory of scenario JSON files")
	name := flag.String("scenario", "one-idle-pane", "scenario served at start-up")
	flag.Parse()

	f, err := newFake(*dir, *name)
	if err != nil {
		log.Fatal(err)
	}
	wsLn, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	ctlLn, err := net.Listen("tcp", *control)
	if err != nil {
		log.Fatal(err)
	}
	f.setControlURL("http://" + ctlLn.Addr().String())
	errc := make(chan error, 2)
	go func() { errc <- http.Serve(wsLn, f.wsHandler()) }()
	go func() { errc <- http.Serve(ctlLn, f.controlHandler()) }()
	// Both ports are bound by now: scripts/e2e-ios.sh waits for this line.
	log.Printf("fake-companion: ws=%s control=%s scenario=%s", *listen, *control, *name)
	log.Fatal(<-errc)
}
