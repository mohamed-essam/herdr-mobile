import HerdrKit
import SwiftUI

// Shared pieces of herdr's look. Ported from Android's Components.kt, RepoAvatar.kt
// and StatusIndicator.kt.

/// A workspace's number on its cycled color.
struct WorkspaceBadge: View {
    let number: Int
    var size: CGFloat = 28
    @Environment(\.herdr) private var c

    var body: some View {
        Text(number > 0 ? "\(number)" : "·")
            .font(.system(size: size * 13 / 28, weight: .semibold, design: .monospaced))
            .foregroundStyle(avatarInk)
            .frame(width: size, height: size)
            .background(number > 0 ? c.badge(number) : c.surface1, in: .rect(cornerRadius: size / 3.5))
    }
}

/// A repo's monogram on its own accent.
struct RepoAvatar: View {
    let name: String
    var size: CGFloat = 28
    @Environment(\.herdr) private var c

    var body: some View {
        Text(monogram(name))
            .font(.system(size: size / 2, weight: .semibold, design: .monospaced))
            .foregroundStyle(avatarInk)
            .frame(width: size, height: size)
            .background(c.avatar(name), in: .rect(cornerRadius: size / 4))
    }
}

/// "● NEEDS YOU · 2": mono, tracked, uppercased.
struct SectionLabel: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text.uppercased()).font(HerdrFont.section).tracking(0.9).foregroundStyle(color)
    }
}

struct Wordmark: View {
    var font = HerdrFont.wordmark
    @Environment(\.herdr) private var c

    var body: some View {
        Text("\(Text("herdr ").foregroundStyle(c.text))\(Text("❯").foregroundStyle(c.mauve))").font(font)
    }
}

/// The working agent's ASCII spinner (| / - \); a still "*" with animations off.
struct Spinner: View {
    var font = HerdrFont.section
    @Environment(\.launchOptions) private var options

    var body: some View {
        if options.disableAnimations {
            Text(statusGlyph(.working)).font(font)
        } else {
            TimelineView(.periodic(from: .now, by: 0.2)) { t in
                Text(["|", "/", "-", "\\"][Int(t.date.timeIntervalSinceReferenceDate * 5) % 4]).font(font)
            }
        }
    }
}

/// A full-width filled button: mauve, or surface0 while disabled.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.herdr) private var c
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(HerdrFont.title)
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(enabled ? c.crust : c.overlay0)
            .background(enabled ? c.mauve : c.surface0, in: .rect(cornerRadius: HerdrRadius.card))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// surface0 with text; `ghost` is the outlined variant.
struct SecondaryButtonStyle: ButtonStyle {
    var ghost = false
    var height: CGFloat = 36
    @Environment(\.herdr) private var c
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(HerdrFont.small)
            .padding(.horizontal, 14)
            .frame(minHeight: height)
            .foregroundStyle(enabled ? (ghost ? c.subtext1 : c.text) : c.overlay0)
            .background(ghost ? Color.clear : c.surface0, in: .rect(cornerRadius: HerdrRadius.button))
            .overlay(RoundedRectangle(cornerRadius: HerdrRadius.button).stroke(ghost ? c.surface1 : .clear))
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.5)
    }
}

/// A card-shaped list, on mantle unless `background` says otherwise, with hairline dividers between rows.
struct ListCard<Content: View>: View {
    var background: Color?
    var radius = HerdrRadius.tile
    @ViewBuilder let content: Content
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(spacing: 0) {
            Group(subviews: content) { rows in
                ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                    if i > 0 { Divider().overlay(c.surface0) }
                    row
                }
            }
        }
        .background(background ?? c.mantle, in: .rect(cornerRadius: radius))
    }
}

/// A list row that is one button.
struct ListRowButton<Label: View>: View {
    let action: () -> Void
    @ViewBuilder let label: Label

    var body: some View {
        Button(action: action) {
            HStack { label }
                .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                .padding(.horizontal, 16)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// A "⌕" search field on `container`.
struct SearchField: View {
    @Binding var text: String
    let placeholder: String
    var container: Color?
    var id: String
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(spacing: 12) {
            Text("⌕").font(HerdrFont.code).foregroundStyle(c.overlay2)
            TextField(placeholder, text: $text)
                .font(HerdrFont.small)
                .foregroundStyle(c.text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier(id)
        }
        .padding(.horizontal, 16)
        .frame(height: 44)
        .background(container ?? c.base, in: .rect(cornerRadius: HerdrRadius.field))
    }
}

/// A sheet's title.
struct SheetTitle: View {
    let text: String
    @Environment(\.herdr) private var c

    var body: some View {
        Text(text).font(HerdrFont.headline).foregroundStyle(c.text).frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension View {
    /// A sheet's text field: on mantle, no autocapitalization or autocorrection.
    func herdrField(font: Font = HerdrFont.body) -> some View {
        modifier(HerdrField(font: font))
    }

    /// herdr's sheet chrome: `color` behind, the grab handle, full height unless `detents` says otherwise.
    func herdrSheet(_ color: Color, detents: Set<PresentationDetent> = [.large]) -> some View {
        presentationBackground(color)
            .presentationDragIndicator(.visible)
            .presentationDetents(detents)
    }
}

/// An error toast: a red capsule. Callers place it and clear it.
struct Toast: View {
    let message: String
    @Environment(\.herdr) private var c

    var body: some View {
        Text(message)
            .font(HerdrFont.small).foregroundStyle(c.crust)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(c.red, in: .capsule)
    }
}

private struct HerdrField: ViewModifier {
    let font: Font
    @Environment(\.herdr) private var c

    func body(content: Content) -> some View {
        content
            .font(font).foregroundStyle(c.text)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .padding(.horizontal, 16).frame(height: 48)
            .background(c.mantle, in: .rect(cornerRadius: HerdrRadius.field))
    }
}
