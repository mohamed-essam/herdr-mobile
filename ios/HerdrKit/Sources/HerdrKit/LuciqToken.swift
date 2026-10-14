import Foundation

/// The Luciq app token a build carries in its Info.plist, set at build time from the gitignored
/// `ios/Config/Luciq.local.xcconfig`. A build without that file gets no token, and Luciq stays off.
public struct LuciqToken: Equatable, Sendable {
    public let value: String

    /// Nil when missing, blank, or an unexpanded `$(…)` / `${…}` build setting.
    public init?(parsing raw: String?) {
        guard let t = raw?.trimmed.nonEmpty, !t.contains("$("), !t.contains("${") else { return nil }
        value = t
    }
}
