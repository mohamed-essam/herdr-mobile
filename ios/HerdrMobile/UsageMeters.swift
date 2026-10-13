import HerdrKit
import SwiftUI

// The usage meters: a context bar under each agent row, the 5h/7d limits strip on the
// dashboard, and the usage line in pane headers. Ported from Android's UsageMeters.kt.

extension EnvironmentValues {
    /// The account's latest rate-limit reading, for the pane headers.
    @Entry var usageLimits: Limits?
}

extension HerdrColors {
    func tone(_ t: UsageTone) -> Color {
        switch t {
        case .normal: overlay2
        case .warn: yellow
        case .critical: red
        }
    }
}

/// A thin fill bar; `fraction` 0…1.
private struct Meter: View {
    let fraction: Double
    let color: Color
    @Environment(\.herdr) private var c

    var body: some View {
        Capsule().fill(c.surface0).frame(height: 2)
            .overlay(alignment: .leading) {
                GeometryReader { geo in Capsule().fill(color).frame(width: geo.size.width * min(max(fraction, 0), 1)) }
            }
            .accessibilityHidden(true)
    }
}

/// The context window's fill under a dashboard row.
struct ContextBar: View {
    let paneId: String
    let percent: Int
    @Environment(\.herdr) private var c

    var body: some View {
        Meter(fraction: Double(percent) / 100, color: c.tone(UsageTone(percent: Double(percent))))
            .accessibilityElement()
            .accessibilityLabel("context \(percent)%")
            .accessibilityIdentifier("ctx-\(paneId)")
    }
}

/// The dashboard strip: one line per window, dimmed with its age when stale; nothing
/// when the reading isn't worth showing.
struct LimitsStrip: View {
    let limits: Limits
    let now: Int
    @Environment(\.herdr) private var c

    var body: some View {
        if limits.worthShowing(now: now) {
            let stale = limits.stale(now: now)
            VStack(alignment: .leading, spacing: 6) {
                ForEach(limits.shown, id: \.kind) { w in
                    let expired = w.expired(now: now)
                    let color = expired ? c.overlay0 : c.tone(UsageTone(percent: w.percentUsed))
                    HStack(spacing: 8) {
                        Text(w.label).font(HerdrFont.meta).foregroundStyle(c.overlay2).frame(width: 24, alignment: .leading)
                        Meter(fraction: expired ? 0 : w.percentUsed / 100, color: color)
                        Text(w.valueText(now: now)).font(HerdrFont.meta).foregroundStyle(color)
                    }
                    .accessibilityElement()
                    .accessibilityLabel(w.text(now: now))
                    .accessibilityIdentifier("limit-\(w.kind.raw)")
                }
                if stale {
                    Text(limits.ageText(now: now)).font(HerdrFont.meta).foregroundStyle(c.overlay0)
                        .accessibilityIdentifier("limitsAge")
                }
            }
            .opacity(stale ? 0.5 : 1)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("limitsStrip")
            .padding(.horizontal, 16).padding(.bottom, 16)
        }
    }
}

/// The pane header's line: "ctx ▓ 61% · 5h 42% ↻ 2h13m · 7d 18% ↻ Thu 14:00"; nothing
/// when there is neither a context reading nor limits worth showing.
struct UsageLine: View {
    let usage: PaneUsage?
    let limits: Limits?
    let now: Int
    @Environment(\.herdr) private var c

    var body: some View {
        let line = limits?.line(now: now)
        if usage != nil || line != nil {
            HStack(spacing: 6) {
                if let usage {
                    let color = c.tone(UsageTone(percent: Double(usage.percent)))
                    Text("ctx").foregroundStyle(c.overlay2)
                    Meter(fraction: Double(usage.percent) / 100, color: color).frame(width: 32)
                    Text("\(usage.percent)%").foregroundStyle(color)
                }
                if let line {
                    Text((usage == nil ? "" : "· ") + line).foregroundStyle(c.overlay2).lineLimit(1)
                        .opacity(limits?.stale(now: now) == true ? 0.5 : 1)
                }
            }
            .font(HerdrFont.meta)
            .accessibilityElement()
            .accessibilityLabel([usage.map { "ctx \($0.percent)%" }, line].compactMap { $0 }.joined(separator: " · "))
            .accessibilityIdentifier("usageLine")
        }
    }
}
