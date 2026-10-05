# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

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
