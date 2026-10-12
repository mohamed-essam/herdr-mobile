import json

from herdr_hermes import events
from herdr_hermes.events import (
    assistant_text,
    cap,
    clip,
    hello,
    question_notice,
    state,
    tool_result,
    tool_use,
    user_text,
)


def test_cap_short_unchanged():
    assert cap("hi") == "hi"
    assert cap("a" * 65536) == "a" * 65536


def test_cap_truncates():
    out = cap("a" * 70000)
    suffix = "…[truncated]"
    assert out.endswith(suffix)
    assert len(out) == 65536 + len(suffix)
    assert out[:65536] == "a" * 65536


def test_clip():
    assert clip("abc", 3) == "abc"
    assert clip("abcd", 3) == "ab…"
    assert len(clip("x" * 500, 400)) == 400


def test_user_text():
    assert user_text("u1", "hello", 5) == {"type": "user_text", "uuid": "u1", "text": "hello", "ts": 5}


def test_user_text_capped():
    assert user_text("u1", "a" * 70000, 5)["text"].endswith("…[truncated]")


def test_assistant_text():
    assert assistant_text("a1", "yo", 6) == {"type": "assistant_text", "uuid": "a1", "text": "yo", "ts": 6}


def test_tool_use_first_string_arg():
    assert tool_use("t1", "terminal", {"n": 1, "command": "ls -la"}, 7) == {
        "type": "tool_use",
        "uuid": "t1",
        "toolUseId": "t1",
        "tool": "terminal",
        "summary": "ls -la",
        "ts": 7,
    }


def test_tool_use_summary_clipped():
    s = tool_use("t1", "x", {"a": "y" * 300}, 1)["summary"]
    assert len(s) == 120 and s.endswith("…")


def test_tool_use_no_string_arg():
    assert tool_use("t1", "x", {"n": 1}, 1)["summary"] == ""
    assert tool_use("t1", "x", {}, 1)["summary"] == ""


def test_tool_result_string():
    assert tool_result("t1", "ok", False, 8) == {
        "type": "tool_result",
        "toolUseId": "t1",
        "isError": False,
        "preview": "ok",
        "ts": 8,
    }


def test_tool_result_dict_json_and_clip():
    r = {"k": "v"}
    assert tool_result("t1", r, True, 1)["preview"] == json.dumps(r)
    assert tool_result("t1", r, True, 1)["isError"] is True
    big = tool_result("t1", {"k": "v" * 1000}, False, 1)["preview"]
    assert len(big) == 400 and big.endswith("…")


def test_tool_result_non_json_falls_back_to_str():
    assert tool_result("t1", b"raw", False, 1)["preview"] == str(b"raw")


def test_question_notice():
    ev = question_notice("r1", "Which one?", 9)
    assert ev["type"] == "assistant_text"
    assert ev["text"] == "Hermes is asking: Which one?. Answer in the terminal."
    assert ev["ts"] == 9
    assert "r1" in ev["uuid"]


def test_hello_and_state():
    assert hello("s1", "/w") == {"type": "hello", "sessionId": "s1", "cwd": "/w", "agent": "hermes"}
    assert state(True) == {"type": "state", "state": "working"}
    assert state(False) == {"type": "state", "state": "idle"}


def test_constants():
    assert (events.MAX_TEXT, events.PREVIEW, events.SUMMARY) == (65536, 400, 120)
