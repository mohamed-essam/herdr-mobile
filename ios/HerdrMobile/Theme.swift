import HerdrKit
import SwiftUI

/// herdr's dark, terminal-first Catppuccin look: Mocha in dark mode, Latte in light,
/// with herdr's status colors. Ported from Android's Palette.kt and Theme.kt.
/// https://catppuccin.com
struct HerdrColors {
    let crust, mantle, base, surface0, surface1, surface2, overlay0, overlay2, subtext1, text: Color
    let mauve, blue, green, yellow, peach, red: Color
    let isDark: Bool

    static let mocha = HerdrColors(
        crust: Color(hex: 0x11111B), mantle: Color(hex: 0x181825), base: Color(hex: 0x1E1E2E),
        surface0: Color(hex: 0x313244), surface1: Color(hex: 0x45475A), surface2: Color(hex: 0x585B70),
        overlay0: Color(hex: 0x6C7086), overlay2: Color(hex: 0x9399B2), subtext1: Color(hex: 0xBAC2DE), text: Color(hex: 0xCDD6F4),
        mauve: Color(hex: 0xCBA6F7), blue: Color(hex: 0x89B4FA), green: Color(hex: 0xA6E3A1),
        yellow: Color(hex: 0xF9E2AF), peach: Color(hex: 0xFAB387), red: Color(hex: 0xF38BA8), isDark: true)

    static let latte = HerdrColors(
        crust: Color(hex: 0xDCE0E8), mantle: Color(hex: 0xE6E9EF), base: Color(hex: 0xEFF1F5),
        surface0: Color(hex: 0xCCD0DA), surface1: Color(hex: 0xBCC0CC), surface2: Color(hex: 0xACB0BE),
        overlay0: Color(hex: 0x9CA0B0), overlay2: Color(hex: 0x7C7F93), subtext1: Color(hex: 0x5C5F77), text: Color(hex: 0x4C4F69),
        mauve: Color(hex: 0x8839EF), blue: Color(hex: 0x1E66F5), green: Color(hex: 0x40A02B),
        yellow: Color(hex: 0xDF8E1D), peach: Color(hex: 0xFE640B), red: Color(hex: 0xD20F39), isDark: false)

    /// An agent's status → herdr's color; a shell (nil) or an unknown status is neutral.
    func status(_ s: AgentStatus?) -> Color {
        switch s {
        case .blocked: red
        case .working: yellow
        case .done: green
        case .idle: isDark ? overlay2 : overlay0
        default: overlay0
        }
    }

    /// A workspace badge, cycled by herdr's number so a workspace keeps its color everywhere.
    func badge(_ number: Int) -> Color {
        let cycle = [blue, green, peach, mauve]
        return cycle[((number - 1) % cycle.count + cycle.count) % cycle.count]
    }

    /// A stable accent for a repo name.
    func avatar(_ seed: String) -> Color {
        let accents = [mauve, blue, green, yellow, peach, red]
        let h = seed.unicodeScalars.reduce(Int32(0)) { $0 &* 31 &+ Int32(truncatingIfNeeded: $1.value) }
        return accents[(Int(h) % accents.count + accents.count) % accents.count]
    }
}

/// Dark ink for text on a bright accent, in both themes.
let avatarInk = Color(hex: 0x11111B)

/// herdr's terminal glyph for a status; working spins separately.
func statusGlyph(_ s: AgentStatus?) -> String {
    switch s {
    case .blocked: "●"
    case .working: "*"
    case .done: "✓"
    case .idle: "○"
    default: "·"
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

extension EnvironmentValues {
    var herdr: HerdrColors { colorScheme == .dark ? .mocha : .latte }
    /// The launch switches, for the clock and animations.
    @Entry var launchOptions = LaunchOptions { _ in nil }
}

/// The type ramp by role. Mono for identity, numbers and tool output; the system face for prose.
/// The Android app bundles JetBrains Mono and Geist; this uses the system's own faces, except
/// for the terminal and its key bar, which bundle JetBrains Mono.
enum HerdrFont {
    /// The bundled terminal face's PostScript name.
    static let terminalFace = "JetBrainsMono-Regular"
    static let display = Font.system(size: 28, weight: .semibold)
    static let headline = Font.system(size: 22, weight: .semibold)
    static let title = Font.system(size: 15, weight: .semibold)
    static let body = Font.system(size: 15)
    static let small = Font.system(size: 13)
    static let caption = Font.system(size: 11)
    static let wordmark = Font.system(size: 17, weight: .semibold, design: .monospaced)
    static let stat = Font.system(size: 28, weight: .semibold, design: .monospaced)
    static let badge = Font.system(size: 13, weight: .semibold, design: .monospaced)
    static let code = Font.system(size: 12, design: .monospaced)
    static let meta = Font.system(size: 11, design: .monospaced)
    static let section = Font.system(size: 11, weight: .semibold, design: .monospaced)
}

enum HerdrRadius {
    static let badge: CGFloat = 8
    static let button: CGFloat = 10
    static let field: CGFloat = 12
    static let tile: CGFloat = 14
    static let card: CGFloat = 16
}
