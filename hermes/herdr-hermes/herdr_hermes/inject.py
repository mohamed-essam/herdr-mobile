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

The phone's pending bubble is confirmed by a user_text with its text. An
injected prompt gets it from pre_llm_call; what never reaches the model (a
plugin command, a host-run slash or `!` shell line, a refused message) gets
it here, ahead of its output or error, as the engine records a command for
the Claude mod. Only the classic CLI runs an injected slash or `!` line
itself; the TUI and the gateway send it to the model.
"""
from __future__ import annotations

import asyncio
import inspect
import logging
import queue
import re
import threading
import time
from typing import Any, Callable, Mapping, Optional, Set, Tuple

from . import events

logger = logging.getLogger(__name__)

REFUSED = (
    "Hermes refused the message: injection is not allowed for this session "
    "(set plugins.entries.herdr-hermes.allow_gateway_injection)"
)
COMMAND_TIMEOUT = 30.0  # an async handler's bound, as Hermes' own runner
_WORKER = "herdr-hermes-inject"
_SLASH = re.compile(r"/(\S+)(?:\s+([\s\S]*))?")


def host_runs(text: str) -> bool:
    """True for what the CLI runs itself instead of sending it to the model:
    a slash command (cli.py `_looks_like_slash_command`: `/help`, not
    `/Users/x`) or a `!` shell line."""
    t = text.strip()
    if t.startswith("!"):
        return True
    return t.startswith("/") and "/" not in t.split()[0][1:]


def parse_slash(text: str, names: Set[str]) -> Optional[Tuple[str, str]]:
    """(command, args) for `/name args` naming a known command, else None."""
    m = _SLASH.fullmatch(text.strip())
    if not m or m.group(1) not in names:
        return None
    return m.group(1), (m.group(2) or "").strip()


def _in_cli(ctx: Any) -> bool:
    # hermes_cli/plugins_injection.py: the REPL takes it when one is attached.
    return getattr(getattr(ctx, "_manager", None), "_cli_ref", None) is not None


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
        self._thread: Optional[threading.Thread] = None
        if start:
            self._thread = threading.Thread(target=self._run, name=_WORKER, daemon=True)
            self._thread.start()

    # -- called from the loop thread / Hermes' hooks -----------------------

    def submit(self, msg_id: str, text: str) -> None:
        self._jobs.put((msg_id, text))

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
        # BaseException: a handler's SystemExit or CancelledError must not end
        # the worker, or every later phone message would stall unseen. The
        # worker is a daemon thread, so not re-raising never blocks exit.
        except BaseException:
            logger.exception("herdr-hermes: phone message %s failed", msg_id)

    def _ts(self) -> int:
        return int(self._clock() * 1000)

    def _echo(self, msg_id: str, text: str) -> None:
        self._push(events.user_text(f"user-{msg_id}", text, self._ts()))

    def _run_command(self, msg_id: str, name: str, args: str, entry: Any) -> None:
        command = f"/{name} {args}" if args else f"/{name}"
        uuid = f"cmd-{msg_id}"
        self._echo(msg_id, command)
        try:
            handler = entry["handler"]
            result = _resolve(handler(args))
        except BaseException as exc:  # SystemExit, CancelledError too (see _handle)
            self._push(events.command_output(uuid, command, _error_text(exc), self._ts(), is_error=True))
            return
        text = "" if result is None else str(result)
        if text.strip():
            self._push(events.command_output(uuid, command, text, self._ts()))

    def _inject(self, msg_id: str, text: str) -> None:
        try:
            ok = self._ctx.inject_message(text, "user", session_key=self._session_key())
            error = None if ok else REFUSED
        except Exception as exc:
            error = f"Hermes could not take the message: {_error_text(exc)}"
        if error is None:
            if host_runs(text) and _in_cli(self._ctx):
                self._echo(msg_id, text.strip())
            return
        self._echo(msg_id, text.strip())
        self._push(events.command_output(f"cmd-{msg_id}", text, error, self._ts(), is_error=True))
