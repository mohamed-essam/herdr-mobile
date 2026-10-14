"""Pure mappers from Hermes session data to herdr chat events.

Shapes mirror ChatEvent (mod/herdr-chat/hooks/normalize.ts) and Control
(mod/herdr-chat/hooks/register.ts). No I/O.
"""
from __future__ import annotations

import json

MAX_TEXT = 64 * 1024
PREVIEW = 400
SUMMARY = 120
_TRUNCATED = "…[truncated]"


def cap(s: str) -> str:
    return s[:MAX_TEXT] + _TRUNCATED if len(s) > MAX_TEXT else s


def clip(s: str, n: int) -> str:
    return s[: n - 1] + "…" if len(s) > n else s


def user_text(uuid: str, text: str, ts: int) -> dict:
    return {"type": "user_text", "uuid": uuid, "text": cap(text), "ts": ts}


def assistant_text(uuid: str, text: str, ts: int) -> dict:
    return {"type": "assistant_text", "uuid": uuid, "text": cap(text), "ts": ts}


def tool_use(tool_call_id: str, tool: str, args: dict, ts: int) -> dict:
    detail = next((v for v in (args or {}).values() if isinstance(v, str)), "")
    return {
        "type": "tool_use",
        "uuid": tool_call_id,
        "toolUseId": tool_call_id,
        "tool": tool,
        "summary": clip(" ".join(detail.split()), SUMMARY),
        "ts": ts,
    }


def _result_text(result: object) -> str:
    if isinstance(result, str):
        return result
    try:
        return json.dumps(result)
    except (TypeError, ValueError):
        return str(result)


def tool_result(tool_call_id: str, result: object, is_error: bool, ts: int) -> dict:
    return {
        "type": "tool_result",
        "toolUseId": tool_call_id,
        "isError": bool(is_error),
        "preview": clip(_result_text(result), PREVIEW),
        "ts": ts,
    }


def question_notice(request_id: str, prompt: str, ts: int) -> dict:
    return assistant_text(
        f"question-{request_id}",
        f"Hermes is asking: {prompt}. Answer in the terminal.",
        ts,
    )


def command_output(uuid: str, command: str, text: str, ts: int, is_error: bool = False) -> dict:
    ev = {"type": "command_output", "uuid": uuid, "command": command, "text": cap(text), "ts": ts}
    if is_error:
        ev["isError"] = True
    return ev


def hello(session_id: str, cwd: str) -> dict:
    return {"type": "hello", "sessionId": session_id, "cwd": cwd, "agent": "hermes"}


def state(working: bool) -> dict:
    return {"type": "state", "state": "working" if working else "idle"}
