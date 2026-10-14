"""Build the chat history snapshot from Hermes' conversation_history.

Message shape (OpenAI style, see hermes-agent agent/tool_dispatch_helpers.py and
agent/message_metadata.py): roles system/user/assistant/tool; `content` is a str,
None, or a list of parts ({"type": "text", "text": ...} / image parts);
assistant `tool_calls` entries are {"id", "type": "function",
"function": {"name", "arguments": <JSON string>}}; tool messages carry
`tool_call_id`; every message may carry `timestamp` (float epoch seconds).

Ids are derived from the message index (`h-<index>`) or the tool call id, so a
resync reproduces the same ids. Never raises: odd items are skipped.
"""
from __future__ import annotations

import json
from typing import Any

from . import events

MAX_EVENTS = 5000


def _text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for p in content:
            if isinstance(p, dict) and p.get("type") in ("text", "input_text", "output_text"):
                t = p.get("text")
                if isinstance(t, str) and t:
                    parts.append(t)
        return "\n".join(parts)
    return ""


def _ts(msg: dict, now_ms: int) -> int:
    """Message `timestamp` (epoch seconds, or ms if already large) else now_ms."""
    t = msg.get("timestamp")
    if isinstance(t, (int, float)) and not isinstance(t, bool) and t > 0:
        return int(t) if t >= 1e11 else int(t * 1000)
    return now_ms


def _args(raw: Any) -> dict:
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            val = json.loads(raw)
        except ValueError:
            return {}
        return val if isinstance(val, dict) else {}
    return {}


def _is_error(content: Any) -> bool:
    """Hermes tool_error() yields a JSON object with an "error" key."""
    if not isinstance(content, str) or '"error"' not in content:
        return False
    try:
        val = json.loads(content)
    except ValueError:
        return False
    return isinstance(val, dict) and "error" in val


def _convert(i: int, msg: dict, now_ms: int) -> list[dict]:
    role = msg.get("role")
    ts = _ts(msg, now_ms)
    out: list[dict] = []
    if role == "user":
        text = _text(msg.get("content"))
        if text:
            out.append(events.user_text(f"h-{i}", text, ts))
    elif role == "assistant":
        text = _text(msg.get("content"))
        if text:
            out.append(events.assistant_text(f"h-{i}", text, ts))
        calls = msg.get("tool_calls")
        for n, call in enumerate(calls if isinstance(calls, list) else []):
            if not isinstance(call, dict):
                continue
            fn = call.get("function")
            fn = fn if isinstance(fn, dict) else {}
            cid = call.get("id")
            cid = cid if isinstance(cid, str) and cid else f"h-{i}-{n}"
            name = fn.get("name")
            out.append(events.tool_use(
                cid, name if isinstance(name, str) else "", _args(fn.get("arguments")), ts))
    elif role == "tool":
        cid = msg.get("tool_call_id")
        cid = cid if isinstance(cid, str) and cid else f"h-{i}"
        content = msg.get("content")
        text = _text(content) if not isinstance(content, str) else content
        out.append(events.tool_result(cid, text, _is_error(text), ts))
    return out


def from_messages(messages: list[dict], now_ms: int) -> list[dict]:
    out: list[dict] = []
    if not isinstance(messages, (list, tuple)):
        return out
    for i, msg in enumerate(messages):
        if not isinstance(msg, dict):
            continue
        try:
            out.extend(_convert(i, msg, now_ms))
        except Exception:
            continue
    return out[-MAX_EVENTS:]
