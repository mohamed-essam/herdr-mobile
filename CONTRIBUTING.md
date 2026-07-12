# Contributing to herdr-mobile

Thanks for your interest. herdr-mobile is a small, opinionated companion for
[herdr](https://herdr.dev), and contributions that keep it focused and well-built are
welcome.

## Before you start

- **Bugs:** open an issue using the bug report template with a clear reproduction.
- **Features / larger changes:** open an issue to discuss the idea before writing a big
  PR, so we can agree on scope and direction first.
- **Understand your code.** Using AI to help write code is fine — submitting code you
  can't explain is not. Be ready to describe what your change does and how it behaves at
  the edges.

## Project layout

- `companion/` — the Go daemon (`herdr-mobiled`). Talks to herdr's socket API and serves
  the app over WebSocket.
- `app/` — the Android app (Kotlin + Jetpack Compose), plus the vendored Termux
  `terminal-emulator` / `terminal-view` modules.
- `docs/` — design specs and implementation plans for each feature.

## Development

Requirements: Go 1.23+, JDK 17, Android SDK (compileSdk 36).

```bash
# Companion: build + test
cd companion
go build ./...
go test ./...
gofmt -l .          # should print nothing

# App: unit tests + debug build
cd app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

CI runs the same Go and Android checks on every pull request; please make sure they pass
locally first.

## Pull requests

- Keep PRs focused — one logical change per PR.
- Add or update tests for behavior changes.
- Follow the existing code style (`gofmt` for Go; match the surrounding Kotlin/Compose
  idiom).
- Note user-facing changes in [`CHANGELOG.md`](CHANGELOG.md) under `## Unreleased`.
- Don't commit secrets, tailnet IPs, or personal paths.

## Security

Please report security vulnerabilities privately — see [`SECURITY.md`](SECURITY.md).
Do not open a public issue for a vulnerability.

## License

By contributing, you agree that your contributions are licensed under the project's
[AGPL-3.0-or-later](LICENSE) license.
