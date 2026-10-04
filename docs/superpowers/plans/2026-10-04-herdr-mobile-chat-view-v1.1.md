# herdr-mobile Chat View v1.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the history leaks and depth, add timestamps, Markdown, text selection, inline images, deep-history paging, and answering AskUserQuestion from the phone.

**Architecture:** The mod rebuilds history from the session's transcript `.jsonl` (same filters as live rows, real timestamps, full depth, sent in chunks), extracts images into a side map, and races AskUserQuestion's terminal dialog against a phone answer collected by long-poll. The companion keeps 5000 events per pane, pages history to the app, holds images and answers. The app renders Markdown, makes text selectable, shows timestamps, loads images inline, pages older history on scroll, and shows a question card.

**Tech Stack:** Claude Code 2.1.289 hooks-module plugin (TypeScript, `claude plugin test`); Go 1.23; Kotlin + Jetpack Compose + Material3, `com.mikepenz:multiplatform-markdown-renderer-m3`.

**Spec:** `docs/superpowers/specs/2026-10-04-herdr-mobile-chat-view-v1.1-design.md` (addendum to `docs/superpowers/specs/2026-10-04-herdr-mobile-chat-view-design.md`).

**How to read this plan.** The v1 code went through several review rounds, so this plan gives each task's exact contracts (wire shapes, names, limits) and exact test cases rather than whole-file code. Implementers read the current file before changing it and fit the change into its existing structure and style. Exact values below are binding.

## Global Constraints

- `companionProtocol` 8 → 9; every change additive. An older app must keep working against the new companion (new frames only on request; unknown fields ignored).
- Hooks never do I/O except `tool.call` for `AskUserQuestion` (its waits are in-flight `$` calls). All other I/O stays in the 1 s sync tick.
- Never delay, change or refuse a conversation row: `session.append` hooks keep awaiting `next(e)` and returning its result unchanged.
- Transcript filter (history): keep rows with `type` `user` or `assistant`; drop `isMeta: true`, `isSidechain: true`, rows whose `message` has no `role`. Normalize with the existing block normalizer using the row's `uuid`.
- `ts` = epoch milliseconds (number). History: parsed from the row's ISO `timestamp`; live rows: `Date.now()` when queued. Optional on the wire.
- Transcript path: refreshed from every `classic.SessionStart`, `classic.UserPromptSubmit` and `classic.Stop` event's `transcript_path` (the file moves when a session enters a worktree). Fallback to the v1 api-form snapshot when the path is unknown, unreadable, or yields no message rows.
- Snapshot chunking: `snapshot_begin {total}` → `snapshot_chunk {events}` (each chunk's events JSON ≤ 2 MB) → `snapshot_end`; at most one chunk per sync tick. The v1 single `snapshot` event stays accepted.
- Images: `/sync` body gains `images: { "<id>": { "mediaType": string, "data": base64 } }`; id `<uuid>#<blockIndex>` (`#<blockIndex>.<n>` for the n-th image inside one tool_result block). Events reference ids: `tool_result.images: [id]`, `user_text.images: [id]`. One sync body ≤ 6 MB including images; an image that doesn't fit the current body waits for the next tick; a single image larger than 5 MB is dropped (its reference stays; the companion answers `missing`).
- Companion: ring 500 → 5000 events/pane; image LRU 30/pane; `chat_snapshot` carries the latest 300 events + `hasMore`; `chat_history` limit ≤ 300.
- AskUserQuestion: `question {uuid, toolUseId, questions, ts}` event; `POST /answer {paneId, toolUseId}` long-poll held ≤ 25 s → `{answer: {"<question text>": "<string>"} | null}`; app `chat_answer {reqId, paneId, toolUseId, answers}` → `chat_answer_result {reqId, ok, error?}` with `no_mod` / `no_question`; pending questions forgotten when their `tool_result` arrives or after 1 h. Multi-select answers: labels joined with `", "`.
- App timestamps: `HH:mm` today, `MMM d HH:mm` otherwise, device locale/zone; none when `ts` is absent; only under user bubbles and assistant messages.
- Image LRU in the app: 30 decoded images.
- Suites: mod `claude plugin test mod/herdr-chat` + `claude plugin validate mod/herdr-chat` + `npx -p typescript tsc -p <scratch tsconfig that includes /home/messam/tmp/golang/claude-1000/bundled-skills/2.1.289/42c18bb875218fb9edf6e55d55211d7a/plugin-authoring/types/claude-code.d.ts and the mod's hooks + tests>` (the `tsc` on PATH is not TypeScript); Go `go -C companion vet ./...` + `go -C companion test -race ./...`; app `export ANDROID_HOME=/home/messam/Android/Sdk; cd app && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`.
- No attribution trailers in commit messages.

## Review Focus

1. **A very long session (thousands of rows, many screenshots):** the resync must not exceed the companion's body limit or stall the heartbeat — chunks and per-tick image budget. Test in Task 2.
2. **The transcript moved mid-session (worktree entered):** history must come from the new path. Test in Task 1.
3. **Phone answer and terminal answer at almost the same time:** exactly one answer reaches Claude; the other is ignored without error. Tests in Tasks 3 and 4.
4. **Scroll to top while a page is in flight, or after a new epoch:** no duplicate pages, stale-epoch pages ignored, visible item stays put. Tests in Task 6.
5. **Old app against the new companion:** no new frame is sent unless requested; `chat_snapshot` with `hasMore` still parses. Test in Task 5.

---

### Task 1: Mod — history from the transcript, timestamps

**Files:** Modify `mod/herdr-chat/hooks/register.ts`, `mod/herdr-chat/hooks/normalize.ts`; tests `mod/herdr-chat/tests/transcript.test.ts` (new), `register.test.ts`, `normalize.test.ts`.

**Interfaces (produced):**
- `normalize.ts`: `ChatEvent` variants gain optional `ts?: number`; `normalizeBlocks(role, content, uuid, ts?)` sets `ts` on every event it returns when given.
- New `hooks/transcript.ts`: `export function eventsFromTranscript(jsonl: string): ChatEvent[] | null` — parses line by line (bad lines skipped), applies the transcript filter (Global Constraints), normalizes each kept row with its `uuid` and `ts = Date.parse(row.timestamp)` (omitted if NaN); returns `null` when no `user`/`assistant` row was found at all.
- `State` gains `transcriptPath: string | undefined`.
- Classic hooks: `on('classic.SessionStart' | 'classic.UserPromptSubmit' | 'classic.Stop', …)` each sets `s.transcriptPath = e.transcript_path` when non-empty and returns `next(e)` unchanged.
- `queueResync` builds history with `$.fs.read(s.transcriptPath)` → `eventsFromTranscript`; on missing path / read rejection / `null` it uses the v1 `normalizeSnapshot($.session.messages({as:'api'}))` path. It no longer caps history at 500 (the api fallback keeps its 500 cap).
- Live rows: `queueAppended` passes `Date.now()` as `ts`.

**Steps:**
- [ ] Write failing tests in `transcript.test.ts` (pure, no `$`): a fixture `.jsonl` string containing, in order: a non-message row (`{"type":"queue-operation"}`), a user prompt with `timestamp:"2026-10-04T15:10:14.835Z"`, an `isMeta:true` user row ("Base directory for this skill: …"), an `isSidechain:true` assistant row, an assistant row with text + tool_use, a user row with a tool_result, a user row whose text is "[Image: original 1280x2856 …]" with `isMeta:true`, an `agent-message` user row with `isMeta:true`, a malformed line `{not json`. Assert: exactly the prompt (`user_text`, `uuid` = row uuid, `ts` = 1791126614835), assistant text, tool_use and tool_result come out, in order; nothing from the meta/sidechain/bad lines. Assert `eventsFromTranscript("")` and a file of only non-message rows return `null`.
- [ ] Write failing tests in `register.test.ts`: (a) a `classic.SessionStart` with `transcript_path` = P1 then a resync reads P1 (mock `fs.read` op hook answering `{ value: <fixture> }` and recording the path); (b) a later `classic.UserPromptSubmit` with P2 makes the next resync read P2; (c) `fs.read` rejecting → the snapshot comes from the `session.messages` mock (fallback); (d) live `queueAppended` events carry a numeric `ts`. Check the op name and argument shape for `$.fs.read` in the d.ts (`'fs.read'`) and the classic event names (`ClassicEventName`) before writing the hooks; adapt the test harness to the real shapes, never the behaviour.
- [ ] Run `claude plugin test mod/herdr-chat`: expect the new tests to FAIL.
- [ ] Implement as specified. Keep `normalizeSnapshot` for the fallback.
- [ ] Run tests + validate + tsc: all green.
- [ ] Commit: `feat(mod): build chat history from the session transcript with timestamps`.

### Task 2: Mod — chunked snapshots and images

**Files:** Modify `mod/herdr-chat/hooks/register.ts`, `normalize.ts`; tests `register.test.ts`, `normalize.test.ts`.

**Interfaces (produced):**
- Control events: `{type:'snapshot_begin', total: number}`, `{type:'snapshot_chunk', events: ChatEvent[]}`, `{type:'snapshot_end'}` (replace the single `snapshot` the mod sends; the companion still accepts `snapshot`).
- `normalizeBlocks` returns images separately: change its result to `{ events: ChatEvent[]; images: Record<string, {mediaType: string; data: string}> }` (update all callers), filling `tool_result.images` / `user_text.images` with ids per Global Constraints. A user message whose only content is images yields a `user_text` with `text: ''` and `images`.
- `State` gains `imageQueue: Array<[id, {mediaType, data}]>`; `tick` adds images to the body's `images` map while the serialized body stays ≤ 6 MB, leaving the rest for later ticks; drops (with no retry) a single image > 5 MB.
- `queueResync` queues `snapshot_begin`, then chunks of events whose serialized size ≤ 2 MB, then `snapshot_end`; `tick` sends at most one `snapshot_chunk` per body (the rest of `pending` waits behind it, preserving order).

**Steps:**
- [ ] Failing tests: (a) a tool_result with two image blocks + text yields `images` with ids `<uuid>#<i>.0` and `<uuid>#<i>.1`, the tool_result event lists both ids, the preview has only the text; (b) a user message of one image → `user_text {text:'', images:[id]}`; (c) a resync over a history of 3 × 1.5 MB events produces begin, three chunks over three ticks (one chunk per sync body), end — and live rows appended during the resync arrive after `snapshot_end`; (d) six 1.5 MB images queued → no body exceeds 6 MB, all six delivered over consecutive ticks; (e) a 6 MB image is never sent and doesn't block later images.
- [ ] Run → FAIL. Implement. Run tests + validate + tsc → green.
- [ ] Commit: `feat(mod): chunked snapshots and an image side channel in /sync`.

### Task 3: Mod — AskUserQuestion from the phone

**Files:** Modify `mod/herdr-chat/hooks/register.ts` (or a new `hooks/ask.ts` imported by it); tests `mod/herdr-chat/tests/ask.test.ts`.

**Interfaces (produced):**
- `on('tool.call', { tool: 'AskUserQuestion' }, …)`: when `s.paneId` is set, push `{type:'question', uuid: e.tool_use_id, toolUseId: e.tool_use_id, questions: e.questions, ts: Date.now()}` onto `pending`; start `const dialog = next(e)`; loop `POST http://chat/answer {paneId, toolUseId}` (same socketPath) while the dialog is pending; the first of {dialog settles, a poll returns a non-null `answer`} wins. Phone win → `return { result: { questions: e.questions, answers: answer } }`. Dialog win → return its result unchanged; ignore any poll still in flight. A poll that fails (companion down) is retried after the next tick's interval via the poll loop; it never throws out of the hook. When `s.paneId` is unset, return `next(e)` directly.
- (Spike reference: `$.ui.toast` is not used in production.)

**Steps:**
- [ ] Failing tests (mock `http.fetch` per URL, `tool.call` raised via `$.tool.call` — check the d.ts for the exact call/answer shapes and that a test hook beneath answers `next`): (a) phone answers on the 2nd poll while the dialog (a test hook that never settles until released) is pending → result is `{questions, answers}` with the phone's map, and a `question` event was queued; (b) the dialog settles first → its result is returned unchanged and a late phone answer is ignored; (c) a failing `/answer` fetch keeps polling and the dialog's result is still returned; (d) without `HERDR_PANE_ID` the hook just returns `next(e)`.
- [ ] Run → FAIL. Implement. Tests + validate + tsc → green.
- [ ] Commit: `feat(mod): answer AskUserQuestion from the phone`.

### Task 4: Companion — chunked snapshots, deep history, images, answers

**Files:** Modify `companion/internal/chatbridge/hub.go`, `server.go`; tests in `companion/internal/chatbridge/v11_test.go` (new).

**Interfaces (produced):**
- `RingCap = 5000`. `isChatEvent` accepts `question`.
- Chunked snapshot: `snapshot_begin` starts a staging buffer for the pane (previous epoch stays visible); `snapshot_chunk` appends to staging; `snapshot_end` bumps `epoch`, swaps staging in (fresh seqs from 1), fans the `snapshot` update. A new `snapshot_begin` discards an unfinished staging. v1 `snapshot` unchanged.
- `Hub.Subscribe` returns a snapshot of the latest 300 entries; `Snapshot` gains `HasMore bool`.
- `func (h *Hub) History(paneID string, epoch, beforeSeq, limit int) (events []Entry, hasMore bool, ok bool)` — `ok=false` when the epoch is not current; up to `min(limit,300)` entries with seq < beforeSeq, ascending.
- Images: `SyncResync` (or a new `SyncBody` taking the parsed body) stores `images` per pane in an LRU of 30; `func (h *Hub) Image(paneID, id string) (mediaType, data string, ok bool)`.
- Questions/answers: the hub tracks pending question toolUseIds per pane (added on `question`, removed when a `tool_result` with that `toolUseId` is applied, or after 1 h). `func (h *Hub) Answer(paneID, toolUseID string, answers map[string]string) error` → `ErrNoMod` / `ErrNoQuestion` ("no_question"); stores the answer and wakes a waiting poll. `func (h *Hub) WaitAnswer(ctx, paneID, toolUseID string, timeout time.Duration) (map[string]string, bool)`.
- HTTP: `/sync` body gains `images`; new `POST /answer {paneId, toolUseId}` → waits up to 25 s via `WaitAnswer` → `{"answer": {...}}` or `{"answer": null}`; counts as a heartbeat (updates lastSeen).
- `/sync` body limit stays 8 MB (the mod keeps bodies ≤ 6 MB).

**Steps:**
- [ ] Failing tests: chunked swap (subscriber sees the old epoch until `snapshot_end`; then one snapshot update with all chunk events, seqs from 1); abandoned staging discarded by a new begin; ring cap 5000; Subscribe returns last 300 + HasMore; History paging (exact seqs, hasMore false at the start, stale epoch → ok=false, limit clamp); image LRU (31st evicts the oldest; Image ok/missing); Answer → no_mod / no_question / ok, WaitAnswer returns immediately when an answer is stored, waits and returns on a later Answer, times out with false; question forgotten on its tool_result; `/answer` handler over the test socket (immediate answer, timeout → null); `/sync` with images stored.
- [ ] Run `go -C companion test ./internal/chatbridge/` → FAIL. Implement. `go -C companion vet ./...` + `go -C companion test -race ./...` → green.
- [ ] Commit: `feat(companion): chunked snapshots, deep history, image store and answers in chatbridge`.

### Task 5: Companion — protocol 9 frames

**Files:** Modify `companion/internal/proto/proto.go`, `companion/internal/wsserver/server.go`; tests `proto_test.go`, `server_test.go`.

**Interfaces (produced):**
- `companionProtocol: 9`. `ChatSnapshot` frame gains `"hasMore": bool`.
- App → companion: `chat_history {reqId, paneId, epoch, beforeSeq, limit}`, `chat_image {paneId, id}`, `chat_answer {reqId, paneId, toolUseId, answers}` (add `Epoch int`, `BeforeSeq int`, `Limit int`, `ImageID string` (json `id`… note `ID` already exists as `id` — reuse it), `ToolUseID string`, `Answers map[string]string` to `ClientMsg`).
- Companion → app: `chat_history_page {reqId, paneId, epoch, events, hasMore}` (events never null; on stale epoch `events: [], hasMore: false, stale: true`), `chat_image_data {paneId, id, mediaType, data}` or `{paneId, id, missing: true}`, `chat_answer_result {reqId, ok, error?}`.
- `ChatHub` interface gains `History`, `Image`, `Answer`.

**Steps:**
- [ ] Failing tests: protocol 9 asserted (update the existing 8 assertions); `chat_snapshot` has `hasMore`; history page frame over a real hub (two pages, then hasMore false; stale epoch flagged); image frame (present / missing); answer result (ok / no_mod / no_question); an old-style client that never sends the new frames receives none of the new frame types (read for 300 ms after `chat_open` and a few events).
- [ ] Run → FAIL. Implement. vet + `test -race ./...` → green.
- [ ] Commit: `feat(companion): protocol 9 — history pages, images and answers over the WebSocket`.

### Task 6: App — protocol, model and repository

**Files:** Modify `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`, `net/CompanionClient.kt`, `data/ChatModel.kt`, `data/ChatRepository.kt`; tests `ProtocolTest.kt`, `ChatReducerTest.kt`, `ChatRepositoryTest.kt`.

**Interfaces (produced):**
- `ChatEvent` variants gain `ts: Long?` (UserText, AssistantText, TaskNotice; others may ignore); `UserText` gains `images: List<String>`; `ToolResult` gains `images: List<String>`; new `ChatEvent.Question(uuid, toolUseId, questions: List<Question>, ts: Long?)` with `Question(question, header, kind /* choice|text|number */, options: List<Option(label, description)>, multiSelect, min, max, step, unit)`.
- Frames: `ChatSnapshot` gains `hasMore`; new `ServerFrame.ChatHistoryPage(reqId, paneId, epoch, entries, hasMore, stale)`, `ChatImageData(paneId, id, mediaType, data: String?, missing: Boolean)`, `ChatAnswerResult(reqId, ok, error)`. `ClientMsg.chatHistory(reqId, paneId, epoch, beforeSeq, limit)`, `chatImage(paneId, id)`, `chatAnswer(reqId, paneId, toolUseId, answers: Map<String,String>)`. `CompanionClient.sendAnswer(...)` (suspend, throws on !ok, like `sendChat`).
- `ChatView` gains `hasMore: Boolean`, `loadingOlder: Boolean`, `answered: Set<String>` (toolUseIds whose tool_result arrived), `answering: Set<String>` (in flight). Reducer: history page for the current epoch prepends entries with seq < first seq (dedup), sets `hasMore`, clears `loadingOlder`; stale/other-epoch page only clears `loadingOlder`.
- `ChatRepository`: `loadOlder(paneId)` (no-op unless `loaded && hasMore && !loadingOlder`; sends `chat_history` with `beforeSeq` = first entry's seq, limit 300; sets `loadingOlder`); `imageState(paneId, id): StateFlow<ImageState>` (`Loading | Ready(bytes) | Missing`) backed by an LRU of 30 that requests each id once via `chat_image`; `answer(paneId, toolUseId, answers)` (suspend; marks answering; failure clears it and surfaces the error via the existing error path).
- Pure formatter `fun formatTs(ts: Long, now: Long, zone: ZoneId, locale: Locale): String` → `HH:mm` / `MMM d HH:mm`.

**Steps:**
- [ ] Failing tests: parsing of every new frame/field (absent fields default; `ts` null when missing; question kinds); reducer — page prepend + dedup + hasMore, stale page ignored, `loadingOlder` lifecycle, `answered` set when a tool_result with a question's toolUseId arrives (live and in snapshot); repository — `loadOlder` sends one request while one is in flight and none when `hasMore` false, image requested once and served from cache afterwards, LRU eviction at 31, `answer` failure path; `formatTs` today vs earlier day vs across midnight (fixed zone + locale).
- [ ] Run `:app:testDebugUnitTest` → FAIL. Implement. Unit tests + assembleDebug → green.
- [ ] Commit: `feat(app): chat protocol 9 — timestamps, images, history paging and answers`.

### Task 7: App — UI

**Files:** Modify `app/gradle/libs.versions.toml`, `app/app/build.gradle.kts`, `ui/ChatScreen.kt`, `ui/DashboardViewModel.kt`; create `ui/ChatMarkdown.kt`, `ui/ImageViewer.kt`, `ui/QuestionCard.kt`; delete `ui/ChatText.kt` and its test (`splitFences` replaced).

**Interfaces / behaviour:**
- Markdown: add `com.mikepenz:multiplatform-markdown-renderer-m3` (+ its core artifact if required) at the latest version compatible with the project's Compose BOM and Kotlin (check Maven Central; pin in `libs.versions.toml`). `ChatMarkdown(text)` renders assistant text with Catppuccin colours/typography; code blocks monospace on `surfaceContainer`; links open in the browser.
- Selection: user bubble text, assistant messages and expanded tool output inside `SelectionContainer`; tool card header stays clickable.
- Timestamps: `formatTs` under user bubbles and assistant messages when `ts != null`.
- Images: `tool_result.images` render under the tool card, `user_text.images` inside the bubble; max height 240dp, rounded; loading placeholder; "image unavailable" for Missing; tap → `ImageViewer` full-screen dialog with pinch-zoom and pan, close on back/tap.
- Paging: when the first list item is visible and `hasMore`, call `vm.loadOlder(paneId)`; a "loading earlier…" row at the top while `loadingOlder`; after a prepend keep the previously first visible item at the same position (adjust `firstVisibleItemIndex` by the number of prepended items).
- Question card: for `ChatEvent.Question` — header chip, question text, option buttons with descriptions (single-select submits on tap when it is the only question), checkboxes + Submit for multi-select, "Other…" text field, text field for `kind: text`, number field clamped to min..max for `kind: number`, one Submit for multi-question calls; disabled while disconnected or answering; collapsed (dimmed, showing the answer) once `answered` contains its toolUseId.
- VM: `loadOlder(paneId)`, `imageState(paneId, id)`, `answerQuestion(paneId, toolUseId, answers)`.

**Steps:**
- [ ] Unit-test any new pure helpers (answers map building from selections: single, multi → `", "` join, other text, number); run → FAIL → implement → green.
- [ ] Implement the UI; full unit tests + `assembleDebug` green.
- [ ] Commit: `feat(app): markdown, selectable text, timestamps, inline images, paging and question card`.

### Task 8: Docs + live validation

**Files:** Modify `README.md` (chat view section: images, history depth, answering questions), `CHANGELOG.md`.

- [ ] Docs + commit `docs: chat view v1.1`.
- [ ] Live validation (controller, emulator + a real Claude in a throwaway herdr pane; the user's own companion must not be touched — run a test companion on another port with `HERDR_MOBILE_CHAT_SOCK` pointing at a private 0700 dir, and start the test Claude with the same env var): (1) Markdown renders (bold, code block, list, table); (2) long-press copies text; (3) timestamps show; (4) after `/reload-plugins`, no skill/peer/image-caption bubbles in history; (5) Claude Reads a PNG → image shows inline and opens full-screen; (6) a long session scrolled to the top loads older pages back to the first prompt; (7) Claude calls AskUserQuestion → card on the phone; answering from the phone closes the terminal dialog and Claude receives the answer; answering in the terminal collapses the card.
