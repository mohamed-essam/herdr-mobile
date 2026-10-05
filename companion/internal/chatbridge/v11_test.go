package chatbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"
)

func chunk(evs ...json.RawMessage) json.RawMessage {
	parts := make([]string, len(evs))
	for i, e := range evs {
		parts[i] = string(e)
	}
	return raw(`{"type":"snapshot_chunk","events":[` + strings.Join(parts, ",") + `]}`)
}

func hello(s string) json.RawMessage {
	return raw(`{"type":"hello","sessionId":"` + s + `","cwd":"/r"}`)
}

func questionEv(id string) json.RawMessage {
	return raw(`{"type":"question","uuid":"` + id + `","toolUseId":"` + id + `","questions":[{"question":"Color?","options":[{"label":"Blue"}]}],"ts":1}`)
}

func toolResult(id string) json.RawMessage {
	return raw(`{"type":"tool_result","toolUseId":"` + id + `","isError":false,"preview":"ok"}`)
}

func TestChunkedSnapshotSwapsAtEnd(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), raw(`{"type":"snapshot","events":[` + string(userText(1)) + `]}`)})
	_, ch, cancel := h.Subscribe("p", "")
	defer cancel()
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":3}`), chunk(userText(10), userText(11))})
	h.Sync("p", "s", []json.RawMessage{chunk(userText(12), raw(`{"type":"bogus"}`))})
	snap, _, c2 := h.Subscribe("p", "")
	c2()
	if snap.Epoch != 2 || len(snap.Events) != 1 {
		t.Fatalf("old epoch should stay visible during staging: %+v", snap)
	}
	select {
	case u := <-ch:
		t.Fatalf("no update expected while staging, got %+v", u)
	default:
	}
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_end"}`)})
	u := <-ch
	if u.Kind != "snapshot" || u.Epoch != 3 || u.Snapshot.Epoch != 3 || len(u.Snapshot.Events) != 3 {
		t.Fatalf("swap update: %+v", u)
	}
	for i, e := range u.Snapshot.Events {
		if e.Seq != i+1 {
			t.Fatalf("seqs not from 1: %+v", u.Snapshot.Events)
		}
	}
	if !strings.Contains(string(u.Snapshot.Events[0].Event), `"u10"`) {
		t.Fatalf("first event %s", u.Snapshot.Events[0].Event)
	}
	// Live events after the swap continue the new epoch's seqs.
	h.Sync("p", "s", []json.RawMessage{userText(13)})
	if u := <-ch; u.Kind != "event" || u.Epoch != 3 || u.Entry.Seq != 4 {
		t.Fatalf("live after swap: %+v", u)
	}
}

func TestEmptyChunkedSnapshot(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), userText(1)})
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":0}`), raw(`{"type":"snapshot_end"}`)})
	snap, _, cancel := h.Subscribe("p", "")
	defer cancel()
	if snap.Epoch != 2 || len(snap.Events) != 0 || snap.HasMore {
		t.Fatalf("empty chunked snapshot: %+v", snap)
	}
}

func TestNewBeginDiscardsUnfinishedStaging(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":2}`), chunk(userText(1), userText(2))})
	h.Sync("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":1}`), chunk(userText(3)), raw(`{"type":"snapshot_end"}`)})
	snap, _, cancel := h.Subscribe("p", "")
	defer cancel()
	if snap.Epoch != 2 || len(snap.Events) != 1 || !strings.Contains(string(snap.Events[0].Event), `"u3"`) {
		t.Fatalf("staging not discarded: %+v", snap)
	}
	// Stray chunk/end without a begin are ignored.
	h.Sync("p", "s", []json.RawMessage{chunk(userText(4)), raw(`{"type":"snapshot_end"}`)})
	snap2, _, c2 := h.Subscribe("p", "")
	c2()
	if snap2.Epoch != 2 || len(snap2.Events) != 1 {
		t.Fatalf("stray chunk/end applied: %+v", snap2)
	}
}

func fill(h *Hub, n int) {
	evs := make([]json.RawMessage, 0, n)
	for i := 1; i <= n; i++ {
		evs = append(evs, userText(i))
	}
	h.Sync("p", "s", evs)
}

func TestRingCap5000AndSubscribeTail(t *testing.T) {
	if RingCap != 5000 {
		t.Fatalf("RingCap = %d", RingCap)
	}
	h := NewHub(nil)
	fill(h, RingCap+10)
	snap, _, cancel := h.Subscribe("p", "")
	defer cancel()
	if len(snap.Events) != 300 || !snap.HasMore || snap.Events[0].Seq != RingCap+10-299 || snap.Events[299].Seq != RingCap+10 {
		t.Fatalf("tail: len=%d hasMore=%v first=%d", len(snap.Events), snap.HasMore, snap.Events[0].Seq)
	}
	evs, more, ok := h.History("p", "", snap.Epoch, 1<<30, 5000)
	_ = more
	if !ok || len(evs) != 300 {
		t.Fatalf("clamp: ok=%v len=%d", ok, len(evs))
	}
	// Walk back to the start of the ring.
	before, total := snap.Events[0].Seq, len(snap.Events)
	for {
		page, more, ok := h.History("p", "", snap.Epoch, before, 300)
		if !ok {
			t.Fatal("not ok")
		}
		total += len(page)
		if len(page) > 0 {
			before = page[0].Seq
		}
		if !more {
			break
		}
	}
	if total != RingCap || before != 11 {
		t.Fatalf("ring holds %d, oldest %d", total, before)
	}
}

func TestSubscribeSmallNoMore(t *testing.T) {
	h := NewHub(nil)
	fill(h, 300)
	snap, _, cancel := h.Subscribe("p", "")
	defer cancel()
	if len(snap.Events) != 300 || snap.HasMore {
		t.Fatalf("len=%d hasMore=%v", len(snap.Events), snap.HasMore)
	}
}

func TestHistoryPaging(t *testing.T) {
	h := NewHub(nil)
	fill(h, 20)
	snap, _, cancel := h.Subscribe("p", "")
	defer cancel()
	evs, more, ok := h.History("p", "", snap.Epoch, 11, 4)
	if !ok || !more || len(evs) != 4 || evs[0].Seq != 7 || evs[3].Seq != 10 {
		t.Fatalf("page: ok=%v more=%v %+v", ok, more, evs)
	}
	evs, more, ok = h.History("p", "", snap.Epoch, 5, 10)
	if !ok || more || len(evs) != 4 || evs[0].Seq != 1 || evs[3].Seq != 4 {
		t.Fatalf("start page: ok=%v more=%v %+v", ok, more, evs)
	}
	evs, more, ok = h.History("p", "", snap.Epoch, 1, 10)
	if !ok || more || len(evs) != 0 {
		t.Fatalf("before first: ok=%v more=%v %+v", ok, more, evs)
	}
	if _, _, ok := h.History("p", "", snap.Epoch+1, 11, 4); ok {
		t.Fatal("stale epoch should not be ok")
	}
	if _, _, ok := h.History("nope", "", 1, 11, 4); ok {
		t.Fatal("unknown pane should not be ok")
	}
}

func TestImageLRU(t *testing.T) {
	h := NewHub(nil)
	for i := 0; i < ImageCap+1; i++ {
		h.SyncBody("p", "s", nil, map[string]Image{fmt.Sprintf("u%d#0", i): {MediaType: "image/png", Data: fmt.Sprintf("d%d", i)}})
	}
	if _, _, ok := h.Image("p", "u0#0"); ok {
		t.Fatal("oldest image should be evicted")
	}
	mt, data, ok := h.Image("p", "u30#0")
	if !ok || mt != "image/png" || data != "d30" {
		t.Fatalf("newest image: %q %q %v", mt, data, ok)
	}
	if _, _, ok := h.Image("p", "missing"); ok {
		t.Fatal("missing image ok")
	}
	if _, _, ok := h.Image("other", "u30#0"); ok {
		t.Fatal("images are per pane")
	}
}

func TestImagesSurviveSnapshotBegin(t *testing.T) {
	h := NewHub(nil)
	// Images can precede the chunks that reference them.
	h.SyncBody("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":1}`)}, map[string]Image{"u1#0": {MediaType: "image/png", Data: "x"}})
	h.SyncBody("p", "s", []json.RawMessage{raw(`{"type":"snapshot_begin","total":1}`), chunk(userText(1)), raw(`{"type":"snapshot_end"}`)}, nil)
	if _, _, ok := h.Image("p", "u1#0"); !ok {
		t.Fatal("snapshot_begin must not clear images")
	}
}

func TestAnswerErrorsAndDelivery(t *testing.T) {
	h := NewHub(nil)
	if err := h.Answer("p", "t1", map[string]string{"Color?": "Blue"}); !errors.Is(err, ErrNoMod) {
		t.Fatalf("unknown pane: %v", err)
	}
	h.Sync("p", "s", []json.RawMessage{hello("s")})
	if err := h.Answer("p", "t1", map[string]string{"Color?": "Blue"}); !errors.Is(err, ErrNoQuestion) || ErrNoQuestion.Error() != "no_question" {
		t.Fatalf("no question: %v", err)
	}
	h.Sync("p", "s", []json.RawMessage{questionEv("t1"), questionEv("t1")}) // idempotent re-send
	if err := h.Answer("p", "t1", map[string]string{"Color?": "Blue"}); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	ans, ok := h.WaitAnswer(context.Background(), "p", "t1", 5*time.Second)
	if !ok || ans["Color?"] != "Blue" || time.Since(start) > time.Second {
		t.Fatalf("stored answer: %v %v", ans, ok)
	}
	// Its tool_result forgets the question.
	h.Sync("p", "s", []json.RawMessage{toolResult("t1")})
	if err := h.Answer("p", "t1", map[string]string{"Color?": "Red"}); !errors.Is(err, ErrNoQuestion) {
		t.Fatalf("after tool_result: %v", err)
	}
	if _, ok := h.WaitAnswer(context.Background(), "p", "t1", 50*time.Millisecond); ok {
		t.Fatal("forgotten question still answers")
	}
}

func TestWaitAnswerWakesOnLaterAnswer(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("t1")})
	got := make(chan map[string]string, 1)
	go func() {
		a, ok := h.WaitAnswer(context.Background(), "p", "t1", 5*time.Second)
		if !ok {
			a = nil
		}
		got <- a
	}()
	time.Sleep(50 * time.Millisecond)
	if err := h.Answer("p", "t1", map[string]string{"Color?": "Green"}); err != nil {
		t.Fatal(err)
	}
	select {
	case a := <-got:
		if a["Color?"] != "Green" {
			t.Fatalf("woke with %v", a)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("waiter not woken")
	}
}

func TestWaitAnswerTimesOutAndHonorsContext(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("t1")})
	start := time.Now()
	if _, ok := h.WaitAnswer(context.Background(), "p", "t1", 80*time.Millisecond); ok {
		t.Fatal("want timeout")
	}
	if d := time.Since(start); d < 70*time.Millisecond || d > 2*time.Second {
		t.Fatalf("timeout took %v", d)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, ok := h.WaitAnswer(ctx, "p", "t1", 5*time.Second); ok {
		t.Fatal("cancelled ctx should return false")
	}
}

func TestQuestionExpiresAfterAnHour(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("t1")})
	c.add(QuestionTTL + time.Second)
	h.Sync("p", "s", nil) // keep the pane live
	if err := h.Answer("p", "t1", map[string]string{"a": "b"}); !errors.Is(err, ErrNoQuestion) {
		t.Fatalf("expired question: %v", err)
	}
}

func TestNewSessionForgetsQuestions(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s1", []json.RawMessage{hello("s1"), questionEv("t1")})
	h.Sync("p", "s2", []json.RawMessage{hello("s2")})
	if err := h.Answer("p", "t1", map[string]string{"a": "b"}); !errors.Is(err, ErrNoQuestion) {
		t.Fatalf("question from the old session: %v", err)
	}
}

func TestQuestionIsChatEvent(t *testing.T) {
	h := NewHub(nil)
	_, ch, cancel := h.Subscribe("p", "")
	defer cancel()
	h.Sync("p", "s", []json.RawMessage{questionEv("t1")})
	if u := <-ch; u.Kind != "event" || !strings.Contains(string(u.Entry.Event), `"question"`) {
		t.Fatalf("question update: %+v", u)
	}
}

func TestAnswerHandler(t *testing.T) {
	h := NewHub(nil)
	h.answerWait = 100 * time.Millisecond
	_, c := serve(t, h)
	post := func(body string) (int, map[string]json.RawMessage) {
		t.Helper()
		res, err := c.Post("http://chat/answer", "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var got map[string]json.RawMessage
		json.NewDecoder(res.Body).Decode(&got)
		return res.StatusCode, got
	}
	if code, _ := post(`{"toolUseId":"t1"}`); code != 400 {
		t.Fatalf("missing paneId: %d", code)
	}
	if code, _ := post(`nope`); code != 400 {
		t.Fatalf("bad json: %d", code)
	}
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("t1")})
	start := time.Now()
	code, got := post(`{"paneId":"p","toolUseId":"t1"}`)
	if code != 200 || string(got["answer"]) != "null" || time.Since(start) < 90*time.Millisecond {
		t.Fatalf("timeout: %d %s after %v", code, got["answer"], time.Since(start))
	}
	if err := h.Answer("p", "t1", map[string]string{"Color?": "Blue"}); err != nil {
		t.Fatal(err)
	}
	code, got = post(`{"paneId":"p","toolUseId":"t1"}`)
	if code != 200 || string(got["answer"]) != `{"Color?":"Blue"}` {
		t.Fatalf("answer: %d %s", code, got["answer"])
	}
}

func TestAnswerHandlerIsHeartbeat(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	h.answerWait = 10 * time.Millisecond
	_, cl := serve(t, h)
	h.Sync("p", "s", []json.RawMessage{hello("s")})
	c.add(4 * time.Second)
	res, err := cl.Post("http://chat/answer", "application/json", strings.NewReader(`{"paneId":"p","toolUseId":"t1"}`))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	c.add(2 * time.Second)
	h.Tick()
	if !h.Live("p") {
		t.Fatal("/answer should refresh lastSeen")
	}
}

func TestSyncHandlerStoresImages(t *testing.T) {
	h := NewHub(nil)
	_, c := serve(t, h)
	body := `{"paneId":"p","sessionId":"s","events":[{"type":"tool_result","toolUseId":"x","isError":false,"preview":"","images":["u#1"]}],"images":{"u#1":{"mediaType":"image/png","data":"QUJD"}}}`
	res, err := c.Post("http://chat/sync", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("status %d", res.StatusCode)
	}
	mt, data, ok := h.Image("p", "u#1")
	if !ok || mt != "image/png" || data != "QUJD" {
		t.Fatalf("image: %q %q %v", mt, data, ok)
	}
}

func TestPollingKeepsQuestionAlive(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("t1"), questionEv("t2")})
	for i := 0; i < 2; i++ {
		c.add(59 * time.Minute)
		h.Sync("p", "s", nil)
		h.WaitAnswer(context.Background(), "p", "t1", time.Millisecond)
	}
	if err := h.Answer("p", "t1", map[string]string{"a": "b"}); err != nil {
		t.Fatalf("polled question: %v", err)
	}
	if err := h.Answer("p", "t2", map[string]string{"a": "b"}); !errors.Is(err, ErrNoQuestion) {
		t.Fatalf("unpolled question after 118 min: %v", err)
	}
	h.Sync("p", "s", []json.RawMessage{questionEv("t3")})
	c.add(61 * time.Minute)
	h.Sync("p", "s", nil)
	if err := h.Answer("p", "t3", map[string]string{"a": "b"}); !errors.Is(err, ErrNoQuestion) {
		t.Fatalf("61 min without a poll: %v", err)
	}
}

func TestAnswerRejectsEmpty(t *testing.T) {
	h := NewHub(nil)
	h.Sync("p", "s", []json.RawMessage{hello("s"), questionEv("t1")})
	for _, a := range []map[string]string{nil, {}} {
		if err := h.Answer("p", "t1", a); !errors.Is(err, ErrEmpty) {
			t.Fatalf("answers %v: %v", a, err)
		}
	}
}

func TestImageStoreByteCap(t *testing.T) {
	h := NewHub(nil)
	big := strings.Repeat("x", 15<<20)
	for i := 0; i < 3; i++ {
		h.SyncBody("p", "s", nil, map[string]Image{fmt.Sprintf("b%d", i): {MediaType: "image/png", Data: big}})
	}
	if _, _, ok := h.Image("p", "b0"); ok {
		t.Fatal("oldest image should be evicted over 40 MB")
	}
	for _, id := range []string{"b1", "b2"} {
		if _, _, ok := h.Image("p", id); !ok {
			t.Fatalf("%s evicted", id)
		}
	}
	// Replacing an id accounts its bytes once.
	h.SyncBody("p", "s", nil, map[string]Image{"b2": {MediaType: "image/png", Data: big}})
	if _, _, ok := h.Image("p", "b1"); !ok {
		t.Fatal("re-storing b2 double-counted its bytes")
	}
}

func TestStagingClearedOnDeathAndNewSession(t *testing.T) {
	c := &fakeClock{t: time.Unix(1000, 0)}
	h := NewHub(c.now)
	h.Sync("p", "s1", []json.RawMessage{hello("s1"), raw(`{"type":"snapshot_begin","total":2}`), chunk(userText(1))})
	c.add(LiveWindow)
	h.Tick()
	h.Sync("p", "s1", []json.RawMessage{chunk(userText(2)), raw(`{"type":"snapshot_end"}`)})
	snap, _, c1 := h.Subscribe("p", "")
	c1()
	if snap.Epoch != 1 {
		t.Fatalf("staging survived the mod's death: %+v", snap)
	}
	h.Sync("p", "s1", []json.RawMessage{raw(`{"type":"snapshot_begin","total":2}`), chunk(userText(3))})
	h.Sync("p", "s2", []json.RawMessage{hello("s2"), raw(`{"type":"snapshot_end"}`)})
	snap, _, c2 := h.Subscribe("p", "")
	c2()
	if snap.Epoch != 1 {
		t.Fatalf("staging survived a new session: %+v", snap)
	}
}
