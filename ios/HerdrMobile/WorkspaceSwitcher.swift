import HerdrKit
import SwiftUI

/// Workspaces as tiles grouped under their repo, each with a status strip of its panes.
/// A long press opens the workspace's actions. Ported from Android's WorkspaceSwitcher.kt.
///
/// Implementer default (CPE-2094): a tap closes the switcher; opening the workspace's most
/// relevant pane waits for the chat (M5) and the terminal (M6).
struct WorkspaceSwitcher: View {
    let repos: [RepoNode]
    let actionsEnabled: Bool
    let onActions: (RowAction) -> Void
    let onNew: () -> Void
    let onDismiss: () -> Void
    @State private var query = ""
    @Environment(\.herdr) private var c

    var body: some View {
        let shown = filterRepos(repos, query: query)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Workspaces").font(HerdrFont.headline).foregroundStyle(c.text)
                    Spacer()
                    Button("+ New", action: onNew).buttonStyle(SecondaryButtonStyle(ghost: true)).disabled(!actionsEnabled)
                        .accessibilityIdentifier("switcherNew")
                }
                .padding(.bottom, 8)
                SearchField(text: $query, placeholder: "Search workspace, agent, path", id: "switcherSearch")
                if shown.isEmpty {
                    Text(repos.isEmpty ? "No workspaces yet" : "Nothing matches “\(query.trimmingCharacters(in: .whitespaces))”")
                        .font(HerdrFont.small).foregroundStyle(c.overlay2).padding(.vertical, 16)
                }
                ForEach(shown, id: \.name) { r in
                    HStack(spacing: 8) {
                        RepoAvatar(name: r.name, size: 16)
                        SectionLabel(text: r.name, color: c.overlay0)
                        Spacer()
                        Text("\(r.workspaces.count)").font(HerdrFont.meta).foregroundStyle(c.overlay0)
                    }
                    .padding(.top, 8)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("repo-\(r.name)")
                    LazyVGrid(columns: [GridItem(spacing: 8), GridItem(spacing: 8)], spacing: 8) {
                        ForEach(r.workspaces, id: \.workspace.workspaceId) { w in
                            WorkspaceTile(node: w, onTap: onDismiss,
                                          onLongPress: actionsEnabled && !w.isOrphans ? { onActions(workspaceAction(w)) } : nil)
                        }
                    }
                }
            }
            .padding(16)
        }
    }
}

private struct WorkspaceTile: View {
    let node: WorkspaceNode
    let onTap: () -> Void
    let onLongPress: (() -> Void)?
    @Environment(\.herdr) private var c

    var body: some View {
        let status = node.status
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                WorkspaceBadge(number: node.workspace.number)
                Spacer()
                HStack(spacing: 4) {
                    if status == .working { Spinner(font: HerdrFont.meta) } else { Text(statusGlyph(status)) }
                    Text(status.raw)
                }
                .font(HerdrFont.meta).foregroundStyle(c.status(status))
            }
            Text(node.workspace.displayLabel)
                .font(HerdrFont.title).foregroundStyle(c.text).lineLimit(1)
            StatusStrip(panes: node.panes)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(c.base, in: .rect(cornerRadius: HerdrRadius.card))
        .contentShape(.rect)
        .onTapGesture(perform: onTap)
        .onLongPressGesture { onLongPress?() }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Actions") { onLongPress?() }
        .accessibilityIdentifier("tile-\(node.workspace.workspaceId)")
    }
}

/// One segment per pane, colored by its agent's status; shells and idle agents are neutral.
private struct StatusStrip: View {
    let panes: [Pane]
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(spacing: 4) {
            if panes.isEmpty {
                segment(nil)
            } else {
                ForEach(Array(panes.enumerated()), id: \.offset) { _, p in segment(p.rowStatus?.agentStatus) }
            }
        }
    }

    private func segment(_ status: AgentStatus?) -> some View {
        let lit = [AgentStatus.blocked, .working, .done].contains(status)
        return RoundedRectangle(cornerRadius: 3).fill(lit ? c.status(status) : c.surface1).frame(height: 6)
    }
}
