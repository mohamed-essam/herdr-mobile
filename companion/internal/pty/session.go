// Package pty runs a subprocess on a pseudo-terminal and streams its bytes.
// Used to bridge `herdr terminal attach <pane>` to the app over the WebSocket.
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

// Close kills the process and closes the PTY. Killing the `herdr terminal attach`
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
