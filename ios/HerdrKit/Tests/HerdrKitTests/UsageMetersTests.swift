import Foundation
import Testing
@testable import HerdrKit

// The usage meters' wording and thresholds, on a fixed clock in UTC.

private let utc = TimeZone(identifier: "UTC")!
/// 2026-10-01T11:47:00Z, a Thursday.
private let now = 1_790_855_220_000
private let minute = 60_000

private func window(_ kind: LimitKind, _ pct: Double, resetsIn ms: Int?) -> LimitWindow {
    LimitWindow(kind: kind, percentUsed: pct, resetsAt: ms.map { Date(timeIntervalSince1970: Double(now + $0) / 1000) })
}

@Test func toneTurnsWarnAt60AndCriticalAt85() {
    #expect([0, 59.9, 60, 84.9, 85, 100].map(UsageTone.init(percent:)) == [.normal, .normal, .warn, .warn, .critical, .critical])
}

@Test func describesAWindowWithItsReset() {
    #expect(window(.fiveHour, 42.5, resetsIn: 133 * minute).text(now: now, zone: utc) == "5h 43% ↻ 2h13m")
    // A part-minute counts as a whole one.
    #expect(window(.fiveHour, 7, resetsIn: 44 * minute + 1).text(now: now, zone: utc) == "5h 7% ↻ 45m")
    // The 7-day window says when, not how long.
    #expect(window(.sevenDay, 18, resetsIn: 3 * 24 * 60 * minute).text(now: now, zone: utc) == "7d 18% ↻ Sun 11:47")
    // On the 24-hour clock, as Android's "EEE HH:mm".
    #expect(window(.sevenDay, 18, resetsIn: (3 * 24 * 60 + 133) * minute).text(now: now, zone: utc) == "7d 18% ↻ Sun 14:00")
    #expect(window(.sevenDay, 18, resetsIn: nil).text(now: now, zone: utc) == "7d 18%")
    // Reset already: its percentage is stale.
    #expect(window(.fiveHour, 90, resetsIn: 0).text(now: now, zone: utc) == "5h —")
    #expect(window(.fiveHour, 90, resetsIn: 0).expired(now: now))
    #expect(!window(.fiveHour, 90, resetsIn: nil).expired(now: now))
}

@Test func showsTheFiveHourThenTheSevenDayWindow() {
    let l = Limits(windows: [window(.sevenDay, 1, resetsIn: nil), window(.unknown("opus"), 2, resetsIn: nil), window(.fiveHour, 3, resetsIn: nil)],
                   observedAt: now)
    #expect(l.shown.map(\.kind) == [.fiveHour, .sevenDay])
}

@Test func aReadingOlderThanFiveMinutesIsStale() {
    #expect(!Limits(windows: [], observedAt: now - 5 * minute).stale(now: now))
    #expect(Limits(windows: [], observedAt: now - 5 * minute - 1).stale(now: now))
}

@Test func anOldReadingWhoseWindowsAllResetIsNotWorthShowing() {
    let old = now - 60 * minute
    #expect(!Limits(windows: [], observedAt: now).worthShowing(now: now))
    #expect(!Limits(windows: [window(.unknown("opus"), 5, resetsIn: nil)], observedAt: now).worthShowing(now: now))
    #expect(!Limits(windows: [window(.fiveHour, 5, resetsIn: -minute), window(.sevenDay, 5, resetsIn: 0)], observedAt: old).worthShowing(now: now))
    #expect(Limits(windows: [window(.fiveHour, 5, resetsIn: -minute), window(.sevenDay, 5, resetsIn: minute)], observedAt: old).worthShowing(now: now))
    // A fresh reading shows even with every window reset ("5h —").
    #expect(Limits(windows: [window(.fiveHour, 5, resetsIn: -minute)], observedAt: now).worthShowing(now: now))
}

@Test func theHeaderLineJoinsTheWindowsAndAgesAStaleReading() {
    let fresh = Limits(windows: [window(.fiveHour, 42, resetsIn: 133 * minute), window(.sevenDay, 18, resetsIn: nil)], observedAt: now)
    #expect(fresh.line(now: now, zone: utc) == "5h 42% ↻ 2h13m · 7d 18%")
    let stale = Limits(windows: fresh.windows, observedAt: now - 6 * minute)
    #expect(stale.line(now: now, zone: utc) == "5h 42% ↻ 2h13m · 7d 18% · as of 6m ago")
    let gone = Limits(windows: [window(.fiveHour, 42, resetsIn: -minute)], observedAt: now - 60 * minute)
    #expect(gone.line(now: now, zone: utc) == nil)
}

@Test @MainActor func theDashboardKeepsTheLatestLimits() {
    let store = DashboardStore(request: { _ in .pong })
    #expect(store.limits == nil)
    let l = Limits(windows: [window(.fiveHour, 1, resetsIn: nil)], observedAt: now)
    store.apply(.limits(l))
    #expect(store.limits == l)
}
