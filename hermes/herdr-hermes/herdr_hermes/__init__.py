"""herdr-hermes: streams a Hermes session to herdr-mobile's chat view.

`register(ctx)` is the plugin entry. Outside a herdr pane, or without a
usable chat socket, it registers nothing and starts nothing.
"""
from __future__ import annotations

import logging
import os
import sys
import threading
import time
import types
from typing import Any

from . import inject, loop, socket_client
from .hooks import Hooks

__all__ = ["register"]

logger = logging.getLogger(__name__)

# Seams for tests.
_make_loop = loop.Loop
_make_injector = inject.Injector
_clock = time.time

# Hermes may call register more than once per process (one plugin manager per
# profile, a forced reload), each time from a freshly imported copy of this
# package, so the running pane state is kept outside it, in a module that is
# never evicted.
_RUNTIME_MODULE = "_herdr_hermes_runtime"


def _runtime() -> Any:
    rt = sys.modules.get(_RUNTIME_MODULE)
    if rt is None:
        fresh = types.ModuleType(_RUNTIME_MODULE)
        fresh.lock = threading.Lock()  # type: ignore[attr-defined]
        fresh.panes = {}  # pane id -> Hooks  # type: ignore[attr-defined]
        fresh.warned = set()  # type: ignore[attr-defined]
        rt = sys.modules.setdefault(_RUNTIME_MODULE, fresh)
    return rt


def _start(ctx: Any, pane_id: str, path: str) -> Hooks:
    hooks = Hooks(clock=_clock, cwd=os.getcwd)
    injector = _make_injector(ctx, hooks.push, hooks.session_key)
    lp = _make_loop(pane_id=pane_id, socket=path, on_message=injector.submit,
                    history=hooks.history_events)
    hooks.attach(lp, injector)
    lp.start()
    return hooks


def register(ctx: Any) -> None:
    pane_id = os.environ.get("HERDR_PANE_ID") or ""
    if not pane_id:
        logger.debug("herdr-hermes: not in a herdr pane (HERDR_PANE_ID unset); chat off")
        return
    path = socket_client.socket_path(os.environ)
    if path is None:
        logger.debug("herdr-hermes: no chat socket (HERDR_MOBILE_CHAT_SOCK, XDG_RUNTIME_DIR unset); chat off")
        return
    rt = _runtime()
    reason = socket_client.check_private_dir(os.path.dirname(path))
    if reason:
        with rt.lock:
            first = reason not in rt.warned
            rt.warned.add(reason)
        if first:
            logger.warning("herdr-hermes: chat off: %s", reason)
        else:
            logger.debug("herdr-hermes: chat off: %s", reason)
        return
    with rt.lock:
        hooks = rt.panes.get(pane_id)
        if hooks is None:
            hooks = rt.panes[pane_id] = _start(ctx, pane_id, path)
    for name, callback in hooks.callbacks().items():
        ctx.register_hook(name, callback)
