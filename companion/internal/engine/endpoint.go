package engine

import (
	"encoding/json"
	"errors"
	"io/fs"
	"log"
	"os"
	"path/filepath"
)

// endpointFile keeps the phone's push endpoint across restarts, so a
// companion the phone isn't connected to still notifies after it restarts.
type endpointFile struct {
	Endpoint string `json:"endpoint"`
}

// loadEndpoint reads the saved endpoint; a missing, unreadable or malformed
// file means none (the phone re-registers on its next connect).
func loadEndpoint(path string) string {
	if path == "" {
		return ""
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, fs.ErrNotExist) {
			log.Printf("push endpoint: %v", err)
		}
		return ""
	}
	var f endpointFile
	if err := json.Unmarshal(b, &f); err != nil {
		log.Printf("push endpoint: %s: %v", path, err)
		return ""
	}
	return f.Endpoint
}

// saveEndpoint writes the endpoint atomically, 0600, like limits.json.
func saveEndpoint(path, ep string) {
	if path == "" {
		return
	}
	b, _ := json.Marshal(endpointFile{Endpoint: ep})
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		log.Printf("push endpoint: %v", err)
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		log.Printf("push endpoint: %v", err)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		log.Printf("push endpoint: %v", err)
	}
}
