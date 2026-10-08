# Context window + 5h/7d limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show each Claude session's context fill and the account's 5h/7d rate-limit usage (with reset times) on the phone: limits on the dashboard and in pane headers, context in pane headers and as a bar under each dashboard row.

**Architecture:** The herdr-chat mod listens to `session.measure` (seeded by `$.session.usage()`) and adds a `usage` field to every `/sync` body. The companion stores per-pane context in the existing pane summary (→ `pane_update`) and feeds rate-limit windows to a new `limits.Tracker` (newest wins, persisted to disk) that broadcasts a new `limits` frame. The app parses both and renders them via a shared `UsageMeters.kt`.

**Tech Stack:** TypeScript Claude Code hooks module (`claude plugin test`), Go 1.x companion (`go test`), Kotlin + Jetpack Compose app (JUnit via Gradle).

**Spec:** `docs/superpowers/specs/2026-10-08-herdr-mobile-usage-limits-design.md`

## Global Constraints

- Only `five_hour` and `seven_day` windows are carried; `spend_limit` is dropped in the mod.
- `companionProtocol` becomes **11**.
- New fields/frames are additive: `omitempty` in Go, defaults in Kotlin; older apps/companions/mods must keep working.
- Colour bands: below 60 neutral (`overlay2`), 60–84 `yellow`, 85+ `red`.
- Staleness: limits older than **5 minutes** (by `observedAt`) are dimmed with `· as of <age> ago`.
- A window whose `resetsAt` has passed renders as `5h —` / `7d —`.
- Reset format: `five_hour` → `↻ 2h13m` / `↻ 13m`; `seven_day` → `↻ Thu 14:00` (local time).
- Limits broadcast only when the windows change or `observedAt` advanced ≥ 60 s since the last broadcast.
- State dir: `--state-dir`, default `$XDG_STATE_HOME/herdr-mobile`, else `~/.local/state/herdr-mobile`, created 0700; file `limits.json` written temp + rename.
- Mod engine rule: helpers that take `$` must be top-level `function` declarations in `register.ts` (not arrows/consts), or the module fails to load at runtime.

## Review Focus

1. **`resetsAt` with a non-`Z` offset** (`2026-10-08T19:40:00+03:00`) — must parse; covered in Task 7 (`formatReset` test with offset).
2. **Mod goes offline** — pane context must disappear from the dashboard row and header (summary empties when not live); covered in Task 3 test `TestContextClearedWhenModGoesOffline`.
3. **Corrupt or missing `limits.json`** — companion must start with no reading, not crash; covered in Task 4 test `TestLoadIgnoresCorruptFile`.
4. **`$.session.usage()` throws or returns nothing** (test kit, older engine) — mod must keep syncing without `usage`; covered in Task 1 test "no usage hook: syncs carry no usage".
5. **A `/sync` with `usage` but no `context`** (session before its first response) — must clear the pane's old context, not keep it; covered in Task 3 test `TestUsageWithoutContextClearsContext`.

---

### Task 1: Mod — measure, seed and send `usage`

**Files:**
- Create: `mod/herdr-chat/hooks/usage.ts`
- Modify: `mod/herdr-chat/hooks/register.ts` (State type ~line 46; `takeBody` ~line 474; `tick` body ~line 562; `resync` ~line 275; `session.end` ~line 803; `register` state literal ~line 752; add `session.measure` hook)
- Create: `mod/herdr-chat/tests/usage.test.ts`
- Modify: `mod/herdr-chat/tests/register.test.ts` (`state()` helper line 8; `world()` gets a `usage` option; new tests)

**Interfaces:**
- Produces (wire, consumed by Task 3): `/sync` body field `usage?: { context?: { percent: number; tokens?: number; window: number }; limits: { kind: 'five_hour' | 'seven_day'; percentUsed: number; resetsAt?: string }[] }`.
- Produces (TS): `export type Usage`, `export function toUsage(u): Usage | null`; `State.usage: Usage | null`.

- [ ] **Step 1: Write the failing unit test** — `mod/herdr-chat/tests/usage.test.ts`:

```ts
import { describe, expect, test } from 'claude-code/testing'
import { toUsage } from '../hooks/usage'

describe('toUsage', () => {
  test('keeps context with a percent and only the 5h/7d windows', () => {
    expect(toUsage({
      context: { tokens: 122000, window: 200000, percent: 61 },
      rateLimits: [
        { kind: 'five_hour', percentUsed: 42.5, resetsAt: '2026-10-08T19:40:00Z' },
        { kind: 'seven_day', percentUsed: 18, resetsAt: '2026-10-15T14:00:00Z' },
        { kind: 'spend_limit', percentUsed: 3 },
      ],
    })).toEqual({
      context: { percent: 61, tokens: 122000, window: 200000 },
      limits: [
        { kind: 'five_hour', percentUsed: 42.5, resetsAt: '2026-10-08T19:40:00Z' },
        { kind: 'seven_day', percentUsed: 18, resetsAt: '2026-10-15T14:00:00Z' },
      ],
    })
  })

  test('no percent yet: no context', () => {
    expect(toUsage({ context: { window: 200000 }, rateLimits: [] })).toEqual({ limits: [] })
  })

  test('nothing to read: null', () => {
    expect(toUsage(undefined)).toBeNull()
    expect(toUsage(null)).toBeNull()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `claude plugin test mod/herdr-chat`
Expected: FAIL — cannot resolve `../hooks/usage`.

- [ ] **Step 3: Implement `hooks/usage.ts`**

```ts
// The status line's figures as the companion takes them (see the /sync
// `usage` field): the live context window's fill and the account's 5-hour
// and 7-day rate-limit windows. A gateway's spend_limit is not carried.
export type UsageWindow = { kind: 'five_hour' | 'seven_day'; percentUsed: number; resetsAt?: string }
export type Usage = {
  context?: { percent: number; tokens?: number; window: number }
  limits: UsageWindow[]
}

type Measured = {
  context?: { tokens?: number; window: number; percent?: number }
  rateLimits?: readonly { kind: string; percentUsed: number; resetsAt?: string }[]
} | null | undefined

// `$.session.usage()`'s answer or a `session.measure` event, as sent. Null
// when there is nothing to read (an engine without the op).
export function toUsage(u: Measured): Usage | null {
  if (!u) return null
  const out: Usage = { limits: [] }
  const c = u.context
  if (c && typeof c.percent === 'number') {
    out.context = { percent: c.percent, ...(typeof c.tokens === 'number' ? { tokens: c.tokens } : {}), window: c.window }
  }
  for (const r of u.rateLimits ?? []) {
    if (r.kind !== 'five_hour' && r.kind !== 'seven_day') continue
    out.limits.push({ kind: r.kind, percentUsed: r.percentUsed, ...(r.resetsAt ? { resetsAt: r.resetsAt } : {}) })
  }
  return out
}
```

- [ ] **Step 4: Run unit tests** — `claude plugin test mod/herdr-chat` → the three `toUsage` tests PASS.

- [ ] **Step 5: Write failing integration tests** in `tests/register.test.ts`.

First update the `state()` helper on line 8 to add `usage: null` at the end of the object literal. Then extend `world()`'s `opts` with `usage?: unknown` and, right after the `on('session.id', ...)` line, add:

```ts
  if (opts.usage !== undefined) on('session.usage', () => ({ value: opts.usage as never }))
```

Add these tests inside `describe('herdr-chat', ...)`:

```ts
  const USAGE = {
    startedAt: 0,
    context: { tokens: 50000, window: 200000, percent: 25 },
    rateLimits: [{ kind: 'five_hour', percentUsed: 10, resetsAt: '2026-10-08T19:40:00Z' }],
  }

  test('the resync seeds usage from session.usage and every sync carries it', async ($, on) => {
    const w = world(on, { usage: USAGE })
    await start($)
    await w.clock.advance(3000)
    const last = w.syncs[w.syncs.length - 1] as any
    expect(last.usage).toEqual({
      context: { percent: 25, tokens: 50000, window: 200000 },
      limits: [{ kind: 'five_hour', percentUsed: 10, resetsAt: '2026-10-08T19:40:00Z' }],
    })
  })

  test('session.measure replaces the reading', async ($, on) => {
    const w = world(on, { usage: USAGE })
    await start($)
    await w.clock.advance(2000)
    await $.session.measure({
      context: { tokens: 160000, window: 200000, percent: 80 },
      rateLimits: [{ kind: 'seven_day', percentUsed: 30 }],
      changed: ['context', 'rateLimits'],
    })
    await w.clock.advance(1000)
    const last = w.syncs[w.syncs.length - 1] as any
    expect(last.usage).toEqual({ context: { percent: 80, tokens: 160000, window: 200000 }, limits: [{ kind: 'seven_day', percentUsed: 30 }] })
  })

  test('no usage hook: syncs carry no usage', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(3000)
    expect(w.syncs.length).toBeGreaterThan(0)
    for (const s of w.syncs as any[]) expect(s.usage).toBeUndefined()
  })
```

- [ ] **Step 6: Run to verify they fail** — `claude plugin test mod/herdr-chat` → the first two FAIL (`usage` undefined). The third may pass already; that is fine.

- [ ] **Step 7: Wire it into `register.ts`**

Import at the top with the other hook imports:

```ts
import { toUsage, type Usage } from './usage'
```

Add to the `State` type (after `threadsMissed: boolean`):

```ts
  // The latest status-line figures (session.measure, seeded by
  // $.session.usage() at each resync); sent on every /sync while set.
  usage: Usage | null
```

Add `usage: null,` to the state literal in `register` (after `threadsMissed: false,`).

Add a top-level function (next to `resync`):

```ts
// Reads the status line's figures once; a session.measure that landed
// meanwhile is newer and wins. An engine without the op leaves usage unset.
async function seedUsage($: EngineInterface, s: State) {
  try {
    const u = toUsage(await $.session.usage())
    if (!s.usage) s.usage = u
  } catch {
    // the next session.measure fills it
  }
}
```

In `resync`, after `s.building = true`, add `void seedUsage($, s)`.

In `session.end`'s `clear`/`resume` branch, add `s.usage = null` (the new session's context starts over; the resync re-seeds it).

In `takeBody`, change the initial size line to count the field:

```ts
  let bytes = utf8Bytes(JSON.stringify({ paneId: s.paneId, sessionId: s.sessionId, events: [], images: {}, ...(s.usage ? { usage: s.usage } : {}) }))
```

In `tick`, extend the body object (after the `images` spread):

```ts
        ...(s.usage ? { usage: s.usage } : {}),
```

Register the hook after `session.append`'s:

```ts
  on('session.measure', async ($, e, next) => {
    s.usage = toUsage(e)
    return next(e)
  })
```

- [ ] **Step 8: Run all mod tests** — `claude plugin test mod/herdr-chat` → all PASS (the 192 earlier ones plus the 6 new ones).

- [ ] **Step 9: Commit**

```bash
git add mod/herdr-chat/hooks/usage.ts mod/herdr-chat/hooks/register.ts mod/herdr-chat/tests/usage.test.ts mod/herdr-chat/tests/register.test.ts
git commit -m "feat(mod): send context and 5h/7d usage on every /sync"
```

---

### Task 2: Companion state — `Pane.Context` and `SetSummary(Summary)`

**Files:**
- Modify: `companion/internal/state/store.go` (Pane ~line 34; `summary` type ~line 54; `Apply` line ~145; `SetSummary` ~line 202)
- Modify: `companion/internal/state/store_test.go` (all `SetSummary(` calls: lines 226, 230, 237, 257, 274, 278, 284)
- Modify: `companion/internal/engine/engine.go:68-72`

**Interfaces:**
- Produces: `state.Context{Percent int; Tokens int; Window int}` (json `percent`, `tokens,omitempty`, `window`); `state.Pane.Context *Context` (json `context,omitempty`); `state.Summary{Activity *Activity; Ask *Ask; BgRunning int; Context *Context}`; `func (s *Store) SetSummary(paneID string, sum Summary) (Pane, bool)`.

- [ ] **Step 1: Update the existing tests to the new signature and add a context test** — in `store_test.go`, rewrite each call: `s.SetSummary("w1:p1", act, ask, 0)` → `s.SetSummary("w1:p1", Summary{Activity: act, Ask: ask})`; `s.SetSummary("w1:p1", nil, nil, 2)` → `s.SetSummary("w1:p1", Summary{BgRunning: 2})`; `s.SetSummary("w1:p1", nil, nil, 0)` → `s.SetSummary("w1:p1", Summary{})`; and so on for every call listed above. Then add:

```go
func TestSetSummaryContext(t *testing.T) {
	s := NewStore()
	s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"}))
	p, changed := s.SetSummary("w1:p1", Summary{Context: &Context{Percent: 61, Tokens: 122000, Window: 200000}})
	if !changed || p.Context == nil || p.Context.Percent != 61 {
		t.Fatalf("set: changed=%v pane=%+v", changed, p)
	}
	if _, again := s.SetSummary("w1:p1", Summary{Context: &Context{Percent: 61, Tokens: 122000, Window: 200000}}); again {
		t.Fatal("an equal context should report no change")
	}
	if ch, _ := s.Apply(infos(herdr.PaneInfo{PaneID: "w1:p1", WorkspaceID: "w1", Agent: "claude"})); len(ch) != 0 || s.Snapshot()[0].Context == nil {
		t.Fatalf("poll lost context: %+v", s.Snapshot())
	}
	if p, changed := s.SetSummary("w1:p1", Summary{}); !changed || p.Context != nil {
		t.Fatalf("clear: %v %+v", changed, p)
	}
	b, _ := json.Marshal(p)
	if strings.Contains(string(b), `"context"`) {
		t.Fatalf("nil context must be omitted: %s", b)
	}
}
```

(add `"strings"` to the imports if it is not there.)

- [ ] **Step 2: Run to verify failure** — `cd companion && go test ./internal/state/` → compile errors (`Summary`, `Context` undefined; wrong arg count).

- [ ] **Step 3: Implement in `store.go`**

Add to `Pane` after `BgRunning`:

```go
	// Context is the Claude session's context-window fill, from the
	// herdr-chat mod (nil while no mod is live). omitempty keeps older apps
	// unaffected.
	Context *Context `json:"context,omitempty"`
```

Add types (after `Ask`):

```go
// Context is a session's context-window fill: Percent of Window tokens used
// (Tokens when the engine reported them).
type Context struct {
	Percent int `json:"percent"`
	Tokens  int `json:"tokens,omitempty"`
	Window  int `json:"window"`
}

// Summary is what the herdr-chat mod reports for a pane's dashboard row.
type Summary struct {
	Activity  *Activity
	Ask       *Ask
	BgRunning int
	Context   *Context
}

func sameContext(a, b *Context) bool { return a == b || (a != nil && b != nil && *a == *b) }
```

Replace the private `summary` struct with the exported `Summary` everywhere (`s.summary map[string]Summary`; in `Apply` set `np.Activity, np.Ask, np.BgRunning, np.Context = sum.Activity, sum.Ask, sum.BgRunning, sum.Context`). Replace `SetSummary`:

```go
// SetSummary records the pane's latest summary (zero fields clear). Like
// SetChat it reports the updated pane and true only when a known pane's
// summary actually changed, and keeps a summary set before herdr reports
// the pane.
func (s *Store) SetSummary(paneID string, sum Summary) (Pane, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// Keep the stored pointers when the value is unchanged, so Apply's pane
	// comparison sees no change.
	old := s.summary[paneID]
	if sameActivity(old.Activity, sum.Activity) {
		sum.Activity = old.Activity
	}
	if sameAsk(old.Ask, sum.Ask) {
		sum.Ask = old.Ask
	}
	if sameContext(old.Context, sum.Context) {
		sum.Context = old.Context
	}
	if sum.Activity == nil && sum.Ask == nil && sum.BgRunning == 0 && sum.Context == nil {
		delete(s.summary, paneID)
	} else {
		s.summary[paneID] = sum
	}
	p, ok := s.panes[paneID]
	if !ok || (sameActivity(p.Activity, sum.Activity) && sameAsk(p.Ask, sum.Ask) && p.BgRunning == sum.BgRunning && sameContext(p.Context, sum.Context)) {
		return Pane{}, false
	}
	p.Activity, p.Ask, p.BgRunning, p.Context = sum.Activity, sum.Ask, sum.BgRunning, sum.Context
	s.panes[paneID] = p
	return p, true
}
```

`Apply` compares panes with `old != np`. That stays correct because `SetSummary` keeps the stored `Context` pointer when the value is unchanged.

- [ ] **Step 4: Update the engine caller** — `engine.go` lines 68-72 (Task 3 adds `s.Context` to `chatbridge.Summary`; until then pass nil):

```go
	e.hub.SetOnSummary(func(paneID string, s chatbridge.Summary) {
		sum := state.Summary{Activity: (*state.Activity)(s.Activity), Ask: (*state.Ask)(s.Ask), BgRunning: s.BgRunning}
		if p, changed := e.store.SetSummary(paneID, sum); changed {
			e.srv.Broadcast(proto.PaneUpdate(p))
		}
	})
```

- [ ] **Step 5: Run** — `cd companion && go vet ./... && go test ./...` → PASS.

- [ ] **Step 6: Commit**

```bash
git add companion/internal/state companion/internal/engine/engine.go
git commit -m "feat(companion): pane context in the store; SetSummary takes a Summary"
```

---

### Task 3: Companion chatbridge — read `usage` from `/sync`

**Files:**
- Create: `companion/internal/limits/window.go` (just the `Window` type, so chatbridge can use it; Task 4 fills the package)
- Modify: `companion/internal/chatbridge/hub.go` (Summary ~line 118; `empty`/`sameSummary` ~line 124; `pane` struct ~line 141; `Hub` struct + `NewHub` ~line 175; `summary()` ~line 441)
- Modify: `companion/internal/chatbridge/server.go` (`syncReq` ~line 89; `/sync` handler ~line 123)
- Modify: `companion/internal/engine/engine.go` (pass `Context` through)
- Create: `companion/internal/chatbridge/usage_test.go`

**Interfaces:**
- Consumes: `state.Summary.Context` (Task 2).
- Produces: `limits.Window{Kind string; PercentUsed float64; ResetsAt string}` (json `kind`, `percentUsed`, `resetsAt,omitempty`); `chatbridge.Context` (same layout as `state.Context`); `chatbridge.Usage{Context *Context; Limits []limits.Window}`; `chatbridge.Summary.Context *Context`; `func (h *Hub) SetUsage(paneID string, u *Usage)`; `func (h *Hub) SetOnLimits(fn func([]limits.Window))`.

- [ ] **Step 1: Create `companion/internal/limits/window.go`**

```go
// Package limits keeps the account's rate-limit reading (Claude's 5-hour
// and 7-day windows) as the herdr-chat mods last reported it.
package limits

// Window is one rate-limit window: Kind "five_hour" or "seven_day",
// PercentUsed 0-100, ResetsAt an ISO 8601 time (empty when unknown).
type Window struct {
	Kind        string  `json:"kind"`
	PercentUsed float64 `json:"percentUsed"`
	ResetsAt    string  `json:"resetsAt,omitempty"`
}
```

- [ ] **Step 2: Write failing tests** — `companion/internal/chatbridge/usage_test.go`:

```go
package chatbridge

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/limits"
)

func TestUsageContextReachesSummary(t *testing.T) {
	h := NewHub(nil)
	var log summaryLog
	log.record(h)
	h.SetUsage("w1:p1", &Usage{Context: &Context{Percent: 61, Tokens: 122000, Window: 200000}})
	h.SyncBody("w1:p1", "s1", nil, nil)
	if c := log.last(t).Context; c == nil || c.Percent != 61 || c.Window != 200000 {
		t.Fatalf("context: %+v", c)
	}
}

func TestUsageWithoutContextClearsContext(t *testing.T) {
	h := NewHub(nil)
	var log summaryLog
	log.record(h)
	h.SetUsage("w1:p1", &Usage{Context: &Context{Percent: 61, Window: 200000}})
	h.SyncBody("w1:p1", "s1", nil, nil)
	h.SetUsage("w1:p1", &Usage{})
	h.SyncBody("w1:p1", "s1", nil, nil)
	if c := log.last(t).Context; c != nil {
		t.Fatalf("want cleared, got %+v", c)
	}
}

func TestNilUsageKeepsContext(t *testing.T) {
	h := NewHub(nil)
	var log summaryLog
	log.record(h)
	h.SetUsage("w1:p1", &Usage{Context: &Context{Percent: 40, Window: 200000}})
	h.SyncBody("w1:p1", "s1", nil, nil)
	h.SetUsage("w1:p1", nil) // an older mod's body
	h.SyncBody("w1:p1", "s1", nil, nil)
	if c := log.last(t).Context; c == nil || c.Percent != 40 {
		t.Fatalf("context lost: %+v", c)
	}
}

func TestContextClearedWhenModGoesOffline(t *testing.T) {
	now := time.Unix(1000, 0)
	h := NewHub(func() time.Time { return now })
	var log summaryLog
	log.record(h)
	h.SetUsage("w1:p1", &Usage{Context: &Context{Percent: 61, Window: 200000}})
	h.SyncBody("w1:p1", "s1", nil, nil)
	now = now.Add(LiveWindow + time.Second)
	h.Tick()
	if c := log.last(t).Context; c != nil {
		t.Fatalf("offline pane kept context: %+v", c)
	}
}

func TestUsageLimitsGoToOnLimits(t *testing.T) {
	h := NewHub(nil)
	var mu sync.Mutex
	var got [][]limits.Window
	h.SetOnLimits(func(ws []limits.Window) { mu.Lock(); got = append(got, ws); mu.Unlock() })
	h.SetUsage("w1:p1", &Usage{})
	h.SetUsage("w1:p1", &Usage{Limits: []limits.Window{{Kind: "five_hour", PercentUsed: 42}}})
	mu.Lock()
	defer mu.Unlock()
	if len(got) != 1 || got[0][0].Kind != "five_hour" {
		t.Fatalf("onLimits calls: %+v", got)
	}
}

func TestSyncHandlerReadsUsage(t *testing.T) {
	h := NewHub(nil)
	var log summaryLog
	log.record(h)
	var limitsSeen []limits.Window
	h.SetOnLimits(func(ws []limits.Window) { limitsSeen = ws })
	body := `{"paneId":"w1:p1","sessionId":"s1","events":[],"usage":{"context":{"percent":25,"tokens":50000,"window":200000},"limits":[{"kind":"seven_day","percentUsed":18,"resetsAt":"2026-10-15T14:00:00Z"}]}}`
	rec := httptest.NewRecorder()
	h.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/sync", bytes.NewBufferString(body)))
	if rec.Code != 200 {
		t.Fatalf("status %d", rec.Code)
	}
	if c := log.last(t).Context; c == nil || c.Percent != 25 {
		t.Fatalf("context: %+v", c)
	}
	if len(limitsSeen) != 1 || limitsSeen[0].ResetsAt != "2026-10-15T14:00:00Z" {
		t.Fatalf("limits: %+v", limitsSeen)
	}
}
```

The module path is `github.com/mohamed-essam/herdr-mobile/companion` (from `companion/go.mod`).

- [ ] **Step 3: Run to verify failure** — `cd companion && go test ./internal/chatbridge/ -run 'Usage|Context'` → compile errors.

- [ ] **Step 4: Implement in `hub.go`**

Add the types near `Summary`:

```go
// Context is a session's context-window fill as the mod reports it.
type Context struct {
	Percent int `json:"percent"`
	Tokens  int `json:"tokens,omitempty"`
	Window  int `json:"window"`
}

// Usage is a /sync body's `usage`: the session's context fill (nil before
// its first response) and the account's rate-limit windows.
type Usage struct {
	Context *Context        `json:"context"`
	Limits  []limits.Window `json:"limits"`
}
```

Extend `Summary` with `Context *Context` (update its doc comment to mention the context fill); `empty()` adds `&& s.Context == nil`; `sameSummary` adds `ctxEq := a.Context == b.Context || (a.Context != nil && b.Context != nil && *a.Context == *b.Context)` and returns `actEq && askEq && ctxEq && a.BgRunning == b.BgRunning`.

Add `context *Context` to `pane` (comment: `// context is the session's latest context fill (Usage.Context).`). In `summary()`: `s := Summary{Activity: p.activity, BgRunning: p.bgRunning, Context: p.context}`.

Add `onLimits func([]limits.Window)` to `Hub`; default it in `NewHub` to `func([]limits.Window) {}`. Add:

```go
// SetOnLimits registers the callback for a /sync's rate-limit windows. It
// is called without the hub's lock held, once per /sync that carries any.
// Set it before the hub is used.
func (h *Hub) SetOnLimits(fn func([]limits.Window)) { h.onLimits = fn }

// SetUsage records a /sync body's usage, ahead of SyncBody (whose summary
// delivery then carries the context). nil (an older mod) changes nothing;
// a usage without context clears the pane's.
func (h *Hub) SetUsage(paneID string, u *Usage) {
	if u == nil {
		return
	}
	h.mu.Lock()
	h.get(paneID).context = u.Context
	h.mu.Unlock()
	if len(u.Limits) > 0 {
		h.onLimits(u.Limits)
	}
}
```

- [ ] **Step 5: Implement in `server.go`** — add `Usage *Usage \`json:"usage"\`` to `syncReq`; in the `/sync` handler call `h.SetUsage(req.PaneID, req.Usage)` right before `h.SyncBody(...)`.

- [ ] **Step 6: Pass context in `engine.go`** — in the `SetOnSummary` callback, add `Context: (*state.Context)(s.Context)` to the `state.Summary` literal.

- [ ] **Step 7: Run** — `cd companion && go vet ./... && go test -race ./...` → PASS.

- [ ] **Step 8: Commit**

```bash
git add companion/internal/limits/window.go companion/internal/chatbridge companion/internal/engine/engine.go
git commit -m "feat(companion): read the mod's usage; pane context in the summary"
```

---

### Task 4: Companion `limits.Tracker` — newest wins, persisted, throttled broadcast

**Files:**
- Create: `companion/internal/limits/tracker.go`
- Create: `companion/internal/limits/tracker_test.go`

**Interfaces:**
- Consumes: `limits.Window` (Task 3).
- Produces: `limits.Limits{Windows []Window \`json:"limits"\`; ObservedAt int64 \`json:"observedAt"\`}`; `func New(path string, now func() time.Time) *Tracker`; `(*Tracker).SetOnChange(fn func(Limits))`; `(*Tracker).Observe(ws []Window)`; `(*Tracker).Current() (Limits, bool)`; `const Rebroadcast = 60 * time.Second`.

- [ ] **Step 1: Write failing tests** — `tracker_test.go`:

```go
package limits

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func clock(start time.Time) (*time.Time, func() time.Time) {
	t := start
	return &t, func() time.Time { return t }
}

func TestObserveNewestWinsAndBroadcastsOnChange(t *testing.T) {
	now, fn := clock(time.UnixMilli(1_000_000))
	tr := New("", fn)
	var got []Limits
	tr.SetOnChange(func(l Limits) { got = append(got, l) })
	if _, ok := tr.Current(); ok {
		t.Fatal("no reading yet")
	}
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 42}})
	*now = now.Add(time.Second)
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 42}}) // same: no broadcast
	*now = now.Add(time.Second)
	tr.Observe([]Window{{Kind: "five_hour", PercentUsed: 43}})
	if len(got) != 2 || got[1].Windows[0].PercentUsed != 43 || got[1].ObservedAt != 1_002_000 {
		t.Fatalf("broadcasts: %+v", got)
	}
	cur, _ := tr.Current()
	if cur.ObservedAt != 1_002_000 {
		t.Fatalf("current: %+v", cur)
	}
}

func TestObserveRebroadcastsAfterAMinuteUnchanged(t *testing.T) {
	now, fn := clock(time.UnixMilli(0))
	tr := New("", fn)
	n := 0
	tr.SetOnChange(func(Limits) { n++ })
	ws := []Window{{Kind: "seven_day", PercentUsed: 18}}
	tr.Observe(ws)
	*now = now.Add(59 * time.Second)
	tr.Observe(ws)
	if n != 1 {
		t.Fatalf("rebroadcast too early: %d", n)
	}
	*now = now.Add(time.Second)
	tr.Observe(ws)
	if n != 2 {
		t.Fatalf("want a rebroadcast at 60s, got %d", n)
	}
}

func TestObserveIgnoresEmpty(t *testing.T) {
	tr := New("", nil)
	tr.SetOnChange(func(Limits) { t.Fatal("must not broadcast") })
	tr.Observe(nil)
	if _, ok := tr.Current(); ok {
		t.Fatal("empty observation stored")
	}
}

func TestPersistAndReload(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "state")
	path := filepath.Join(dir, "limits.json")
	_, fn := clock(time.UnixMilli(5000))
	New(path, fn).Observe([]Window{{Kind: "five_hour", PercentUsed: 12.5, ResetsAt: "2026-10-08T19:40:00Z"}})
	if fi, err := os.Stat(dir); err != nil || fi.Mode().Perm() != 0o700 {
		t.Fatalf("state dir: %v %v", fi, err)
	}
	cur, ok := New(path, fn).Current()
	if !ok || cur.ObservedAt != 5000 || cur.Windows[0].ResetsAt != "2026-10-08T19:40:00Z" {
		t.Fatalf("reloaded: %+v %v", cur, ok)
	}
}

func TestLoadIgnoresCorruptFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "limits.json")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, ok := New(path, nil).Current(); ok {
		t.Fatal("corrupt file must read as no reading")
	}
}
```

- [ ] **Step 2: Run to verify failure** — `cd companion && go test ./internal/limits/` → compile errors.

- [ ] **Step 3: Implement `tracker.go`**

```go
package limits

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"
)

// Rebroadcast is how far ObservedAt may advance with unchanged windows
// before the reading goes out again (it keeps the app's "as of" age true).
const Rebroadcast = 60 * time.Second

// Limits is the latest reading: its windows and when a mod last reported
// it (epoch ms).
type Limits struct {
	Windows    []Window `json:"limits"`
	ObservedAt int64    `json:"observedAt"`
}

// Tracker keeps the newest reading any pane's mod reported (one account is
// assumed: readings from several overwrite each other), saved to path.
type Tracker struct {
	mu       sync.Mutex
	path     string
	now      func() time.Time
	onChange func(Limits)
	cur      Limits
	sentAt   int64
}

// New loads the reading saved at path (none when path is empty, missing or
// unreadable).
func New(path string, now func() time.Time) *Tracker {
	if now == nil {
		now = time.Now
	}
	t := &Tracker{path: path, now: now, onChange: func(Limits) {}}
	if path == "" {
		return t
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return t
	}
	var l Limits
	if json.Unmarshal(b, &l) == nil && len(l.Windows) > 0 {
		t.cur, t.sentAt = l, l.ObservedAt
	}
	return t
}

// SetOnChange registers the broadcast. It runs under the tracker's lock, so
// it must be fast and must not call back into the tracker. Set it before use.
func (t *Tracker) SetOnChange(fn func(Limits)) { t.onChange = fn }

// Current returns the reading, false when there is none.
func (t *Tracker) Current() (Limits, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.cur, len(t.cur.Windows) > 0
}

// Observe records a mod's windows as the newest reading, and saves and
// broadcasts it when they changed or Rebroadcast passed since the last.
func (t *Tracker) Observe(ws []Window) {
	if len(ws) == 0 {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	now := t.now().UnixMilli()
	changed := !slices.Equal(ws, t.cur.Windows)
	t.cur = Limits{Windows: slices.Clone(ws), ObservedAt: now}
	if !changed && now-t.sentAt < Rebroadcast.Milliseconds() {
		return
	}
	t.sentAt = now
	t.save()
	t.onChange(t.cur)
}

func (t *Tracker) save() {
	if t.path == "" {
		return
	}
	b, _ := json.Marshal(t.cur)
	dir := filepath.Dir(t.path)
	tmp := t.path + ".tmp"
	if err := os.MkdirAll(dir, 0o700); err != nil {
		log.Printf("limits: %v", err)
		return
	}
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		log.Printf("limits: %v", err)
		return
	}
	if err := os.Rename(tmp, t.path); err != nil {
		log.Printf("limits: %v", err)
	}
}
```

(`go.mod` says go 1.23, so `slices` is available.)

- [ ] **Step 4: Run** — `cd companion && go test -race ./internal/limits/` → PASS.

- [ ] **Step 5: Commit**

```bash
git add companion/internal/limits
git commit -m "feat(companion): limits tracker (newest wins, persisted, throttled)"
```

---

### Task 5: Companion wiring — `limits` frame, protocol 11, connect burst, `--state-dir`

**Files:**
- Modify: `companion/internal/proto/proto.go` (`Welcome` line 56; add `Limits`)
- Modify: `companion/internal/proto/proto_test.go:84-85`
- Modify: `companion/internal/wsserver/server.go` (field + setter ~line 101; connect burst ~line 141)
- Modify: `companion/internal/wsserver/server_test.go:328-329` + new test
- Modify: `companion/internal/engine/engine.go` (`Config.StateDir`; wiring in `New`)
- Modify: `companion/cmd/herdr-mobiled/main.go` (flag + default)

**Interfaces:**
- Consumes: `limits.New`, `Tracker.SetOnChange/Observe/Current`, `Limits` (Task 4); `Hub.SetOnLimits` (Task 3).
- Produces (wire, consumed by Task 6): `{"t":"limits","limits":[{"kind","percentUsed","resetsAt"}],"observedAt":<ms>}`; welcome `companionProtocol: 11`.
- Produces (Go): `proto.Limits(l limits.Limits) []byte`; `(*wsserver.Server).SetLimitsSnapshot(fn func() []byte)`; `engine.Config.StateDir string`.

- [ ] **Step 1: Write failing tests**

In `proto_test.go`, change both `10`s on lines 84-85 to `11`, and add:

```go
func TestLimitsFrame(t *testing.T) {
	var got map[string]any
	_ = json.Unmarshal(Limits(limits.Limits{Windows: []limits.Window{{Kind: "five_hour", PercentUsed: 42, ResetsAt: "2026-10-08T19:40:00Z"}}, ObservedAt: 77}), &got)
	ws := got["limits"].([]any)
	if got["t"] != "limits" || got["observedAt"].(float64) != 77 || len(ws) != 1 || ws[0].(map[string]any)["kind"] != "five_hour" {
		t.Fatalf("frame: %+v", got)
	}
}
```

In `server_test.go`, change lines 328-329 to expect `11`, and add:

```go
func TestInitialSnapshotIncludesLimitsWhenKnown(t *testing.T) {
	s := NewServer(AllowAll{}, &stubRPC{})
	s.SetLimitsSnapshot(func() []byte { return []byte(`{"t":"limits","limits":[{"kind":"seven_day","percentUsed":18}],"observedAt":5}`) })
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	ctx := context.Background()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(websocket.StatusNormalClosure, "")
	l := readUntil(t, ctx, c, "limits")
	if l["observedAt"].(float64) != 5 {
		t.Fatalf("limits frame: %+v", l)
	}
}
```

- [ ] **Step 2: Run to verify failure** — `cd companion && go test ./internal/proto/ ./internal/wsserver/` → FAIL/compile errors.

- [ ] **Step 3: Implement proto** — in `Welcome` change `"companionProtocol": 10` → `11`. Add (and import the limits package):

```go
// Limits is the account's rate-limit reading (5h/7d windows) and when a mod
// last reported it.
func Limits(l limits.Limits) []byte {
	return must(map[string]any{"t": "limits", "limits": l.Windows, "observedAt": l.ObservedAt})
}
```

- [ ] **Step 4: Implement wsserver** — add field `limitsSnapshot func() []byte` to `Server`; default it in `NewServer` to `func() []byte { return nil }`; add the setter next to the others:

```go
func (s *Server) SetLimitsSnapshot(fn func() []byte)               { s.limitsSnapshot = fn }
```

In the connect burst, after the `TabsSnapshot` line:

```go
		if f := s.limitsSnapshot(); f != nil {
			c.send <- f
		}
```

- [ ] **Step 5: Wire the engine** — add to `Config`:

```go
	// StateDir holds what survives a restart (limits.json); empty keeps it
	// in memory only.
	StateDir string
```

Add `limits *limits.Tracker` to `Engine`. In `New`, after `e.hub = chatbridge.NewHub(nil)`:

```go
	path := ""
	if cfg.StateDir != "" {
		path = filepath.Join(cfg.StateDir, "limits.json")
	}
	e.limits = limits.New(path, nil)
	// Runs under the tracker's lock: Broadcast is non-blocking.
	e.limits.SetOnChange(func(l limits.Limits) { e.srv.Broadcast(proto.Limits(l)) })
	e.hub.SetOnLimits(e.limits.Observe)
	e.srv.SetLimitsSnapshot(func() []byte {
		if l, ok := e.limits.Current(); ok {
			return proto.Limits(l)
		}
		return nil
	})
```

- [ ] **Step 6: Add the flag in `main.go`**

```go
// defaultStateDir is $XDG_STATE_HOME/herdr-mobile, else
// ~/.local/state/herdr-mobile.
func defaultStateDir() string {
	if d := os.Getenv("XDG_STATE_HOME"); d != "" {
		return filepath.Join(d, "herdr-mobile")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".local", "state", "herdr-mobile")
}
```

In `main`: `stateDir := flag.String("state-dir", defaultStateDir(), "directory for state kept across restarts (the last rate-limit reading)")`; pass `StateDir: *stateDir` into `engine.Config`; add `state=%s` to the startup log line.

- [ ] **Step 7: Run** — `cd companion && go vet ./... && go test -race ./... && go build ./...` → PASS.

- [ ] **Step 8: Commit**

```bash
git add companion
git commit -m "feat(companion): limits frame (protocol 11), sent on connect; --state-dir"
```

---

### Task 6: App data — parse `context` and `limits`, expose `limits`

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt` (`Pane` ~line 24; `ServerFrame` ~line 286; `parseServerFrame` ~line 315)
- Modify: `app/app/src/main/java/dev/herdr/mobile/data/PaneRepository.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt:37`
- Modify: `app/app/src/test/java/dev/herdr/mobile/ProtocolTest.kt`, `PaneRepositoryTest.kt`

**Interfaces:**
- Consumes: wire frame and field from Tasks 3 and 5.
- Produces: `PaneUsage(percent: Int, tokens: Long, window: Long)`; `Pane.context: PaneUsage?`; `LimitWindow(kind: String, percentUsed: Double, resetsAt: String?)`; `Limits(windows: List<LimitWindow>, observedAt: Long)`; `ServerFrame.LimitsFrame(limits: Limits)`; `PaneRepository.limits: StateFlow<Limits?>`; `DashboardViewModel.limits: StateFlow<Limits?>`.

- [ ] **Step 1: Write failing tests**

`ProtocolTest.kt`:

```kotlin
    @Test fun parsesPaneContext() {
        val p = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1","context":{"percent":61,"tokens":122000,"window":200000}}}""") as ServerFrame.PaneUpdate).pane
        assertEquals(PaneUsage(61, 122000, 200000), p.context)
        val q = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p2"}}""") as ServerFrame.PaneUpdate).pane
        assertNull(q.context)
    }

    @Test fun parsesLimitsFrame() {
        val f = parseServerFrame("""{"t":"limits","limits":[{"kind":"five_hour","percentUsed":42.5,"resetsAt":"2026-10-08T19:40:00Z"},{"kind":"seven_day","percentUsed":18}],"observedAt":77}""")
        val l = (f as ServerFrame.LimitsFrame).limits
        assertEquals(77L, l.observedAt)
        assertEquals(LimitWindow("five_hour", 42.5, "2026-10-08T19:40:00Z"), l.windows[0])
        assertNull(l.windows[1].resetsAt)
    }
```

`PaneRepositoryTest.kt`:

```kotlin
    @Test fun keepsLatestLimits() {
        val repo = PaneRepository()
        assertNull(repo.limits.value)
        val l = Limits(listOf(LimitWindow("five_hour", 42.0, null)), 5)
        repo.onFrame(ServerFrame.LimitsFrame(l))
        assertEquals(l, repo.limits.value)
        repo.onFrame(ServerFrame.Panes(emptyList())) // a reconnect's snapshot keeps it
        assertEquals(l, repo.limits.value)
    }
```

- [ ] **Step 2: Run to verify failure** — `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests 'dev.herdr.mobile.ProtocolTest' --tests 'dev.herdr.mobile.PaneRepositoryTest'` → compile errors.

- [ ] **Step 3: Implement `Protocol.kt`** — add to `Pane` after `bgRunning`:

```kotlin
    /** The Claude session's context-window fill; null when no herdr-chat mod is live. */
    val context: PaneUsage? = null,
```

Add types after `PaneAsk`:

```kotlin
/** A session's context window: [percent] of [window] tokens used ([tokens] when known, else 0). */
@Serializable
data class PaneUsage(
    val percent: Int = 0,
    val tokens: Long = 0,
    val window: Long = 0,
)

/** One rate-limit window: [kind] "five_hour" or "seven_day"; [resetsAt] ISO 8601 or null. */
@Serializable
data class LimitWindow(
    val kind: String = "",
    val percentUsed: Double = 0.0,
    val resetsAt: String? = null,
)

/** The account's rate-limit reading and when a mod last reported it (epoch ms). */
data class Limits(val windows: List<LimitWindow>, val observedAt: Long)
```

In `ServerFrame` add `data class LimitsFrame(val limits: Limits) : ServerFrame`. In `parseServerFrame` add:

```kotlin
        "limits" -> ServerFrame.LimitsFrame(Limits(
            (obj["limits"] as? JsonArray)?.map { json.decodeFromJsonElement<LimitWindow>(it) } ?: emptyList(),
            obj["observedAt"]?.jsonPrimitive?.longOrNull ?: 0L))
```

- [ ] **Step 4: Implement the repository and VM** — in `PaneRepository` add:

```kotlin
    private val _limits = MutableStateFlow<Limits?>(null)
    /** The latest rate-limit reading; null until the companion sends one. */
    val limits: StateFlow<Limits?> = _limits.asStateFlow()
```

and in `onFrame`: `is ServerFrame.LimitsFrame -> { _limits.value = frame.limits; return }` (import `dev.herdr.mobile.net.Limits`). In `DashboardViewModel` after `val panes`: `val limits: StateFlow<Limits?> = repo.limits`.

- [ ] **Step 5: Run** — same Gradle command → PASS.

- [ ] **Step 6: Commit**

```bash
git add app/app/src
git commit -m "feat(app): parse pane context and the limits frame"
```

---

### Task 7: App `UsageMeters.kt` — formatting rules and meter composables

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/UsageMeters.kt`
- Create: `app/app/src/test/java/dev/herdr/mobile/UsageMetersTest.kt`

**Interfaces:**
- Consumes: `PaneUsage`, `LimitWindow`, `Limits` (Task 6); `Herdr.colors`, `HerdrType.meta`, `relativeAge` (existing).
- Produces: `enum class UsageTone { Normal, Warn, Critical }`; `fun usageTone(pct: Double): UsageTone`; `fun windowExpired(resetsAt: String?, now: Long): Boolean`; `fun formatReset(kind: String, resetsAt: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String`; `fun limitsStale(observedAt: Long, now: Long): Boolean`; `fun limitLabel(kind: String): String`; `fun limitWindows(limits: Limits?): List<LimitWindow>` (5h then 7d only); `fun limitText(w: LimitWindow, now: Long, zone: ZoneId = ZoneId.systemDefault()): String`; `@Composable fun ContextBar(percent: Int, modifier: Modifier = Modifier)`; `@Composable fun LimitsStrip(limits: Limits, now: Long, modifier: Modifier = Modifier)`; `@Composable fun UsageLine(context: PaneUsage?, limits: Limits?, now: Long)`; `@Composable fun rememberNow(intervalMs: Long = 30_000): Long`.

- [ ] **Step 1: Write the failing tests** — `UsageMetersTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.net.LimitWindow
import dev.herdr.mobile.net.Limits
import dev.herdr.mobile.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UsageMetersTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-08T17:27:00Z").toEpochMilli()

    @Test fun tones() {
        assertEquals(UsageTone.Normal, usageTone(59.9))
        assertEquals(UsageTone.Warn, usageTone(60.0))
        assertEquals(UsageTone.Warn, usageTone(84.9))
        assertEquals(UsageTone.Critical, usageTone(85.0))
    }

    @Test fun fiveHourCountdown() {
        assertEquals("↻ 2h13m", formatReset("five_hour", "2026-10-08T19:40:00Z", now, utc))
        assertEquals("↻ 13m", formatReset("five_hour", "2026-10-08T17:40:00Z", now, utc))
    }

    @Test fun sevenDayClock() {
        assertEquals("↻ Thu 14:00", formatReset("seven_day", "2026-10-15T14:00:00Z", now, utc))
    }

    @Test fun offsetTimestampsParse() {
        assertEquals("↻ 2h13m", formatReset("five_hour", "2026-10-08T22:40:00+03:00", now, utc))
    }

    @Test fun unknownOrPastResetIsBlank() {
        assertEquals("", formatReset("five_hour", null, now, utc))
        assertEquals("", formatReset("five_hour", "garbage", now, utc))
        assertEquals("", formatReset("five_hour", "2026-10-08T17:00:00Z", now, utc))
    }

    @Test fun expiry() {
        assertTrue(windowExpired("2026-10-08T17:00:00Z", now))
        assertFalse(windowExpired("2026-10-08T19:40:00Z", now))
        assertFalse(windowExpired(null, now))
    }

    @Test fun staleness() {
        assertFalse(limitsStale(now - 5 * 60_000, now))
        assertTrue(limitsStale(now - 5 * 60_000 - 1, now))
    }

    @Test fun windowsAreFiveHourThenSevenDayOnly() {
        val l = Limits(listOf(LimitWindow("seven_day", 18.0), LimitWindow("spend_limit", 3.0), LimitWindow("five_hour", 42.0)), now)
        assertEquals(listOf("five_hour", "seven_day"), limitWindows(l).map { it.kind })
        assertTrue(limitWindows(null).isEmpty())
    }

    @Test fun limitTextRoundsAndShowsReset() {
        assertEquals("5h 42% ↻ 2h13m", limitText(LimitWindow("five_hour", 42.4, "2026-10-08T19:40:00Z"), now, utc))
        assertEquals("7d 18%", limitText(LimitWindow("seven_day", 18.0, null), now, utc))
    }

    @Test fun expiredWindowShowsDash() {
        assertEquals("5h —", limitText(LimitWindow("five_hour", 97.0, "2026-10-08T17:00:00Z"), now, utc))
    }
}
```

- [ ] **Step 2: Run to verify failure** — `cd app && ./gradlew --no-daemon :app:testDebugUnitTest --tests 'dev.herdr.mobile.UsageMetersTest'` → compile errors.

- [ ] **Step 3: Implement `UsageMeters.kt`**

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.LimitWindow
import dev.herdr.mobile.net.Limits
import dev.herdr.mobile.net.PaneUsage
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrType
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** How full a meter reads: below 60 normal, 60–84 warn, 85+ critical. */
enum class UsageTone { Normal, Warn, Critical }

fun usageTone(pct: Double): UsageTone = when {
    pct >= 85 -> UsageTone.Critical
    pct >= 60 -> UsageTone.Warn
    else -> UsageTone.Normal
}

@Composable
private fun toneColor(t: UsageTone): Color = when (t) {
    UsageTone.Normal -> Herdr.colors.overlay2
    UsageTone.Warn -> Herdr.colors.yellow
    UsageTone.Critical -> Herdr.colors.red
}

private fun resetMs(resetsAt: String?): Long? =
    resetsAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** True once the window's reset time has passed (its percentage is stale). */
fun windowExpired(resetsAt: String?, now: Long): Boolean = resetMs(resetsAt)?.let { it <= now } ?: false

/** "↻ 2h13m" for the 5-hour window, "↻ Thu 14:00" for the 7-day one; "" when unknown or past. */
fun formatReset(kind: String, resetsAt: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = resetMs(resetsAt) ?: return ""
    if (at <= now) return ""
    if (kind == "five_hour") {
        val mins = (at - now + 59_999) / 60_000
        val h = mins / 60
        return if (h > 0) "↻ ${h}h${mins % 60}m" else "↻ ${mins}m"
    }
    return "↻ " + Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH))
}

/** The reading is older than five minutes: no live mod is reporting. */
fun limitsStale(observedAt: Long, now: Long): Boolean = now - observedAt > 5 * 60_000

fun limitLabel(kind: String): String = when (kind) {
    "five_hour" -> "5h"
    "seven_day" -> "7d"
    else -> kind
}

/** The 5-hour then the 7-day window; others are dropped. */
fun limitWindows(limits: Limits?): List<LimitWindow> =
    listOf("five_hour", "seven_day").mapNotNull { k -> limits?.windows?.firstOrNull { it.kind == k } }

/** "5h 42% ↻ 2h13m"; "5h —" once the window has reset. */
fun limitText(w: LimitWindow, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val label = limitLabel(w.kind)
    if (windowExpired(w.resetsAt, now)) return "$label —"
    val reset = formatReset(w.kind, w.resetsAt, now, zone)
    return listOf("$label ${w.percentUsed.roundToInt()}%", reset).filter { it.isNotEmpty() }.joinToString(" ")
}

/** A ticking clock for countdowns, every [intervalMs]. */
@Composable
fun rememberNow(intervalMs: Long = 30_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** A thin fill bar; [fraction] 0..1. */
@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.height(2.dp).clip(RoundedCornerShape(1.dp)).background(Herdr.colors.surface0)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/** The context window's fill under a dashboard row. */
@Composable
fun ContextBar(percent: Int, modifier: Modifier = Modifier) {
    Meter(percent / 100f, toneColor(usageTone(percent.toDouble())), modifier.fillMaxWidth())
}

/** The dashboard strip: one line per window, dimmed with its age when stale. */
@Composable
fun LimitsStrip(limits: Limits, now: Long, modifier: Modifier = Modifier) {
    val windows = limitWindows(limits)
    if (windows.isEmpty()) return
    val stale = limitsStale(limits.observedAt, now)
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().alpha(if (stale) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (w in windows) {
            val expired = windowExpired(w.resetsAt, now)
            val color = if (expired) Herdr.colors.overlay0 else toneColor(usageTone(w.percentUsed))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(limitLabel(w.kind), style = HerdrType.meta, color = Herdr.colors.overlay2, modifier = Modifier.width(24.dp))
                Meter(if (expired) 0f else (w.percentUsed / 100).toFloat(), color, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(limitText(w, now).removePrefix(limitLabel(w.kind)).trim(), style = HerdrType.meta, color = color)
            }
        }
        if (stale) Text("as of ${relativeAge(limits.observedAt, now)} ago", style = HerdrType.meta, color = Herdr.colors.overlay0)
    }
}

/** The pane header's line: "ctx ▓ 61% · 5h 42% ↻ 2h13m · 7d 18% ↻ Thu 14:00". */
@Composable
fun UsageLine(context: PaneUsage?, limits: Limits?, now: Long) {
    val windows = limitWindows(limits)
    if (context == null && windows.isEmpty()) return
    val stale = limits != null && limitsStale(limits.observedAt, now)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (context != null) {
            val color = toneColor(usageTone(context.percent.toDouble()))
            Text("ctx", style = HerdrType.meta, color = Herdr.colors.overlay2)
            Meter(context.percent / 100f, color, Modifier.width(32.dp))
            Text("${context.percent}%", style = HerdrType.meta, color = color)
        }
        if (windows.isNotEmpty()) {
            val text = windows.joinToString(" · ") { limitText(it, now) } + if (stale) " · as of ${relativeAge(limits!!.observedAt, now)} ago" else ""
            Text(
                (if (context != null) "· " else "") + text,
                style = HerdrType.meta,
                color = Herdr.colors.overlay2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (stale) 0.5f else 1f),
            )
        }
    }
}
```

(Theme imports are `dev.herdr.mobile.ui.theme.Herdr` and `dev.herdr.mobile.ui.theme.HerdrType`, as in `PaneHeader.kt`.)

- [ ] **Step 4: Run** — same Gradle command → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/app/src
git commit -m "feat(app): usage meters (context bar, limits strip, header line)"
```

---

### Task 8: App screens — dashboard strip and row bar, header line in chat/terminal/thread

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (collect `limits` ~line 128; `item(key = "stats")` line 194; `PaneListRow` ~line 605)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/PaneHeader.kt` (signature line 29; the breadcrumb `Column` ~line 78)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/ChatScreen.kt:74`, `TerminalScreen.kt:210`, `ThreadScreen.kt:57`

**Interfaces:**
- Consumes: `DashboardViewModel.limits` (Task 6); `LimitsStrip`, `ContextBar`, `UsageLine`, `rememberNow` (Task 7).
- Produces: `PaneHeader(..., usage: PaneUsage? = null, limits: Limits? = null, now: Long = 0L, ...)`.

- [ ] **Step 1: Dashboard strip** — in `DashboardScreen`, next to the other `collectAsState` lines: `val limits by vm.limits.collectAsState()`. After `item(key = "stats") { StatTiles(counts, dim) }`:

```kotlin
                    limits?.let { l ->
                        if (limitWindows(l).isNotEmpty()) item(key = "limits") {
                            LimitsStrip(l, now, dim.padding(bottom = 16.dp))
                        }
                    }
```

`StatTiles` has `bottom = 24.dp`. With the strip below it, change that to `bottom = 12.dp` so the stats and strip read as one block.

- [ ] **Step 2: Row bar** — in `PaneListRow`, inside the text `Column(Modifier.weight(1f), ...)`, after the `Row` that holds `sub` and `BgChip`:

```kotlin
            pane.context?.let { ContextBar(it.percent, Modifier.padding(top = 4.dp)) }
```

- [ ] **Step 3: Header line** — add parameters to `PaneHeader` (before `modifier`):

```kotlin
    usage: PaneUsage? = null,
    limits: Limits? = null,
    now: Long = 0L,
```

and, inside the padded `Column` right after the breadcrumb `Text(...)`:

```kotlin
            UsageLine(usage, limits, now)
```

Update the KDoc above `PaneHeader` to mention the usage line.

- [ ] **Step 4: Pass the figures from the three screens.** In each of `ChatScreen`, `TerminalScreen` and `ThreadScreen`, add near the other `collectAsState` lines:

```kotlin
    val limits by vm.limits.collectAsState()
    val now = rememberNow()
```

and pass `usage = pane.context, limits = limits, now = now` to `PaneHeader(...)`. `pane` is the live pane: the dashboard re-reads it from `panes` before opening `PaneScreen`, and `ThreadScreen` gets the parent pane. So a thread shows the parent session's context, as the spec requires.

- [ ] **Step 5: Build and run the unit tests** — `cd app && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/app/src
git commit -m "feat(app): limits strip, row context bar, usage line in pane headers"
```

---

### Task 9: Live verification on the emulator + docs

**Files:**
- Modify: any doc that states the companion protocol number or lists frames. Find them with `grep -rn "companionProtocol\|protocol 10" README.md docs/ companion/ app/ mod/ --include=*.md`.

- [ ] **Step 1: Update the docs** found by the grep: protocol 11, the new `limits` frame, `Pane.context`, the `/sync` `usage` field, and the `--state-dir` flag.

- [ ] **Step 2: Start a test companion** (never touch the production `herdr-mobiled.service`, and never use `scripts/dev-emulator.sh`, which kills every herdr-mobiled):

```bash
mkdir -p /run/user/1000/hmtest
cd companion && go build -o /home/messam/tmp/golang/claude-1000/-home-messam-work-personal-herdr-mobile/92491aa6-08df-416d-b524-79e3fbae05af/scratchpad/hm-test ./cmd/herdr-mobiled
/home/messam/tmp/golang/claude-1000/-home-messam-work-personal-herdr-mobile/92491aa6-08df-416d-b524-79e3fbae05af/scratchpad/hm-test --listen 127.0.0.1:8788 --chat-socket /run/user/1000/hmtest/chat.sock --state-dir /run/user/1000/hmtest/state
```

(run it in the background)

- [ ] **Step 3: Start a throwaway Claude pane** — create it with `herdr workspace create`, then run in it:

```bash
HERDR_MOBILE_CHAT_SOCK=/run/user/1000/hmtest/chat.sock claude --plugin-dir <worktree>/mod/herdr-chat --settings '{"enabledPlugins":{"herdr-chat@herdr-mobile":false}}'
```

Send it one short prompt so a response reports usage.

- [ ] **Step 4: Check the companion end** — `ls -l /run/user/1000/hmtest/state/limits.json` (mode 0600, inside a 0700 dir) and `cat` it: it should show `five_hour`/`seven_day` windows.

- [ ] **Step 5: Check the app on the emulator** — install the debug APK, seed `ws://10.0.2.2:8788` (DataStore recipe from the v1 design memory), and confirm:
  - (a) the dashboard strip shows 5h and 7d with their resets
  - (b) the Claude row has a context bar
  - (c) the chat header shows `ctx … · 5h … · 7d …`
  - (d) the terminal header of the same pane shows the same line
  - (e) after quitting the throwaway Claude and waiting more than 5 minutes, the strip dims with "as of … ago" and the row bar disappears
  - (f) after restarting the test companion, the strip comes back from `limits.json`

  Take a screenshot of each with `adb exec-out screencap -p`.

- [ ] **Step 6: Clean up** — kill the test companion by PID (`pgrep -f hm-test`), close the throwaway workspace, and remove `/run/user/1000/hmtest`.

- [ ] **Step 7: Commit the docs**

```bash
git add -A docs README.md
git commit -m "docs: protocol 11 limits frame, pane context, --state-dir"
```
