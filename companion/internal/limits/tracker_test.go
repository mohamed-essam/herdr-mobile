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
	tr := New("", nil)
	var got []Limits
	tr.SetOnChange(func(l Limits) { got = append(got, l) })
	if _, ok := tr.Current(); ok {
		t.Fatal("no reading yet")
	}
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 42}}, 1_000_000)
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 42}}, 1_001_000) // same: no broadcast
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 43}}, 1_002_000)
	if len(got) != 2 || got[1].Windows[0].PercentUsed != 43 || got[1].ObservedAt != 1_002_000 {
		t.Fatalf("broadcasts: %+v", got)
	}
	cur, _ := tr.Current()
	if cur.ObservedAt != 1_002_000 {
		t.Fatalf("current: %+v", cur)
	}
}

// Two panes resend their own last readings every sync: the older
// measurement must neither replace the newer nor cause a broadcast.
func TestObserveAlternatingPanesKeepsNewestMeasurement(t *testing.T) {
	tr := New("", nil)
	var got []Limits
	tr.SetOnChange(func(l Limits) { got = append(got, l) })
	a := []Window{{Kind: "five_hour", PercentUsed: 42}}
	b := []Window{{Kind: "five_hour", PercentUsed: 30}}
	for range 3 {
		tr.Observe(a, 2000)
		tr.Observe(b, 1000)
	}
	if len(got) != 1 {
		t.Fatalf("want one broadcast, got %+v", got)
	}
	if cur, _ := tr.Current(); cur.Windows[0].PercentUsed != 42 || cur.ObservedAt != 2000 {
		t.Fatalf("current: %+v", cur)
	}
	tr.Observe(b, 3000) // B measured anew: it wins
	if len(got) != 2 || got[1].Windows[0].PercentUsed != 30 || got[1].ObservedAt != 3000 {
		t.Fatalf("broadcasts: %+v", got)
	}
	tr.Observe(a, 2000)
	if cur, _ := tr.Current(); cur.Windows[0].PercentUsed != 30 || len(got) != 2 {
		t.Fatalf("older A replaced B: %+v %+v", cur, got)
	}
}

// An older mod sends no stamp: its reading is taken only when there is none.
func TestObserveUnstampedOnlyWhenEmpty(t *testing.T) {
	_, fn := clock(time.UnixMilli(7000))
	tr := New("", fn)
	n := 0
	tr.SetOnChange(func(Limits) { n++ })
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 10}}, 0)
	if cur, _ := tr.Current(); cur.ObservedAt != 7000 || n != 1 {
		t.Fatalf("first unstamped: %+v %d", cur, n)
	}
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 20}}, 0)
	if cur, _ := tr.Current(); cur.Windows[0].PercentUsed != 10 || n != 1 {
		t.Fatalf("second unstamped replaced: %+v %d", cur, n)
	}
}

func TestObserveRebroadcastsAfterAMinuteUnchanged(t *testing.T) {
	tr := New("", nil)
	n := 0
	tr.SetOnChange(func(Limits) { n++ })
	ws := []Window{{Kind: "seven_day", PercentUsed: 18}}
	tr.Observe(ws, 1)
	tr.Observe(ws, 1+59_000)
	if n != 1 {
		t.Fatalf("rebroadcast too early: %d", n)
	}
	tr.Observe(ws, 1+60_000)
	if n != 2 {
		t.Fatalf("want a rebroadcast at 60s, got %d", n)
	}
}

func TestObserveIgnoresEmpty(t *testing.T) {
	tr := New("", nil)
	tr.SetOnChange(func(Limits) { t.Fatal("must not broadcast") })
	tr.Observe(nil, 1000)
	if _, ok := tr.Current(); ok {
		t.Fatal("empty observation stored")
	}
}

func TestPersistAndReload(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "state")
	path := filepath.Join(dir, "limits.json")
	New(path, nil).Observe([]Window{{Kind: "five_hour", PercentUsed: 12.5, ResetsAt: "2026-10-08T19:40:00Z"}}, 5000)
	if fi, err := os.Stat(dir); err != nil || fi.Mode().Perm() != 0o700 {
		t.Fatalf("state dir: %v %v", fi, err)
	}
	cur, ok := New(path, nil).Current()
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
