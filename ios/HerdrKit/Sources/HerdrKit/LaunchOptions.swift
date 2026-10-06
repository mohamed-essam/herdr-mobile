import Foundation

/// The launch-argument contract the e2e flows drive. iOS turns `-companionURL ws://…`
/// into a UserDefaults value, so each switch is a plain settings read, and a saved
/// setting under the same key works the same way.
public struct LaunchOptions: Sendable {
    /// `-companionURL <ws url>`: connect to this companion. Nil unless it is a ws/wss URL with a host.
    public let companionURL: URL?
    /// `-disableAnimations YES`
    public let disableAnimations: Bool
    /// `-fixedNow <ISO time>`: freeze the clock so timestamps are stable.
    public let fixedNow: Date?

    /// `string` reads a setting, e.g. `UserDefaults.standard.string(forKey:)`.
    public init(reading string: (String) -> String?) {
        companionURL = string("companionURL")
            .flatMap(URL.init(string:))
            .flatMap { ["ws", "wss"].contains($0.scheme) && $0.host()?.isEmpty == false ? $0 : nil }
        disableAnimations = string("disableAnimations").map { ($0 as NSString).boolValue } ?? false
        fixedNow = string("fixedNow").flatMap { try? Date($0, strategy: .iso8601) }
    }

    /// Reads `defaults`, after `-resetState YES` clears the app's saved settings in `domain`.
    /// Launch arguments live in their own domain, so they survive the reset.
    public static func load(from defaults: UserDefaults, domain: String) -> LaunchOptions {
        if defaults.bool(forKey: "resetState") {
            defaults.removePersistentDomain(forName: domain)
        }
        return LaunchOptions(reading: defaults.string(forKey:))
    }

    /// The current time, or `fixedNow` when it is set.
    public func now() -> Date { fixedNow ?? Date() }
}
