# herdr-mobile — Chat View v1.1 — Design Spec (addendum)

**Date:** 2026-10-04
**Builds on:** `docs/superpowers/specs/2026-10-04-herdr-mobile-chat-view-design.md` (v1, merged). Everything there still holds unless this document changes it.
**Components:** `mod/herdr-chat`, companion (`chatbridge`, `proto`, `wsserver`), app.
**Protocol:** `companionProtocol` 8 → 9 (additive).

## Goal

Eight follow-ups from using v1 on the phone:

1. Assistant text renders Markdown (bold, italics, inline code, code blocks, headings, lists, links, tables).
2. Long-press selects text and copies it.
3. Messages show a small timestamp.
4. Loaded skills don't show as user messages.
5. Images Claude reads don't show as user messages, and render inline in the chat.
6. "Another Claude session sent a message" doesn't show as a user message.
7. History goes back to the start of the session.
8. AskUserQuestion can be answered from the phone.

## Root causes (verified)

- **4, 5, 6** only appear in the *rebuilt history* (snapshot), not in live rows. v1 builds history from `$.session.messages({ as: "api" })`, which drops the `isMeta` flag. In the transcript file, skill bodies ("Base directory for this skill…"), image captions ("[Image: original 1280x2856…]"), "Tool loaded." and peer-agent messages are all `isMeta: true` rows. The live filter already drops `isMeta`.
- **7**: the snapshot is capped at 500 events (and 4 MB); a long session has thousands of rows.
- **3**: the api-form history has no timestamps; every transcript row has `timestamp` (ISO 8601).
- **8**: the phone only sees a `tool_use` card; nothing lets it answer.

## 1. History from the transcript (mod)

- **Path.** The mod hooks the classic hook events that carry `transcript_path` (`classic.SessionStart`, `classic.UserPromptSubmit`, `classic.Stop`) and keeps the latest value. The path is NOT stable: entering a worktree relocates the transcript to the worktree's project directory, so it must be refreshed from every such event rather than cached once.
- **Reading.** On every resync (start, `/clear`, `/resume`, recovery, companion-requested resync) the mod reads the file with `$.fs` and parses it line by line. A line that isn't JSON is skipped.
- **Filtering (same rules as live rows).** Keep only rows with `type` `user` or `assistant`; drop `isMeta: true`, `isSidechain: true`, and rows whose message has no `role`. Rows from before a `/compact` boundary are kept (history goes to session start). Each kept row goes through the existing block normalizer (system-reminder / local-command stripping, task notifications), with the row's real `uuid`.
- **Timestamps.** Every event gains `ts` (epoch ms): from the row's `timestamp` in history, `Date.now()` at append time for live rows. `ts` is optional on the wire.
- **Fallback.** If there is no transcript path, the file can't be read, or it yields zero rows for a session that has messages, the mod uses v1's api-form snapshot (no `ts`, 500-event tail).
- **Chunking.** History is sent as `snapshot_begin {total}` → one or more `snapshot_chunk {events}` (each batch ≤ 2 MB serialized) → `snapshot_end`, spread across sync ticks. The hub only swaps in the new epoch at `snapshot_end`; until then the previous view stays. A v1 single `snapshot` event is still accepted.

## 2. Images (mod, companion, app)

- **Mod.** Image blocks inside `tool_result` content (e.g. Read on a PNG) and image blocks in a user's own message are extracted into a separate `images` map on the `/sync` body: `{ "<id>": { mediaType, data } }` (base64 as stored). The id is `<uuid>#<blockIndex>[.<n>]`. The event keeps only references: `tool_result.images: [id]`, and a user message with images gets `user_text.images: [id]` (a user message that is only an image still yields a `user_text` with empty text and the image ids). History images are included the same way, subject to the chunk budget (an image that would exceed it is dropped and replaced by a reference the companion will answer `missing` for).
- **Companion.** Images are stored per pane in an LRU of 30 (by id), never put on the event stream or in history pages. New WS frames: app → `chat_image {paneId, id}`; companion → `chat_image_data {paneId, id, mediaType, data}` or `chat_image_data {paneId, id, missing: true}`.
- **App.** Images load automatically: when an event referencing an image id is shown, the app requests it once and caches the bytes in memory (bounded, LRU 30). A `tool_result` image renders under its tool card (max height ~240dp, rounded); a user image renders inside the bubble. Tap opens a full-screen viewer with pinch-to-zoom. While loading: a placeholder box; `missing`: a small "image unavailable" label.

## 3. Deep history and paging (companion, app)

- Hub ring buffer 500 → 5000 events per pane.
- `chat_open` now sends the latest 300 events in `chat_snapshot` plus `hasMore: bool`.
- New frames: app → `chat_history {paneId, epoch, beforeSeq, limit}` (limit ≤ 300); companion → `chat_history_page {paneId, epoch, events, hasMore}`. A page for a stale epoch is ignored by the app.
- App: when the first list item becomes visible and `hasMore`, request the previous page once (no request while one is in flight), show a "loading earlier…" row, prepend the page and keep the visible item anchored.

## 4. AskUserQuestion from the phone (spike-gated)

**Spike first** (a throwaway mod in the scratchpad, not shipped): verify in a real session whether a `tool.call` hook on `AskUserQuestion` can
(a) run `next(e)` (the terminal dialog) while concurrently waiting on the phone through an in-flight `$.http.fetch` long-poll (in-flight `$` calls don't count against the hook budget), and
(b) when the phone answers first, settle the call with the answers (e.g. `next({ ...e, answers })` in place of the pending dialog, or answering without `next`) and have the terminal dialog close.

**Path A (spike passes).**
- Mod: on `tool.call` for `AskUserQuestion`, queue a `question {uuid, toolUseId, questions}` event (questions as the tool input gives them: `question`, `header`, `options[{label, description}]`, `multiSelect`), then race the terminal dialog against `GET /answer?pane&toolUseId` (long-poll, companion holds up to 30 s, the mod re-polls). First answer wins; the other side is cancelled/ignored.
- Companion: app → `chat_answer {reqId, paneId, toolUseId, answers}` (`answers`: question text → chosen label(s) or free text); stored per pane until the mod collects it; `chat_answer_result {reqId, ok, error?}` (`no_mod`, `no_question`).
- App: a question card (see §5).

**Path B (spike fails: the dialog can't be settled from a hook).** The card still shows; answering sends key presses to the pane through herdr's existing `send_keys` (arrow keys to the option, space for multi-select, Enter; "Other" types the text). The companion maps `chat_answer` to the key sequence using the question's option order.

Either way, the card collapses to the chosen answer when the `tool_result` for that `toolUseId` arrives (answered on either side).

## 5. App UI

- **Markdown (1).** Add `com.mikepenz:multiplatform-markdown-renderer-m3` (latest release compatible with the project's Compose BOM; pinned in `libs.versions.toml`) and render assistant text with it, colours and typography from the Catppuccin theme, code blocks monospace on `surfaceContainer`. Replaces `splitFences` / `TextSegment`. User bubbles stay plain text.
- **Selection (2).** User bubbles and assistant messages are wrapped in `SelectionContainer`; long-press selects with the system copy toolbar. Expanded tool output is selectable too. Tool card headers stay tap-to-expand.
- **Timestamps (3).** A dim `labelSmall` line under each user bubble and each assistant message: `HH:mm` if today, `MMM d HH:mm` otherwise (device locale and time zone); nothing when `ts` is absent. Tool cards, notices and pending bubbles show none (pending keeps its status label).
- **Question card (8).** Header chip, question text, one button per option (description as secondary text), checkboxes + "Submit" for multi-select, an "Other…" field, all questions of one call in one card. Disabled while disconnected or while an answer is in flight. Collapses to the answer (dimmed) once the matching `tool_result` arrives.

## 6. Failure cases (new)

| Situation | Behaviour |
|---|---|
| Transcript unreadable / relocated mid-read | Fallback to api-form snapshot; next resync tries the transcript again. |
| Snapshot chunks interrupted (mod offline) | Hub keeps the previous epoch; mod restarts the chunked snapshot after recovery. |
| Image evicted from the companion LRU | `missing: true` → "image unavailable". |
| Answer sent but the terminal answered first | Mod ignores the late answer; `chat_answer_result` still ok; card collapses to the terminal's answer when the tool_result arrives. |
| Old app (protocol 8) vs new companion | New frames are only sent on request; `ts`, `images`, `hasMore` are ignored by the v1 parser. |

## 7. Testing

- **Mod:** transcript parsing and filtering (isMeta, sidechain, non-message types, bad lines, compaction), `ts`, image extraction and ids, chunking under the byte budget, fallback path, transcript path refresh from classic events, question event (Path A) — via `claude plugin test`.
- **Companion:** chunked snapshot swap semantics, ring 5000, `chat_history` paging, image LRU + `chat_image`, answer store + long-poll (Path A) or key-sequence mapping (Path B).
- **App:** reducer (paging prepend/anchor data, stale epoch pages, `ts`, image cache state, question state), protocol parsing; live on the emulator: Markdown, long-press copy, timestamps, an image Read by Claude, scroll-to-top paging in a long session, an AskUserQuestion answered from the phone.

## Out of scope

Permission/approval prompts (still a v2 candidate), editing/deleting messages, image upload from the phone, persisting history in the companion across restarts.
