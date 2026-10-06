// Command fake-companion serves the companion WebSocket protocol from scenario
// files in e2e/scenarios, for the iOS end-to-end flows. Frames come from the
// companion's own proto package, so they can't drift from the real wire format.
// Like the real server, it answers hello and ping with pong.
//
// A localhost control API lets a flow drive it:
//
//	POST /scenario {"name":"one-idle-pane"}  load a scenario, (re)start the WS side, clear /received
//	POST /emit     <one JSON frame>          send a frame to every connected app
//	GET  /received                           JSON array of the frames the app sent
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
	"time"

	"github.com/coder/websocket"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/proto"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/state"
)

type scenario struct {
	Workspaces []state.Workspace `json:"workspaces"`
	Tabs       []state.Tab       `json:"tabs"`
	Panes      []state.Pane      `json:"panes"`
}

func (s scenario) snapshot() [][]byte {
	return [][]byte{proto.PanesSnapshot(s.Panes), proto.WorkspacesSnapshot(s.Workspaces), proto.TabsSnapshot(s.Tabs)}
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

// ponytail: one lock held across socket writes keeps frame order simple; fine
// for one app on localhost, per-client send queues if a flow ever needs more.
type fake struct {
	dir string

	mu       sync.Mutex
	scenario scenario
	stopped  bool
	clients  map[*websocket.Conn]struct{}
	received []json.RawMessage
}

func newFake(dir, name string) (*fake, error) {
	s, err := loadScenario(dir, name)
	if err != nil {
		return nil, err
	}
	return &fake{dir: dir, scenario: s, clients: map[*websocket.Conn]struct{}{}, received: []json.RawMessage{}}, nil
}

func write(c *websocket.Conn, frame []byte) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := c.Write(ctx, websocket.MessageText, frame); err != nil {
		log.Printf("write: %v", err)
	}
}

func (f *fake) wsHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		if f.stopped {
			f.mu.Unlock()
			http.Error(w, "companion stopped", http.StatusServiceUnavailable)
			return
		}
		c, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			f.mu.Unlock()
			return
		}
		write(c, proto.Welcome("fake", 0))
		for _, frame := range f.scenario.snapshot() {
			write(c, frame)
		}
		f.clients[c] = struct{}{}
		f.mu.Unlock()
		log.Printf("app connected from %s", r.RemoteAddr)

		defer func() {
			f.mu.Lock()
			delete(f.clients, c)
			f.mu.Unlock()
			c.CloseNow()
			log.Printf("app disconnected")
		}()
		for {
			_, b, err := c.Read(r.Context())
			if err != nil {
				return
			}
			log.Printf("app sent %s", b)
			m, perr := proto.ParseClient(b)
			if !json.Valid(b) {
				b, _ = json.Marshal(string(b))
			}
			f.mu.Lock()
			f.received = append(f.received, b)
			if perr == nil && (m.T == "hello" || m.T == "ping") {
				write(c, proto.Pong()) // as wsserver's readLoop does
			}
			f.mu.Unlock()
		}
	})
}

func (f *fake) broadcast(frame []byte) {
	for c := range f.clients {
		write(c, frame)
	}
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
		defer f.mu.Unlock()
		f.scenario, f.stopped, f.received = s, false, []json.RawMessage{}
		for _, frame := range s.snapshot() {
			f.broadcast(frame)
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
		f.mu.Lock()
		defer f.mu.Unlock()
		f.broadcast(b)
		log.Printf("emit %s", b)
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("GET /received", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(f.received)
	})
	mux.HandleFunc("POST /stop", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		f.stopped = true
		for c := range f.clients {
			c.CloseNow()
		}
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
