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

### Chat view for Claude Code panes (optional)

herdr-mobile can show Claude Code panes as a chat instead of a terminal. It needs
the `herdr-chat` Claude Code plugin (Claude Code 2.1.289 or newer), which streams
the conversation to the companion over a private Unix socket.

    claude plugin marketplace add /path/to/herdr-mobile/mod
    claude plugin install herdr-chat@herdr-mobile

Restart running Claude sessions (or run `/reload-plugins` in them). Claude
sessions started inside herdr panes then open in chat on the phone; the top-bar
button switches to the terminal. Sessions outside herdr are unaffected.

The companion listens on `$XDG_RUNTIME_DIR/herdr-mobile/chat.sock` by default.
Set `HERDR_MOBILE_CHAT_SOCK` to override this path, or pass `--chat-socket ''`
to disable chat. If `HERDR_MOBILE_CHAT_SOCK` is not set and `$XDG_RUNTIME_DIR` is
unavailable, the chat view is off (there is no `/tmp` fallback). A custom socket
path must be in a directory only you can access (mode 0700 or stricter, owned by
you); otherwise the companion refuses to listen and logs why. Set
`HERDR_MOBILE_CHAT_SOCK` in the environment where Claude runs. If you pass
`--chat-socket <path>` to the companion, set `HERDR_MOBILE_CHAT_SOCK` to the same
path in the environment Claude runs in, since the plugin cannot see the flag.

The chat shows assistant replies as Markdown (long-press to select and copy) with
timestamps, and loads older history as you scroll to the top: the companion keeps
the last 5000 events per pane and the app shows the latest 300 first. Images Claude
reads, and images in your prompts, render inline; tap one for full-screen
pinch-zoom. The companion keeps the latest 30 images (up to 40 MB) per pane, and
older ones, like any image over about 2.6 MB (3.5 MB base64, never sent), show
"image unavailable". When Claude asks a question (`AskUserQuestion`) the phone
shows a question card; answer from the phone or the terminal, whichever comes
first, and the card collapses to the answer. Only `http`, `https` and `mailto`
links open (in a browser).

Subagents and workflow agents appear as cards under the Agent or Workflow call that
started them, with a live status and activity line. Tap one to open its read-only
thread; nested subagents stack, and back walks up. A background-tasks strip
("⟳ 2 running · 1 done") opens a sheet listing each shell, subagent, workflow and
monitor task with its status and duration; tapping a subagent or workflow task
scrolls to its card. The dashboard shows a "⟳ N" badge on panes with background
tasks running. The companion keeps up to 1000 events per thread and 20 threads per
pane, finished tasks drop off after 10 minutes, and background shells and workflows
that started before a plugin reload aren't recovered.

The plugin also reports each session's context window and the account's 5-hour and
7-day rate-limit windows (the figures Claude Code's status line shows). The dashboard
shows the 5h and 7d meters with their reset times under the stat tiles and a thin
context bar under each Claude session row; the chat and terminal headers show
`ctx 61% · 5h 42% ↻2h13m · 7d 18% ↻Thu 14:00`. Only panes running Claude with the
plugin have a context reading. Rate limits are only reported on a Claude
subscription, after the first API response. A reading older than 5 minutes is dimmed
("as of 40m ago"). The companion keeps the last rate-limit reading in
`limits.json` under its state directory (`--state-dir`, default
`$XDG_STATE_HOME/herdr-mobile`, falling back to `~/.local/state/herdr-mobile`), so
the strip survives a restart. If panes on different Claude accounts report
different readings, the strip follows whichever was measured last.

Chat view v2 uses companion protocol 10; context and rate limits use protocol 11.
Update both the companion (rebuild and restart it) and the plugin (`/reload-plugins`
in running Claude sessions); an older app keeps working against a newer companion,
and a newer plugin keeps working against an older companion.

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

## Release to internal TestFlight

How to ship the iOS app (`ios/`) to your own team through TestFlight. Internal testing only:
no App Store review, and only people on your App Store Connect team can install it.

### Prerequisites

- **A paid Apple Developer Program team.** A free Apple ID can't upload to TestFlight.
- **An App Store Connect app record** for the bundle ID `dev.instabug.herdr.mobile`:
  1. [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list)
     → Identifiers → **+** → App IDs → App → Explicit Bundle ID `dev.instabug.herdr.mobile`,
     no capabilities.
  2. [App Store Connect](https://appstoreconnect.apple.com) → Apps → **+** → New App → iOS,
     any name nobody has taken, that Bundle ID, any SKU.
- **An App Store Connect team API key with the Admin role.** App Store Connect → Users and
  Access → Integrations → App Store Connect API → Team Keys → **+**, Access: **Admin**. Note the
  **Key ID** and the **Issuer ID** shown on that page, and download the `.p8` file (Apple
  lets you download it once). Keep the `.p8` outside this repo, e.g. `~/.appstoreconnect/`.
  The herdr app's existing key (the `.p8`, its Key ID and the Issuer ID) is in 1Password with
  yousefhamza; ask them for it instead of making a new one.
- **Xcode 26.6.** If `xcode-select -p` points at another Xcode, prefix each command below with
  `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` to run it with Xcode 26.6
  without changing your default, e.g.
  `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -version`.

### Steps

1. **Set up the Luciq token file** (crash and bug reports). Copy the example and fill in the
   two app tokens from the Luciq dashboard (Settings → SDK Integration):

   ```bash
   cp ios/Config/Luciq.local.xcconfig.example ios/Config/Luciq.local.xcconfig
   # edit it: LUCIQ_APP_TOKEN_DEBUG = <development app token>
   #          LUCIQ_APP_TOKEN_RELEASE = <production app token>
   ```

   `ios/Config/Luciq.local.xcconfig` is gitignored; never commit it. Debug builds use the
   development token, Release builds (and so TestFlight) the production one. Without the file
   the app still builds and runs, with Luciq off.

2. **Check the release build** without signing or uploading anything:

   ```bash
   scripts/testflight-ios.sh --check
   ```

   It archives the Release build unsigned into `build/testflight/`, prints one `ok` or `FAIL`
   line per check (app icon, export compliance, privacy manifest, the Luciq production token,
   no development token in the app), and ends with `All checks passed.` Without the token file
   it prints `ok   LuciqAppToken empty: no production token, Luciq off`.

3. **Archive, sign and upload:**

   ```bash
   ASC_KEY_PATH=$HOME/.appstoreconnect/AuthKey_ABC123DEFG.p8 \
   ASC_KEY_ID=ABC123DEFG \
   ASC_ISSUER_ID=00000000-0000-0000-0000-000000000000 \
   scripts/testflight-ios.sh
   ```

   (Placeholders: use your own key path, Key ID and Issuer ID.) It runs the same checks on the
   signed archive, then uploads it and prints `Uploaded. The build shows in TestFlight once App
   Store Connect has processed it.` Processing takes a few minutes; App Store Connect emails you
   when the build is ready. Run it again for each new build: Xcode raises the build number on
   upload.

   Uploading from the command line needs this API key. Being signed in to Xcode with your Apple
   ID is not enough: `xcodebuild` then fails with `Failed to find an account with App Store
   Connect access`. Without the key, use the Xcode alternative below.

4. **Add internal testers** once, in App Store Connect → Apps → your app → TestFlight →
   Internal Testing → **+**: create a group, tick **Enable automatic distribution**, and add
   testers (they must be users on your team, under Users and Access). Every processed build
   then reaches them on its own.

5. **Install.** Each tester installs the **TestFlight** app from the App Store, opens the email
   invite (or the app), and taps **Install** next to herdr.

**Xcode alternative to steps 2–3.** Open `ios/HerdrMobile.xcodeproj`, pick **Any iOS Device
(arm64)** as the destination, then **Product → Archive**. When the Organizer opens, select the
archive → **Distribute App** → **TestFlight Internal Only** → **Distribute**. This still needs
the token file from step 1 (without it the build has Luciq off), and it skips the script's
checks.

## Contributing

Issues and pull requests are welcome — please read [`CONTRIBUTING.md`](CONTRIBUTING.md)
first.

## License

herdr-mobile is licensed under **AGPL-3.0-or-later** — the same license family as herdr
itself. See [`LICENSE`](LICENSE). Bundled third-party components (the Termux terminal
emulator, JetBrains Mono) are documented in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
