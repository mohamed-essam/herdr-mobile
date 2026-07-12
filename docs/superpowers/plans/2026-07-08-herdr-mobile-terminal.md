# herdr-mobile Interactive Terminal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tap an agent pane in herdr-mobile to open a live, interactive terminal for that pane.

**Architecture:** The companion spawns `herdr agent attach <pane_id>` on a PTY and streams raw bytes over the existing WebSocket (new `term_*` frames, base64). The Android app renders with the vendored Termux VT engine (`TerminalEmulator` + `TerminalView`) via a `RemoteTerminalSession` that pipes bytes to/from the socket instead of a local subprocess. Quick-reply is retired.

**Tech Stack:** Go 1.23 + `github.com/creack/pty` (companion); Kotlin/Compose + vendored Termux `terminal-emulator`/`terminal-view` (GPLv3) + OkHttp WebSocket (app).

## Global Constraints

- Companion WS `companionProtocol` bumps **1 → 2** (frames are additive; older frames unchanged).
- PTY bytes travel **base64-encoded inside JSON text frames** (no binary WS frames this phase).
- Companion stays **localhost-only, no auth** (app reaches it via emulator `10.0.2.2` or Tailscale). Do not add tokens.
- **Agent panes only.** `herdr agent attach <pane_id>` resolves for agent panes; shell panes are out of scope.
- Vendoring Termux makes the app **GPLv3** — add a top-level `LICENSE` (GPL-3.0-or-later) + attribution.
- Toolchain (unchanged from v1): Go 1.23; Android compileSdk/targetSdk 36, minSdk 26, AGP 8.13.2, Gradle 8.14.5, Kotlin 2.3.0, JDK 17, Compose BOM 2026.06.01.
- Module Go path prefix: `github.com/mohamed-essam/herdr-mobile/companion`.
- Termux Java package is `com.termux.terminal` (emulator) / `com.termux.view` (view); AGP namespaces `com.termux.emulator` / `com.termux.view`.
- creack/pty API: `pty.StartWithSize(cmd *exec.Cmd, ws *pty.Winsize) (*os.File, error)`, `pty.Setsize(f, ws)`, `pty.Winsize{Rows, Cols, X, Y uint16}`.

---

## Task 1: Companion `pty` package (PTY session over a subprocess)

**Files:**
- Create: `companion/internal/pty/session.go`
- Test: `companion/internal/pty/session_test.go`
- Modify: `companion/go.mod`, `companion/go.sum` (add creack/pty)

**Interfaces:**
- Produces:
  - `func Start(argv []string, cols, rows uint16, onData func([]byte), onExit func(int)) (*Session, error)`
  - `func (s *Session) Write(b []byte) error`
  - `func (s *Session) Resize(cols, rows uint16) error`
  - `func (s *Session) Close() error`

- [ ] **Step 1: Add the creack/pty dependency**

Run:
```bash
cd companion && go get github.com/creack/pty@v1.1.24
```
Expected: `go.mod` gains `require github.com/creack/pty v1.1.24`; `go.sum` updated.

- [ ] **Step 2: Write the failing test**

Create `companion/internal/pty/session_test.go`:
```go
package pty

import (
	"bytes"
	"sync"
	"testing"
	"time"
)

// collect is a tiny thread-safe byte sink for onData.
type collect struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (c *collect) add(b []byte) { c.mu.Lock(); c.buf.Write(b); c.mu.Unlock() }
func (c *collect) string() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.buf.String()
}

func TestSessionEchoesInputAndData(t *testing.T) {
	got := &collect{}
	s, err := Start([]string{"cat"}, 80, 24, got.add, func(int) {})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.Write([]byte("hello\n")); err != nil {
		t.Fatal(err)
	}
	deadline := time.After(2 * time.Second)
	for !bytes.Contains([]byte(got.string()), []byte("hello")) {
		select {
		case <-deadline:
			t.Fatalf("never saw echoed input, got %q", got.string())
		case <-time.After(20 * time.Millisecond):
		}
	}
}

func TestSessionResizeReflectedInChild(t *testing.T) {
	got := &collect{}
	// `stty size` prints "<rows> <cols>" read from the controlling tty.
	s, err := Start([]string{"sh", "-c", "sleep 0.3; stty size"}, 100, 40, got.add, func(int) {})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.Resize(120, 50); err != nil {
		t.Fatal(err)
	}
	deadline := time.After(2 * time.Second)
	for !bytes.Contains([]byte(got.string()), []byte("50 120")) {
		select {
		case <-deadline:
			t.Fatalf("resize not reflected, got %q", got.string())
		case <-time.After(20 * time.Millisecond):
		}
	}
}

func TestSessionOnExitFires(t *testing.T) {
	code := make(chan int, 1)
	s, err := Start([]string{"sh", "-c", "exit 3"}, 80, 24, func([]byte) {}, func(c int) { code <- c })
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	select {
	case c := <-code:
		if c != 3 {
			t.Fatalf("want exit code 3, got %d", c)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("onExit never fired")
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd companion && go test ./internal/pty/ -run TestSession -v`
Expected: FAIL — `undefined: Start`.

- [ ] **Step 4: Implement `session.go`**

Create `companion/internal/pty/session.go`:
```go
// Package pty runs a subprocess on a pseudo-terminal and streams its bytes.
// Used to bridge `herdr agent attach <pane>` to the app over the WebSocket.
package pty

import (
	"os"
	"os/exec"
	"sync"

	creackpty "github.com/creack/pty"
)

type Session struct {
	cmd  *exec.Cmd
	ptmx *os.File

	closeOnce sync.Once
}

// Start launches argv on a PTY sized cols x rows. onData is called with each
// chunk read from the PTY; onExit is called once with the process exit code
// when it ends. Both callbacks run on the session's own goroutines.
func Start(argv []string, cols, rows uint16, onData func([]byte), onExit func(int)) (*Session, error) {
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")
	ptmx, err := creackpty.StartWithSize(cmd, &creackpty.Winsize{Rows: rows, Cols: cols})
	if err != nil {
		return nil, err
	}
	s := &Session{cmd: cmd, ptmx: ptmx}

	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := ptmx.Read(buf)
			if n > 0 {
				chunk := make([]byte, n)
				copy(chunk, buf[:n])
				onData(chunk)
			}
			if err != nil {
				break
			}
		}
		code := 0
		if werr := cmd.Wait(); werr != nil {
			if ee, ok := werr.(*exec.ExitError); ok {
				code = ee.ExitCode()
			} else {
				code = -1
			}
		}
		onExit(code)
	}()

	return s, nil
}

func (s *Session) Write(b []byte) error {
	_, err := s.ptmx.Write(b)
	return err
}

func (s *Session) Resize(cols, rows uint16) error {
	return creackpty.Setsize(s.ptmx, &creackpty.Winsize{Rows: rows, Cols: cols})
}

// Close kills the process and closes the PTY. Killing the `herdr agent attach`
// client detaches without harming the pane.
func (s *Session) Close() error {
	s.closeOnce.Do(func() {
		if s.cmd.Process != nil {
			_ = s.cmd.Process.Kill()
		}
		_ = s.ptmx.Close()
	})
	return nil
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd companion && go test ./internal/pty/ -v`
Expected: PASS (all three).

- [ ] **Step 6: Commit**

```bash
cd companion && git add go.mod go.sum internal/pty/
git commit -m "feat(companion): pty package streaming a subprocess over a pseudo-terminal"
```

---

## Task 2: Companion protocol `term_*` frames

**Files:**
- Modify: `companion/internal/proto/proto.go`
- Test: `companion/internal/proto/proto_test.go`

**Interfaces:**
- Consumes: existing `ClientMsg`, `must`, frame builders.
- Produces:
  - `ClientMsg` gains: `TermID string json:"termId"`, `Target string json:"target"`, `Cols int json:"cols"`, `Rows int json:"rows"`, `Data string json:"data"`
  - `func TermOpened(reqID, termID string) []byte`
  - `func TermData(termID string, dataB64 string) []byte`
  - `func TermExit(termID string, code int) []byte`
  - `func TermError(reqID, termID, message string) []byte`
  - `Welcome` now reports `"companionProtocol": 2`

- [ ] **Step 1: Write the failing test**

Append to `companion/internal/proto/proto_test.go`:
```go
func TestParseClientTermFields(t *testing.T) {
	m, err := ParseClient([]byte(`{"t":"term_open","reqId":"r9","target":"w6:p1","cols":80,"rows":24}`))
	if err != nil {
		t.Fatal(err)
	}
	if m.T != "term_open" || m.Target != "w6:p1" || m.Cols != 80 || m.Rows != 24 || m.ReqID != "r9" {
		t.Fatalf("bad term_open parse: %+v", m)
	}
	m2, _ := ParseClient([]byte(`{"t":"term_input","termId":"t1","data":"aGk="}`))
	if m2.TermID != "t1" || m2.Data != "aGk=" {
		t.Fatalf("bad term_input parse: %+v", m2)
	}
}

func TestTermFrames(t *testing.T) {
	var got map[string]any

	json.Unmarshal(TermOpened("r9", "t1"), &got)
	if got["t"] != "term_opened" || got["reqId"] != "r9" || got["termId"] != "t1" {
		t.Fatalf("bad term_opened: %v", got)
	}

	json.Unmarshal(TermData("t1", "aGk="), &got)
	if got["t"] != "term_data" || got["termId"] != "t1" || got["data"] != "aGk=" {
		t.Fatalf("bad term_data: %v", got)
	}

	json.Unmarshal(TermExit("t1", 3), &got)
	if got["t"] != "term_exit" || got["termId"] != "t1" || got["code"].(float64) != 3 {
		t.Fatalf("bad term_exit: %v", got)
	}

	json.Unmarshal(TermError("r9", "t1", "boom"), &got)
	if got["t"] != "term_error" || got["message"] != "boom" {
		t.Fatalf("bad term_error: %v", got)
	}
}

func TestWelcomeAdvertisesProtocol2(t *testing.T) {
	var got map[string]any
	json.Unmarshal(Welcome("0.7.1", 14), &got)
	if got["companionProtocol"].(float64) != 2 {
		t.Fatalf("want companionProtocol 2, got %v", got["companionProtocol"])
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd companion && go test ./internal/proto/ -run 'TestParseClientTermFields|TestTermFrames|TestWelcomeAdvertisesProtocol2' -v`
Expected: FAIL — `undefined: TermOpened` (and the welcome assertion).

- [ ] **Step 3: Extend `proto.go`**

In `companion/internal/proto/proto.go`, add fields to `ClientMsg` (after `Keys string`):
```go
	TermID string `json:"termId"`
	Target string `json:"target"`
	Cols   int    `json:"cols"`
	Rows   int    `json:"rows"`
	Data   string `json:"data"`
```

Change `Welcome` to advertise protocol 2:
```go
func Welcome(version string, protocol int) []byte {
	return must(map[string]any{"t": "welcome", "herdrVersion": version, "herdrProtocol": protocol, "companionProtocol": 2})
}
```

Add the four builders at the end of the file:
```go
func TermOpened(reqID, termID string) []byte {
	return must(map[string]any{"t": "term_opened", "reqId": reqID, "termId": termID})
}
func TermData(termID, dataB64 string) []byte {
	return must(map[string]any{"t": "term_data", "termId": termID, "data": dataB64})
}
func TermExit(termID string, code int) []byte {
	return must(map[string]any{"t": "term_exit", "termId": termID, "code": code})
}
func TermError(reqID, termID, message string) []byte {
	return must(map[string]any{"t": "term_error", "reqId": reqID, "termId": termID, "message": message})
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd companion && go test ./internal/proto/ -v`
Expected: PASS (existing + new).

- [ ] **Step 5: Commit**

```bash
cd companion && git add internal/proto/
git commit -m "feat(companion): term_* protocol frames + companionProtocol v2"
```

---

## Task 3: Companion wsserver PTY bridge

**Files:**
- Modify: `companion/internal/wsserver/server.go`
- Test: `companion/internal/wsserver/server_test.go` (create)

**Interfaces:**
- Consumes: `pty.Start`, `proto.Term*`, existing `client{conn, send}`, `readLoop`, `remove`.
- Produces: per-client PTY sessions keyed by server-generated `termId` (`t<n>`), created via a package-level `attachArgv` builder so tests can inject a fake command.

Design notes:
- Add to `client`: `sessions map[string]*pty.Session` + `smu sync.Mutex`.
- Add to `Server`: `termSeq atomic.Uint64` and `attachArgv func(target string) []string` (default `[]string{"herdr","agent","attach", target}`).
- `term_data` sends use a blocking send guarded by ctx so terminal bytes are never dropped (backpressures the PTY read instead); pane broadcasts keep their existing non-blocking drop.
- Concurrency cap: `maxTerms = 8` per client.
- On disconnect: `remove`/`readLoop` return path closes all of the client's sessions.

- [ ] **Step 1: Write the failing test**

Create `companion/internal/wsserver/server_test.go`:
```go
package wsserver

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// stubRPC satisfies HerdrRPC without touching herdr.
type stubRPC struct{}

func (stubRPC) ReadPane(context.Context, string, string, int) (string, error) { return "", nil }
func (stubRPC) SendText(context.Context, string, string) error                { return nil }
func (stubRPC) SendKeys(context.Context, string, string) error               { return nil }

// readUntil reads frames until one with t==want is seen (or timeout).
func readUntil(t *testing.T, ctx context.Context, c *websocket.Conn, want string) map[string]any {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		rctx, cancel := context.WithTimeout(ctx, time.Second)
		_, b, err := c.Read(rctx)
		cancel()
		if err != nil {
			continue
		}
		var m map[string]any
		json.Unmarshal(b, &m)
		if m["t"] == want {
			return m
		}
	}
	t.Fatalf("never saw frame t=%q", want)
	return nil
}

func TestTermOpenEchoBridge(t *testing.T) {
	s := NewServer(AllowAll{}, stubRPC{})
	// Bridge to `cat` instead of herdr: echoes input straight back as term_data.
	s.attachArgv = func(target string) []string { return []string{"cat"} }

	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	// open a terminal
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r1","target":"w6:p1","cols":80,"rows":24}`))
	opened := readUntil(t, ctx, c, "term_opened")
	termID, _ := opened["termId"].(string)
	if termID == "" {
		t.Fatal("no termId in term_opened")
	}

	// send input; cat echoes it back as term_data
	in := base64.StdEncoding.EncodeToString([]byte("ping\n"))
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_input","termId":"`+termID+`","data":"`+in+`"}`))
	data := readUntil(t, ctx, c, "term_data")
	dec, _ := base64.StdEncoding.DecodeString(data["data"].(string))
	if !strings.Contains(string(dec), "ping") {
		t.Fatalf("echo not received, got %q", dec)
	}
}

func TestTermExitOnProcessEnd(t *testing.T) {
	s := NewServer(AllowAll{}, stubRPC{})
	s.attachArgv = func(target string) []string { return []string{"sh", "-c", "exit 0"} }
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, _ := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	defer c.Close(websocket.StatusNormalClosure, "")
	c.Write(ctx, websocket.MessageText, []byte(`{"t":"term_open","reqId":"r1","target":"x"}`))
	readUntil(t, ctx, c, "term_opened")
	readUntil(t, ctx, c, "term_exit")
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd companion && go test ./internal/wsserver/ -v`
Expected: FAIL — `s.attachArgv undefined` / compile error.

- [ ] **Step 3: Wire the PTY bridge into `server.go`**

In `companion/internal/wsserver/server.go`:

Add imports: `"encoding/base64"`, `"strconv"`, `"sync/atomic"`, and `"github.com/mohamed-essam/herdr-mobile/companion/internal/pty"`.

Add fields to `Server` (after `herdrProt int`):
```go
	termSeq    atomic.Uint64
	attachArgv func(target string) []string
```

In `NewServer`, set the default builder before `return`:
```go
	srv := &Server{auth: auth, rpc: rpc, clients: map[*client]struct{}{},
		snapshot: func() []state.Pane { return nil }, onPush: func(string) {},
		herdrVer: "unknown", herdrProt: 0}
	srv.attachArgv = func(target string) []string { return []string{"herdr", "agent", "attach", target} }
	return srv
```
(Replace the existing single-expression `return &Server{...}` with the above.)

Add fields to `client`:
```go
type client struct {
	conn     *websocket.Conn
	send     chan []byte
	sessions map[string]*pty.Session
	smu      sync.Mutex
}
```
Initialize `sessions` where the client is created in `Handler`:
```go
		c := &client{conn: conn, send: make(chan []byte, 64), sessions: map[string]*pty.Session{}}
```

Add a helper to safely enqueue a frame (blocking, ctx-aware) — terminal data must not be dropped:
```go
func sendBlocking(ctx context.Context, c *client, frame []byte) {
	select {
	case c.send <- frame:
	case <-ctx.Done():
	}
}
```

Add the term handlers and cleanup. In `readLoop`, add cases to the `switch m.T` block:
```go
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
```

Add methods (bottom of file):
```go
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
```

Ensure sessions are torn down on disconnect. In `Handler`, change the cleanup defer:
```go
		s.add(c)
		defer func() { c.closeAll(); s.remove(c) }()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd companion && go test ./internal/wsserver/ -v`
Expected: PASS (`TestTermOpenEchoBridge`, `TestTermExitOnProcessEnd`).

- [ ] **Step 5: Run the full companion suite**

Run: `cd companion && go build ./... && go test ./...`
Expected: all packages `ok`.

- [ ] **Step 6: Commit**

```bash
cd companion && git add internal/wsserver/
git commit -m "feat(companion): PTY bridge over the websocket (term_open/input/resize/close)"
```

---

## Task 4: Vendor the Termux terminal modules (GPLv3)

**Files:**
- Create: `app/terminal-emulator/` (copied source), `app/terminal-view/` (copied source)
- Create: `app/terminal-emulator/build.gradle.kts`, `app/terminal-view/build.gradle.kts`
- Modify: `app/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java` (two edits)
- Modify: `app/settings.gradle.kts`, `app/app/build.gradle.kts`
- Create: `LICENSE` (repo root)

**Interfaces:**
- Produces: Gradle modules `:terminal-emulator` and `:terminal-view`; `com.termux.terminal.TerminalSession` becomes non-final with a `protected` `mTranscriptRows`.

- [ ] **Step 1: Copy the two module source trees**

Run (uses a shallow clone; network required):
```bash
cd /tmp && rm -rf tx && git clone --depth 1 https://github.com/termux/termux-app.git tx
cd ~/herdr-mobile/app
mkdir -p terminal-emulator terminal-view
cp -r /tmp/tx/terminal-emulator/src terminal-emulator/src
cp -r /tmp/tx/terminal-view/src terminal-view/src
# drop the native subprocess code — we never spawn one
rm -rf terminal-emulator/src/main/jni
rm -rf terminal-view/src/androidTest terminal-emulator/src/test
```
Expected: `terminal-emulator/src/main/java/com/termux/terminal/*.java` and `terminal-view/src/main/java/com/termux/view/*.java` present; no `jni/` dir.

- [ ] **Step 2: Write `terminal-emulator/build.gradle.kts`**

```kotlin
plugins { id("com.android.library") }

android {
    namespace = "com.termux.emulator"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies { implementation("androidx.annotation:annotation:1.9.0") }
```

- [ ] **Step 3: Write `terminal-view/build.gradle.kts`**

```kotlin
plugins { id("com.android.library") }

android {
    namespace = "com.termux.view"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
    api(project(":terminal-emulator"))
}
```

- [ ] **Step 4: Make `TerminalSession` subclassable (two edits)**

In `app/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`:
- Change `public final class TerminalSession extends TerminalOutput {` → `public class TerminalSession extends TerminalOutput {`
- Change `private final Integer mTranscriptRows;` → `protected final Integer mTranscriptRows;`

- [ ] **Step 5: Register the modules**

In `app/settings.gradle.kts`, add after the existing `include(":app")`:
```kotlin
include(":terminal-emulator")
include(":terminal-view")
```

In `app/app/build.gradle.kts`, add to `dependencies { ... }`:
```kotlin
    implementation(project(":terminal-view"))
```

- [ ] **Step 6: Add the GPLv3 LICENSE**

Run:
```bash
cd ~/herdr-mobile
curl -sL https://www.gnu.org/licenses/gpl-3.0.txt -o LICENSE
```
Expected: `LICENSE` contains "GNU GENERAL PUBLIC LICENSE Version 3".

- [ ] **Step 7: Verify the modules compile**

Run:
```bash
cd ~/herdr-mobile/app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :terminal-emulator:assembleDebug :terminal-view:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. If a Termux source file references a stripped resource or `BuildConfig`, remove that reference (e.g. delete an unused androidTest leftover) until it compiles — do not add features.

- [ ] **Step 8: Commit**

```bash
cd ~/herdr-mobile
git add LICENSE app/settings.gradle.kts app/app/build.gradle.kts app/terminal-emulator app/terminal-view
git commit -m "build(app): vendor Termux terminal-emulator + terminal-view (GPLv3); app is now GPLv3"
```

---

## Task 5: `RemoteTerminalSession` bridge class

**Files:**
- Create: `app/terminal-emulator/src/main/java/com/termux/terminal/RemoteTerminalSession.java`

**Interfaces:**
- Consumes: `TerminalSession` (non-final), `TerminalEmulator`, `TerminalSessionClient`.
- Produces:
  - `interface RemoteTerminalSession.Io { void sendInput(byte[] data); void sendResize(int cols, int rows); }`
  - `new RemoteTerminalSession(TerminalSessionClient client, Io io)`
  - `void feed(byte[] data, int len)` — append remote bytes to the emulator on the main thread
  - overrides `initializeEmulator` (no subprocess), `updateSize` (emits resize), `write` (emits input)

Rationale: `TerminalView` only knows how to drive a `TerminalSession` (writes input via `write`/`writeCodePoint`, reads size via `updateSize`, paints from `getEmulator()`). Subclassing lets us reuse the entire view unchanged while swapping the local PTY for the WebSocket.

- [ ] **Step 1: Write the class**

```java
package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;

/**
 * A {@link TerminalSession} with no local subprocess: input the view produces is
 * forwarded to {@link Io#sendInput} (→ websocket term_input) and bytes arriving
 * from the companion are fed via {@link #feed} into the emulator. Reuses the
 * whole Termux TerminalView unchanged.
 */
public class RemoteTerminalSession extends TerminalSession {

    public interface Io {
        void sendInput(byte[] data);
        void sendResize(int cols, int rows);
    }

    private final Io mIo;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public RemoteTerminalSession(TerminalSessionClient client, Io io) {
        // shell/cwd/args/env are unused (no subprocess); transcriptRows default.
        super("/system/bin/sh", "/", new String[0], new String[0], 2000, client);
        this.mIo = io;
    }

    /** Create the emulator WITHOUT spawning a subprocess or IO threads. */
    @Override
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        mIo.sendResize(columns, rows);
    }

    /** The view calls this on layout/rotation; forward size changes upstream. */
    @Override
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        boolean firstTime = (mEmulator == null);
        super.updateSize(columns, rows, cellWidthPixels, cellHeightPixels);
        if (!firstTime) mIo.sendResize(columns, rows);
    }

    /** All view-originated input (keys, codepoints, paste) routes here. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (data == null || count <= 0) return;
        byte[] slice = (offset == 0 && count == data.length) ? data : Arrays.copyOfRange(data, offset, offset + count);
        mIo.sendInput(slice);
    }

    /** Feed bytes received from the companion into the emulator (main thread). */
    public void feed(final byte[] data, final int len) {
        mMain.post(() -> {
            if (mEmulator != null) {
                mEmulator.append(data, len);
                notifyScreenUpdate();
            }
        });
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run:
```bash
cd ~/herdr-mobile/app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :terminal-emulator:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. (If `mEmulator`, `mTranscriptRows`, `mClient`, or `notifyScreenUpdate` are not visible, re-check Task 4 Step 4 and that this file is in package `com.termux.terminal`.)

- [ ] **Step 3: Commit**

```bash
cd ~/herdr-mobile
git add app/terminal-emulator/src/main/java/com/termux/terminal/RemoteTerminalSession.java
git commit -m "feat(app): RemoteTerminalSession bridging Termux to the websocket"
```

---

## Task 6: App protocol + CompanionClient terminal methods

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/CompanionClient.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt` (create)

**Interfaces:**
- Consumes: `parseServerFrame`, `ServerFrame`, `ClientMsg`, `CompanionClient.request/send/frames`.
- Produces:
  - `ServerFrame.TermOpened(reqId, termId)`, `TermData(termId, data)`, `TermExit(termId, code)`, `TermError(reqId, termId, message)`
  - `ClientMsg.termOpen(reqId, target, cols, rows)`, `termInput(termId, dataB64)`, `termResize(termId, cols, rows)`, `termClose(termId)`
  - `suspend fun CompanionClient.openTerminal(target, cols, rows): String` (returns termId)
  - `fun CompanionClient.sendTermInput(termId, data: ByteArray)`, `sendTermResize(termId, cols, rows)`, `closeTerminal(termId)`

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`:
```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.net.ClientMsg
import dev.herdr.mobile.net.ServerFrame
import dev.herdr.mobile.net.parseServerFrame
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun parsesTermFrames() {
        assertTrue(parseServerFrame("""{"t":"term_opened","reqId":"r1","termId":"t1"}""") is ServerFrame.TermOpened)
        val d = parseServerFrame("""{"t":"term_data","termId":"t1","data":"aGk="}""")
        assertTrue(d is ServerFrame.TermData)
        assertEquals("aGk=", (d as ServerFrame.TermData).data)
        val x = parseServerFrame("""{"t":"term_exit","termId":"t1","code":3}""")
        assertEquals(3, (x as ServerFrame.TermExit).code)
    }

    @Test fun buildsTermClientMessages() {
        assertTrue(ClientMsg.termOpen("r1", "w6:p1", 80, 24).contains("\"term_open\""))
        assertTrue(ClientMsg.termInput("t1", "aGk=").contains("\"aGk=\""))
        assertTrue(ClientMsg.termResize("t1", 100, 40).contains("\"cols\":100"))
        assertTrue(ClientMsg.termClose("t1").contains("\"term_close\""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd ~/herdr-mobile/app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests dev.herdr.mobile.ProtocolTest
```
Expected: FAIL — unresolved `ServerFrame.TermOpened` / `ClientMsg.termOpen`.

- [ ] **Step 3: Extend `Protocol.kt`**

Add to the `ServerFrame` sealed interface:
```kotlin
    data class TermOpened(val reqId: String, val termId: String) : ServerFrame
    data class TermData(val termId: String, val data: String) : ServerFrame
    data class TermExit(val termId: String, val code: Int) : ServerFrame
    data class TermError(val reqId: String, val termId: String, val message: String) : ServerFrame
```

Add cases to `parseServerFrame`'s `when` (before `else`):
```kotlin
        "term_opened" -> ServerFrame.TermOpened(
            obj["reqId"]!!.jsonPrimitive.content, obj["termId"]!!.jsonPrimitive.content)
        "term_data" -> ServerFrame.TermData(
            obj["termId"]!!.jsonPrimitive.content, obj["data"]!!.jsonPrimitive.content)
        "term_exit" -> ServerFrame.TermExit(
            obj["termId"]!!.jsonPrimitive.content, obj["code"]?.jsonPrimitive?.int ?: 0)
        "term_error" -> ServerFrame.TermError(
            obj["reqId"]?.jsonPrimitive?.content ?: "", obj["termId"]?.jsonPrimitive?.content ?: "",
            obj["message"]?.jsonPrimitive?.content ?: "")
```
Add the import for `int`: ensure `import kotlinx.serialization.json.*` is present (it is).

Add to `object ClientMsg`:
```kotlin
    fun termOpen(reqId: String, target: String, cols: Int, rows: Int) =
        obj("t" to JsonPrimitive("term_open"), "reqId" to JsonPrimitive(reqId), "target" to JsonPrimitive(target), "cols" to JsonPrimitive(cols), "rows" to JsonPrimitive(rows))
    fun termInput(termId: String, dataB64: String) =
        obj("t" to JsonPrimitive("term_input"), "termId" to JsonPrimitive(termId), "data" to JsonPrimitive(dataB64))
    fun termResize(termId: String, cols: Int, rows: Int) =
        obj("t" to JsonPrimitive("term_resize"), "termId" to JsonPrimitive(termId), "cols" to JsonPrimitive(cols), "rows" to JsonPrimitive(rows))
    fun termClose(termId: String) =
        obj("t" to JsonPrimitive("term_close"), "termId" to JsonPrimitive(termId))
```

- [ ] **Step 4: Extend `CompanionClient.kt`**

Add to the `onMessage` correlation `when` (so `term_opened`/`term_error` complete a pending request):
```kotlin
                    is ServerFrame.TermOpened -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.TermError -> if (frame.reqId.isNotEmpty()) pending.remove(frame.reqId)?.complete(frame)
```
(Place alongside the existing `PaneRead`/`Ack`/`ErrorFrame` cases.)

Add methods to the class (near `sendKeys`):
```kotlin
    suspend fun openTerminal(target: String, cols: Int, rows: Int): String {
        val id = "r${seq.incrementAndGet()}"
        return when (val f = request(id, ClientMsg.termOpen(id, target, cols, rows))) {
            is ServerFrame.TermOpened -> f.termId
            is ServerFrame.TermError -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to term_open")
        }
    }

    fun sendTermInput(termId: String, data: ByteArray) {
        val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        ws?.send(ClientMsg.termInput(termId, b64))
    }

    fun sendTermResize(termId: String, cols: Int, rows: Int) { ws?.send(ClientMsg.termResize(termId, cols, rows)) }

    fun closeTerminal(termId: String) { ws?.send(ClientMsg.termClose(termId)) }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```bash
cd ~/herdr-mobile/app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests dev.herdr.mobile.ProtocolTest --tests dev.herdr.mobile.CompanionClientTest
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/net/ app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt
git commit -m "feat(app): term_* protocol frames + CompanionClient terminal methods"
```

---

## Task 7: TerminalScreen (Compose) + key toolbar

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt`

**Interfaces:**
- Consumes: `CompanionClient` (via `DashboardViewModel`), `RemoteTerminalSession`, Termux `TerminalView`, `TerminalViewClient`.
- Produces: `@Composable fun TerminalScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit)`.

Notes:
- `TerminalView` is an Android `View`; host it in `AndroidView`. Set a `RemoteTerminalSession` whose `Io` forwards to the VM's client; collect `vm.termFrames(termId)` and call `session.feed(...)`.
- Add a thin passthrough on the VM (Task 7 Step 1) exposing `client` operations + a filtered frame flow, so the UI never touches `CompanionClient` directly.
- The key toolbar sends control bytes directly via `session.write(...)` (Esc=0x1b, Tab=0x09, Ctrl-C=0x03, arrows=ESC sequences); Ctrl is a sticky modifier applied to the next letter (byte = letter & 0x1f).

- [ ] **Step 1: Add VM passthrough for terminals**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`, add:
```kotlin
    suspend fun openTerminal(target: String, cols: Int, rows: Int) = client.openTerminal(target, cols, rows)
    fun termInput(termId: String, data: ByteArray) = client.sendTermInput(termId, data)
    fun termResize(termId: String, cols: Int, rows: Int) = client.sendTermResize(termId, cols, rows)
    fun closeTerminal(termId: String) = client.closeTerminal(termId)
    val frames get() = client.frames
```
(`client` is already a constructor field.)

- [ ] **Step 2: Minimal TerminalViewClient**

Create `app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt`:
```kotlin
package dev.herdr.mobile.ui

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** No-frills client: default gestures, hardware-key passthrough, no logging. */
class TerminalViewClientImpl(private val view: TerminalView) : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: MotionEvent) { view.requestFocus() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
```

- [ ] **Step 3: TerminalScreen**

Create `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`:
```kotlin
package dev.herdr.mobile.ui

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var termId by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<RemoteTerminalSession?>(null) }
    var status by remember { mutableStateOf("connecting…") }
    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }

    // Feed incoming term_data into the emulator; react to exit.
    LaunchedEffect(termId) {
        val id = termId ?: return@LaunchedEffect
        vm.frames.collect { f ->
            when (f) {
                is ServerFrame.TermData -> if (f.termId == id) {
                    val bytes = Base64.decode(f.data, Base64.NO_WRAP)
                    session?.feed(bytes, bytes.size)
                }
                is ServerFrame.TermExit -> if (f.termId == id) status = "session ended (${f.code})"
                else -> {}
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { termId?.let { vm.closeTerminal(it) } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
                },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
        bottomBar = { session?.let { KeyToolbar(it) } },
    ) { pad ->
        AndroidView(
            modifier = Modifier.padding(pad).fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    setTextSize(36)
                    val client = TerminalViewClientImpl(this)
                    setTerminalViewClient(client)
                    val sessionClient = terminalSessionClient(this)
                    val sess = RemoteTerminalSession(sessionClient, object : RemoteTerminalSession.Io {
                        override fun sendInput(data: ByteArray) { termId?.let { vm.termInput(it, data) } }
                        override fun sendResize(cols: Int, rows: Int) { termId?.let { vm.termResize(it, cols, rows) } }
                    })
                    session = sess
                    attachSession(sess)
                    // open the remote terminal with the view's initial geometry
                    scope.launch {
                        val cols = if (mEmulator != null) mEmulator.mColumns else 80
                        val rows = if (mEmulator != null) mEmulator.mRows else 24
                        runCatching { vm.openTerminal(pane.paneId, cols, rows) }
                            .onSuccess { termId = it; status = "connected" }
                            .onFailure { status = "failed: ${it.message}" }
                    }
                }
            },
        )
    }
}

/** Minimal TerminalSessionClient (emulator-package callbacks). */
private fun terminalSessionClient(view: TerminalView): TerminalSessionClient =
    object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { view.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

@Composable
private fun KeyToolbar(session: RemoteTerminalSession) {
    val esc = byteArrayOf(0x1b)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp)) {
            KeyChip("esc") { session.write(esc, 0, 1) }
            KeyChip("tab") { session.write(byteArrayOf(0x09), 0, 1) }
            KeyChip("^C") { session.write(byteArrayOf(0x03), 0, 1) }
            KeyChip("↑") { session.write("[A".toByteArray(), 0, 3) }
            KeyChip("↓") { session.write("[B".toByteArray(), 0, 3) }
            KeyChip("←") { session.write("[D".toByteArray(), 0, 3) }
            KeyChip("→") { session.write("[C".toByteArray(), 0, 3) }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clickableNoRipple(onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(androidx.compose.foundation.clickable { onClick() })
```

- [ ] **Step 4: Verify the app compiles**

Run:
```bash
cd ~/herdr-mobile/app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If `TerminalSessionClient` has a different method set than listed, adjust the anonymous object to match the vendored interface exactly (check `terminal-emulator/.../TerminalSessionClient.java`).

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt
git commit -m "feat(app): interactive TerminalScreen (Termux view) + key toolbar"
```

---

## Task 8: Wire the dashboard; retire quick-reply

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`
- Delete: `app/app/src/main/java/dev/herdr/mobile/ui/QuickReplySheet.kt`

**Interfaces:**
- Consumes: `TerminalScreen`, `Pane.agent`.
- Produces: tapping an **agent** pane navigates to `TerminalScreen`; non-agent panes are non-tappable.

- [ ] **Step 1: Delete the quick-reply sheet**

```bash
rm app/app/src/main/java/dev/herdr/mobile/ui/QuickReplySheet.kt
```

- [ ] **Step 2: Remove the now-unused peek/reply path from the ViewModel**

In `DashboardViewModel.kt`, delete these three methods (superseded by the terminal):
```kotlin
    suspend fun peek(paneId: String): String = client.readPane(paneId)
    suspend fun reply(paneId: String, text: String, sendEnter: Boolean) { ... }
    suspend fun quickKey(paneId: String, key: String) = client.sendKeys(paneId, key)
```
(Keep `openTerminal`/`termInput`/`termResize`/`closeTerminal`/`frames` from Task 7, and `registerPush`, `start`, `panes`, `connected`.)

- [ ] **Step 3: Route taps to the terminal (agent panes only)**

Replace the body of `DashboardScreen` so a selected agent pane shows `TerminalScreen` instead of the sheet:
```kotlin
    var selected by remember { mutableStateOf<Pane?>(null) }

    LaunchedEffect(initialPaneId, panes) {
        if (initialPaneId != null && selected == null) {
            panes.firstOrNull { it.paneId == initialPaneId && it.agent != null }?.let { selected = it }
        }
    }

    selected?.let { pane ->
        TerminalScreen(vm, pane) { selected = null }
        return   // full-screen terminal replaces the dashboard while open
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HerdrTopBar(connected, panes.size) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!connected) ReconnectingBanner()
            if (panes.isEmpty()) {
                EmptyState(connected)
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                    items(panes, key = { it.paneId }) { pane ->
                        PaneRow(pane) { p -> if (p.agent != null) selected = p }
                    }
                }
            }
        }
    }
```
(Remove the old `QuickReplySheet(...)` call. `PaneRow`'s `onClick` now only sets `selected` for agent panes; non-agent taps are ignored.)

- [ ] **Step 4: Verify the app builds and unit tests pass**

Run:
```bash
cd ~/herdr-mobile/app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`; tests green. (If a leftover reference to `QuickReplySheet` remains, remove it.)

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add -A app/app/src/main/java/dev/herdr/mobile/ui/
git commit -m "feat(app): tap agent pane opens the terminal; retire quick-reply"
```

---

## Task 9: Live validation on the emulator + docs

**Files:**
- Modify: `.superpowers/sdd/progress.md`
- Modify: `the project design-notes memory`

- [ ] **Step 1: Rebuild the companion and deploy the app to the emulator**

Run:
```bash
cd ~/herdr-mobile
go -C companion build -o ~/.local/bin/herdr-mobiled ./cmd/herdr-mobiled
scripts/dev-emulator.sh --build
```
Expected: companion rebuilt, emulator booted, APK installed, app on the dashboard.

- [ ] **Step 2: Open omega3's terminal and verify a live render**

Run:
```bash
export ANDROID_HOME=$HOME/Android/Sdk; export PATH=$PATH:$ANDROID_HOME/platform-tools
# tap the omega3 agent row (find its y from a UI dump), then screenshot
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -n omega3
# tap the resolved coordinates, then:
adb exec-out screencap -p > $CLAUDE_JOB_DIR/tmp/term-live.png
```
Expected: the screenshot shows omega3's live terminal content rendered in color.

- [ ] **Step 3: Type a command through the terminal and confirm it lands**

Drive input via the soft keyboard / `adb shell input text` focused on the TerminalView, send a newline, screenshot, and confirm the command executed in the pane (cross-check with `herdr pane read w7:p1`). Verify Esc and Ctrl-C toolbar keys interrupt. Rotate the emulator (`adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1`) and confirm the terminal reflows (a `term_resize` was sent).

- [ ] **Step 4: Verify detach is clean**

Press back to leave the terminal (`term_close`), then confirm the pane still exists and is healthy:
```bash
python3 - <<'PY'
import socket, json
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); s.connect("~/.config/herdr/herdr.sock")
s.sendall((json.dumps({"id":"x","method":"pane.list","params":{}})+"\n").encode())
b=b""
while not b.endswith(b"\n"): b+=s.recv(65536)
print("w7:p1 present:", any(p["pane_id"]=="w7:p1" for p in json.loads(b)["result"]["panes"]))
PY
```
Expected: `w7:p1 present: True`.

- [ ] **Step 5: Update progress ledger + memory, then commit**

Record: the terminal feature shipped, the companion PTY bridge, the Termux vendoring (GPLv3), the two vendored edits, and any live-testing gotchas discovered. Then:
```bash
cd ~/herdr-mobile
git add .superpowers/sdd/progress.md
git commit -m "docs: record interactive terminal (v2 phase 1) shipped + validated"
```

---

## Self-Review

**Spec coverage:**
- Companion PTY bridge (`herdr agent attach`, base64 over WS) → Tasks 1–3. ✓
- `term_*` frames + protocol v2 → Task 2. ✓
- Disconnect closes sessions; concurrent cap → Task 3. ✓
- Termux VT engine, drive `TerminalEmulator` directly (no local subprocess) → Tasks 4–5, 7. ✓
- Key toolbar → Task 7. ✓
- Tap agent pane → terminal; retire quick-reply; non-agent panes non-tappable → Task 8. ✓
- GPLv3 LICENSE + attribution → Task 4. ✓
- Companion tests without herdr (sh/cat) → Tasks 1, 3. ✓
- App term_* (de)serialization tests → Task 6. ✓
- Live validation (render, type, resize, detach) → Task 9. ✓
- Non-goals (shell panes, sidebar, auth, structural mgmt, binary frames) → not implemented. ✓

**Placeholder scan:** No TBD/TODO; every code step has full code. Two "adjust to match the vendored interface" verification notes (Task 4 Step 7, Task 7 Step 4) are guardrails against Termux version drift, not missing content.

**Type consistency:** `RemoteTerminalSession.Io{sendInput,sendResize}`, `feed(byte[],int)`, `openTerminal→String termId`, `ServerFrame.Term*`, `ClientMsg.term*`, and companion `proto.Term*`/`attachArgv`/`termSeq` names are used identically across tasks. ✓
