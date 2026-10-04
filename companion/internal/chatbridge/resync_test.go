package chatbridge

import (
	"encoding/json"
	"strings"
	"testing"
)

func raws(ss ...string) []json.RawMessage {
	out := make([]json.RawMessage, len(ss))
	for i, s := range ss {
		out[i] = json.RawMessage(s)
	}
	return out
}

func TestSyncResyncFlag(t *testing.T) {
	h := NewHub(nil)
	// A fresh hub (e.g. the companion restarted between two mod ticks) that
	// never saw a hello asks the mod to resync.
	if _, resync := h.SyncResync("p", "s1", raws(`{"type":"user_text","uuid":"u","text":"hi"}`)); !resync {
		t.Fatal("no hello yet: want resync")
	}
	if _, resync := h.SyncResync("p", "s1", nil); !resync {
		t.Fatal("still no hello: want resync")
	}
	if _, resync := h.SyncResync("p", "s1", raws(`{"type":"hello","sessionId":"s1","cwd":"/r"}`, `{"type":"snapshot","events":[]}`)); resync {
		t.Fatal("hello applied: want no resync")
	}
	if _, resync := h.SyncResync("p", "s1", nil); resync {
		t.Fatal("same session: want no resync")
	}
	// A different request session id without a hello in the batch.
	if _, resync := h.SyncResync("p", "s2", nil); !resync {
		t.Fatal("session changed without hello: want resync")
	}
	if _, resync := h.SyncResync("p", "s2", raws(`{"type":"hello","sessionId":"s2","cwd":"/r"}`)); resync {
		t.Fatal("hello for the new session: want no resync")
	}
}

func TestSyncHandlerReportsResync(t *testing.T) {
	_, c := serve(t, NewHub(nil))
	post := func(body string) map[string]json.RawMessage {
		t.Helper()
		res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var got map[string]json.RawMessage
		if err := json.NewDecoder(res.Body).Decode(&got); err != nil {
			t.Fatal(err)
		}
		return got
	}
	got := post(`{"paneId":"p","sessionId":"s","events":[]}`)
	if string(got["resync"]) != "true" {
		t.Fatalf("fresh hub: resync = %s", got["resync"])
	}
	got = post(`{"paneId":"p","sessionId":"s","events":[{"type":"hello","sessionId":"s","cwd":"/r"}]}`)
	if r, ok := got["resync"]; ok && string(r) != "false" {
		t.Fatalf("after hello: resync = %s", r)
	}
	if string(got["messages"]) != "[]" {
		t.Fatalf("messages = %s", got["messages"])
	}
}
