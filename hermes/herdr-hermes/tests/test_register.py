import functools
import importlib.util
import inspect
import logging
import os
import sys
import types

import pytest

import herdr_hermes
from herdr_hermes import history
from herdr_hermes.hooks import HOOKS
from herdr_hermes.loop import Loop

PLUGIN_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ALL_HOOKS = {
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
}


class FakeCtx:
    def __init__(self):
        self.hooks = {}

    def register_hook(self, hook_name, callback):
        self.hooks.setdefault(hook_name, []).append(callback)

    def call(self, name, *args, **kw):
        (cb,) = self.hooks[name]
        return cb(*args, **kw)


class FakeLoop:
    instances = []

    def __init__(self, **kw):
        self.kw = kw
        self.events = []
        self.sessions = []
        self.started = 0
        FakeLoop.instances.append(self)

    def push(self, event):
        self.events.append(event)

    def set_session(self, session_id, cwd):
        self.sessions.append((session_id, cwd))

    def start(self):
        self.started += 1

    def stop(self):
        pass


class FakeInjector:
    instances = []

    def __init__(self, ctx, push, session_key, **kw):
        self.ctx = ctx
        self.push = push
        self.session_key = session_key
        self.submitted = []
        FakeInjector.instances.append(self)

    def submit(self, msg_id, text):
        self.submitted.append((msg_id, text))

    def command_names(self):
        return set()

    def close(self):
        pass


@pytest.fixture(autouse=True)
def fresh(monkeypatch, tmp_path):
    sys.modules.pop(herdr_hermes._RUNTIME_MODULE, None)
    FakeLoop.instances = []
    FakeInjector.instances = []
    monkeypatch.setattr(herdr_hermes, "_make_loop", FakeLoop)
    monkeypatch.setattr(herdr_hermes, "_make_injector", FakeInjector)
    monkeypatch.setattr(herdr_hermes, "_clock", lambda: 1.0)
    sock_dir = tmp_path / "sock"
    sock_dir.mkdir(mode=0o700)
    monkeypatch.setenv("HERDR_MOBILE_CHAT_SOCK", str(sock_dir / "chat.sock"))
    monkeypatch.setenv("HERDR_PANE_ID", "w1:p1")
    yield
    sys.modules.pop(herdr_hermes._RUNTIME_MODULE, None)


def active():
    ctx = FakeCtx()
    herdr_hermes.register(ctx)
    (loop,) = FakeLoop.instances
    (inj,) = FakeInjector.instances
    return ctx, loop, inj


def in_session():
    """active(), after a session start: tool and interim payloads need one."""
    ctx, loop, inj = active()
    ctx.call("on_session_start", session_id="s1", platform="cli")
    return ctx, loop, inj


# -- inert -------------------------------------------------------------------


@pytest.mark.parametrize("pane", [None, ""])
def test_inert_without_pane(monkeypatch, pane):
    if pane is None:
        monkeypatch.delenv("HERDR_PANE_ID")
    else:
        monkeypatch.setenv("HERDR_PANE_ID", pane)
    ctx = FakeCtx()
    assert herdr_hermes.register(ctx) is None
    assert ctx.hooks == {}
    assert FakeLoop.instances == [] and FakeInjector.instances == []


def test_inert_without_socket(monkeypatch):
    monkeypatch.delenv("HERDR_MOBILE_CHAT_SOCK")
    monkeypatch.delenv("XDG_RUNTIME_DIR", raising=False)
    ctx = FakeCtx()
    herdr_hermes.register(ctx)
    assert ctx.hooks == {}
    assert FakeLoop.instances == []


def test_inert_with_shared_socket_dir_warns_once(monkeypatch, tmp_path, caplog):
    shared = tmp_path / "shared"
    shared.mkdir()
    shared.chmod(0o755)
    monkeypatch.setenv("HERDR_MOBILE_CHAT_SOCK", str(shared / "chat.sock"))
    ctx = FakeCtx()
    with caplog.at_level(logging.DEBUG):
        herdr_hermes.register(ctx)
        herdr_hermes.register(ctx)
    assert ctx.hooks == {}
    assert FakeLoop.instances == []
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1
    assert "group/other" in warnings[0].getMessage()


# -- active ------------------------------------------------------------------


def test_registers_every_hook_once_and_starts_the_loop():
    ctx, loop, inj = active()
    assert set(HOOKS) == ALL_HOOKS
    assert set(ctx.hooks) == ALL_HOOKS
    assert all(len(cbs) == 1 for cbs in ctx.hooks.values())
    assert loop.started == 1
    assert loop.kw["pane_id"] == "w1:p1"
    assert loop.kw["socket"] == os.environ["HERDR_MOBILE_CHAT_SOCK"]
    assert loop.kw["on_message"] == inj.submit
    assert inj.ctx is ctx


def test_callbacks_take_every_kwarg():
    # hermes_cli/plugins_dispatch.py _hook_callback_kwargs passes a callback
    # only the names its inspect.signature declares unless it has **kwargs.
    ctx, _, _ = active()
    for name, (cb,) in ctx.hooks.items():
        params = inspect.signature(cb).parameters.values()
        assert any(p.kind is inspect.Parameter.VAR_KEYWORD for p in params), name


def test_double_register_shares_one_loop():
    ctx1, loop, inj = active()
    ctx2 = FakeCtx()
    herdr_hermes.register(ctx2)
    assert len(FakeLoop.instances) == 1 and len(FakeInjector.instances) == 1
    assert loop.started == 1
    assert set(ctx2.hooks) == ALL_HOOKS
    ctx1.call("on_session_start", session_id="s1")
    ctx2.call("on_interim_message", text="hi")
    assert loop.events == [{"type": "assistant_text", "uuid": "hermes-1", "text": "hi", "ts": 1000}]


def test_on_session_start_sets_the_session():
    ctx, loop, _ = active()
    assert ctx.call("on_session_start", session_id="s1", model="m", platform="cli") is None
    assert loop.sessions == [("s1", os.getcwd())]
    assert loop.events == []


def test_pre_llm_call_pushes_user_text_and_working():
    ctx, loop, _ = active()
    ctx.call("on_session_start", session_id="s1", model="m", platform="cli")
    r = ctx.call(
        "pre_llm_call", session_id="s1", task_id="t", turn_id="u1", user_message="hello",
        conversation_history=[{"role": "user", "content": "hello"}], is_first_turn=True,
        model="m", platform="cli", parent_session_id="", sender_id="",
    )
    assert r is None  # a non-None return would be injected into the prompt
    assert loop.events == [
        {"type": "user_text", "uuid": "hermes-1", "text": "hello", "ts": 1000},
        {"type": "state", "state": "working"},
    ]


def test_pre_llm_call_pushes_a_phone_prompt_too():
    # Its user_text is what confirms the phone's pending bubble.
    ctx, loop, _ = in_session()
    ctx.call("pre_llm_call", session_id="s1", user_message="from phone", conversation_history=[])
    assert loop.events == [
        {"type": "user_text", "uuid": "hermes-1", "text": "from phone", "ts": 1000},
        {"type": "state", "state": "working"},
    ]


def test_pre_llm_call_adopts_the_session_when_none_started():
    # A resumed session (stored prompt) fires no on_session_start.
    ctx, loop, _ = active()
    ctx.call("pre_llm_call", session_id="s9", user_message="hi", conversation_history=[])
    assert loop.sessions == [("s9", os.getcwd())]
    ctx.call("pre_llm_call", session_id="s10", user_message="hi", conversation_history=[])
    assert loop.sessions == [("s9", os.getcwd())]  # e.g. a compression rotation


def test_on_session_reset_lets_the_next_prompt_adopt_a_different_session():
    ctx, loop, _ = active()
    ctx.call("pre_llm_call", session_id="s1", user_message="a", conversation_history=[])
    ctx.call("on_session_reset", session_id="s2", platform="cli", reason="resume")
    assert loop.events[-1] == {"type": "state", "state": "idle"}
    ctx.call("pre_llm_call", session_id="s2", user_message="b", conversation_history=[])
    assert loop.sessions == [("s1", os.getcwd()), ("s2", os.getcwd())]


def test_on_session_end_keeps_the_session():
    ctx, loop, _ = active()
    ctx.call("pre_llm_call", session_id="s1", user_message="a", conversation_history=[])
    ctx.call("on_session_end", session_id="s1", completed=True, platform="cli")
    ctx.call("pre_llm_call", session_id="s1", user_message="b", conversation_history=[])
    assert loop.sessions == [("s1", os.getcwd())]


def test_post_llm_call_pushes_assistant_text_then_idle():
    ctx, loop, _ = active()
    r = ctx.call(
        "post_llm_call", session_id="s1", task_id="t", turn_id="u1", user_message="q",
        assistant_response="answer", conversation_history=[], model="m", platform="cli",
    )
    assert r is None
    assert loop.events == [
        {"type": "assistant_text", "uuid": "hermes-1", "text": "answer", "ts": 1000},
        {"type": "state", "state": "idle"},
    ]


@pytest.mark.parametrize("resp", ["", "   ", None, 5])
def test_post_llm_call_without_text_only_goes_idle(resp):
    ctx, loop, _ = active()
    ctx.call("post_llm_call", session_id="s1", assistant_response=resp)
    assert loop.events == [{"type": "state", "state": "idle"}]


def test_on_interim_message_pushes_assistant_text():
    # agent/stream_delivery.py: _enqueue_stream_hook("on_interim_message", text=..., already_streamed=...)
    ctx, loop, _ = in_session()
    ctx.call("on_interim_message", turn_id="u", iteration=1, session_id="s1", model="m",
             provider="p", surface="cli", text="thinking aloud", already_streamed=False,
             telemetry_schema_version=1)
    assert loop.events == [{"type": "assistant_text", "uuid": "hermes-1", "text": "thinking aloud", "ts": 1000}]


def test_pre_tool_call_pushes_tool_use_and_returns_none():
    ctx, loop, _ = in_session()
    r = ctx.call("pre_tool_call", tool_name="terminal", args={"command": "ls  -la"},
                 task_id="", session_id="s1", tool_call_id="call_1", turn_id="u1",
                 api_request_id="", middleware_trace=[])
    assert r is None  # a dict return could block or modify the call
    assert loop.events == [{
        "type": "tool_use", "uuid": "call_1", "toolUseId": "call_1", "tool": "terminal",
        "summary": "ls -la", "ts": 1000,
    }]


def test_pre_tool_call_with_non_dict_args():
    ctx, loop, _ = in_session()
    ctx.call("pre_tool_call", tool_name="x", args=["a"], tool_call_id="c")
    assert loop.events[0]["summary"] == ""


def test_tool_ids_pair_without_a_tool_call_id():
    ctx, loop, _ = in_session()
    ctx.call("pre_tool_call", tool_name="read", args={})
    ctx.call("pre_tool_call", tool_name="read", args={})
    ctx.call("pre_tool_call", tool_name="terminal", args={})
    ctx.call("post_tool_call", tool_name="terminal", result="t")
    ctx.call("post_tool_call", tool_name="read", result="r1")
    ctx.call("post_tool_call", tool_name="read", result="r2")
    uses = [e["toolUseId"] for e in loop.events if e["type"] == "tool_use"]
    results = [e["toolUseId"] for e in loop.events if e["type"] == "tool_result"]
    assert results == [uses[2], uses[0], uses[1]]


def test_tool_and_interim_payloads_wait_for_a_session():
    # A gateway's tool payloads name no platform; its turns never give the pane a session.
    ctx, loop, _ = active()
    ctx.call("pre_tool_call", tool_name="t", args={}, tool_call_id="c", session_id="g1")
    ctx.call("post_tool_call", tool_name="t", result="r", tool_call_id="c", session_id="g1")
    ctx.call("on_interim_message", text="hi", session_id="g1")
    assert loop.events == []


@pytest.mark.parametrize("platform", ["telegram", "discord", "desktop", "cron"])
def test_messaging_platform_payloads_are_dropped(platform):
    ctx, loop, _ = active()
    ctx.call("on_session_start", session_id="g1", platform=platform)
    ctx.call("pre_llm_call", session_id="g1", user_message="hi", conversation_history=[], platform=platform)
    ctx.call("post_llm_call", session_id="g1", assistant_response="yo", platform=platform)
    ctx.call("on_session_end", session_id="g1", platform=platform)
    ctx.call("agent_loop_stopped", session_key="k", platform=platform)
    assert loop.sessions == [] and loop.events == []
    assert FakeInjector.instances[0].session_key() is None


@pytest.mark.parametrize("platform", ["cli", "tui", ""])
def test_terminal_platform_payloads_are_kept(platform):
    ctx, loop, _ = active()
    ctx.call("pre_llm_call", session_id="s1", user_message="hi", conversation_history=[], platform=platform)
    assert loop.sessions == [("s1", os.getcwd())]
    assert [e["type"] for e in loop.events] == ["user_text", "state"]


def test_history_copy_waits_for_the_events_it_covers():
    # A resync copying the history while pre_llm_call runs must not get the
    # prompt before its user_text is queued (it would be sent twice).
    import threading
    ctx, loop, _ = in_session()
    hooks = herdr_hermes._runtime().panes["w1:p1"]
    pushing, release = threading.Event(), threading.Event()
    copied = []

    def push(event):
        if event["type"] == "user_text":
            pushing.set()
            release.wait(5)
        loop.events.append(event)

    loop.push = push
    turn = threading.Thread(target=ctx.call, args=("pre_llm_call",), kwargs=dict(
        session_id="s1", user_message="hi", conversation_history=[{"role": "user", "content": "hi"}]))
    turn.start()
    assert pushing.wait(5)
    copy = threading.Thread(target=hooks.history_events, args=(lambda: copied.append(list(loop.events)),))
    copy.start()
    copy.join(0.2)
    assert copied == []  # blocked behind the hook
    release.set()
    turn.join(5)
    copy.join(5)
    assert [e["type"] for e in copied[0]] == ["user_text", "state"]


@pytest.mark.parametrize("status,is_error", [("ok", False), ("error", True), ("blocked", True),
                                             ("timeout", True), ("cancelled", True)])
def test_post_tool_call_pushes_tool_result(status, is_error):
    # model_tools.py _tool_result_observer_fields: "ok" on success.
    ctx, loop, _ = in_session()
    r = ctx.call("post_tool_call", tool_name="terminal", args={}, result='{"output": "x"}',
                 task_id="", session_id="s1", tool_call_id="call_1", turn_id="", api_request_id="",
                 duration_ms=3, status=status, error_type=None, error_message=None, middleware_trace=[])
    assert r is None
    assert loop.events == [{
        "type": "tool_result", "toolUseId": "call_1", "isError": is_error,
        "preview": '{"output": "x"}', "ts": 1000,
    }]


@pytest.mark.parametrize("name,kw", [
    ("agent_loop_stopped", {"session_key": "k", "platform": "tui", "reason": "user_stop",
                            "invalidation_reason": "session_interrupt"}),
    ("on_session_end", {"session_id": "s1", "completed": True, "interrupted": False, "platform": "cli"}),
    ("on_session_reset", {"session_id": "s2", "platform": "cli", "reason": "new_session"}),
])
def test_stop_end_reset_go_idle(name, kw):
    ctx, loop, _ = active()
    assert ctx.call(name, **kw) is None
    assert loop.events == [{"type": "state", "state": "idle"}]


def test_on_human_input_request_pushes_a_notice():
    ctx, loop, _ = active()
    ctx.call("on_human_input_request", kind="clarify", request_id="r1", session_id="s1",
             session_key="", platform="cli", prompt="Which file?")
    assert loop.events == [{
        "type": "assistant_text", "uuid": "question-r1",
        "text": "Hermes is asking: Which file?. Answer in the terminal.", "ts": 1000,
    }]


def test_session_key_is_the_last_one_delivered():
    ctx, _, inj = active()
    assert inj.session_key() is None
    ctx.call("agent_loop_stopped", session_key="k1", platform="tui")
    assert inj.session_key() == "k1"
    ctx.call("on_human_input_request", request_id="r", prompt="p", session_key="")
    assert inj.session_key() == "k1"
    ctx.call("on_human_input_request", request_id="r", prompt="p", session_key="k2")
    assert inj.session_key() == "k2"


# -- forked / subagent payloads ----------------------------------------------


def test_parent_session_payloads_push_nothing():
    ctx, loop, _ = active()
    ctx.call("on_session_start", session_id="main")
    ctx.call("on_session_start", session_id="child", platform="subagent")
    ctx.call("pre_llm_call", session_id="child", user_message="sub task",
             conversation_history=[{"role": "user", "content": "sub task"}], parent_session_id="main")
    # The child's later payloads carry no parent id, only its session id.
    ctx.call("pre_tool_call", tool_name="t", args={}, session_id="child", tool_call_id="c")
    ctx.call("post_tool_call", tool_name="t", result="r", session_id="child", tool_call_id="c", status="ok")
    ctx.call("on_interim_message", session_id="child", text="x")
    ctx.call("post_llm_call", session_id="child", assistant_response="done", platform="subagent")
    ctx.call("on_session_end", session_id="child", platform="subagent")
    assert loop.sessions == [("main", os.getcwd())]
    assert loop.events == []
    # The parent's history snapshot is untouched by the child's.
    assert loop.kw["history"]() == []


def test_subagent_pre_llm_call_is_skipped_without_on_session_start():
    ctx, loop, _ = active()
    ctx.call("pre_llm_call", session_id="child", user_message="x", conversation_history=[],
             parent_session_id="main")
    ctx.call("pre_tool_call", tool_name="t", args={}, session_id="child", tool_call_id="c")
    assert loop.events == [] and loop.sessions == []


def test_forked_run_tool_and_interim_payloads_push_nothing():
    # agent/background_review.py: a persist-disabled fork shares the parent's
    # session_id; its turn_id (agent/turn_context.py _bind_turn_identity) is
    # its own, and the tool/interim hooks carry it.
    ctx, loop, _ = active()
    ctx.call("on_session_start", session_id="s1")
    ctx.call("pre_llm_call", session_id="s1", turn_id="T1", user_message="", conversation_history=[])
    loop.events.clear()
    ctx.call("pre_tool_call", tool_name="memory", args={"a": "b"}, session_id="s1",
             tool_call_id="f1", turn_id="F1")
    ctx.call("post_tool_call", tool_name="memory", result="ok", session_id="s1",
             tool_call_id="f1", turn_id="F1", status="ok")
    ctx.call("on_interim_message", session_id="s1", turn_id="F1", text="reviewing")
    assert loop.events == []
    ctx.call("pre_tool_call", tool_name="t", args={}, session_id="s1", tool_call_id="c1", turn_id="T1")
    ctx.call("post_tool_call", tool_name="t", result="r", session_id="s1", tool_call_id="c1",
             turn_id="T1", status="ok")
    ctx.call("on_interim_message", session_id="s1", turn_id="T1", text="main")
    assert [e["type"] for e in loop.events] == ["tool_use", "tool_result", "assistant_text"]


def test_payloads_without_a_turn_id_are_kept():
    # No turn to attribute them to (no main turn seen yet, or an empty
    # turn_id): kept, as before, rather than dropping the main run's rows.
    ctx, loop, _ = active()
    ctx.call("pre_tool_call", tool_name="t", args={}, session_id="s1", tool_call_id="c0", turn_id="X")
    ctx.call("pre_llm_call", session_id="s1", turn_id="T1", user_message="", conversation_history=[])
    loop.events.clear()
    ctx.call("pre_tool_call", tool_name="t", args={}, session_id="s1", tool_call_id="c1", turn_id="")
    ctx.call("post_tool_call", tool_name="t", result="r", session_id="s1", tool_call_id="c1", status="ok")
    ctx.call("on_interim_message", session_id="s1", text="x")
    assert [e["type"] for e in loop.events] == ["tool_use", "tool_result", "assistant_text"]


def test_first_turn_of_a_new_session_sends_working_after_the_snapshot(monkeypatch):
    sent = []

    def fake_sync(path, body, timeout=10.0):
        sent.append(body)
        return {}

    class QuietLoop(Loop):
        def start(self):  # ticks are driven by the test
            pass

    monkeypatch.setattr(herdr_hermes, "_make_loop", functools.partial(QuietLoop, sync=fake_sync))
    ctx = FakeCtx()
    herdr_hermes.register(ctx)
    lp = sys.modules[herdr_hermes._RUNTIME_MODULE].panes["w1:p1"]._loop
    ctx.call("on_session_start", session_id="s1")
    ctx.call("pre_llm_call", session_id="s1", turn_id="T1", user_message="hi",
             conversation_history=[{"role": "user", "content": "hi"}])
    lp.tick_once()
    evs = sent[-1]["events"]
    assert [e["type"] for e in evs] == [
        "hello", "snapshot_begin", "snapshot_chunk", "snapshot_end", "state"]
    assert evs[-1] == {"type": "state", "state": "working"}
    assert evs[2]["events"] == [{"type": "user_text", "uuid": "h-0", "text": "hi", "ts": 1000}]


# -- history snapshot --------------------------------------------------------


def test_history_is_a_snapshot_of_the_latest_conversation():
    ctx, loop, _ = active()
    ctx.call("on_session_start", session_id="s1")
    msgs = [{"role": "user", "content": "hi"}]
    ctx.call("pre_llm_call", session_id="s1", user_message="hi", conversation_history=msgs)
    msgs.append({"role": "assistant", "content": "later"})  # Hermes' list moves on
    assert loop.kw["history"]() == history.from_messages([{"role": "user", "content": "hi"}], 1000)
    final = [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "yo"}]
    ctx.call("post_llm_call", session_id="s1", assistant_response="yo", conversation_history=final)
    assert loop.kw["history"]() == history.from_messages(final, 1000)


def test_a_new_session_drops_the_old_history():
    ctx, loop, _ = active()
    ctx.call("on_session_start", session_id="s1")
    ctx.call("pre_llm_call", session_id="s1", user_message="hi",
             conversation_history=[{"role": "user", "content": "hi"}])
    ctx.call("on_session_start", session_id="s2")
    assert loop.kw["history"]() == []


# -- robustness --------------------------------------------------------------


@pytest.mark.parametrize("name", sorted(ALL_HOOKS))
def test_callbacks_never_raise_on_garbage(name):
    ctx, loop, _ = active()
    garbage = [
        {},
        {"session_id": 5, "user_message": object(), "conversation_history": "nope",
         "assistant_response": [1], "args": 3, "tool_call_id": None, "status": None,
         "text": 7, "prompt": None, "request_id": 9, "session_key": 4, "parent_session_id": None,
         "platform": 1, "tool_name": None, "result": object()},
    ]
    for kw in garbage:
        assert ctx.call(name, **kw) is None
    assert ctx.call(name, "positional", 2) is None


def test_callbacks_swallow_a_failing_loop():
    ctx, loop, _ = active()

    def boom(*a, **k):
        raise RuntimeError("boom")

    loop.push = boom
    loop.set_session = boom
    for name in ALL_HOOKS:
        assert ctx.call(name, session_id="s", text="t", user_message="u",
                        assistant_response="a", tool_name="t", args={}, tool_call_id="c",
                        status="ok", result="r", prompt="p", request_id="r") is None


# -- the plugin root, loaded the way Hermes' directory loader does -----------


def test_plugin_root_loads_as_a_directory_plugin(monkeypatch):
    # Mirrors hermes_cli/plugins_loader.py PluginManager._load_directory_module.
    monkeypatch.delenv("HERDR_PANE_ID")
    ns = types.ModuleType("hermes_plugins_test")
    ns.__path__ = []
    ns.__package__ = "hermes_plugins_test"
    monkeypatch.setitem(sys.modules, "hermes_plugins_test", ns)
    name = "hermes_plugins_test.herdr_hermes"
    spec = importlib.util.spec_from_file_location(
        name, os.path.join(PLUGIN_ROOT, "__init__.py"), submodule_search_locations=[PLUGIN_ROOT])
    module = importlib.util.module_from_spec(spec)
    module.__package__ = name
    module.__path__ = [PLUGIN_ROOT]
    sys.modules[name] = module
    try:
        spec.loader.exec_module(module)
        ctx = FakeCtx()
        assert module.register(ctx) is None
        assert ctx.hooks == {}
    finally:
        for n in [n for n in sys.modules if n == name or n.startswith(name + ".")]:
            del sys.modules[n]
