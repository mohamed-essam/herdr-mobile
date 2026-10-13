package main

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strconv"
	"strings"
)

// The fake's attach program, run by term_open in place of `herdr terminal attach`
// (herdr may be installed where the e2e flows run, and --takeover would seize a
// real pane). It is this binary again, as `fake-companion -attach <target>
// -control <control URL>`: it prints a banner, reports each read of what the
// app typed to POST /attach/input, and exits with the code POST /term-exit
// hands to GET /attach/wait.

// attachArgs reports whether args ask for the attach program.
func attachArgs(args []string) (target, control string, ok bool) {
	if len(args) != 4 || args[0] != "-attach" || args[2] != "-control" {
		return "", "", false
	}
	return args[1], args[3], true
}

// attachArgv records the app's term_open and runs the attach program for target.
func (f *fake) attachArgv(target string) []string {
	f.rec.record("TermOpen", target)
	f.mu.Lock()
	defer f.mu.Unlock()
	return []string{f.exe, "-attach", target, "-control", f.control}
}

// setControlURL is where attach programs reach the control API.
func (f *fake) setControlURL(u string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.control = u
}

// waitExit blocks until POST /term-exit, or takes the exit already pending.
func (f *fake) waitExit(w http.ResponseWriter, r *http.Request) {
	ch := make(chan int, 1)
	f.mu.Lock()
	if f.pendingExit != nil {
		ch <- *f.pendingExit
		f.pendingExit = nil
	} else {
		f.exitWaiters[ch] = struct{}{}
	}
	f.mu.Unlock()
	defer func() {
		f.mu.Lock()
		delete(f.exitWaiters, ch)
		f.mu.Unlock()
	}()
	select {
	case code := <-ch:
		fmt.Fprint(w, code)
	case <-r.Context().Done(): // the attach was closed
	}
}

// endAttaches ends every live attach with code, or the next one when none is live.
func (f *fake) endAttaches(code int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.exitWaiters) == 0 {
		f.pendingExit = &code
		return
	}
	for ch := range f.exitWaiters {
		ch <- code
		delete(f.exitWaiters, ch)
	}
}

// runAttach is the attach program: the PTY is raw, so each key arrives as the
// app sent it (ctrl-c as 0x03, not a signal).
func runAttach(target, control string) {
	raw := exec.Command("stty", "raw", "-echo")
	raw.Stdin = os.Stdin
	_ = raw.Run()
	fmt.Printf("fake attach %s\r\n", target)
	go func() {
		resp, err := http.Get(control + "/attach/wait")
		if err != nil {
			os.Exit(1)
		}
		b, _ := io.ReadAll(resp.Body)
		code, _ := strconv.Atoi(strings.TrimSpace(string(b)))
		os.Exit(code)
	}()
	buf := make([]byte, 4096)
	for {
		n, err := os.Stdin.Read(buf)
		if n > 0 {
			if resp, err := http.Post(control+"/attach/input?target="+url.QueryEscape(target), "application/octet-stream", bytes.NewReader(buf[:n])); err == nil {
				resp.Body.Close()
			}
			os.Stdout.Write(bytes.ReplaceAll(buf[:n], []byte("\r"), []byte("\r\n")))
		}
		if err != nil {
			return
		}
	}
}
