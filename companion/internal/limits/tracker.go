package limits

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"
)

// Rebroadcast is how far ObservedAt may advance with unchanged windows
// before the reading goes out again (it keeps the app's "as of" age true).
const Rebroadcast = 60 * time.Second

// Limits is the latest reading: its windows and when a mod last reported
// it (epoch ms).
type Limits struct {
	Windows    []Window `json:"limits"`
	ObservedAt int64    `json:"observedAt"`
}

// Tracker keeps the newest reading any pane's mod reported (one account is
// assumed: readings from several overwrite each other), saved to path.
type Tracker struct {
	mu       sync.Mutex
	path     string
	now      func() time.Time
	onChange func(Limits)
	cur      Limits
	sentAt   int64
}

// New loads the reading saved at path (none when path is empty, missing or
// unreadable).
func New(path string, now func() time.Time) *Tracker {
	if now == nil {
		now = time.Now
	}
	t := &Tracker{path: path, now: now, onChange: func(Limits) {}}
	if path == "" {
		return t
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return t
	}
	var l Limits
	if json.Unmarshal(b, &l) == nil && len(l.Windows) > 0 {
		t.cur, t.sentAt = l, l.ObservedAt
	}
	return t
}

// SetOnChange registers the broadcast. It runs under the tracker's lock, so
// it must be fast and must not call back into the tracker. Set it before use.
func (t *Tracker) SetOnChange(fn func(Limits)) { t.onChange = fn }

// Current returns the reading, false when there is none.
func (t *Tracker) Current() (Limits, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.cur, len(t.cur.Windows) > 0
}

// Observe records a mod's windows as the newest reading, and saves and
// broadcasts it when they changed or Rebroadcast passed since the last.
func (t *Tracker) Observe(ws []Window) {
	if len(ws) == 0 {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	now := t.now().UnixMilli()
	changed := !slices.Equal(ws, t.cur.Windows)
	t.cur = Limits{Windows: slices.Clone(ws), ObservedAt: now}
	if !changed && now-t.sentAt < Rebroadcast.Milliseconds() {
		return
	}
	t.sentAt = now
	t.save()
	t.onChange(t.cur)
}

func (t *Tracker) save() {
	if t.path == "" {
		return
	}
	b, _ := json.Marshal(t.cur)
	dir := filepath.Dir(t.path)
	tmp := t.path + ".tmp"
	if err := os.MkdirAll(dir, 0o700); err != nil {
		log.Printf("limits: %v", err)
		return
	}
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		log.Printf("limits: %v", err)
		return
	}
	if err := os.Rename(tmp, t.path); err != nil {
		log.Printf("limits: %v", err)
	}
}
