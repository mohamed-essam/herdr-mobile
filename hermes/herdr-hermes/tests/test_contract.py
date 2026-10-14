"""Pins the plugin's /sync output to the fixture the companion's
TestHermesSyncBodyIsAccepted accepts, so protocol drift on either side fails
a test."""
import json
import os

from herdr_hermes import events
from herdr_hermes.loop import Loop

FIXTURE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "..", "companion", "internal", "chatbridge", "testdata", "hermes_sync.json",
)


def emitted_body():
    """The body a Loop sends on the first turn of a new session."""
    sent = []

    def fake_sync(path, body, timeout=10.0):
        sent.append(body)
        return {}

    history = [{"type": "user_text", "uuid": "h-0", "text": "hi", "ts": 1000}]
    loop = Loop(pane_id="w1:p1", socket="unused", on_message=lambda i, t: None,
                history=lambda covered: (covered(), history)[1], sync=fake_sync)
    loop.set_session("s1", "/work/demo")
    loop.push(events.state(True))
    loop.tick_once()
    assert len(sent) == 1
    return sent[0]


def test_loop_emits_the_body_the_companion_fixture_holds():
    with open(FIXTURE, encoding="utf-8") as f:
        assert emitted_body() == json.load(f)
