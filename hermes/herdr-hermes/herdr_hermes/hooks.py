"""Hermes hook callbacks: each payload becomes chat events on the loop's queue.

Kwargs as Hermes sends them (hermes-agent): on_session_start
(agent/conversation_loop.py), pre_llm_call (agent/turn_context.py),
post_llm_call and on_session_end (agent/turn_finalizer.py),
on_interim_message (agent/stream_delivery.py, `text`), pre_tool_call
(hermes_cli/plugins.py), post_tool_call (model_tools.py, `status` "ok" on
success), agent_loop_stopped (gateway/run_agent_cache.py,
tui_gateway/session_lifecycle.py), on_session_reset
(hermes_cli/cli_session_mixin.py, gateway/slash_commands_session.py),
on_human_input_request (tools/human_input_hooks.py).

Every callback only enqueues, never raises and returns None: a non-None
pre_llm_call return is injected into the prompt, and a pre_tool_call dict
can block or rewrite the tool call. Subagent payloads are skipped: their
on_session_start / post_llm_call / on_session_end carry platform
"subagent", their pre_llm_call a parent_session_id, and their tool and
interim payloads only their session_id, remembered from the first two.
"""
from __future__ import annotations

import itertools
import logging
import threading
from typing import Any, Callable, Dict, List, Optional, Set

from . import events, history
from .history import _text as _message_text  # str or content parts to text

logger = logging.getLogger(__name__)

HOOKS = (
    "on_session_start",
    "pre_llm_call",
    "post_llm_call",
    "on_interim_message",
    "pre_tool_call",
    "post_tool_call",
    "agent_loop_stopped",
    "on_session_end",
    "on_session_reset",
    "on_human_input_request",
)
_SUBAGENT_PLATFORM = "subagent"
_OK = "ok"  # model_tools.py _tool_result_observer_fields' success status


def _str(v: Any) -> str:
    return v if isinstance(v, str) else ""


class Hooks:
    """The hook callbacks of one pane. `attach` wires the loop and injector,
    which need `push`, `session_key` and `history_events` to be built."""

    def __init__(self, *, clock: Callable[[], float], cwd: Callable[[], str]) -> None:
        self._clock = clock
        self._cwd = cwd
        self._loop: Any = None
        self._injector: Any = None
        self._lock = threading.Lock()  # guards the fields below
        self._session_id: Optional[str] = None
        self._session_key: Optional[str] = None
        self._history: List[Any] = []
        self._subagents: Set[str] = set()
        self._ids = itertools.count(1)

    def attach(self, loop: Any, injector: Any) -> None:
        self._loop = loop
        self._injector = injector

    # -- for the loop and the injector ---------------------------------------

    def push(self, event: dict) -> None:
        loop = self._loop
        if loop is not None:
            loop.push(event)

    def session_key(self) -> Optional[str]:
        with self._lock:
            return self._session_key

    def history_events(self) -> List[dict]:
        """Called on the loop thread: converts a copy, never Hermes' list."""
        with self._lock:
            msgs = list(self._history)
        return history.from_messages(msgs, self._ts())

    def callbacks(self) -> Dict[str, Callable[..., None]]:
        return {name: self._guarded(name, getattr(self, "_" + name)) for name in HOOKS}

    # -- helpers -------------------------------------------------------------

    def _guarded(self, name: str, fn: Callable[[Dict[str, Any]], None]) -> Callable[..., None]:
        # No functools.wraps: Hermes reads inspect.signature (which follows
        # __wrapped__) and passes a callback without **kwargs only the names
        # it declares (hermes_cli/plugins_dispatch.py _hook_callback_kwargs).
        def callback(*_args: Any, **kw: Any) -> None:
            try:
                fn(kw)
            except Exception:
                logger.debug("herdr-hermes: %s callback failed", name, exc_info=True)
            return None

        callback.__name__ = callback.__qualname__ = "herdr_hermes_" + name
        return callback

    def _ts(self) -> int:
        return int(self._clock() * 1000)

    def _uuid(self) -> str:
        return "hermes-%d" % next(self._ids)

    def _skip(self, kw: Dict[str, Any]) -> bool:
        """True for a subagent's payload; else remembers its session_key."""
        sid = _str(kw.get("session_id"))
        sub = bool(_str(kw.get("parent_session_id"))) or kw.get("platform") == _SUBAGENT_PLATFORM
        with self._lock:
            if sub:
                if sid:
                    self._subagents.add(sid)
                return True
            if sid and sid in self._subagents:
                return True
            key = _str(kw.get("session_key"))
            if key:
                self._session_key = key
        return False

    def _keep_history(self, kw: Dict[str, Any]) -> None:
        msgs = kw.get("conversation_history")
        if isinstance(msgs, list):
            with self._lock:
                self._history = list(msgs)

    def _set_session(self, sid: str) -> None:
        with self._lock:
            if sid != self._session_id:
                self._history = []
                self._subagents.clear()
            self._session_id = sid
        self._loop.set_session(sid, self._cwd())

    # -- the hooks -----------------------------------------------------------

    def _on_session_start(self, kw: Dict[str, Any]) -> None:
        sid = _str(kw.get("session_id"))
        if self._skip(kw) or not sid:
            return
        self._set_session(sid)

    def _pre_llm_call(self, kw: Dict[str, Any]) -> None:
        if self._skip(kw):
            return
        sid = _str(kw.get("session_id"))
        with self._lock:
            adopt = bool(sid) and self._session_id is None
        if adopt:  # a resumed session fires no on_session_start
            self._set_session(sid)
        self._keep_history(kw)
        text = _message_text(kw.get("user_message"))
        if text.strip() and not self._injector.consume_echo(text):
            self.push(events.user_text(self._uuid(), text, self._ts()))
        self.push(events.state(True))

    def _post_llm_call(self, kw: Dict[str, Any]) -> None:
        if self._skip(kw):
            return
        self._keep_history(kw)
        text = _str(kw.get("assistant_response"))
        if text.strip():
            self.push(events.assistant_text(self._uuid(), text, self._ts()))
        self.push(events.state(False))

    def _on_interim_message(self, kw: Dict[str, Any]) -> None:
        text = _str(kw.get("text"))
        if self._skip(kw) or not text.strip():
            return
        self.push(events.assistant_text(self._uuid(), text, self._ts()))

    def _tool_id(self, kw: Dict[str, Any]) -> str:
        return _str(kw.get("tool_call_id")) or self._uuid()

    def _pre_tool_call(self, kw: Dict[str, Any]) -> None:
        if self._skip(kw):
            return
        args = kw.get("args")
        self.push(events.tool_use(self._tool_id(kw), _str(kw.get("tool_name")),
                                  args if isinstance(args, dict) else {}, self._ts()))

    def _post_tool_call(self, kw: Dict[str, Any]) -> None:
        if self._skip(kw):
            return
        status = kw.get("status", _OK)
        self.push(events.tool_result(self._tool_id(kw), kw.get("result"), status != _OK, self._ts()))

    def _idle(self, kw: Dict[str, Any]) -> None:
        if self._skip(kw):
            return
        self.push(events.state(False))

    _agent_loop_stopped = _idle
    _on_session_end = _idle
    _on_session_reset = _idle

    def _on_human_input_request(self, kw: Dict[str, Any]) -> None:
        if self._skip(kw):
            return
        rid = _str(kw.get("request_id")) or self._uuid()
        self.push(events.question_notice(rid, _str(kw.get("prompt")), self._ts()))
