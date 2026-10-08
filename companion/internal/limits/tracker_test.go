package limits

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func clock(start time.Time) (*time.Time, func() time.Time) {
	t := start
	return &t, func() time.Time { return t }
}

func TestObserveNewestWinsAndBroadcastsOnChange(t *testing.T) {
	now, fn := clock(time.UnixMilli(1_000_000))
	tr := New("", fn)
	var got []Limits
	tr.SetOnChange(func(l Limits) { got = append(got, l) })
	if _, ok := tr.Current(); ok {
		t.Fatal("no reading yet")
	}
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 42}})
	*now = now.Add(time.Second)
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 42}}) // same: no broadcast
	*now = now.Add(time.Second)
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 43}})
	if len(got) != 2 || got[1].Windows[0].PercentUsed != 43 || got[1].ObservedAt != 1_002_000 {
		t.Fatalf("broadcasts: %+v", got)
	}
	cur, _ := tr.Current()
	if cur.ObservedAt != 1_002_000 {
		t.Fatalf("current: %+v", cur)
	}
}

func TestObserveRebroadcastsAfterAMinuteUnchanged(t *testing.T) {
	now, fn := clock(time.UnixMilli(0))
	tr := New("", fn)
	n := 0
	tr.SetOnChange(func(Limits) { n++ })
	ws := []Window{{Kind: "seven_day", PercentUsed: 18}}
	tr.Observe(ws)
	*now = now.Add(59 * time.Second)
	tr.Observe(ws)
	if n != 1 {
		t.Fatalf("rebroadcast too early: %d", n)
	}
	*now = now.Add(time.Second)
	tr.Observe(ws)
	if n != 2 {
		t.Fatalf("want a rebroadcast at 60s, got %d", n)
	}
}

func TestObserveIgnoresEmpty(t *testing.T) {
	tr := New("", nil)
	tr.SetOnChange(func(Limits) { t.Fatal("must not broadcast") })
	tr.Observe(nil)
	if _, ok := tr.Current(); ok {
		t.Fatal("empty observation stored")
	}
}

func TestPersistAndReload(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "state")
	path := filepath.Join(dir, "limits.json")
	_, fn := clock(time.UnixMilli(5000))
	New(path, fn).Observe([]Window{{Kind: "five_hour", PercentUsed: 12.5, ResetsAt: "2026-10-08T19:40:00Z"}})
	if fi, err := os.Stat(dir); err != nil || fi.Mode().Perm() != 0o700 {
		t.Fatalf("state dir: %v %v", fi, err)
	}
	cur, ok := New(path, fn).Current()
	if !ok || cur.ObservedAt != 5000 || cur.Windows[0].ResetsAt != "2026-10-08T19:40:00Z" {
		t.Fatalf("reloaded: %+v %v", cur, ok)
	}
}

func TestLoadIgnoresCorruptFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "limits.json")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, ok := New(path, nil).Current(); ok {
		t.Fatal("corrupt file must read as no reading")
	}
}
