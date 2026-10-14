import threading

import pytest

from herdr_hermes.inject import REFUSED, Injector, host_runs, parse_slash


class FakeManager:
    def __init__(self, commands, cli):
        self._plugin_commands = commands
        self._cli_ref = object() if cli else None


class FakeCtx:
    def __init__(self, commands=None, result=True, cli=True):
        self._manager = FakeManager(commands or {}, cli)
        self.result = result
        self.injected = []
        self.on_inject = None

    def inject_message(self, content, role="user", *, session_key=None, origin=None):
        if self.on_inject:
            self.on_inject(content)
        self.injected.append((content, role, session_key))
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


class Clock:
    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t


def make(ctx, key="sk-1", clock=None):
    pushed = []
    inj = Injector(ctx, pushed.append, lambda: key, clock=clock or Clock(), start=False)
    return inj, pushed


def cmd(handler):
    return {"handler": handler, "description": "d"}


def echo(msg_id, text, ts=1000000):
    return {"type": "user_text", "uuid": "user-" + msg_id, "text": text, "ts": ts}


def rows(pushed):
    """The pushed events after each message's echo."""
    return [e for e in pushed if e["type"] != "user_text"]


# -- parse_slash (mirrors parseSlash in mod/herdr-chat/hooks/commands.ts) ----


def test_parse_slash_known():
    assert parse_slash("/foo", {"foo"}) == ("foo", "")
    assert parse_slash("  /foo  a b  ", {"foo"}) == ("foo", "a b")
    assert parse_slash("/foo\nline1\nline2", {"foo"}) == ("foo", "line1\nline2")


def test_parse_slash_unknown_or_not_slash():
    assert parse_slash("/bar x", {"foo"}) is None
    assert parse_slash("foo", {"foo"}) is None
    assert parse_slash("/", {"foo"}) is None
    assert parse_slash("x /foo", {"foo"}) is None


def test_parse_slash_is_case_sensitive():
    assert parse_slash("/Foo", {"foo"}) is None


# -- injection -------------------------------------------------------------


def test_plain_text_injected_with_session_key():
    ctx = FakeCtx()
    inj, pushed = make(ctx)
    inj.submit("m1", "hello")
    assert ctx.injected == []  # nothing runs until the worker does
    inj.run_pending()
    assert ctx.injected == [("hello", "user", "sk-1")]
    assert pushed == []


def test_session_key_read_at_run_time():
    ctx = FakeCtx()
    keys = iter(["a", "b"])
    inj = Injector(ctx, lambda ev: None, lambda: next(keys), clock=Clock(), start=False)
    inj.submit("m1", "x")
    inj.submit("m2", "y")
    inj.run_pending()
    assert [k for _, _, k in ctx.injected] == ["a", "b"]


def test_refused_pushes_exact_error_row():
    ctx = FakeCtx(result=False)
    inj, pushed = make(ctx, clock=Clock(5.0))
    inj.submit("m1", "hello")
    inj.run_pending()
    assert REFUSED == (
        "Hermes refused the message: injection is not allowed for this session "
        "(set plugins.entries.herdr-hermes.allow_gateway_injection)"
    )
    # The echo confirms the phone's bubble; the error row follows it.
    assert pushed == [echo("m1", "hello", 5000), {
        "type": "command_output", "uuid": "cmd-m1", "command": "hello",
        "text": REFUSED, "isError": True, "ts": 5000,
    }]


@pytest.mark.parametrize("result", [None, 0, ""])
def test_falsy_result_is_refused(result):
    inj, pushed = make(FakeCtx(result=result))
    inj.submit("m1", "hello")
    inj.run_pending()
    assert rows(pushed)[0]["text"] == REFUSED


def test_truthy_result_is_accepted():
    inj, pushed = make(FakeCtx(result="msg-42"))
    inj.submit("m1", "hello")
    inj.run_pending()
    assert pushed == []


def test_inject_raising_pushes_error_row():
    ctx = FakeCtx(result=RuntimeError("boom"))
    inj, pushed = make(ctx)
    inj.submit("m1", "hello")
    inj.run_pending()
    assert pushed[0] == echo("m1", "hello")
    assert len(pushed) == 2
    assert pushed[1]["isError"] is True and pushed[1]["uuid"] == "cmd-m1"
    assert "boom" in pushed[1]["text"]


def test_unknown_slash_goes_to_inject():
    ctx = FakeCtx(commands={"foo": cmd(lambda a: "x")})
    inj, pushed = make(ctx)
    inj.submit("m1", "/model gpt")
    inj.run_pending()
    assert ctx.injected == [("/model gpt", "user", "sk-1")]
    # The CLI runs it as a command: no pre_llm_call will echo it.
    assert pushed == [echo("m1", "/model gpt")]


def test_slash_outside_the_cli_is_left_to_pre_llm_call():
    # The TUI and the gateway send it to the model, whose pre_llm_call echoes it.
    inj, pushed = make(FakeCtx(cli=False))
    inj.submit("m1", "/model gpt")
    inj.run_pending()
    assert pushed == []


def test_injected_shell_line_is_echoed():
    inj, pushed = make(FakeCtx())
    inj.submit("m1", "!ls")
    inj.run_pending()
    assert pushed == [echo("m1", "!ls")]


def test_host_runs():
    assert host_runs("/help") and host_runs(" /model gpt ") and host_runs("!ls")
    assert not host_runs("/Users/x/file.md please") and not host_runs("hello /help")


# -- commands --------------------------------------------------------------


def test_known_slash_runs_command():
    calls = []

    def handler(raw):
        calls.append(raw)
        return "ran " + raw

    ctx = FakeCtx(commands={"foo": cmd(handler)})
    inj, pushed = make(ctx, clock=Clock(2.0))
    inj.submit("m7", "/foo a b")
    inj.run_pending()
    assert calls == ["a b"]
    assert ctx.injected == []
    assert pushed == [echo("m7", "/foo a b", 2000), {
        "type": "command_output", "uuid": "cmd-m7", "command": "/foo a b",
        "text": "ran a b", "ts": 2000,
    }]


def test_command_without_output_pushes_only_its_echo():
    ctx = FakeCtx(commands={"foo": cmd(lambda a: None), "bar": cmd(lambda a: "  ")})
    inj, pushed = make(ctx)
    inj.submit("m1", "/foo")
    inj.submit("m2", "/bar")
    inj.run_pending()
    assert pushed == [echo("m1", "/foo"), echo("m2", "/bar")]


def test_command_raising_pushes_error_row():
    def handler(raw):
        raise ValueError("bad args")

    ctx = FakeCtx(commands={"foo": cmd(handler)})
    inj, pushed = make(ctx)
    inj.submit("m1", "/foo")
    inj.run_pending()
    pushed = rows(pushed)
    assert pushed[0]["command"] == "/foo"
    assert pushed[0]["text"] == "bad args"
    assert pushed[0]["isError"] is True


def test_async_command_handler():
    async def handler(raw):
        return "async " + raw

    ctx = FakeCtx(commands={"foo": cmd(handler)})
    inj, pushed = make(ctx)
    inj.submit("m1", "/foo z")
    inj.run_pending()
    pushed = rows(pushed)
    assert pushed[0]["text"] == "async z"
    assert "isError" not in pushed[0]


def test_command_exiting_keeps_worker_alive():
    import sys

    def handler(raw):
        sys.exit(3)

    ctx = FakeCtx(commands={"foo": cmd(handler)})
    inj, pushed = make(ctx)
    inj.submit("m1", "/foo")
    inj.submit("m2", "after")
    inj.run_pending()
    pushed = rows(pushed)
    assert pushed[0]["uuid"] == "cmd-m1" and pushed[0]["isError"] is True
    assert pushed[0]["text"] == "3"
    assert ctx.injected == [("after", "user", "sk-1")]


def test_async_command_cancelled_keeps_worker_alive():
    import asyncio

    async def handler(raw):
        raise asyncio.CancelledError()

    ctx = FakeCtx(commands={"foo": cmd(handler)})
    inj, pushed = make(ctx)
    inj.submit("m1", "/foo")
    inj.submit("m2", "after")
    inj.run_pending()
    pushed = rows(pushed)
    assert pushed[0]["uuid"] == "cmd-m1" and pushed[0]["isError"] is True
    assert pushed[0]["text"] == "CancelledError"
    assert ctx.injected == [("after", "user", "sk-1")]


def test_worker_thread_survives_system_exit():
    import sys

    def handler(raw):
        sys.exit()

    ctx = FakeCtx(commands={"foo": cmd(handler)})
    pushed = []
    inj = Injector(ctx, pushed.append, lambda: None, clock=Clock())
    try:
        inj.submit("m1", "/foo")
        inj.submit("m2", "after")
    finally:
        inj.close()
    pushed = rows(pushed)
    assert pushed[0]["isError"] is True and pushed[0]["text"] == "SystemExit"
    assert ctx.injected == [("after", "user", None)]
    assert not any(t.name == "herdr-hermes-inject" for t in threading.enumerate())


def test_command_names_read_from_registry():
    ctx = FakeCtx(commands={"foo": cmd(lambda a: None)})
    inj, _ = make(ctx)
    assert inj.command_names() == {"foo"}


def test_no_registry_means_no_commands():
    class Bare:
        def inject_message(self, content, role="user", *, session_key=None, origin=None):
            return True

    inj = Injector(Bare(), lambda ev: None, lambda: None, clock=Clock(), start=False)
    assert inj.command_names() == set()


# -- ordering --------------------------------------------------------------


def test_submits_run_in_order():
    seen = []
    ctx = FakeCtx(commands={"foo": cmd(lambda a: seen.append("cmd " + a))})
    ctx.on_inject = lambda c: seen.append("inject " + c)
    inj, _ = make(ctx)
    inj.submit("m1", "first")
    inj.submit("m2", "/foo second")
    inj.submit("m3", "third")
    inj.run_pending()
    assert seen == ["inject first", "cmd second", "inject third"]


def test_worker_thread_returns_immediately_and_keeps_order():
    release = threading.Event()
    started = threading.Event()
    seen = []

    def on_inject(content):
        if content == "first":
            started.set()
            assert release.wait(5)
        seen.append(content)

    ctx = FakeCtx()
    ctx.on_inject = on_inject
    inj = Injector(ctx, lambda ev: None, lambda: None, clock=Clock())
    try:
        inj.submit("m1", "first")
        assert started.wait(5)
        # The worker is stuck in the first message; submit must not wait on it.
        inj.submit("m2", "second")
        assert seen == []
        release.set()
    finally:
        release.set()
        inj.close()
    assert seen == ["first", "second"]
    assert not any(t.name == "herdr-hermes-inject" for t in threading.enumerate())


def test_close_without_start_is_noop():
    inj, _ = make(FakeCtx())
    inj.close()


def test_run_pending_rejected_with_worker():
    inj = Injector(FakeCtx(), lambda ev: None, lambda: None, clock=Clock())
    try:
        with pytest.raises(RuntimeError):
            inj.run_pending()
    finally:
        inj.close()


