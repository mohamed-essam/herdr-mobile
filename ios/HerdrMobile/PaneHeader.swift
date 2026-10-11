import HerdrKit
import SwiftUI

/// The header of a pane's chat or thread: back, the terminal toggle, then the workspace
/// badge and title and a mono breadcrumb ending in the live status ("… › claude · working").
/// Ported from Android's PaneHeader.kt.
///
/// Implementer default (CPE-2096): the terminal is M6, so ">_ terminal" shows disabled.
struct PaneHeader: View {
    let context: PaneContext
    let breadcrumb: String
    let status: String
    let statusColor: Color
    let onBack: () -> Void
    var showsToggle = false
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button(action: onBack) {
                    Text("←").font(.system(size: 17, weight: .semibold)).foregroundStyle(c.text)
                        .frame(width: 40, height: 40).contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Back")
                .accessibilityIdentifier("chatBack")
                Spacer()
                if showsToggle {
                    Button(">_ terminal") {}
                        .font(HerdrFont.meta).foregroundStyle(c.overlay0)
                        .padding(.horizontal, 12).frame(height: 36)
                        .background(c.base, in: .rect(cornerRadius: HerdrRadius.field))
                        .disabled(true)
                        .accessibilityIdentifier("terminalToggle")
                }
            }
            .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 4)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    WorkspaceBadge(number: context.workspaceNumber, size: 20)
                    Text(context.workspaceLabel).font(HerdrFont.display).foregroundStyle(c.text).lineLimit(1)
                        .accessibilityIdentifier("chatTitle")
                }
                Text("\(breadcrumb.isEmpty ? "" : "\(breadcrumb) · ")\(Text(status).foregroundStyle(statusColor))")
                    .font(HerdrFont.meta).foregroundStyle(c.overlay2).lineLimit(1)
                    .accessibilityIdentifier("chatStatus")
            }
            .padding(.horizontal, 20).padding(.top, 4).padding(.bottom, 16)
            Rectangle().fill(c.base).frame(height: 1)
        }
    }
}
