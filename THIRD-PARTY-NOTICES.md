# Third-party notices

herdr-mobile is licensed under **AGPL-3.0-or-later** (see [`LICENSE`](LICENSE)).
It bundles the following third-party components, each under its own license.

## Termux terminal emulator and view

- **Modules:** `app/terminal-emulator/` (`com.termux.terminal`) and
  `app/terminal-view/` (`com.termux.view`)
- **Source:** [termux/termux-app](https://github.com/termux/termux-app)
- **License:** GPL-3.0-only. These modules incorporate code from Jack Palevich's
  *Terminal Emulator for Android*, originally released under Apache-2.0.
- **Copyright:** © Fredrik Fornwall and the Termux contributors; portions
  © Jack Palevich and the Android Open Source Project.

GPL-3.0 and Apache-2.0 are both compatible with this project's AGPL-3.0-or-later
license. The upstream files retain their original headers where present.

## JetBrains Mono

- **File:** `app/app/src/main/assets/fonts/JetBrainsMono-Regular.ttf`
- **Source:** [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono)
- **License:** SIL Open Font License 1.1 — full text in
  [`app/app/src/main/assets/fonts/OFL.txt`](app/app/src/main/assets/fonts/OFL.txt)

## Runtime dependencies

Go and Gradle dependencies (e.g. `github.com/coder/websocket`,
`github.com/creack/pty`, AndroidX, Jetpack Compose) are fetched at build time and
governed by their respective licenses as declared in `companion/go.mod` and the
Gradle version catalog (`app/gradle/libs.versions.toml`).
