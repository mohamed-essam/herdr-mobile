package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/messam/herdr-mobile/companion/internal/engine"
)

func defaultSocket() string {
	if v := os.Getenv("HERDR_SOCKET_PATH"); v != "" {
		return v
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config", "herdr", "herdr.sock")
}

func main() {
	socket := flag.String("socket", defaultSocket(), "path to herdr.sock")
	listen := flag.String("listen", "0.0.0.0:8787", "WS listen address (bind to your tailnet IP in production)")
	poll := flag.Duration("poll", 1500*time.Millisecond, "pane.list poll interval")
	flag.Parse()

	e := engine.New(engine.Config{SocketPath: *socket, ListenAddr: *listen, PollInterval: *poll})

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	log.Printf("herdr-mobiled: socket=%s listen=%s", *socket, *listen)
	if err := e.Run(ctx); err != nil {
		log.Fatal(err)
	}
}
