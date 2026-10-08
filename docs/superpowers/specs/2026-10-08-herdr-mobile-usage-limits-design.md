# herdr-mobile: context window and 5h/7d rate limits

Date: 2026-10-08. Builds on the chat view (2026-10-04, v1.1) and chat v2 (2026-10-05).

## Goal

See, from the phone, how full each Claude session's context window is and how
much of the account's 5-hour and 7-day rate-limit windows is used, with when
each window resets — without opening a terminal.

- **Dashboard:** 5h and 7d meters (with reset times) in a strip under the stat
  tiles; a thin context bar under each Claude session row.
- **Chat and terminal views:** context, 5h and 7d (with reset times) on one meta
  line in the pane header.

Out of scope: push alerts at thresholds, per-category context breakdown, cost,
gateway `spend_limit` windows, multi-account disambiguation.

## Source of the figures

The Claude Code mod API (2.1.292) exposes the status line's figures:

- `$.session.usage()` → `{ startedAt, context: { tokens?, window, percent? },
  rateLimits: [{ kind, percentUsed, resetsAt? }], cost? }`. Free without
  `breakdown`.
- `session.measure` event → `{ context, rateLimits, cost?, changed }`, pushed after
  each main-thread turn and whenever a rate-limit window moves a whole point.
- `rateLimits` is empty off a subscription or before the first API response;
  `kind` is `five_hour`, `seven_day` or `spend_limit`; `resetsAt` is ISO 8601.

Only panes running Claude with the herdr-chat mod live have figures. A plain
shell pane has no context reading and shows no context bar.

## 1. Mod (`mod/herdr-chat`)

New `hooks/usage.ts`:

- `type Reading = { context?: { percent: number; tokens?: number; window: number };
  limits: { kind: 'five_hour' | 'seven_day'; percentUsed: number; resetsAt?: string }[] }`;
  `type Usage = Reading & { limitsAt: number }`.
- `limitsAt` is when the limits were measured (epoch ms). Each mod's limits are
  its own process's last API response, so the companion needs the measurement
  time to keep the newest reading across panes (see Account limits).
- `toUsage(u: SessionUsage | SessionMeasureInput): Reading` keeps `context` only when
  `percent` is defined, rounds `percent`, `tokens` and `window` to integers (the
  companion decodes them as ints; a fraction would fail the whole `/sync`), and
  keeps only the `five_hour` and `seven_day` windows. Callers add the stamp.

`register.ts`:

- `State.usage: Usage | null`; `State.usageGen: number`.
- `on('session.measure')` sets `s.usage = { ...toUsage(e), limitsAt: <now> }`
  (every measure follows an API response) and bumps `usageGen`, then `next(e)`.
- `session.start` and `resync` seed it from `$.session.usage()`, so a resumed,
  idle session has a reading before its next turn. The seed has no stamp of its
  own: `limitsAt` is the held reading's, else `SessionUsage.startedAt` (a lower
  bound on the reading's age). It writes only if `usageGen` is unchanged across
  its read, so a measure or reset that landed meanwhile wins. A failure is
  ignored (the next `session.measure` fills it).
- `session.end` with `clear`/`resume` keeps the held `limits` and `limitsAt`
  and drops only `context` (a `usage` without `context` clears the pane's
  context in the companion), and bumps `usageGen`.
- `tick` adds `usage: s.usage` to every `/sync` body while it is non-null, also
  on held (offline/building) heartbeats. It is ~200 bytes over a Unix socket
  once a second, so no diffing; the companion deduplicates. Body size
  accounting in `takeBody` includes it.
- An older companion ignores the field (Go's decoder ignores unknown fields).

## 2. Companion (`companion/`)

### Per-pane context

- `chatbridge.syncReq` gains `Usage *Usage` (`context`, `limits`, `limitsAt`
  — epoch ms, 0 from a mod that sends none).
- `chatbridge.pane` gains `context *Context` (`Percent int`, `Tokens int`,
  `Window int`), set from each `/sync` that carries `usage.context`.
- `Summary` gains `Context *Context`; `pane.summary()` includes it, so it is
  empty while the mod is not live — the same lifecycle as `Activity`.
- `state.Store.SetSummary(paneID, sum Summary)` replaces the positional
  `(activity, ask, bgRunning)` parameters, which this change would grow to four.
  `state.Pane` gains `Context *Context \`json:"context,omitempty"\``.
- Changes reach the app through the existing `pane_update` path.

### Account limits

- `limits.Tracker` keeps `Limits{ Windows []Window; ObservedAt int64 }`
  (`Window{Kind string; PercentUsed float64; ResetsAt string}`), fed by
  `Hub.SetOnLimits(func(ws, at))` for every `/sync` whose `usage.limits` is
  non-empty. The newest *measurement* wins across panes: a reading is taken only
  when its `usage.limitsAt` is newer than the held `ObservedAt`; an older or
  equal one is ignored (no state change, broadcast or save), so two panes
  resending their own last readings every second do not flap. `limitsAt == 0`
  (an older mod) is taken only when there is no reading, stamped now.
  `ObservedAt` is the reading's `limitsAt`. A taken reading is broadcast only
  when the windows change or `ObservedAt` advances by ≥ 60 s since the last
  broadcast, so the app's "as of" age stays accurate without a frame per second.
- Persisted to `<state dir>/limits.json` (write temp file + rename) on each
  broadcast, loaded at startup. State dir: new `--state-dir` flag, default
  `$XDG_STATE_HOME/herdr-mobile`, falling back to `~/.local/state/herdr-mobile`;
  created 0700. A missing or unreadable file means no reading.
- New frame `proto.Limits(l)`: `{"t":"limits","limits":[{kind,percentUsed,resetsAt}],"observedAt":ms}`.
  Sent to every client on broadcast and in the connect burst after
  `workspaces`, when a reading exists.
- `companionProtocol` 10 → 11. Older apps ignore the frame and the field.

Known limitation: if panes on different Claude accounts report different
readings, the strip follows whichever was measured last (stamped by the mod,
`usage.limitsAt`), not a per-account reading.

## 3. App (`app/`)

### Data

- `Protocol.kt`: `Pane.context: PaneUsage?` (`percent`, `tokens`, `window`);
  `Limits(windows: List<LimitWindow>, observedAt: Long)`,
  `LimitWindow(kind, percentUsed: Double, resetsAt: String?)`; parse the
  `limits` frame.
- `PaneRepository.limits: StateFlow<Limits?>`.

### Shared UI (`ui/UsageMeters.kt`)

- `usageColor(pct)`: below 60 neutral (`overlay2`), 60–84 `yellow`, 85+ `red`.
- `formatReset(kind, resetsAt, now)`: `five_hour` → `↻ 2h13m` (or `↻ 13m`);
  `seven_day` → `↻ Thu 14:00` in the phone's local time. Empty when unknown.
- `windowExpired(resetsAt, now)`: true once `resetsAt` has passed; the meter
  then shows `5h —` rather than a stale percentage.
- `limitsStale(observedAt, now)`: true when older than 5 minutes; the strip
  dims and appends `· as of 40m ago`.
- `LimitMeter(window, now)`: label, thin bar, percent, reset.
- `ContextBar(percent)`: 2 dp full-width bar, coloured by `usageColor`.

### Dashboard

- Under `StatTiles`: a strip with the `five_hour` and `seven_day` meters on two
  lines. Hidden while no reading has ever arrived, and when every shown window
  has reset and the reading is stale (`limitsWorthShowing`): an old persisted
  reading would only say `5h — · 7d —`. The header line's limits part follows
  the same rule.
- `PaneListRow`: a `ContextBar` across the bottom of the text column when
  `pane.context != null`.

### Chat and terminal

- `PaneHeader` gets a meta line under the breadcrumb:
  `ctx ▓▓▓░ 61% · 5h 42% ↻2h13m · 7d 18% ↻Thu 14:00`. The `ctx` part is
  omitted when the pane has no context reading; the line is omitted when there
  is neither context nor limits. Same staleness and expiry rules as the strip.
- Thread screens show the parent pane's figures.
- Relative times refresh with the existing `now` ticker.

## 4. Testing

- **Mod:** `toUsage` filtering (no percent → no context; `spend_limit`
  dropped) and rounding; `/sync` body carries `usage` once set and omits it
  before, held heartbeats included; seeded on `session.start` with
  `limitsAt = startedAt`; `session.measure` stamps `limitsAt`; a seed resolving
  after a measure does not overwrite it; `/clear` keeps limits, drops context,
  and the resync seed refills it. The test kit stubs `$.session.usage`.
- **Companion:** context set from `/sync`, cleared when the mod goes offline;
  `SetSummary` with the struct; limits newest-measurement-wins (alternating
  panes broadcast once; unstamped only when empty), persist and reload,
  broadcast only on change or ≥ 60 s age advance; `limits` frame in the connect
  burst; welcome says protocol 11.
- **App (JVM):** frame and field parsing; `formatReset` for both kinds;
  expiry; `usageColor` bands; staleness.
- **Live:** emulator + test companion on 127.0.0.1:8788 with a throwaway
  `--plugin-dir` Claude pane; confirm the strip, row bar and header line fill
  after one turn.
