// Command fake-companion serves the companion WebSocket protocol from scenario
// files in e2e/scenarios, for the iOS end-to-end flows. It runs the real
// wsserver.Server (orchestrator default, CPE-2091) over a scenario's state,
// with a herdr and a chat hub that only record what the app asked for, so the
// handshake, replies and frames can't drift from the real companion.
//
// A localhost control API lets a flow drive it:
//
//	POST /scenario {"name":"one-idle-pane"}  load a scenario, (re)start the WS side, clear /received
//	POST /emit     <one JSON frame>          send a frame to every connected app
//	GET  /received                           JSON array of the companion calls the app caused,
//	                                         e.g. {"call":"RenamePane","args":["w1:p1","build"]}
//	POST /stop                               drop every app and refuse new WS connections (503)
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

	"github.com/mohamed-essam/herdr-mobile/companion/internal/proto"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/wsserver"
)

type scenario struct {
	Workspaces []state.Workspace `json:"workspaces"`
	Tabs       []state.Tab       `json:"tabs"`
	Panes      []state.Pane      `json:"panes"`
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
}

func newFake(dir, name string) (*fake, error) {
	s, err := loadScenario(dir, name)
	if err != nil {
		return nil, err
	}
	f := &fake{dir: dir, scenario: s, rec: &recorder{}}
	f.dropped, f.drop = context.WithCancel(context.Background())
	f.srv = wsserver.NewServer(wsserver.AllowAll{}, f.rec)
	f.srv.SetHerdrInfo("fake", 0)
	f.srv.SetInitialSnapshot(func() []state.Pane { return f.current().Panes })
	f.srv.SetWorkspaceSnapshot(func() []state.Workspace { return f.current().Workspaces })
	f.srv.SetTabSnapshot(func() []state.Tab { return f.current().Tabs })
	f.srv.SetPushEndpoint(func(endpoint string) { f.rec.record("RegisterPush", endpoint) })
	f.srv.SetChat(f.rec)
	return f, nil
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
		if f.dropped.Err() != nil {
			f.dropped, f.drop = context.WithCancel(context.Background())
		}
		f.mu.Unlock()
		f.rec.reset()
		f.srv.Broadcast(proto.PanesSnapshot(s.Panes))
		f.srv.Broadcast(proto.WorkspacesSnapshot(s.Workspaces))
		f.srv.Broadcast(proto.TabsSnapshot(s.Tabs))
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
	mux.HandleFunc("GET /received", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(f.rec.all())
	})
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
	errc := make(chan error, 2)
	go func() { errc <- http.Serve(wsLn, f.wsHandler()) }()
	go func() { errc <- http.Serve(ctlLn, f.controlHandler()) }()
	// Both ports are bound by now: scripts/e2e-ios.sh waits for this line.
	log.Printf("fake-companion: ws=%s control=%s scenario=%s", *listen, *control, *name)
	log.Fatal(<-errc)
}
