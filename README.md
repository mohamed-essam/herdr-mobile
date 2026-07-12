# herdr-mobile

<p align="center">
  Monitor and unblock your <a href="https://herdr.dev">herdr</a> agents from an Android phone.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-666666?labelColor=333333" alt="AGPL-3.0 license" /></a>
  <a href="https://github.com/mohamed-essam/herdr-mobile/actions/workflows/ci.yml"><img src="https://github.com/mohamed-essam/herdr-mobile/actions/workflows/ci.yml/badge.svg" alt="CI status" /></a>
  <a href="https://github.com/mohamed-essam/herdr-mobile/releases/latest"><img src="https://img.shields.io/github/v/release/mohamed-essam/herdr-mobile?label=release&labelColor=333333&color=666666" alt="latest release" /></a>
  <a href="https://github.com/mohamed-essam/herdr-mobile/stargazers"><img src="https://img.shields.io/github/stars/mohamed-essam/herdr-mobile?labelColor=333333&color=666666&logo=github" alt="GitHub stars" /></a>
</p>

---

[herdr](https://herdr.dev) is an agent multiplexer that lives in your terminal.
**herdr-mobile** is an unofficial companion that puts the herd in your pocket: see
which agents are blocked, working, or done — and unblock them — without walking back
to your desk.

- **every agent at a glance** — a live, repo-grouped dashboard of blocked / working /
  done, mirrored from herdr's socket API.
- **get pinged when it matters** — a push notification the moment an agent is blocked
  or finishes, delivered over [UnifiedPush](https://unifiedpush.org) (no Google
  dependency required).
- **quick-reply** — answer a blocked agent's prompt straight from the notification or
  the dashboard.
- **a real terminal when you need it** — attach to any pane over a remote PTY bridge
  (Termux VT), with a Catppuccin true-black theme built for a phone screen.

> **Unofficial project.** herdr-mobile is not affiliated with or endorsed by the herdr
> project. It talks to herdr only through its public socket API.

## Architecture

Two pieces talk over your private network:

```
  ┌─────────────┐   NDJSON / Unix socket   ┌──────────────────┐   JSON / WebSocket   ┌───────────────┐
  │    herdr    │ ───────────────────────► │  herdr-mobiled   │ ───────────────────► │  Android app  │
  │  (your host)│                          │  (Go companion)  │ ◄─────────────────── │ (Kotlin/Compose)
  └─────────────┘                          └──────────────────┘    input / RPC       └───────────────┘
                                                    │
                                                    └──► UnifiedPush ──► phone notifications
```

- **`companion/`** — a small Go daemon (`herdr-mobiled`) that runs on your herdr host,
  subscribes to herdr's socket API, exposes a WebSocket API over your Tailscale network,
  and pushes notifications when an agent needs you.
- **`app/`** — the Android app (Kotlin + Jetpack Compose): the dashboard, quick-reply,
  and the embedded terminal.

## Security

**Read this before you expose the companion.** The v1 companion API has **no
authentication** and can **send input to your terminals**. Treat it like an open door to
your shell.

- Bind `herdr-mobiled` only to a **private address** — your [Tailscale](https://tailscale.com)
  tailnet IP is the intended setup. **Never** bind it to a public interface or `0.0.0.0`.
- The daemon prints a warning if you bind to a non-loopback address, as a reminder to
  keep it on a trusted network.

See [`SECURITY.md`](SECURITY.md) for the full threat model and how to report a
vulnerability.

## Install

### Companion (`herdr-mobiled`)

Grab a prebuilt binary from the [latest release](https://github.com/mohamed-essam/herdr-mobile/releases/latest),
or build from source:

```bash
cd companion
go build -o ~/.local/bin/herdr-mobiled ./cmd/herdr-mobiled

# Bind to your tailnet IP so the API is reachable only on your tailnet:
herdr-mobiled --listen "$(tailscale ip -4):8787"
```

To run it as a user service, see [`companion/deploy/`](companion/deploy/).

### App

Download `app-debug.apk` from the [latest release](https://github.com/mohamed-essam/herdr-mobile/releases/latest)
and install it (you'll need to allow installs from unknown sources), or build it
yourself:

```bash
cd app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug
# → app/app/build/outputs/apk/debug/app-debug.apk
```

Point the app at `ws://<your-tailnet-ip>:8787/` and, optionally, a UnifiedPush
distributor (e.g. [ntfy](https://ntfy.sh)) for notifications.

## Development

```bash
# Companion
cd companion && go test ./...

# App (unit tests + debug build)
cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Requirements: Go 1.23+, JDK 17, Android SDK (compileSdk 36). Design notes and specs for
every feature live under [`docs/`](docs/).

## Contributing

Issues and pull requests are welcome — please read [`CONTRIBUTING.md`](CONTRIBUTING.md)
first.

## License

herdr-mobile is licensed under **AGPL-3.0-or-later** — the same license family as herdr
itself. See [`LICENSE`](LICENSE). Bundled third-party components (the Termux terminal
emulator, JetBrains Mono) are documented in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
