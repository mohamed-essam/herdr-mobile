"""Phone messages into Hermes: a slash command naming a plugin command runs
it, anything else is injected as a user message.

Mirrors `runCommand` and `parseSlash` in mod/herdr-chat/hooks (register.ts,
commands.ts). `submit` is called on the sync loop's thread, which is also the
pane's heartbeat, so it only enqueues: one worker thread runs the messages
in submission order.

Commands are limited to plugin-registered ones (`ctx.register_command`).
Hermes gives plugins no way to run a built-in command (/model, /new, ...):
those are methods on the CLI object (cli.py `HermesCLI.process_command`) or
the gateway runner. A built-in slash therefore goes to `inject_message`
like any text; in the classic CLI the REPL then runs it as a command, its
output staying in the terminal.
"""
from __future__ import annotations

import asyncio
import inspect
import logging
import queue
import re
import threading
import time
from typing import Any, Callable, List, Mapping, Optional, Set, Tuple

from . import events

logger = logging.getLogger(__name__)

REFUSED = (
    "Hermes refused the message: injection is not allowed for this session "
    "(set plugins.entries.herdr-hermes.allow_gateway_injection)"
)
ECHO_WINDOW = 60.0  # seconds a phone-injected prompt waits for its pre_llm_call
COMMAND_TIMEOUT = 30.0  # an async handler's bound, as Hermes' own runner
_WORKER = "herdr-hermes-inject"
_SLASH = re.compile(r"/(\S+)(?:\s+([\s\S]*))?")


def parse_slash(text: str, names: Set[str]) -> Optional[Tuple[str, str]]:
    """(command, args) for `/name args` naming a known command, else None."""
    m = _SLASH.fullmatch(text.strip())
    if not m or m.group(1) not in names:
        return None
    return m.group(1), (m.group(2) or "").strip()


def _registry(ctx: Any) -> Mapping[str, dict]:
    # The plugin commands of ctx's own manager (its profile), as
    # hermes_cli.plugins.get_plugin_commands reads the global one's.
    reg = getattr(getattr(ctx, "_manager", None), "_plugin_commands", None)
    return reg if isinstance(reg, Mapping) else {}


async def _awaited(result: Any) -> Any:
    return await result


def _resolve(result: Any) -> Any:
    # The worker has no running loop, so asyncio.run is safe here.
    if not inspect.isawaitable(result):
        return result
    return asyncio.run(asyncio.wait_for(_awaited(result), COMMAND_TIMEOUT))


def _error_text(exc: BaseException) -> str:
    return str(exc) or type(exc).__name__


class Injector:
    def __init__(
        self,
        ctx: Any,
        push: Callable[[dict], None],
        session_key: Callable[[], Optional[str]],
        *,
        clock: Callable[[], float] = time.time,
        start: bool = True,
    ) -> None:
        """`start=False` runs no worker: jobs wait for `run_pending` (tests)."""
        self._ctx = ctx
        self._push = push
        self._session_key = session_key
        self._clock = clock
        self._jobs: "queue.Queue[Optional[Tuple[str, str]]]" = queue.Queue()
        self._echo_lock = threading.Lock()
        self._echoes: List[Tuple[str, float]] = []  # (stripped text, injected at)
        self._thread: Optional[threading.Thread] = None
        if start:
            self._thread = threading.Thread(target=self._run, name=_WORKER, daemon=True)
            self._thread.start()

    # -- called from the loop thread / Hermes' hooks -----------------------

    def submit(self, msg_id: str, text: str) -> None:
        self._jobs.put((msg_id, text))

    def consume_echo(self, text: str) -> bool:
        """True once per phone-injected `text` from the last ECHO_WINDOW s."""
        key = text.strip()
        with self._echo_lock:
            self._prune()
            for i, (k, _) in enumerate(self._echoes):
                if k == key:
                    del self._echoes[i]
                    return True
        return False

    def command_names(self) -> Set[str]:
        return set(_registry(self._ctx))

    def run_pending(self) -> None:
        """Runs every queued job on the calling thread (no worker only)."""
        if self._thread is not None:
            raise RuntimeError("run_pending is for an Injector built with start=False")
        while True:
            try:
                job = self._jobs.get_nowait()
            except queue.Empty:
                return
            if job is not None:
                self._handle(*job)

    def close(self, timeout: float = 5.0) -> None:
        """Stops the worker after the jobs already queued."""
        t, self._thread = self._thread, None
        if t is None:
            return
        self._jobs.put(None)
        if t is not threading.current_thread():
            t.join(timeout)

    # -- the worker ----------------------------------------------------------

    def _run(self) -> None:
        while True:
            job = self._jobs.get()
            if job is None:
                return
            self._handle(*job)

    def _handle(self, msg_id: str, text: str) -> None:
        try:
            reg = _registry(self._ctx)
            slash = parse_slash(text, set(reg))
            if slash is not None:
                self._run_command(msg_id, slash[0], slash[1], reg[slash[0]])
            else:
                self._inject(msg_id, text)
        except Exception:
            logger.exception("herdr-hermes: phone message %s failed", msg_id)

    def _ts(self) -> int:
        return int(self._clock() * 1000)

    def _run_command(self, msg_id: str, name: str, args: str, entry: Any) -> None:
        command = f"/{name} {args}" if args else f"/{name}"
        uuid = f"cmd-{msg_id}"
        try:
            handler = entry["handler"]
            result = _resolve(handler(args))
        except Exception as exc:
            self._push(events.command_output(uuid, command, _error_text(exc), self._ts(), is_error=True))
            return
        text = "" if result is None else str(result)
        if text.strip():
            self._push(events.command_output(uuid, command, text, self._ts()))

    def _inject(self, msg_id: str, text: str) -> None:
        # Recorded first: Hermes may start the turn (and fire pre_llm_call)
        # before inject_message returns.
        token = (text.strip(), self._clock())
        with self._echo_lock:
            self._echoes.append(token)
        try:
            ok = self._ctx.inject_message(text, "user", session_key=self._session_key())
            error = None if ok is True else REFUSED
        except Exception as exc:
            error = f"Hermes could not take the message: {_error_text(exc)}"
        if error is None:
            return
        with self._echo_lock:
            for i, t in enumerate(self._echoes):
                if t is token:
                    del self._echoes[i]
                    break
        self._push(events.command_output(f"cmd-{msg_id}", text, error, self._ts(), is_error=True))

    def _prune(self) -> None:
        cutoff = self._clock() - ECHO_WINDOW
        self._echoes = [e for e in self._echoes if e[1] >= cutoff]
