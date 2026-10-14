import Foundation

// The usage meters' wording: a pane's context fill and the account's 5h/7d limits.
// Ported from Android's UsageMeters.kt. Times are epoch ms.

/// How full a meter reads.
public enum UsageTone: Equatable, Sendable {
    case normal, warn, critical

    /// Below 60 normal, 60–84 warn, 85 and up critical.
    public init(percent: Double) {
        self = percent >= 85 ? .critical : percent >= 60 ? .warn : .normal
    }
}

extension LimitWindow {
    /// "5h", "7d", or the wire kind.
    public var label: String {
        switch kind {
        case .fiveHour: "5h"
        case .sevenDay: "7d"
        case .unknown(let raw): raw
        }
    }

    /// The window has reset since the reading: its percentage is stale.
    public func expired(now: Int) -> Bool { resetsAt.map { $0.epochMs <= now } ?? false }

    /// "↻ 2h13m" for the 5-hour window, "↻ Thu 14:00" for the 7-day one; "" when unknown or past.
    public func resetText(now: Int, zone: TimeZone = .current) -> String {
        guard let at = resetsAt?.epochMs, at > now else { return "" }
        if kind == .fiveHour {
            let mins = (at - now + 59_999) / 60_000
            return mins >= 60 ? "↻ \(mins / 60)h\(mins % 60)m" : "↻ \(mins)m"
        }
        // Android's "EEE HH:mm": English day names, 24-hour clock.
        let style = Date.VerbatimFormatStyle(
            format: "\(weekday: .abbreviated) \(hour: .twoDigits(clock: .twentyFourHour, hourCycle: .zeroBased)):\(minute: .twoDigits)",
            locale: Locale(identifier: "en_US_POSIX"), timeZone: zone, calendar: Calendar(identifier: .gregorian))
        return "↻ \(resetsAt!.formatted(style))"
    }

    /// The reading without its label: "42% ↻ 2h13m", "—" once the window has reset.
    public func valueText(now: Int, zone: TimeZone = .current) -> String {
        if expired(now: now) { return "—" }
        return ["\(Int(percentUsed.rounded()))%", resetText(now: now, zone: zone)].filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// "5h 42% ↻ 2h13m"; "5h —" once the window has reset.
    public func text(now: Int, zone: TimeZone = .current) -> String { "\(label) \(valueText(now: now, zone: zone))" }
}

extension Limits {
    /// The 5-hour then the 7-day window; others are left out.
    public var shown: [LimitWindow] {
        [LimitKind.fiveHour, .sevenDay].compactMap { k in windows.first { $0.kind == k } }
    }

    /// No mod has reported for over five minutes.
    public func stale(now: Int) -> Bool { now - observedAt > 5 * 60_000 }

    /// "as of 3h ago", for a stale reading.
    public func ageText(now: Int) -> String { "as of \(relativeAge(ms: observedAt, now: now)) ago" }

    /// There are windows to show: not when no mod has reported for a while and every
    /// window has reset (an old saved reading would only say "—").
    public func worthShowing(now: Int) -> Bool {
        !shown.isEmpty && !(stale(now: now) && shown.allSatisfy { $0.expired(now: now) })
    }

    /// The pane header's limits part: "5h 42% ↻ 2h13m · 7d 18% ↻ Thu 14:00", aged when stale;
    /// nil when not worth showing.
    public func line(now: Int, zone: TimeZone = .current) -> String? {
        guard worthShowing(now: now) else { return nil }
        let age = stale(now: now) ? [ageText(now: now)] : []
        return (shown.map { $0.text(now: now, zone: zone) } + age).joined(separator: " · ")
    }
}
