import Foundation

/// The non-printing keys the terminal's key bar sends. Ported from Android's TerminalKeys.kt.
public enum TermKey: CaseIterable, Sendable {
    case esc, tab, up, down, left, right, home, end, pageUp, pageDown

    /// The key's bytes, in xterm's normal-cursor forms.
    public var bytes: Data {
        let esc = "\u{1b}"
        let text = switch self {
        case .esc: esc
        case .tab: "\t"
        case .up: "\(esc)[A"
        case .down: "\(esc)[B"
        case .right: "\(esc)[C"
        case .left: "\(esc)[D"
        case .home: "\(esc)[H"
        case .end: "\(esc)[F"
        case .pageUp: "\(esc)[5~"
        case .pageDown: "\(esc)[6~"
        }
        return Data(text.utf8)
    }
}

/// A sticky key-bar modifier: a tap arms it for one key, a long press locks it.
public enum ModState: Sendable {
    case off, oneShot, locked
}

/// The bar's ctrl and alt. Ported from Android's `ModifierKeys`.
public struct ModifierKeys: Sendable {
    public enum Key: Sendable { case ctrl, alt }

    public private(set) var ctrl = ModState.off
    public private(set) var alt = ModState.off

    public init() {}

    /// Off → one-shot; one-shot or locked → off.
    public mutating func tap(_ key: Key) { self[key] = self[key] == .off ? .oneShot : .off }

    public mutating func lock(_ key: Key) { self[key] = .locked }

    /// Clears what was armed for one key; a lock stays.
    public mutating func consumeOneShot() {
        if ctrl == .oneShot { ctrl = .off }
        if alt == .oneShot { alt = .off }
    }

    /// Which modifiers apply to a typed key, then clears the one-shots.
    public mutating func takeForKey() -> (ctrl: Bool, alt: Bool) {
        defer { consumeOneShot() }
        return (ctrl != .off, alt != .off)
    }

    private subscript(key: Key) -> ModState {
        get { key == .ctrl ? ctrl : alt }
        set { if key == .ctrl { ctrl = newValue } else { alt = newValue } }
    }
}

/// The terminal's font size in points, pinch-stepped and saved. Ported from Android's TerminalFont.kt.
public enum TerminalFont {
    /// The settings key it is saved under; `-resetState` clears it.
    public static let key = "terminalFontSize"
    public static let defaultSize = 16.0
    static let range = 8.0...32.0
    static let step = 2.0

    /// The next size for a pinch `scale`: nil while the pinch is under the threshold, else
    /// one step, clamped (so it may equal `current` at a bound).
    public static func stepped(_ current: Double, scale: Double) -> Double? {
        if scale > 1.1 { return min(current + step, range.upperBound) }
        if scale < 0.9 { return max(current - step, range.lowerBound) }
        return nil
    }

    /// The saved size, clamped; the default when there is none or it isn't a number.
    /// `string` reads a setting, e.g. `UserDefaults.standard.string(forKey:)`.
    public static func saved(_ string: (String) -> String?) -> Double {
        guard let size = string(key).flatMap(Double.init) else { return defaultSize }
        return min(max(size, range.lowerBound), range.upperBound)
    }

    /// What to save under `key`.
    public static func encode(_ size: Double) -> String { String(size) }
}
