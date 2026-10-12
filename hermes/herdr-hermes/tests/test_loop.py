import json
import threading

from herdr_hermes import events
from herdr_hermes.loop import BODY_BYTES, CHUNK_BYTES, HISTORY_EVENTS, Loop
from herdr_hermes.socket_client import SyncError


class FakeSync:
    """Records each body (as the bytes sync would send) and plays replies."""

    def __init__(self):
        self.bodies = []
        self.sizes = []
        self.replies = []  # dicts, or exceptions to raise; {} once empty
        self.on_call = None

    def __call__(self, path, body, timeout=10.0):
        assert path == "/sock"
        data = json.dumps(body).encode("utf-8")
        self.sizes.append(len(data))
        self.bodies.append(json.loads(data))
        if self.on_call:
            self.on_call()
        reply = self.replies.pop(0) if self.replies else {}
        if isinstance(reply, BaseException):
            raise reply
        return reply

    def events(self, i=-1):
        return self.bodies[i]["events"]


def text(i, n=10):
    return events.user_text("u%d" % i, "x" * n, i)


def make(history=None, on_message=None):
    fake = FakeSync()
    got = []
    calls = []

    def hist():
        calls.append(1)
        return list(history or [])

    loop = Loop(
        pane_id="p1",
        socket="/sock",
        on_message=on_message or (lambda i, t: got.append((i, t))),
        history=hist,
        sync=fake,
    )
    return loop, fake, got, calls


def started(history=None, on_message=None):
    """A loop whose session is set and whose initial resync was sent."""
    loop, fake, got, calls = make(history, on_message)
    loop.set_session("s1", "/w")
    loop.tick_once()
    return loop, fake, got, calls


def types(evs):
    return [e["type"] for e in evs]


def snapshot_events(evs):
    out = []
    for e in evs:
        if e["type"] == "snapshot_chunk":
            out.extend(e["events"])
    return out


def test_no_sync_before_a_session():
    loop, fake, _, calls = make()
    loop.push(text(1))
    loop.tick_once()
    assert fake.bodies == [] and calls == []


def test_set_session_sends_hello_then_snapshot():
    hist = [text(1), text(2)]
    loop, fake, _, calls = started(hist)
    body = fake.bodies[0]
    assert body["paneId"] == "p1" and body["sessionId"] == "s1"
    assert types(body["events"]) == ["hello", "snapshot_begin", "snapshot_chunk", "snapshot_end"]
    assert body["events"][0] == events.hello("s1", "/w")
    assert body["events"][1]["total"] == 2
    assert snapshot_events(body["events"]) == hist
    assert calls == [1]


def test_events_sent_in_order_and_history_is_lazy():
    loop, fake, _, calls = started()
    for i in range(5):
        loop.push(text(i))
    loop.tick_once()
    assert fake.events() == [text(i) for i in range(5)]
    assert fake.bodies[-1]["paneId"] == "p1" and fake.bodies[-1]["sessionId"] == "s1"
    loop.tick_once()
    assert calls == [1]  # only the resync read the history


def test_heartbeat_when_queue_empty():
    loop, fake, _, _ = started()
    loop.tick_once()
    loop.tick_once()
    assert fake.bodies[-1] == {"paneId": "p1", "sessionId": "s1", "events": []}
    assert len(fake.bodies) == 3


def test_events_pushed_during_history_build_follow_the_snapshot():
    loop, fake, _, _ = make()
    loop.set_session("s1", "/w")
    loop.push(text(0))  # queued before the resync: covered by the history

    def hist():
        loop.push(text(9))
        return [text(1)]

    loop._history = hist
    loop.tick_once()
    evs = fake.events()
    assert types(evs) == ["hello", "snapshot_begin", "snapshot_chunk", "snapshot_end", "user_text"]
    assert evs[-1] == text(9)


def test_history_capped_to_newest():
    hist = [text(i, 1) for i in range(HISTORY_EVENTS + 7)]
    loop, fake, _, _ = started(hist)
    sent = []
    while True:
        sent.extend(fake.events())
        if sent and sent[-1]["type"] == "snapshot_end":
            break
        loop.tick_once()
    assert sent[1] == {"type": "snapshot_begin", "total": HISTORY_EVENTS}
    assert snapshot_events(sent) == hist[-HISTORY_EVENTS:]


def test_large_history_chunks_and_bodies_respect_limits():
    hist = [text(i, 300_000) for i in range(40)]  # ~12 MB of history
    loop, fake, _, _ = started(hist)
    for _ in range(20):
        loop.tick_once()
    sent = [e for b in fake.bodies for e in b["events"]]
    assert types(sent)[:2] == ["hello", "snapshot_begin"] and sent[-1]["type"] == "snapshot_end"
    assert snapshot_events(sent) == hist
    chunks = [e for e in sent if e["type"] == "snapshot_chunk"]
    assert len(chunks) > 1
    assert all(len(json.dumps(c).encode("utf-8")) <= CHUNK_BYTES for c in chunks)
    assert max(fake.sizes) <= BODY_BYTES
    assert CHUNK_BYTES == 2 * 1024 * 1024 and BODY_BYTES == 4_000_000


def test_body_never_exceeds_limit_with_large_queue():
    loop, fake, _, _ = started()
    # non-ASCII text: the serialized size (escapes included) is what counts
    pushed = [events.assistant_text("a%d" % i, "é" * 60_000, i) for i in range(200)]
    for ev in pushed:
        loop.push(ev)
    for _ in range(40):
        loop.tick_once()
    assert max(fake.sizes) <= BODY_BYTES
    assert sum(1 for b in fake.bodies[1:] if b["events"]) > 1
    assert [e for b in fake.bodies[1:] for e in b["events"]] == pushed


def test_event_alone_over_body_limit_is_dropped_not_wedged():
    loop, fake, _, _ = started()
    huge = {"type": "user_text", "uuid": "big", "text": "x" * BODY_BYTES, "ts": 1}
    loop.push(huge)
    loop.push(text(2))
    loop.tick_once()
    loop.tick_once()
    sent = [e for b in fake.bodies[1:] for e in b["events"]]
    assert sent == [text(2)]
    assert max(fake.sizes) <= BODY_BYTES


def test_event_that_fits_alone_waits_for_the_next_body():
    loop, fake, _, _ = started()
    big = {"type": "user_text", "uuid": "big", "text": "x" * (BODY_BYTES - 150), "ts": 1}
    loop.push(text(1))
    loop.push(big)
    loop.push(text(2))
    for _ in range(3):
        loop.tick_once()
    assert [b["events"] for b in fake.bodies[1:]] == [[text(1)], [big], [text(2)]]
    assert max(fake.sizes) <= BODY_BYTES


def test_messages_delivered_in_order_once():
    loop, fake, got, _ = started()
    fake.replies = [
        {"messages": [{"id": "m1", "text": "one"}, {"id": "m2", "text": "two"}]},
        {"messages": [{"id": "m2", "text": "two"}, {"id": "m3", "text": "three"}]},
        {},
    ]
    loop.tick_once()
    loop.tick_once()
    loop.tick_once()
    assert got == [("m1", "one"), ("m2", "two"), ("m3", "three")]


def test_malformed_messages_are_skipped():
    loop, fake, got, _ = started()
    fake.replies = [{"messages": [{"id": 1, "text": "x"}, "junk", {"id": "ok", "text": "y"}]}]
    loop.tick_once()
    fake.replies = [{"messages": "nope"}]
    loop.tick_once()
    assert got == [("ok", "y")]


def test_on_message_error_does_not_skip_later_messages():
    got = []

    def on_message(i, t):
        got.append(i)
        if i == "m1":
            raise RuntimeError("boom")

    loop, fake, _, _ = started(on_message=on_message)
    fake.replies = [{"messages": [{"id": "m1", "text": "a"}, {"id": "m2", "text": "b"}]}]
    loop.tick_once()
    loop.push(text(1))
    loop.tick_once()
    assert got == ["m1", "m2"]
    assert fake.events() == [text(1)]


def test_sync_error_drops_queue_then_resyncs_on_recovery():
    loop, fake, _, calls = started([text(0)])
    loop.push(text(1))
    fake.replies = [SyncError("down")]
    loop.tick_once()
    assert fake.events() == [text(1)]  # the failed attempt
    loop.push(text(2))  # pushed while offline: dropped
    fake.replies = [SyncError("still down")]
    loop.tick_once()
    assert fake.events() == []  # an empty probe
    loop.push(text(3))
    loop.tick_once()  # the probe succeeds: back online
    assert fake.events() == []
    loop.tick_once()  # full history
    evs = fake.events()
    assert types(evs) == ["hello", "snapshot_begin", "snapshot_chunk", "snapshot_end"]
    assert snapshot_events(evs) == [text(0)]
    assert calls == [1, 1]
    loop.tick_once()
    assert fake.events() == []  # nothing pushed while offline survives


def test_events_pushed_during_a_failing_sync_are_not_delivered():
    loop, fake, _, _ = started()
    fake.on_call = lambda: loop.push(text(7))
    fake.replies = [SyncError("down")]
    loop.tick_once()
    fake.on_call = None
    for _ in range(3):
        loop.tick_once()
    sent = [e for b in fake.bodies[2:] for e in b["events"]]
    assert text(7) not in sent


def test_resync_reply_resends_history():
    loop, fake, _, calls = started([text(0)])
    fake.replies = [{"resync": True}]
    loop.push(text(1))
    loop.tick_once()
    loop.tick_once()
    assert types(fake.events()) == ["hello", "snapshot_begin", "snapshot_chunk", "snapshot_end"]
    assert calls == [1, 1]


def test_tick_once_never_raises():
    loop, fake, _, _ = started()
    fake.replies = [RuntimeError("bug")]
    loop.tick_once()  # treated as a failed sync
    loop.tick_once()
    loop.tick_once()
    assert types(fake.events())[0] == "hello"


def test_history_error_retries_next_tick():
    loop, fake, _, _ = make()
    loop.set_session("s1", "/w")
    state = {"fail": True}

    def hist():
        if state["fail"]:
            state["fail"] = False
            raise OSError("db locked")
        return [text(1)]

    loop._history = hist
    loop.tick_once()
    assert fake.events() == []  # a heartbeat while the history is missing
    loop.tick_once()
    assert types(fake.events())[0] == "hello"


def test_new_session_resyncs_with_new_hello():
    loop, fake, _, _ = started()
    loop.set_session("s2", "/v")
    loop.tick_once()
    assert fake.events()[0] == events.hello("s2", "/v")
    assert fake.bodies[-1]["sessionId"] == "s2"


def test_start_stop_thread():
    loop, fake, _, _ = make()
    loop._tick = 0.001
    loop.set_session("s1", "/w")
    seen = threading.Event()
    fake.on_call = seen.set
    loop.start()
    loop.start()  # idempotent
    assert seen.wait(5)
    loop.stop()
    assert loop._thread is None
    assert not any(t.name == "herdr-hermes-sync" for t in threading.enumerate())
    loop.stop()  # harmless when stopped
