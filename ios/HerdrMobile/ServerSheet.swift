import HerdrKit
import SwiftUI

/// The saved servers as the dashboard shows them, and what it can do with them.
struct ServerControls {
    let servers: [Server]
    let activeId: String
    let switchTo: (Server) -> Void
    let rename: (Server, String) -> Void
    let remove: (Server) -> Void
    let add: () -> Void

    var active: Server? { servers.first { $0.id == activeId } }
}

/// The dashboard header's active-server chip; opens the server list.
struct ServerChip: View {
    let name: String
    let onTap: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Text(name).font(HerdrFont.meta).foregroundStyle(c.subtext1).lineLimit(1).frame(maxWidth: 160)
                Text("▾").font(HerdrFont.meta).foregroundStyle(c.overlay2).accessibilityHidden(true)
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(c.base, in: .capsule)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("serverChip")
    }
}

/// Saved servers: tap one to switch to it, its ⋯ to rename or remove it, and "+ Add server"
/// to connect another. Ported from Android's ServerSheet.kt.
struct ServerSheet: View {
    let controls: ServerControls
    let onRename: (Server) -> Void
    let onRemove: (Server) -> Void
    let onDone: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SheetTitle(text: "Servers").padding(.bottom, 8)
            ForEach(controls.servers) { s in row(s, active: s.id == controls.activeId) }
            Button("+ Add server") { onDone(); controls.add() }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("addServer")
                .padding(.top, 8)
            Spacer()
        }
        .padding(16)
    }

    private func row(_ s: Server, active: Bool) -> some View {
        HStack(spacing: 4) {
            Button {
                onDone()
                controls.switchTo(s)
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.name).font(HerdrFont.title).foregroundStyle(c.text).lineLimit(1)
                        Text(s.hostPort).font(HerdrFont.meta).foregroundStyle(c.overlay2).lineLimit(1)
                    }
                    Spacer()
                    if active { Text("✓").font(HerdrFont.title).foregroundStyle(c.mauve) }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("server-\(s.name)")
            Menu {
                Button("Rename") { onRename(s) }
                Button("Remove", role: .destructive) { onRemove(s) }
            } label: {
                Text("⋯").font(HerdrFont.title).foregroundStyle(c.subtext1).frame(width: 44, height: 44).contentShape(.rect)
            }
            .accessibilityLabel("Server actions")
            .accessibilityIdentifier("serverMenu-\(s.name)")
        }
        .padding(.leading, 16).padding(.vertical, 8).padding(.trailing, 4)
        .background(c.mantle, in: .rect(cornerRadius: HerdrRadius.tile))
        .overlay(RoundedRectangle(cornerRadius: HerdrRadius.tile).stroke(active ? c.mauve.opacity(0.5) : .clear))
    }
}

/// A new name for a server; blank goes back to its address.
struct ServerRenameSheet: View {
    let server: Server
    let onSave: (String) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool
    @Environment(\.herdr) private var c

    private var changed: Bool { text.trimmed != server.name }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            SheetTitle(text: "Rename server")
            TextField(server.hostPort, text: $text)
                .herdrField()
                .focused($focused)
                .onSubmit { if changed { onSave(text) } }
                .accessibilityIdentifier("serverRenameField")
            Text("Leave blank to use the address.").font(HerdrFont.small).foregroundStyle(c.overlay2)
            Button("Save") { onSave(text) }.buttonStyle(PrimaryButtonStyle())
                .disabled(!changed)
                .accessibilityIdentifier("serverRenameSave")
            Spacer()
        }
        .padding(16)
        .onAppear { text = server.name; focused = true }
    }
}

/// "Remove <name>?", confirmed in a sheet.
struct ServerRemoveSheet: View {
    let server: Server
    let onConfirm: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            SheetTitle(text: "Remove \(server.name)?")
            Text("Its address is forgotten. Agents on it keep running.").font(HerdrFont.body).foregroundStyle(c.subtext1)
            Button("Remove", action: onConfirm).buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("serverRemoveConfirm")
            Spacer()
        }
        .padding(16)
    }
}
