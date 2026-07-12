# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

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
