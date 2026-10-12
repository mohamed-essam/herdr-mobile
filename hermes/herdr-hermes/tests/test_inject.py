import threading

import pytest

from herdr_hermes.inject import REFUSED, Injector, parse_slash


class FakeManager:
    def __init__(self, commands):
        self._plugin_commands = commands


class FakeCtx:
    def __init__(self, commands=None, result=True):
        self._manager = FakeManager(commands or {})
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
    assert pushed == [{
        "type": "command_output", "uuid": "cmd-m1", "command": "hello",
        "text": REFUSED, "isError": True, "ts": 5000,
    }]
    assert inj.consume_echo("hello") is False


def test_inject_raising_pushes_error_row():
    ctx = FakeCtx(result=RuntimeError("boom"))
    inj, pushed = make(ctx)
    inj.submit("m1", "hello")
    inj.run_pending()
    assert len(pushed) == 1
    assert pushed[0]["isError"] is True and pushed[0]["uuid"] == "cmd-m1"
    assert "boom" in pushed[0]["text"]
    assert inj.consume_echo("hello") is False


def test_unknown_slash_goes_to_inject():
    ctx = FakeCtx(commands={"foo": cmd(lambda a: "x")})
    inj, pushed = make(ctx)
    inj.submit("m1", "/model gpt")
    inj.run_pending()
    assert ctx.injected == [("/model gpt", "user", "sk-1")]
    assert pushed == []


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
    assert pushed == [{
        "type": "command_output", "uuid": "cmd-m7", "command": "/foo a b",
        "text": "ran a b", "ts": 2000,
    }]


def test_command_without_output_pushes_nothing():
    ctx = FakeCtx(commands={"foo": cmd(lambda a: None), "bar": cmd(lambda a: "  ")})
    inj, pushed = make(ctx)
    inj.submit("m1", "/foo")
    inj.submit("m2", "/bar")
    inj.run_pending()
    assert pushed == []


def test_command_raising_pushes_error_row():
    def handler(raw):
        raise ValueError("bad args")

    ctx = FakeCtx(commands={"foo": cmd(handler)})
    inj, pushed = make(ctx)
    inj.submit("m1", "/foo")
    inj.run_pending()
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
    assert pushed[0]["text"] == "async z"
    assert "isError" not in pushed[0]


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


# -- echo ------------------------------------------------------------------


def test_consume_echo_once():
    ctx = FakeCtx()
    inj, _ = make(ctx)
    inj.submit("m1", "hello")
    inj.run_pending()
    assert inj.consume_echo("hello") is True
    assert inj.consume_echo("hello") is False


def test_consume_echo_ignores_surrounding_whitespace():
    ctx = FakeCtx()
    inj, _ = make(ctx)
    inj.submit("m1", "  hello\n")
    inj.run_pending()
    assert inj.consume_echo("hello") is True


def test_consume_echo_unknown_text():
    inj, _ = make(FakeCtx())
    assert inj.consume_echo("never sent") is False


def test_consume_echo_expires_after_60s():
    clock = Clock(100.0)
    ctx = FakeCtx()
    inj, _ = make(ctx, clock=clock)
    inj.submit("m1", "hello")
    inj.run_pending()
    clock.t = 160.5
    assert inj.consume_echo("hello") is False


def test_consume_echo_within_60s():
    clock = Clock(100.0)
    ctx = FakeCtx()
    inj, _ = make(ctx, clock=clock)
    inj.submit("m1", "hello")
    inj.run_pending()
    clock.t = 159.0
    assert inj.consume_echo("hello") is True


def test_echo_recorded_before_inject_returns():
    # Hermes may start the turn (and fire pre_llm_call) before inject_message returns.
    ctx = FakeCtx()
    inj, _ = make(ctx)
    during = []
    ctx.on_inject = lambda c: during.append(inj.consume_echo(c))
    inj.submit("m1", "hello")
    inj.run_pending()
    assert during == [True]


def test_slash_command_does_not_record_echo():
    ctx = FakeCtx(commands={"foo": cmd(lambda a: None)})
    inj, _ = make(ctx)
    inj.submit("m1", "/foo")
    inj.run_pending()
    assert inj.consume_echo("/foo") is False


def test_two_identical_messages_echo_twice():
    ctx = FakeCtx()
    inj, _ = make(ctx)
    inj.submit("m1", "yes")
    inj.submit("m2", "yes")
    inj.run_pending()
    assert inj.consume_echo("yes") is True
    assert inj.consume_echo("yes") is True
    assert inj.consume_echo("yes") is False
