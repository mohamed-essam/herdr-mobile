# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

- Slash commands from the chat (companion protocol 12): typing `/` in the composer opens a picker of the session's
  commands and skills (prefix matches first, then substring); a tap fills in `/name `. A phone message naming a
  listed command runs it through `$.command.run` instead of being submitted as a prompt; the engine echoes the
  command as a user row, and any text it prints follows as a `command_output` event
  (`{uuid, command, text, isError?}`; `isError` carries the engine's refusal). The herdr-chat plugin lists commands
  with `$.command.list()` (re-checked every 30 s) and queues a `{"type":"commands","commands":[{name, description,
  source}]}` control after each resync and on change; the companion keeps it per pane (across epochs), adds
  `commands` to main `chat_snapshot`s (omitted when the mod sent none) and sends a new `chat_commands` frame on
  change. Older mods send no list and the app shows no picker. Update both the companion and the herdr-chat plugin.
- The chat composer's unsent text is kept per pane while the app runs: leaving the pane, flipping to the terminal
  or opening a thread no longer clears it.
- A pending AskUserQuestion sheet can be minimized ("▾ hide", or the handle) to a one-line bar, so the chat can be
  read at full size; the bar's "Answer" brings the sheet back with the picks made so far. A new question opens
  expanded.

- Context window and 5h/7d rate limits (companion protocol 11): the dashboard shows the account's 5-hour and 7-day
  meters with their reset times in a strip under the stat tiles, and a thin context bar under each Claude session
  row; the chat, terminal and thread headers show `ctx … · 5h … · 7d …` on one line. The herdr-chat plugin reads
  the status-line figures (`session.measure`, seeded from `$.session.usage()`) and adds `usage`
  (`{context?: {percent, tokens?, window}, limits: [{kind, percentUsed, resetsAt?}], limitsAt}`, only
  `five_hour`/`seven_day`; `limitsAt` is when the limits were measured, epoch ms) to every `/sync` body. Pane state gains `context` (`{percent, tokens, window}`, omitted while no herdr-chat mod
  is live). New frame `limits` (`{"t":"limits","limits":[{kind, percentUsed, resetsAt}],"observedAt":ms}`), sent
  when the reading changes (or its age advances by 60 s) and in the connect burst after `workspaces`. The newest
  measurement across panes wins (by `limitsAt`), so idle panes resending older readings don't flap it. The companion persists it to `<state dir>/limits.json` (new `--state-dir` flag,
  default `$XDG_STATE_HOME/herdr-mobile`, else `~/.local/state/herdr-mobile`, created 0700) so the strip survives
  a restart; the app dims a reading older than 5 minutes ("as of … ago") and shows `—` for a window whose reset
  time has passed (and hides the strip once every window has reset on a stale reading). Update both the companion and the herdr-chat plugin; older apps ignore the new frame and field.

- Chat view v2 (companion protocol 10): subagents and workflow agents show as cards under the Agent/Workflow call
  that started them, with live status and activity; tapping one opens a read-only thread (nested subagents stack,
  back walks up). A background-tasks strip opens a sheet of shell/subagent/workflow/monitor tasks with status and
  duration. Pane state gains `bgRunning` (running background task count, omitted when 0), shown as a "⟳ N"
  dashboard badge. Wire: `chat_open`/`chat_close`/`chat_history` take an optional `agentId`; `chat_snapshot` gains
  `agentId`/`missing` and, on main snapshots, `agents` and `tasks`; `chat_event` and `chat_history_page` gain
  `agentId`; new frames `chat_agent` (`{agent}` or `{agentId, removed:true}`) and `chat_tasks`. The herdr-chat
  plugin sends v2 traffic only once the companion's `/sync` reply carries `"threads": true`. Limits: 1000 events
  per thread, 20 threads per pane, newest 20 agents replayed on resync; finished tasks drop off after 10 minutes;
  background shells and workflows started before a plugin reload aren't recovered. Update both the companion and
  the herdr-chat plugin; an older app keeps working against a newer companion.
- Chat view for Claude Code panes via the new herdr-chat Claude Code plugin (companion protocol 8).
- Chat view v1.1 (companion protocol 9): Markdown-rendered assistant messages, long-press to select and copy,
  timestamps, and history rebuilt from the session transcript (5000 events kept per pane; the app shows the latest
  300 and loads older pages as you scroll up). Images Claude reads and images in your prompts render inline with
  full-screen pinch-zoom (latest 30 per pane kept). `AskUserQuestion` shows a question card you can answer from the
  phone or the terminal. Only http, https and mailto links open. Update both the companion and the herdr-chat
  plugin; an older app keeps working against a newer companion.
- Pane state carries what a chat-capable agent is doing: `activity` (`{kind, tool?, text, ts}`, kind one of
  `tool`/`text`/`user`/`question`/`notice`, text a one-line summary of at most 120 characters, ts epoch ms) and
  `ask` (`{toolUseId, questions}`, the newest pending `AskUserQuestion`, answerable with `chat_answer`). Both are
  omitted while no herdr-chat mod is live, so older apps are unaffected.
- Chat events with images carry `imageSizes` (`{id: [width, height]}` in pixels, read from the PNG, JPEG, GIF or
  WebP header by the herdr-chat plugin), so the app reserves each image's space before it loads and the list doesn't
  jump. Optional: events without it fall back to a placeholder.

## [1.0.0] - 2026-07-12

Initial public release.

### Added
- **Live agent dashboard** — a repo-grouped tree of workspaces, tabs, and panes mirrored
  from herdr's socket API, sorted by attention (blocked → done → rest) and recency.
- **Push notifications** over [UnifiedPush](https://unifiedpush.org) when an agent is
  blocked or finishes, with auto-dismiss when the agent resumes.
- **Quick-reply** to a blocked agent from the dashboard.
- **Embedded terminal** — attach to any pane over a remote PTY bridge (Termux VT) with a
  Catppuccin true-black theme, an on-screen key bar with modifier keys, IME support, and a
  reconnecting overlay.
- **Structural actions** — create, rename, close, and move workspaces, tabs, and panes;
  a searchable New Agent picker with recently-used agents and descriptions.
- **Go companion daemon** (`herdr-mobiled`) — subscribes to herdr, serves the app over
  WebSocket, and warns when bound to a non-loopback address.

### Security
- The v1 companion API has no authentication and can send input to your terminals; it is
  intended to run only on a trusted private network (e.g. Tailscale). See
  [`SECURITY.md`](SECURITY.md).

[1.0.0]: https://github.com/mohamed-essam/herdr-mobile/releases/tag/v1.0.0
