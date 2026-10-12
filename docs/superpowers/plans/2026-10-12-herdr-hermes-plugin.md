# herdr-hermes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hermes sessions in herdr panes open as a chat on the phone, with prompts and slash commands flowing back into the session.

**Architecture:** A Python Hermes plugin (`hermes/herdr-hermes/`) enqueues chat events from Hermes hooks and a daemon thread POSTs them to the companion's existing `/sync` endpoint over `chat.sock`; the `/sync` reply carries the phone's prompts, injected with `ctx.inject_message`. The companion and app gain one optional `agent` field on `hello`, carried through the snapshot to the chat header; empty means `claude`.

**Tech Stack:** Python 3 stdlib + pytest (plugin), Go (companion), Kotlin (app).

**Spec:** `docs/superpowers/specs/2026-10-12-herdr-hermes-plugin-design.md`

## Global Constraints

- Wire format: `POST http://chat/sync` over the Unix socket, body `{ paneId, sessionId, events, images?, usage? }`, reply `{ messages: [{id, text}], resync?, threads }`. Unchanged except `hello.agent`.
- Socket path: `HERDR_MOBILE_CHAT_SOCK`, else `$XDG_RUNTIME_DIR/herdr-mobile/chat.sock`, else chat is off. No `/tmp` fallback. Pane id from `HERDR_PANE_ID`; both absent: plugin loads and does nothing.
- Caps (match `mod/herdr-chat/hooks/normalize.ts`): `MAX_TEXT` 64 KiB (`…[truncated]` suffix), `PREVIEW` 400, `SUMMARY` 120. Body at most 4,000,000 bytes; history chunk at most 2 MiB; at most 5000 history events.
- `hello` is `{ type: "hello", sessionId, cwd, agent: "hermes" }`. Empty or absent `agent` means `claude`.
- Hook callbacks only enqueue; network I/O runs on one daemon thread (Hermes hook timeout is 30 s, `plugins.hook_callback_timeout`).
- Questions (`on_human_input_request`) are mirrored read-only as an `assistant_text` notice: `Hermes is asking: <prompt>. Answer in the terminal.`
- Commit style: `feat(companion): ...`, `feat(app): ...`, `feat(hermes): ...`; `go test ./...` and `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug` per `CONTRIBUTING.md`.

## Review Focus

- Phone sends two messages quickly: both injected in order, never interleaved.
- Phone prompt injected into Hermes comes back via `pre_llm_call`: it must not be echoed as a second `user_text`.
- Companion restarts mid-session: `resync: true` leads to `hello` + snapshot; no events lost or duplicated.
- Socket missing, wrong permissions, or `HERDR_PANE_ID` unset: plugin is inert, Hermes starts normally.
- `inject_message` returns False (gateway surface without `allow_gateway_injection`): phone sees an error row, nothing hangs.
- Assistant text over 64 KiB, tool results with non-text or huge output: truncated, valid JSON.
- Hermes subagent/forked runs (`parent_session_id` set, `_persist_disabled`): never published under the parent's pane.

---

### Task 1: Companion carries `hello.agent`

**Files:**
- Modify: `companion/internal/chatbridge/hub.go` (pane struct, `apply` hello case ~line 330, `Snapshot` ~line 61, snapshot builder ~line 582)
- Modify: `companion/internal/proto/proto.go:135-160` (`ChatSnapshot`)
- Test: `companion/internal/chatbridge/hub_test.go`, `companion/internal/proto/proto_test.go`

**Interfaces:**
- Produces: `Snapshot.Agent string` (main snapshots only; `""` = claude); `ChatSnapshot` frame includes `"agent": "<name>"` only when non-empty.

- [ ] **Step 1: Failing tests.** `TestHelloAgentReachesSnapshot`: Sync a `hello` with `"agent":"hermes"` then Subscribe: `snap.Agent == "hermes"`. `TestHelloWithoutAgentIsClaude`: hello with no agent: `snap.Agent == ""` and the encoded frame has no `agent` key. `TestHelloAgentResetsOnNewSession`: a second hello (new sessionId) without `agent` clears it. In `proto_test.go`: `ChatSnapshot` with `Agent:"hermes"` emits `"agent":"hermes"`.
- [ ] **Step 2: Run** `go test ./companion/internal/chatbridge ./companion/internal/proto -run 'HelloAgent|Agent'`; expect FAIL (field undefined).
- [ ] **Step 3: Implement.** Store `agent string` on `pane`. The `apply` head struct already has `Agent json.RawMessage` (used by `type:"agent"` events); do NOT add a second field with tag `agent` (Go drops both). In the `hello` case, `json.Unmarshal` that RawMessage into a string, ignoring errors; set `p.agent` on every hello (so a new session without it resets). Add `Agent string` to `Snapshot`, set it in the main-snapshot builder only, and emit it in `proto.ChatSnapshot` beside `commands`.
- [ ] **Step 4: Run** `go test ./...` in `companion/`; expect PASS.
- [ ] **Step 5: Commit** `feat(companion): carry the agent name from hello to the snapshot`. Check `git log -3 -- companion/internal/proto` for a protocol-version constant bumped by comparable commits ("protocol 12"); if the repo bumps it for additive fields, bump it and its test in this commit.

### Task 2: App shows the reported agent

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt:314-320,412` (`ChatSnapshot` gains `agent: String? = null`, parsed from `obj`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/data/ChatModel.kt:56,104` (view field `agent: String? = null`, `agent = f.agent ?: v.agent` on main snapshots, same rule as `commands`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/ChatScreen.kt:77`
- Test: `app/app/src/test/java/dev/herdr/mobile/ChatReducerTest.kt`

**Interfaces:**
- Consumes: Task 1's `"agent"` key on the snapshot frame.

- [ ] **Step 1: Failing tests** in `ChatReducerTest`: snapshot with `agent:"hermes"` sets `view.agent == "hermes"`; a later snapshot without it keeps it; a thread snapshot (`agentId` set) does not change it. A pure helper test `chatAgentLabel(paneAgent, viewAgent)`: `("", null) -> "claude"`, `(null, "hermes") -> "hermes"`, `("codex", "hermes") -> "codex"`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ChatReducerTest*'`; expect FAIL.
- [ ] **Step 3: Implement** `fun chatAgentLabel(paneAgent: String?, viewAgent: String?): String` in `ChatScreen.kt` (precedence herdr's `pane.agent`, then the chat-reported agent, then `"claude"`, blanks skipped) and use it at line 77.
- [ ] **Step 4: Run** the full app command from `CONTRIBUTING.md`; expect PASS.
- [ ] **Step 5: Commit** `feat(app): label the chat with the agent the mod reports`.

### Task 3: Plugin skeleton and event mappers (`events.py`)

**Files:**
- Create: `hermes/herdr-hermes/plugin.yaml` (`name: herdr-hermes`, `version: 0.1.0`, `description`, `requires_env: []`, same shape as `~/.hermes/plugins/hindsight/plugin.yaml`)
- Create: `hermes/herdr-hermes/herdr_hermes/__init__.py` (empty for now), `.../events.py`
- Create: `hermes/herdr-hermes/pyproject.toml` (pytest only), `hermes/herdr-hermes/tests/test_events.py`

**Interfaces:**
- Produces (all pure, return `dict` or `None`):
  - `cap(s: str) -> str`, `clip(s: str, n: int) -> str`
  - `user_text(uuid: str, text: str, ts: int) -> dict`
  - `assistant_text(uuid: str, text: str, ts: int) -> dict`
  - `tool_use(tool_call_id: str, tool: str, args: dict, ts: int) -> dict` (summary = first string arg, clipped to 120)
  - `tool_result(tool_call_id: str, result: object, is_error: bool, ts: int) -> dict` (preview clipped to 400)
  - `question_notice(request_id: str, prompt: str, ts: int) -> dict` (an `assistant_text` with the Global Constraints wording)
  - `hello(session_id: str, cwd: str) -> dict`, `state(working: bool) -> dict`
- Event shapes are exactly `ChatEvent`/`Control` in `mod/herdr-chat/hooks/normalize.ts` and `register.ts`.

- [ ] **Step 1: Failing tests** with exact dicts for each function, plus: `cap` of 70,000 chars ends with `…[truncated]` and is `65536 + len(suffix)` long; `tool_result` with a dict result serializes via `json.dumps` and clips to 400; a bytes/non-JSON result falls back to `str()`; `tool_use` with no string arg has an empty summary.
- [ ] **Step 2: Run** `pytest hermes/herdr-hermes/tests/test_events.py -v`; expect FAIL (module missing).
- [ ] **Step 3: Implement** the functions above; no I/O.
- [ ] **Step 4: Run** the same; expect PASS.
- [ ] **Step 5: Commit** `feat(hermes): plugin skeleton and chat event mappers`.

### Task 4: Socket client (`socket_client.py`)

**Files:**
- Create: `hermes/herdr-hermes/herdr_hermes/socket_client.py`
- Test: `hermes/herdr-hermes/tests/test_socket_client.py`

**Interfaces:**
- Produces: `socket_path(env: Mapping[str, str]) -> str | None` (rule in Global Constraints; `None` when neither variable set); `check_private_dir(path: str) -> str | None` (returns a reason when the directory is not owned by the user or grants any `0o077` bit, mirroring `chatbridge.Listen`); `class SyncError(Exception)`; `sync(path: str, body: dict, timeout: float = 10.0) -> dict` (POST `/sync` over `AF_UNIX`, returns decoded reply, raises `SyncError` on connect failure, non-200 or bad JSON).

- [ ] **Step 1: Failing tests:** `socket_path` for the three env cases; `check_private_dir` for a `0o700` dir (ok), `0o755` dir (reason); `sync` against a tiny threaded `http.server` on a temp Unix socket: returns the scripted reply, raises `SyncError` on a 500 and on a refused connection. The fake server records the request body so the test asserts it equals what was sent.
- [ ] **Step 2: Run** `pytest hermes/herdr-hermes/tests/test_socket_client.py -v`; expect FAIL.
- [ ] **Step 3: Implement** with `http.client.HTTPConnection` subclass whose `connect` opens an `AF_UNIX` socket; `Host: chat`.
- [ ] **Step 4: Run** the same; expect PASS.
- [ ] **Step 5: Commit** `feat(hermes): unix-socket sync client`.

### Task 5: Sync loop (`loop.py`)

**Files:**
- Create: `hermes/herdr-hermes/herdr_hermes/loop.py`
- Test: `hermes/herdr-hermes/tests/test_loop.py`

**Interfaces:**
- Consumes: `socket_client.sync`, `SyncError`, and `events.*`.
- Produces: `class Loop` with `__init__(self, *, pane_id: str, socket: str, on_message: Callable[[str, str], None], history: Callable[[], list[dict]], tick: float = 1.0, sync: Callable = socket_client.sync)`; `start() -> None` (daemon thread); `stop() -> None`; `push(event: dict) -> None` (thread-safe enqueue); `set_session(session_id: str, cwd: str) -> None` (queues `hello`, marks resync); `tick_once() -> None` (one iteration, exposed for tests). `on_message(id, text)` is called once per phone message, in order.

Behavior: each tick sends queued events (or `[]` as heartbeat) with `paneId`, `sessionId`. On `SyncError`: go offline, clear the queue, set resync-needed; on the next success, send history (`hello` first, then `snapshot_begin {total}`, `snapshot_chunk {events}` chunks of at most 2 MiB serialized and 5000 events total newest-last, `snapshot_end`). A reply with `resync: true` triggers the same. Body capped at 4,000,000 bytes: take events from the queue until the next would exceed it.

- [ ] **Step 1: Failing tests** with an injected fake `sync`: events are sent in order; heartbeat sent when queue empty; `messages` reach `on_message` in order exactly once; a `SyncError` drops the queue, and the next success sends hello + snapshot; `resync: true` does the same; the body never exceeds 4,000,000 bytes with a large queue; events pushed while `SyncError` is raised are not delivered later (matches the mod).
- [ ] **Step 2: Run** `pytest hermes/herdr-hermes/tests/test_loop.py -v`; expect FAIL.
- [ ] **Step 3: Implement** `Loop`; one lock around the queue; `tick_once` never raises.
- [ ] **Step 4: Run** the same; expect PASS.
- [ ] **Step 5: Commit** `feat(hermes): sync loop with offline and resync handling`.

### Task 6: History snapshot (`history.py`)

**Files:**
- Create: `hermes/herdr-hermes/herdr_hermes/history.py`
- Test: `hermes/herdr-hermes/tests/test_history.py`

**Interfaces:**
- Consumes: `events.*`.
- Produces: `from_messages(messages: list[dict], now_ms: int) -> list[dict]`: converts Hermes' OpenAI-style `conversation_history` (the list passed to `pre_llm_call`/`post_llm_call`) to `ChatEvent`s: `user` messages to `user_text`, `assistant` text to `assistant_text`, each `tool_calls` entry to `tool_use`, `tool` role messages to `tool_result`; system messages skipped; returns the newest 5000.

This resolves spec open question 3: the plugin keeps the latest `conversation_history` list reference from the hooks and builds the snapshot from it, so no event buffering is needed.

- [ ] **Step 1: Failing tests** over a hand-written message list covering each role, a tool call with a matching tool result (same `tool_call_id`), multimodal `content` lists (text parts joined, image parts ignored), and a 6000-message list (returns the newest 5000).
- [ ] **Step 2: Run** `pytest hermes/herdr-hermes/tests/test_history.py -v`; expect FAIL.
- [ ] **Step 3: Implement** `from_messages`; uuids are stable (`h-<index>`) so a resync reproduces the same ids.
- [ ] **Step 4: Run** the same; expect PASS.
- [ ] **Step 5: Commit** `feat(hermes): build the chat history from conversation history`.

### Task 7: Injection and commands (`inject.py`)

**Files:**
- Create: `hermes/herdr-hermes/herdr_hermes/inject.py`
- Test: `hermes/herdr-hermes/tests/test_inject.py`

**Interfaces:**
- Produces: `class Injector` with `__init__(self, ctx, push: Callable[[dict], None], session_key: Callable[[], str | None])`; `submit(msg_id: str, text: str) -> None` (serialized by an internal lock/queue so messages run in order); `consume_echo(text: str) -> bool` (True once if `text` was injected by the phone within the last 60 s, so the `pre_llm_call` hook can skip the duplicate `user_text`); `parse_slash(text: str, names: set[str]) -> tuple[str, str] | None`.
- Behavior: a slash text naming a registered Hermes command runs it and pushes a `command_output` row (`uuid: f"cmd-{msg_id}"`, `isError: True` on failure); anything else calls `ctx.inject_message(text, "user", session_key=session_key())`; a `False` return pushes a `command_output` error row `"Hermes refused the message: injection is not allowed for this session (set plugins.entries.herdr-hermes.allow_gateway_injection)"`.

- [ ] **Step 1: Failing tests** with a fake `ctx`: ordering of two submits; `False` return produces the error row; slash with a known command; unknown slash goes to `inject_message`; `consume_echo` returns True once then False, and False after 60 s (injected clock).
- [ ] **Step 2: Run** `pytest hermes/herdr-hermes/tests/test_inject.py -v`; expect FAIL.
- [ ] **Step 3: Implement** `Injector`.
- [ ] **Step 4: Run** the same; expect PASS.
- [ ] **Step 5: Commit** `feat(hermes): inject phone prompts and run slash commands`.

### Task 8: Wire it up (`register`)

**Files:**
- Create: `hermes/herdr-hermes/__init__.py` (the plugin entry; `register(ctx)` re-exported from `herdr_hermes/__init__.py`)
- Modify: `hermes/herdr-hermes/herdr_hermes/__init__.py`
- Test: `hermes/herdr-hermes/tests/test_register.py`

**Interfaces:**
- Produces: `register(ctx) -> None`. Inert (registers nothing, logs one debug line) when the socket path is unresolved, `check_private_dir` returns a reason, or `HERDR_PANE_ID` is unset. Otherwise registers hooks per the spec's mapping table:
  - `on_session_start(session_id, **kw)`: `loop.set_session(session_id, cwd=os.getcwd())`.
  - `pre_llm_call(session_id, user_message, conversation_history, parent_session_id="", **kw)`: keep `conversation_history`; push `user_text` unless `injector.consume_echo(user_message)` or `parent_session_id` is set; push `state(working=True)`.
  - `on_interim_message`: push `assistant_text` (confirm the kwarg name in `agent/` while writing; record it in the test).
  - `post_llm_call(session_id, assistant_response, **kw)`: push `assistant_text` then `state(working=False)`.
  - `pre_tool_call(tool_name, args, tool_call_id, **kw)`: push `tool_use`; `post_tool_call(tool_name, result, tool_call_id, status, **kw)`: push `tool_result` (`is_error = status != "ok"`; confirm the success value in `model_tools.py::_tool_result_observer_fields`).
  - `agent_loop_stopped`, `on_session_end`, `on_session_reset`: push `state(working=False)`.
  - `on_human_input_request(prompt, request_id, **kw)`: push `question_notice`.
  - Every hook callback ignores payloads from forked/persist-disabled agents by returning early when `parent_session_id` is non-empty.
  - Starts `Loop` once, with `on_message=injector.submit` and `history=lambda: history.from_messages(last_history, now)`.

- [ ] **Step 1: Failing tests** with a fake `ctx` recording `register_hook` calls and a fake loop: inert cases register nothing; an active register covers all hooks above; calling each hook produces the expected pushed events (exact dicts); an injected-then-echoed prompt yields no second `user_text`; a payload with `parent_session_id` pushes nothing.
- [ ] **Step 2: Run** `pytest hermes/herdr-hermes/tests/test_register.py -v`; expect FAIL.
- [ ] **Step 3: Implement** `register`; callbacks wrap bodies in try/except so a bug never reaches the agent.
- [ ] **Step 4: Run** `pytest hermes/herdr-hermes -v` and `hermes plugins validate hermes/herdr-hermes`; expect PASS and a clean validation.
- [ ] **Step 5: Commit** `feat(hermes): register the chat hooks`.

### Task 9: Contract test, docs, manual check

**Files:**
- Create: `companion/internal/chatbridge/hermes_contract_test.go`, `companion/internal/chatbridge/testdata/hermes_sync.json` (a body captured from the plugin's loop in Task 5's tests)
- Modify: `README.md` (new "Chat view for Hermes panes" section after the Claude Code one; document `hermes plugins install` and `allow_gateway_injection`), `CHANGELOG.md`

- [ ] **Step 1: Failing test** `TestHermesSyncBodyIsAccepted`: post the fixture to `Handler()`; reply has no `resync` after the hello, snapshot `Agent == "hermes"`, events decode to the expected `Entry` kinds.
- [ ] **Step 2: Run** `go test ./companion/internal/chatbridge -run Hermes`; expect FAIL until the fixture exists, then PASS.
- [ ] **Step 3: Docs.** README section and CHANGELOG entry.
- [ ] **Step 4: Manual verification.** Start the companion, run Hermes in a herdr pane with the plugin enabled, open the pane on the phone. Check: history appears, a reply streams in, a tool call and result show, a phone prompt reaches Hermes with no duplicate row, a `clarify` shows the read-only notice, and the header says `hermes`. Restart the companion mid-session and check it resyncs.
- [ ] **Step 5: Commit** `docs: Hermes chat view`.
