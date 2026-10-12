"""The /sync loop: one daemon thread that streams queued chat events to the
companion and hands the phone's prompts back.

Mirrors `tick` in mod/herdr-chat/hooks/register.ts. Hooks call `push` and
`set_session` from Hermes' threads; only the loop thread does network I/O.
"""
from __future__ import annotations

import collections
import json
import threading
from typing import Callable, Deque, List, Optional

from . import events, socket_client

BODY_BYTES = 4_000_000  # a /sync body's serialized size, at most
CHUNK_BYTES = 2 * 1024 * 1024  # a snapshot_chunk's serialized size, at most
HISTORY_EVENTS = 5000  # the newest history events a snapshot carries
_SEP = 2  # json.dumps' ", " between list items


def _size(obj: object) -> int:
    # The same serialization socket_client.sync sends; bytes, not chars.
    return len(json.dumps(obj).encode("utf-8"))


def _snapshot(history: List[dict]) -> List[dict]:
    """snapshot_begin, chunks each serializing to <= CHUNK_BYTES (an event
    larger than that alone still makes one chunk), snapshot_end."""
    out = [{"type": "snapshot_begin", "total": len(history)}]
    empty = _size({"type": "snapshot_chunk", "events": []})
    cur: List[dict] = []
    size = empty
    for ev in history:
        n = _size(ev)
        if cur and size + _SEP + n > CHUNK_BYTES:
            out.append({"type": "snapshot_chunk", "events": cur})
            cur, size = [], empty
        size += n + (_SEP if cur else 0)
        cur.append(ev)
    if cur:
        out.append({"type": "snapshot_chunk", "events": cur})
    out.append({"type": "snapshot_end"})
    return out


class Loop:
    def __init__(
        self,
        *,
        pane_id: str,
        socket: str,
        on_message: Callable[[str, str], None],
        history: Callable[[], List[dict]],
        tick: float = 1.0,
        sync: Callable = socket_client.sync,
    ) -> None:
        self._pane_id = pane_id
        self._socket = socket
        self._on_message = on_message
        self._history = history
        self._tick = tick
        self._sync = sync
        # _lock guards the fields below; never held across sync or history.
        self._lock = threading.Lock()
        self._queue: Deque[dict] = collections.deque()
        self._session_id: Optional[str] = None
        self._cwd = ""
        self._offline = False
        self._need_resync = False
        # Serializes tick_once.
        self._ticking = threading.Lock()
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    # -- called from Hermes' threads ---------------------------------------

    def push(self, event: dict) -> None:
        with self._lock:
            # Offline, nothing is queued: the resync on recovery sends the
            # full history, which covers these (as the mod drops `pending`).
            if not self._offline:
                self._queue.append(event)

    def set_session(self, session_id: str, cwd: str) -> None:
        with self._lock:
            self._session_id = session_id
            self._cwd = cwd
            self._need_resync = True
            if not self._offline:
                self._queue.append(events.hello(session_id, cwd))

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="herdr-hermes-sync", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        t, self._thread = self._thread, None
        if t is None:
            return
        self._stop.set()
        if t is not threading.current_thread():
            t.join()

    # -- the loop thread ---------------------------------------------------

    def _run(self) -> None:
        stop = self._stop
        while not stop.wait(self._tick):
            self.tick_once()

    def tick_once(self) -> None:
        """One sync round. Never raises."""
        if not self._ticking.acquire(blocking=False):
            return
        try:
            self._tick_once()
        except Exception:  # a bug here must not kill the loop thread
            self._fail()
        finally:
            self._ticking.release()

    def _tick_once(self) -> None:
        with self._lock:
            session_id = self._session_id
            if session_id is None:
                return  # nothing to stream until Hermes names a session
            resync = self._need_resync and not self._offline
            if resync:
                # The history read below covers everything queued so far.
                self._need_resync = False
                self._queue.clear()
                cwd = self._cwd
        held = self._offline
        if resync:
            held = not self._resync(session_id, cwd)
        with self._lock:
            # Offline: an empty probe. A failed history read: a heartbeat.
            evs = [] if held else self._take(session_id)
        body = {"paneId": self._pane_id, "sessionId": session_id, "events": evs}
        try:
            reply = self._sync(self._socket, body)
        except Exception:
            self._fail()
            return
        with self._lock:
            if self._offline:
                self._offline = False
                self._need_resync = True  # sent on the next tick
            elif isinstance(reply, dict) and reply.get("resync") is True:
                self._need_resync = True
        self._deliver(reply.get("messages") if isinstance(reply, dict) else None)

    def _resync(self, session_id: str, cwd: str) -> bool:
        """Puts hello + the snapshot ahead of events pushed during the read."""
        try:
            history = list(self._history())[-HISTORY_EVENTS:]
            head = [events.hello(session_id, cwd)] + _snapshot(history)
        except Exception:
            with self._lock:
                self._need_resync = True  # retried next tick
            return False
        with self._lock:
            self._queue.extendleft(reversed(head))
        return True

    def _take(self, session_id: str) -> List[dict]:
        """Events from the queue's head while the body stays <= BODY_BYTES.

        An event that alone cannot fit in a body is dropped, so it never
        wedges the queue; one that just doesn't fit beside others waits.
        Caller holds _lock.
        """
        base = size = _size({"paneId": self._pane_id, "sessionId": session_id, "events": []})
        out: List[dict] = []
        while self._queue:
            n = _size(self._queue[0])
            if base + n > BODY_BYTES:
                self._queue.popleft()  # alone too large: drop it
                continue
            extra = n + (_SEP if out else 0)
            if size + extra > BODY_BYTES:
                break
            out.append(self._queue.popleft())
            size += extra
        return out

    def _fail(self) -> None:
        # Offline: what was queued (and whatever is pushed until we are back)
        # is dropped; recovery resends the full history instead.
        with self._lock:
            self._offline = True
            self._queue.clear()

    def _deliver(self, messages: object) -> None:
        # No dedupe by id: the companion drains its outbox once per body, and
        # its ids (m1, m2, ...) restart with each companion process.
        if not isinstance(messages, list):
            return
        for m in messages:
            if not isinstance(m, dict):
                continue
            mid, text = m.get("id"), m.get("text")
            if not isinstance(mid, str) or not isinstance(text, str):
                continue
            try:
                self._on_message(mid, text)
            except Exception:
                pass  # one bad message must not skip the rest
