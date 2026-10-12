import HerdrKit
import SwiftUI

/// The dashboard's one sheet at a time. Changing it replaces the sheet on screen.
enum DashboardSheet: Identifiable {
    case switcher
    case actions(RowAction)
    case rename(RowAction)
    case agents(RowAction)
    case otherAgent(RowAction)
    case move(RowAction)
    case servers
    case renameServer(Server)
    case removeServer(Server)

    var id: String {
        switch self {
        case .switcher: "switcher"
        case .actions(let a): "actions:\(a.id)"
        case .rename(let a): "rename:\(a.id)"
        case .agents(let a): "agents:\(a.id)"
        case .otherAgent(let a): "other:\(a.id)"
        case .move(let a): "move:\(a.id)"
        case .servers: "servers"
        case .renameServer(let s): "renameServer:\(s.id)"
        case .removeServer(let s): "removeServer:\(s.id)"
        }
    }
}

struct DashboardSheetView: View {
    let sheet: DashboardSheet
    let store: DashboardStore
    let connected: Bool
    let servers: ServerControls
    /// Replaces the sheet; nil dismisses it.
    let show: (DashboardSheet?) -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        Group {
            switch sheet {
            case .switcher:
                WorkspaceSwitcher(repos: store.repos, actionsEnabled: connected,
                                  onActions: { show(.actions($0)) },
                                  onNew: { run { await store.newWorkspace() } },
                                  onDismiss: { show(nil) })
            case .actions(let target): ActionSheet(target: target, store: store, show: show, run: run)
            case .rename(let target):
                RenameSheet(target: target) { label in run { await store.rename(target, to: label) } }
            case .agents(let target): AgentPicker(target: target, store: store, show: show, run: run)
            case .otherAgent(let target):
                OtherAgentSheet { command in run { await store.startAgent(command, in: target) } }
            case .move(let target): MoveSheet(target: target, store: store, run: run)
            case .servers:
                ServerSheet(controls: servers, onRename: { show(.renameServer($0)) }, onRemove: { show(.removeServer($0)) },
                            onDone: { show(nil) })
            case .renameServer(let s):
                ServerRenameSheet(server: s) { name in
                    show(nil)
                    servers.rename(s, name)
                }
            case .removeServer(let s):
                ServerRemoveSheet(server: s) {
                    show(nil)
                    servers.remove(s)
                }
            }
        }
        .herdrSheet(isSwitcher ? c.mantle : c.base)
    }

    private var isSwitcher: Bool {
        switch sheet {
        case .switcher, .servers: true
        default: false
        }
    }

    /// Closes the sheet and runs `action`.
    private func run(_ action: @escaping @MainActor () async -> Void) {
        show(nil)
        Task { await action() }
    }
}

/// Closes the sheet and runs a store action.
typealias RunAction = (@escaping @MainActor () async -> Void) -> Void

/// A pane's, tab's or workspace's actions: a header with its path, the common actions as
/// tiles, then "Tab actions" and a set-apart Close, which confirms in place when it must.
/// Ported from Android's ActionSheets.kt.
private struct ActionSheet: View {
    let target: RowAction
    let store: DashboardStore
    let show: (DashboardSheet?) -> Void
    let run: RunAction
    @State private var confirm: CloseSummary?
    @State private var alsoCloses: [String] = []
    @Environment(\.herdr) private var c

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let confirm {
                    CloseConfirmation(target: target, summary: confirm, alsoCloses: alsoCloses,
                                      onConfirm: { close() }, onCancel: { show(nil) })
                } else {
                    let header = actionHeader(for: target, in: store.repos)
                    Header(header: header)
                    tiles(header: header)
                    ListCard {
                        if target.kind == .pane, let tab = tabAction(ofPane: target.id, in: store.tree) {
                            ListRowButton(action: { show(.actions(tab)) }) {
                                Text("Tab actions").font(HerdrFont.body).foregroundStyle(c.text)
                                Spacer()
                                Text("›").font(HerdrFont.body).foregroundStyle(c.overlay0)
                            }
                            .accessibilityIdentifier("action-tabActions")
                        }
                        ListRowButton(action: askToClose) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Close \(target.kind.rawValue)").font(HerdrFont.title).foregroundStyle(c.red)
                                Text(closeSubtitle).font(HerdrFont.caption).foregroundStyle(c.overlay2)
                            }
                        }
                        .accessibilityIdentifier("action-close")
                    }
                }
            }
            .padding(16)
        }
    }

    private func tiles(header: ActionHeader) -> some View {
        let all: [Tile] = switch target.kind {
        case .pane: [
            Tile(id: "splitRight", glyph: "▯▯", title: "Split right", subtitle: "new shell") { run { await store.split(target, .right) } },
            Tile(id: "splitDown", glyph: "▭", title: "Split down", subtitle: "new shell") { run { await store.split(target, .down) } },
            Tile(id: "move", glyph: "↗", title: "Move…", subtitle: "to tab or workspace") { show(.move(target)) },
            Tile(id: "rename", glyph: "Aa", title: "Rename", subtitle: target.label) { show(.rename(target)) },
        ]
        case .tab: [
            Tile(id: "newShell", glyph: "$", title: "New shell", subtitle: "in this workspace") { run { await store.newShell(beside: target) } },
            Tile(id: "newAgent", glyph: "❯", title: "New agent", subtitle: "split down") { show(.agents(target)) },
            Tile(id: "rename", glyph: "Aa", title: "Rename", subtitle: header.title) { show(.rename(target)) },
        ]
        case .workspace: [
            Tile(id: "newTab", glyph: "+", title: "New tab", subtitle: "in this workspace") { run { await store.newTab(in: target) } },
            Tile(id: "newAgent", glyph: "❯", title: "New agent", subtitle: "in a new tab") { show(.agents(target)) },
            Tile(id: "rename", glyph: "Aa", title: "Rename", subtitle: target.label) { show(.rename(target)) },
        ]
        }
        return LazyVGrid(columns: [GridItem(spacing: 8), GridItem(spacing: 8)], spacing: 8) {
            ForEach(all) { TileView(tile: $0) }
        }
    }

    private var closeSubtitle: String {
        switch target.kind {
        case .pane: target.isAgent ? "Terminates \(target.label)" : "Ends this shell"
        case .tab, .workspace: "Ends \(counted(target.paneCount, "pane"))"
        }
    }

    private func askToClose() {
        guard needsCloseConfirm(target) else { return close() }
        Task {
            if target.kind == .workspace { alsoCloses = await store.closeImpact(workspaceId: target.id) }
            confirm = closeSummary(for: target, in: store.tree)
        }
    }

    private func close() { run { await store.close(target) } }
}

private struct Header: View {
    let header: ActionHeader
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(spacing: 12) {
            WorkspaceBadge(number: header.number, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(header.title).font(.system(size: 17, weight: .semibold)).foregroundStyle(c.text).lineLimit(1)
                    .accessibilityIdentifier("sheetTitle")
                if !header.path.isEmpty {
                    Text(header.path).font(HerdrFont.meta).foregroundStyle(c.overlay2).lineLimit(1)
                        .accessibilityIdentifier("sheetPath")
                }
            }
            Spacer()
            if let status = header.status {
                let color = c.status(status.agentStatus)
                Text(status.label).font(HerdrFont.meta).foregroundStyle(color)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(color.opacity(0.12), in: .rect(cornerRadius: HerdrRadius.badge))
            }
        }
    }
}

private struct Tile: Identifiable {
    let id: String
    let glyph: String
    let title: String
    let subtitle: String
    let action: () -> Void
}

private struct TileView: View {
    let tile: Tile
    @Environment(\.herdr) private var c

    var body: some View {
        Button(action: tile.action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(tile.glyph).font(.system(size: 15, design: .monospaced)).foregroundStyle(c.mauve)
                Text(tile.title).font(HerdrFont.title).foregroundStyle(c.text)
                Text(tile.subtitle).font(HerdrFont.caption).foregroundStyle(c.overlay2).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(c.surface0, in: .rect(cornerRadius: HerdrRadius.tile))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("action-\(tile.id)")
    }
}

/// What a close ends, the linked worktree workspaces it also closes, and a hold to confirm.
private struct CloseConfirmation: View {
    let target: RowAction
    let summary: CloseSummary
    let alsoCloses: [String]
    let onConfirm: () -> Void
    let onCancel: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "close \(target.kind.rawValue)", color: c.red)
                Text(summary.headline).font(HerdrFont.headline).foregroundStyle(c.text).accessibilityIdentifier("closeHeadline")
            }
            if !summary.panes.isEmpty {
                ListCard {
                    ForEach(Array(summary.panes.enumerated()), id: \.offset) { _, p in
                        HStack(spacing: 12) {
                            Text(statusGlyph(p.status?.agentStatus)).font(HerdrFont.small).foregroundStyle(c.status(p.status?.agentStatus))
                            Text(p.name).font(HerdrFont.body).foregroundStyle(p.status == .shell ? c.overlay2 : c.text).lineLimit(1)
                            Spacer()
                            Text(p.tab).font(HerdrFont.meta).foregroundStyle(c.overlay2)
                        }
                        .padding(.horizontal, 16).frame(minHeight: 48)
                    }
                }
            }
            if !alsoCloses.isEmpty {
                HStack(alignment: .top, spacing: 12) {
                    Text("⚠").font(HerdrFont.small.monospaced())
                    let names = Text(alsoCloses.joined(separator: ", ")).font(HerdrFont.small.monospaced().weight(.semibold))
                    Text("Also closes linked worktree workspace\(alsoCloses.count == 1 ? "" : "s") \(names)")
                }
                .font(HerdrFont.small).foregroundStyle(c.peach)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16).padding(.vertical, 12)
                .background(c.peach.opacity(0.08), in: .rect(cornerRadius: HerdrRadius.tile))
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("alsoCloses")
            }
            HStack(spacing: 8) {
                Button("Cancel", action: onCancel).buttonStyle(SecondaryButtonStyle(height: 52)).frame(width: 112)
                    .accessibilityIdentifier("closeCancel")
                HoldToClose(onConfirm: onConfirm)
            }
        }
    }
}

/// Press and hold for 1.2 s to confirm; the fill runs left to right and an early release
/// cancels. VoiceOver gets a direct "Close" action.
private struct HoldToClose: View {
    let onConfirm: () -> Void
    @State private var holding = false
    @State private var fired = false
    @Environment(\.herdr) private var c

    var body: some View {
        ZStack(alignment: .leading) {
            c.red.opacity(0.15)
            GeometryReader { g in c.red.frame(width: holding ? g.size.width : 0) }
            Text("Hold to close").font(HerdrFont.title).foregroundStyle(holding ? c.crust : c.red).frame(maxWidth: .infinity)
        }
        .frame(height: 52)
        .clipShape(.rect(cornerRadius: HerdrRadius.card))
        .onLongPressGesture(minimumDuration: 1.2, perform: fire) { pressing in
            withAnimation(.linear(duration: pressing ? 1.2 : 0.15)) { holding = pressing }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: "Close", fire)
        .accessibilityIdentifier("holdToClose")
    }

    private func fire() {
        guard !fired else { return }
        fired = true
        onConfirm()
    }
}

private struct RenameSheet: View {
    let target: RowAction
    let onSave: (String) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            SheetTitle(text: "Rename")
            TextField("", text: $text)
                .herdrField()
                .focused($focused)
                .onSubmit(save)
                .accessibilityIdentifier("renameField")
            Button("Save", action: save).buttonStyle(PrimaryButtonStyle())
                .disabled(trimmed.isEmpty || trimmed == target.label)
                .accessibilityIdentifier("renameSave")
            Spacer()
        }
        .padding(16)
        .onAppear { text = target.label; focused = true }
    }

    private func save() {
        if !trimmed.isEmpty && trimmed != target.label { onSave(trimmed) }
    }
}

/// The agents herdr knows, recent ones first, and "Other…" for a typed command.
private struct AgentPicker: View {
    let target: RowAction
    let store: DashboardStore
    let show: (DashboardSheet?) -> Void
    let run: RunAction
    @State private var query = ""
    @Environment(\.herdr) private var c

    var body: some View {
        let recent = query.trimmingCharacters(in: .whitespaces).isEmpty ? store.recentAgents.filter(store.agents.contains) : []
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SheetTitle(text: "New agent").padding(.bottom, 16)
                SearchField(text: $query, placeholder: "Search agents", container: c.mantle, id: "agentSearch")
                if !recent.isEmpty {
                    SectionLabel(text: "recent", color: c.overlay0).padding(.top, 16).padding(.bottom, 8)
                        .accessibilityIdentifier("section-recent")
                    list(recent, idPrefix: "recent")
                    SectionLabel(text: "all", color: c.overlay0).padding(.top, 16).padding(.bottom, 8)
                } else {
                    Spacer().frame(height: 16)
                }
                list(filterAgents(store.agents, query: query).filter { !recent.contains($0) }, idPrefix: "agent")
                Button("Other…") { show(.otherAgent(target)) }
                    .buttonStyle(SecondaryButtonStyle(ghost: true, height: 44))
                    .frame(maxWidth: .infinity)
                    .padding(.top, 8)
                    .accessibilityIdentifier("agentOther")
            }
            .padding(16)
        }
        .task { await store.refreshAgents() }
    }

    @ViewBuilder private func list(_ names: [String], idPrefix: String) -> some View {
        if !names.isEmpty {
            ListCard {
                ForEach(names, id: \.self) { name in
                    ListRowButton(action: { run { await store.startAgent(AgentCommand(name: name, argv: [name]), in: target) } }) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(name).font(HerdrFont.title).foregroundStyle(c.text)
                            if let d = describeAgent(name) { Text(d).font(HerdrFont.caption).foregroundStyle(c.overlay2) }
                        }
                    }
                    .accessibilityIdentifier("\(idPrefix)-\(name)")
                }
            }
        }
    }
}

/// "Run command": a typed agent command, split on whitespace.
private struct OtherAgentSheet: View {
    let onStart: (AgentCommand) -> Void
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            SheetTitle(text: "Run command")
            TextField("e.g. claude --model opus", text: $text)
                .herdrField(font: .system(size: 13, design: .monospaced))
                .accessibilityIdentifier("otherCommandField")
            Button("Start") { onStart(AgentCommand(parsing: text)) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(AgentCommand(parsing: text).argv.isEmpty)
                .accessibilityIdentifier("otherStart")
            Spacer()
        }
        .padding(16)
    }
}

/// Where to move a pane: a new tab, a new workspace, or another existing tab.
private struct MoveSheet: View {
    let target: RowAction
    let store: DashboardStore
    let run: RunAction
    @Environment(\.herdr) private var c

    var body: some View {
        let tree = store.tree
        let current = tabOf(paneId: target.id, in: tree)?.tab.tabId ?? ""
        let dests = tree.flatMap { w in w.tabs.filter { !$0.tab.tabId.isEmpty && $0.tab.tabId != current }.map { (w, $0) } }
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SheetTitle(text: "Move to…").padding(.bottom, 16)
                ListCard {
                    ListRowButton(action: { move(.newTab) }) { Text("+ New tab").font(HerdrFont.title).foregroundStyle(c.mauve) }
                        .accessibilityIdentifier("move-newTab")
                    ListRowButton(action: { move(.newWorkspace) }) { Text("+ New workspace").font(HerdrFont.title).foregroundStyle(c.mauve) }
                        .accessibilityIdentifier("move-newWorkspace")
                }
                if !dests.isEmpty {
                    SectionLabel(text: "existing tab", color: c.overlay0).padding(.top, 16).padding(.bottom, 8)
                    ListCard {
                        ForEach(dests, id: \.1.tab.tabId) { w, t in
                            ListRowButton(action: { run { await store.move(target, toTab: t.tab.tabId) } }) {
                                WorkspaceBadge(number: w.workspace.number, size: 20)
                                Text(w.workspace.displayLabel)
                                    .font(HerdrFont.body).foregroundStyle(c.text).lineLimit(1)
                                Spacer()
                                Text("tab \(t.tab.label.isEmpty ? "\(t.tab.number)" : t.tab.label)").font(HerdrFont.meta).foregroundStyle(c.overlay2)
                            }
                            .accessibilityIdentifier("move-tab-\(t.tab.tabId)")
                        }
                    }
                }
            }
            .padding(16)
        }
    }

    private func move(_ dest: MoveDest) { run { await store.move(target, to: dest) } }
}
