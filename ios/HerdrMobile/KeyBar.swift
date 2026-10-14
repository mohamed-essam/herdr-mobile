import HerdrKit
import SwiftUI

/// The terminal's key bar: [ctrl alt esc tab] over [home end pgup pgdn], the arrow d-pad, and a
/// tab that collapses it to a thin handle. ctrl and alt arm for one typed key on a tap and
/// lock on a long press. Dimmed and inert unless the terminal is live. Ported from Android's
/// TerminalScreen.kt `KeyToolbar`.
struct KeyBar: View {
    @Binding var mods: ModifierKeys
    let enabled: Bool
    let onKey: (TermKey) -> Void
    @State private var expanded = true
    @Environment(\.herdr) private var c

    var body: some View {
        Group {
            if expanded {
                HStack(spacing: 0) {
                    VStack(spacing: 4) {
                        HStack(spacing: 4) {
                            modifier("ctrl", .ctrl, state: mods.ctrl)
                            modifier("alt", .alt, state: mods.alt)
                            cap("esc", .esc)
                            cap("tab", .tab)
                        }
                        HStack(spacing: 4) {
                            cap("home", .home)
                            cap("end", .end)
                            cap("pgup", .pageUp)
                            cap("pgdn", .pageDown)
                        }
                    }
                    Rectangle().fill(c.surface1).frame(width: 1, height: 72).padding(.horizontal, 6)
                    dpad
                    Button { expanded = false } label: {
                        Text("⌄").font(.system(size: 17, design: .monospaced)).foregroundStyle(c.subtext1)
                            .frame(width: 22, height: 72).contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .padding(.leading, 2)
                    .accessibilityLabel("hide keys")
                    .accessibilityIdentifier("keysHide")
                }
                .padding(6)
                .opacity(enabled ? 1 : 0.4)
                .disabled(!enabled)
            } else {
                Button { expanded = true } label: {
                    Text("⌃").font(.system(size: 17, design: .monospaced)).foregroundStyle(c.subtext1)
                        .frame(maxWidth: .infinity).frame(height: 20).contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("show keys")
                .accessibilityIdentifier("keysShow")
            }
        }
        .background(c.mantle)
    }

    /// ↑ over ← ↓ →, in a recessed well.
    private var dpad: some View {
        VStack(spacing: 4) {
            arrow("↑", .up, name: "up")
            HStack(spacing: 4) {
                arrow("←", .left, name: "left")
                arrow("↓", .down, name: "down")
                arrow("→", .right, name: "right")
            }
        }
        .padding(3)
        .background(KeyCapStyle.well, in: .rect(cornerRadius: 12))
    }

    private func cap(_ label: String, _ key: TermKey) -> some View {
        Button { onKey(key) } label: { Text(label).font(KeyCapStyle.font(13)) }
            .buttonStyle(KeyCapStyle(face: KeyCapStyle.face))
            .accessibilityIdentifier("key-\(label)")
    }

    private func arrow(_ glyph: String, _ key: TermKey, name: String) -> some View {
        Button { onKey(key) } label: { Text(glyph).font(KeyCapStyle.font(15)) }
            .buttonStyle(KeyCapStyle(face: KeyCapStyle.arrowFace, width: 30))
            .accessibilityLabel(name)
            .accessibilityIdentifier("key-\(name)")
    }

    private func modifier(_ label: String, _ key: ModifierKeys.Key, state: ModState) -> some View {
        ModifierCap(label: label, state: state, onTap: { mods.tap(key) }, onLock: { mods.lock(key) })
    }
}

/// A sticky modifier cap: armed gets a mauve ring, locked a solid mauve face and an
/// uppercased label; its accessibility value says off / armed / locked.
private struct ModifierCap: View {
    let label: String
    let state: ModState
    let onTap: () -> Void
    let onLock: () -> Void
    @Environment(\.herdr) private var c

    private var stateName: String {
        switch state {
        case .off: "off"
        case .oneShot: "armed"
        case .locked: "locked"
        }
    }

    var body: some View {
        let face: AnyShapeStyle = switch state {
        case .off: AnyShapeStyle(KeyCapStyle.face)
        case .oneShot: AnyShapeStyle(c.mauve.opacity(0.22))
        case .locked: AnyShapeStyle(c.mauve)
        }
        KeyCapStyle.cap(face: face, ring: state == .oneShot ? c.mauve : nil) {
            Text(state == .locked ? label.uppercased() : label).font(KeyCapStyle.font(13))
                .foregroundStyle(state == .locked ? c.crust : KeyCapStyle.ink)
        }
        .frame(maxWidth: .infinity)
        .contentShape(.rect)
        .onTapGesture(perform: onTap)
        .onLongPressGesture(minimumDuration: 0.5, perform: onLock)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(stateName)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("key-\(label)")
    }
}

/// The tactile cap: a dark lip with a gradient face inset 2 pt at the bottom. The bar is
/// always the dark terminal surface.
private struct KeyCapStyle: ButtonStyle {
    static let face = LinearGradient(colors: [Color(hex: 0x3E4056), Color(hex: 0x2A2C3D)], startPoint: .top, endPoint: .bottom)
    static let arrowFace = LinearGradient(colors: [Color(hex: 0x4E5168), Color(hex: 0x363849)], startPoint: .top, endPoint: .bottom)
    static let lip = Color(hex: 0x15151F)
    static let well = HerdrColors.mocha.crust
    static let ink = HerdrColors.mocha.text

    let face: LinearGradient
    var width: CGFloat?

    static func font(_ size: CGFloat) -> Font { .custom(HerdrFont.terminalFace, fixedSize: size) }

    static func cap(face: some ShapeStyle, ring: Color? = nil, @ViewBuilder label: () -> some View) -> some View {
        let shape = RoundedRectangle(cornerRadius: 9)
        return ZStack(alignment: .top) {
            shape.fill(lip).frame(height: 34)
            label().lineLimit(1).padding(.horizontal, 4)
                .frame(maxWidth: .infinity).frame(height: 32)
                .background(face, in: shape)
                .overlay { if let ring { shape.stroke(ring, lineWidth: 1.5) } }
        }
    }

    func makeBody(configuration: Configuration) -> some View {
        Self.cap(face: face) { configuration.label.foregroundStyle(Self.ink) }
            .frame(width: width).frame(maxWidth: width == nil ? .infinity : nil)
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}
