package notify

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/messam/herdr-mobile/companion/internal/state"
)

func TestShouldNotifyBlocked(t *testing.T) {
	p, ok := ShouldNotify(state.Transition{PaneID: "w6:p1", WorkspaceID: "w6", From: "working", To: "blocked"}, "", "Proceed? (y/n)")
	if !ok || p.Kind != "blocked" || p.Title != "w6 needs you" || p.Body != "Proceed? (y/n)" {
		t.Fatalf("bad blocked push: %+v ok=%v", p, ok)
	}
}

func TestShouldNotifyUsesDisplayName(t *testing.T) {
	// A friendly display name (the project/cwd basename) wins over the raw
	// workspace id in the title.
	p, ok := ShouldNotify(state.Transition{PaneID: "w7:p1", WorkspaceID: "w7", From: "working", To: "done"}, "omega3", "")
	if !ok || p.Title != "omega3 finished" {
		t.Fatalf("display name should drive the title, got %+v ok=%v", p, ok)
	}
	// And the WorkspaceID field still carries the raw id for the app.
	if p.WorkspaceID != "w7" {
		t.Fatalf("workspaceId should stay the raw id, got %q", p.WorkspaceID)
	}
}

func TestShouldNotifyFinishedOnlyFromWorking(t *testing.T) {
	if _, ok := ShouldNotify(state.Transition{WorkspaceID: "w6", From: "idle", To: "done"}, "", ""); ok {
		t.Fatal("idle->done should not notify")
	}
	p, ok := ShouldNotify(state.Transition{WorkspaceID: "w6", From: "working", To: "idle"}, "", "")
	if !ok || p.Kind != "finished" || p.Title != "w6 finished" {
		t.Fatalf("working->idle should be a finished push, got %+v ok=%v", p, ok)
	}
	if p.Body != "" {
		t.Fatalf("finished body must be empty, got %q", p.Body)
	}
}

func TestShouldNotifyIgnoresOther(t *testing.T) {
	if _, ok := ShouldNotify(state.Transition{From: "idle", To: "working"}, "", ""); ok {
		t.Fatal("idle->working should not notify")
	}
}

func TestHTTPNotifierPostsJSON(t *testing.T) {
	got := make(chan Push, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var p Push
		json.NewDecoder(r.Body).Decode(&p)
		got <- p
	}))
	defer srv.Close()
	n := NewHTTPNotifier(srv.URL, srv.Client())
	if err := n.Notify(context.Background(), Push{Kind: "blocked", WorkspaceID: "w6", Title: "w6 needs you"}); err != nil {
		t.Fatal(err)
	}
	p := <-got
	if p.Kind != "blocked" || p.Title != "w6 needs you" {
		t.Fatalf("server got wrong push: %+v", p)
	}
}

func TestHTTPNotifierEmptyEndpointIsNoOp(t *testing.T) {
	n := NewHTTPNotifier("", nil)
	if err := n.Notify(context.Background(), Push{Kind: "blocked"}); err != nil {
		t.Fatalf("empty endpoint should be a no-op, got %v", err)
	}
}

func TestHTTPNotifierNon2xxIsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	n := NewHTTPNotifier(srv.URL, srv.Client())
	if err := n.Notify(context.Background(), Push{Kind: "blocked"}); err == nil {
		t.Fatal("expected error on 500 response")
	}
}
