package chatbridge

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/limits"
)

func TestUsageContextReachesSummary(t *testing.T) {
	h := NewHub(nil)
	var log summaryLog
	log.record(h)
	h.SetUsage("w1:p1", &Usage{Context: &Context{Percent: 61, Tokens: 122000, Window: 200000}})
	h.SyncBody("w1:p1", "s1", nil, nil)
	if c := log.last(t).Context; c == nil || c.Percent != 61 || c.Window != 200000 {
		t.Fatalf("context: %+v", c)
	}
}

func TestUsageWithoutContextClearsContext(t *testing.T) {
	h := NewHub(nil)
	var log summaryLog
	log.record(h)
	h.SetUsage("w1:p1", &Usage{Context: &Context{Percent: 61, Window: 200000}})
	h.SyncBody("w1:p1", "s1", nil, nil)
	h.SetUsage("w1:p1", &Usage{})
	h.SyncBody("w1:p1", "s1", nil, nil)
	if c := log.last(t).Context; c != nil {
		t.Fatalf("want cleared, got %+v", c)
	}
}

func TestNilUsageKeepsContext(t *testing.T) {
	h := NewHub(nil)
	var log summaryLog
	log.record(h)
	h.SetUsage("w1:p1", &Usage{Context: &Context{Percent: 40, Window: 200000}})
	h.SyncBody("w1:p1", "s1", nil, nil)
	h.SetUsage("w1:p1", nil) // an older mod's body
	h.SyncBody("w1:p1", "s1", nil, nil)
	if c := log.last(t).Context; c == nil || c.Percent != 40 {
		t.Fatalf("context lost: %+v", c)
	}
}

func TestContextClearedWhenModGoesOffline(t *testing.T) {
	now := time.Unix(1000, 0)
	h := NewHub(func() time.Time { return now })
	var log summaryLog
	log.record(h)
	h.SetUsage("w1:p1", &Usage{Context: &Context{Percent: 61, Window: 200000}})
	h.SyncBody("w1:p1", "s1", nil, nil)
	now = now.Add(LiveWindow + time.Second)
	h.Tick()
	if c := log.last(t).Context; c != nil {
		t.Fatalf("offline pane kept context: %+v", c)
	}
}

func TestUsageLimitsGoToOnLimits(t *testing.T) {
	h := NewHub(nil)
	var mu sync.Mutex
	var got [][]limits.Window
	h.SetOnLimits(func(ws []limits.Window) { mu.Lock(); got = append(got, ws); mu.Unlock() })
	h.SetUsage("w1:p1", &Usage{})
	h.SetUsage("w1:p1", &Usage{Limits: []limits.Window{{Kind: "five_hour", PercentUsed: 42}}})
	mu.Lock()
	defer mu.Unlock()
	if len(got) != 1 || got[0][0].Kind != "five_hour" {
		t.Fatalf("onLimits calls: %+v", got)
	}
}

func TestSyncHandlerReadsUsage(t *testing.T) {
	h := NewHub(nil)
	var log summaryLog
	log.record(h)
	var limitsSeen []limits.Window
	h.SetOnLimits(func(ws []limits.Window) { limitsSeen = ws })
	body := `{"paneId":"w1:p1","sessionId":"s1","events":[],"usage":{"context":{"percent":25,"tokens":50000,"window":200000},"limits":[{"kind":"seven_day","percentUsed":18,"resetsAt":"2026-10-15T14:00:00Z"}]}}`
	rec := httptest.NewRecorder()
	h.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/sync", bytes.NewBufferString(body)))
	if rec.Code != 200 {
		t.Fatalf("status %d", rec.Code)
	}
	if c := log.last(t).Context; c == nil || c.Percent != 25 {
		t.Fatalf("context: %+v", c)
	}
	if len(limitsSeen) != 1 || limitsSeen[0].ResetsAt != "2026-10-15T14:00:00Z" {
		t.Fatalf("limits: %+v", limitsSeen)
	}
}
