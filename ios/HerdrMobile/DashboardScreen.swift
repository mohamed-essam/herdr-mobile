import HerdrKit
import SwiftUI

/// The attention-first dashboard: what needs you (answerable in place), what's working,
/// what's done, then the rest, with the workspace switcher and "+" at the bottom.
/// Ported from Android's DashboardScreen.kt.
///
/// Implementer default (CPE-2094): a pane has nowhere to open until the chat (M5) and the
/// terminal (M6), so tapping a row does nothing and "Other…" / "Answer in chat ›" are
/// disabled. A long press opens the pane's actions.
struct DashboardScreen: View {
    let client: CompanionClient
    let store: DashboardStore
    @State private var sheet: DashboardSheet?
    /// When the connection last dropped; nil while connected.
    @State private var disconnectedSince: Date?
    @Environment(\.launchOptions) private var options
    @Environment(\.herdr) private var c

    private var connected: Bool { client.state == .connected }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { _ in
            // Derived once per render: the store computes these, it doesn't keep them.
            let panes = store.panes
            let repos = store.repos
            let contexts = paneContexts(repos)
            ZStack(alignment: .bottom) {
                c.crust.ignoresSafeArea()
                if panes.isEmpty {
                    if connected { EmptyState { Task { await store.newWorkspace() } } } else { ConnectingState() }
                } else {
                    content(panes: panes, contexts: contexts, now: options.now().epochMs)
                    bottom(repos: repos, contexts: contexts)
                }
            }
        }
        .overlay(alignment: .top) { errorToast }
        .onChange(of: connected, initial: true) { _, up in
            disconnectedSince = up ? nil : disconnectedSince ?? options.now()
        }
        .sheet(item: $sheet) { s in
            DashboardSheetView(sheet: s, store: store, connected: connected, show: { sheet = $0 })
        }
    }

    private func content(panes: [Pane], contexts: [String: PaneContext], now: Int) -> some View {
        let sections = DashboardSections(panes: panes, answers: store.answers)
        let resumed = store.answers.resumed
        // Rebuilt from the same live panes, so a context always holds the current pane.
        func ctx(_ p: Pane) -> PaneContext { contexts[p.paneId] ?? PaneContext(fallback: p) }
        func actions(_ p: Pane) { if connected { sheet = .actions(paneAction(p)) } }
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                TopBar(connected: connected, count: panes.count)
                if !connected { ReconnectBanner(since: disconnectedSince) }
                StatTiles(counts: StatusCounts(panes: panes))
                Group {
                    if !sections.needsYou.isEmpty || !resumed.isEmpty {
                        SectionHeader(id: "needs", text: "● needs you · \(sections.needsYou.count)", color: c.red)
                        ForEach(sections.needsYou, id: \.paneId) { p in
                            NeedsYouCard(ctx: ctx(p), now: now, sending: store.answers[p.paneId].flatMap { $0.phase == .sending ? $0 : nil },
                                         enabled: connected, onAnswer: { label in Task { await store.answer(p, label: label) } },
                                         onActions: { actions(p) })
                            .id("needs:\(p.paneId)") // a resumed row of the same pane sits beside it
                        }
                        ForEach(resumed, id: \.paneId) { a in
                            let context = contexts[a.paneId]
                            ResumedRow(paneId: a.paneId, label: context?.workspaceLabel ?? "pane", number: context?.workspaceNumber ?? 0, answer: a.label)
                                .id("resumed:\(a.paneId)")
                        }
                    }
                    PaneGroup(name: "working", glyph: nil, color: c.yellow, accentAge: true, ctxs: sections.working.map(ctx), now: now, onActions: actions)
                    PaneGroup(name: "done", glyph: "✓", color: c.green, accentAge: false, ctxs: sections.done.map(ctx), now: now, onActions: actions)
                    PaneGroup(name: "idle", glyph: "○", color: c.overlay2, accentAge: false, ctxs: sections.idle.map(ctx), now: now, onActions: actions)
                }
                .opacity(connected ? 1 : 0.45)
            }
            .padding(.bottom, 96)
        }
    }

    private func bottom(repos: [RepoNode], contexts: [String: PaneContext]) -> some View {
        Group {
            if let last = store.answers.resumed.last {
                let label = contexts[last.paneId]?.workspaceLabel ?? "pane"
                Text("Answer sent to \(label)")
                    .font(HerdrFont.small).foregroundStyle(c.text)
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .padding(.horizontal, 20)
                    .background(c.surface0, in: .capsule)
                    .accessibilityIdentifier("answerToast")
            } else {
                BottomBar(workspaces: repos.flatMap(\.workspaces), fabEnabled: connected,
                          onSwitcher: { sheet = .switcher }, onNew: { Task { await store.newWorkspace() } })
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 16)
    }

    @ViewBuilder private var errorToast: some View {
        if let error = store.error {
            Text(error)
                .font(HerdrFont.small).foregroundStyle(c.crust)
                .padding(.horizontal, 16).padding(.vertical, 12)
                .background(c.red, in: .capsule)
                .padding(.top, 8)
                .accessibilityIdentifier("actionError")
                .task(id: error) {
                    try? await Task.sleep(for: .seconds(4))
                    store.clearError()
                }
        }
    }
}

private struct TopBar: View {
    let connected: Bool
    let count: Int
    @Environment(\.herdr) private var c

    var body: some View {
        HStack {
            Wordmark()
            Spacer()
            if connected {
                HStack(spacing: 6) {
                    Circle().fill(c.green).frame(width: 6, height: 6)
                    Text(counted(count, "pane")).font(HerdrFont.meta).foregroundStyle(c.subtext1)
                }
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(c.base, in: .capsule)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("paneCount")
            }
        }
        .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 16)
    }
}

/// "Reconnecting…", with how old the shown state is, ticking every second.
private struct ReconnectBanner: View {
    /// When the connection dropped; nil until the screen has seen it drop.
    let since: Date?
    @Environment(\.launchOptions) private var options
    @Environment(\.herdr) private var c

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let now = options.now()
            banner(stale: staleLabel(sinceMs: (since ?? now).epochMs, now: now.epochMs))
        }
    }

    private func banner(stale: String) -> some View {
        HStack(spacing: 12) {
            Spinner(font: .system(size: 15, weight: .semibold, design: .monospaced)).foregroundStyle(c.yellow)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Reconnecting…").font(HerdrFont.title).foregroundStyle(c.yellow)
                Text("Showing state from \(stale)").font(HerdrFont.caption).foregroundStyle(c.subtext1)
            }
            Spacer()
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(c.yellow.opacity(0.1), in: .rect(cornerRadius: HerdrRadius.tile))
        .overlay(RoundedRectangle(cornerRadius: HerdrRadius.tile).stroke(c.yellow.opacity(0.2)))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("reconnecting")
        .padding(.horizontal, 16).padding(.bottom, 16)
    }
}

private struct StatTiles: View {
    let counts: StatusCounts
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(spacing: 8) {
            ForEach([(AgentStatus.blocked, counts.blocked), (.working, counts.working), (.done, counts.done)], id: \.0) { status, n in
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(n)").font(HerdrFont.stat).foregroundStyle(c.status(status))
                    Text(status.raw).font(HerdrFont.caption).foregroundStyle(c.overlay2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(c.base, in: .rect(cornerRadius: HerdrRadius.tile))
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("stat-\(status.raw)")
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 24)
    }
}

private struct SectionHeader: View {
    let id: String
    let text: String
    let color: Color
    /// Leads with the working spinner.
    var spinner = false

    var body: some View {
        HStack(spacing: 0) {
            if spinner { Spinner().foregroundStyle(color); Text(" ") }
            SectionLabel(text: text, color: color)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(text)
        .accessibilityIdentifier("section-\(id)")
        .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 8)
    }
}

/// A blocked pane: its question with option chips when the dashboard can answer it.
private struct NeedsYouCard: View {
    let ctx: PaneContext
    let now: Int
    /// This pane's answer while it sends.
    let sending: InlineAnswer?
    let enabled: Bool
    let onAnswer: (String) -> Void
    let onActions: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        let pane = ctx.pane
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                WorkspaceBadge(number: ctx.workspaceNumber)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ctx.workspaceLabel).font(HerdrFont.title).foregroundStyle(c.text).lineLimit(1)
                    Text(ctx.meta).font(HerdrFont.meta).foregroundStyle(c.overlay2).lineLimit(1)
                }
                Spacer()
                Text(relativeAge(ms: pane.activity?.ts ?? 0, now: now)).font(HerdrFont.meta).foregroundStyle(c.overlay2)
            }
            if let ask = pane.ask, let choice = ask.inlineChoice {
                question(choice.question)
                if let sending, sending.toolUseId == ask.toolUseId {
                    SendingOptions(paneId: pane.paneId, item: choice, picked: sending.label)
                } else {
                    FlowLayout(spacing: 8) {
                        ForEach(choice.options, id: \.label) { o in
                            Button(o.label) { onAnswer(o.label) }
                                .buttonStyle(SecondaryButtonStyle())
                                .disabled(!enabled)
                                .accessibilityIdentifier("choice-\(pane.paneId)-\(o.label)")
                        }
                        Button("Other…") {}
                            .buttonStyle(SecondaryButtonStyle(ghost: true))
                            .disabled(true)
                            .accessibilityIdentifier("other-\(pane.paneId)")
                    }
                }
            } else if let ask = pane.ask {
                question(ask.questions.first.map(\.question).flatMap { $0.isEmpty ? nil : $0 } ?? "Claude has a question")
                Button("Answer in chat ›") {}
                    .buttonStyle(SecondaryButtonStyle(ghost: true, height: 40))
                    .disabled(true)
                    .accessibilityIdentifier("answerInChat-\(pane.paneId)")
            } else {
                Text(activityLine(pane.activity) ?? "Waiting for you")
                    .font(HerdrFont.body).foregroundStyle(c.subtext1).lineLimit(3)
            }
            if let badge = pane.bgBadge { BgChip(paneId: pane.paneId, text: badge) }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.base, in: .rect(cornerRadius: HerdrRadius.card))
        .overlay(RoundedRectangle(cornerRadius: HerdrRadius.card).stroke(c.red.opacity(0.22)))
        .contentShape(.rect)
        .onLongPressGesture(perform: onActions)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Actions", onActions)
        .accessibilityIdentifier("needs-\(pane.paneId)")
        .padding(.horizontal, 16).padding(.vertical, 4)
    }

    private func question(_ text: String) -> some View {
        Text(text).font(HerdrFont.body).foregroundStyle(c.text).accessibilityIdentifier("question-\(ctx.pane.paneId)")
    }
}

/// The options while an answer sends: the picked one mauve with "sending…", the rest dimmed.
private struct SendingOptions: View {
    let paneId: String
    let item: QuestionItem
    let picked: String
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(spacing: 8) {
            ForEach(item.options, id: \.label) { o in
                let mine = o.label == picked
                HStack {
                    Text(o.label).font(mine ? HerdrFont.title : HerdrFont.body).foregroundStyle(mine ? c.crust : c.text).lineLimit(1)
                    Spacer()
                    if mine { Text("sending…").font(HerdrFont.meta).foregroundStyle(c.crust) }
                }
                .padding(.horizontal, 16)
                .frame(height: 44)
                .background(mine ? c.mauve : c.surface0, in: .rect(cornerRadius: HerdrRadius.field))
                .opacity(mine ? 1 : 0.5)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("sending-\(paneId)")
    }
}

/// "✓ api resumed / You answered SQLite" after an answer lands.
private struct ResumedRow: View {
    let paneId: String
    let label: String
    let number: Int
    let answer: String
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(spacing: 12) {
            Text("✓").font(HerdrFont.title.monospaced()).foregroundStyle(c.crust)
                .frame(width: 28, height: 28).background(c.green, in: .circle).shadow(color: c.green, radius: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(label) resumed").font(HerdrFont.title).foregroundStyle(c.text).lineLimit(1)
                Text("You answered \(answer)").font(HerdrFont.small).foregroundStyle(c.overlay2).lineLimit(1)
            }
            Spacer()
            WorkspaceBadge(number: number, size: 20)
        }
        .padding(16)
        .background(c.base, in: .rect(cornerRadius: HerdrRadius.card))
        .overlay(RoundedRectangle(cornerRadius: HerdrRadius.card).stroke(c.green.opacity(0.25)))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("resumed-\(paneId)")
        .padding(.horizontal, 16).padding(.vertical, 4)
    }
}

/// A pane's background tasks still running: "⟳ 2".
private struct BgChip: View {
    let paneId: String
    let text: String
    @Environment(\.herdr) private var c

    var body: some View {
        Text(text).font(HerdrFont.meta).foregroundStyle(c.yellow)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(c.yellow.opacity(0.12), in: .rect(cornerRadius: HerdrRadius.badge))
            .accessibilityIdentifier("bg-\(paneId)")
    }
}

/// A "<glyph> NAME · n" header over a card of rows; a nil `glyph` is the working spinner.
private struct PaneGroup: View {
    let name: String
    let glyph: String?
    let color: Color
    /// Colors the age in the section's color.
    let accentAge: Bool
    let ctxs: [PaneContext]
    let now: Int
    let onActions: (Pane) -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        if !ctxs.isEmpty {
            SectionHeader(id: name, text: "\(glyph.map { "\($0) " } ?? "")\(name) · \(ctxs.count)", color: color,
                          spinner: glyph == nil)
                .padding(.top, 8)
            ListCard(background: c.base, radius: HerdrRadius.card) {
                ForEach(ctxs, id: \.pane.paneId) { ctx in
                    PaneRow(ctx: ctx, ageColor: accentAge ? color : c.overlay2, now: now, onActions: { onActions(ctx.pane) })
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

private struct PaneRow: View {
    let ctx: PaneContext
    let ageColor: Color
    let now: Int
    let onActions: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        let pane = ctx.pane
        HStack(spacing: 12) {
            WorkspaceBadge(number: ctx.workspaceNumber)
            VStack(alignment: .leading, spacing: 2) {
                let repo = !ctx.repoName.isEmpty && ctx.repoName != ctx.workspaceLabel ? " · \(ctx.repoName)" : ""
                Text("\(Text(ctx.workspaceLabel).font(HerdrFont.title).foregroundStyle(c.text))\(Text(repo).font(.system(size: 13)).foregroundStyle(c.overlay2))")
                    .lineLimit(1)
                HStack(spacing: 8) {
                    Text(activityLine(pane.activity) ?? [pane.displayName, ctx.tabCrumb].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(HerdrFont.meta).foregroundStyle(c.subtext1).lineLimit(1)
                    if let badge = pane.bgBadge { BgChip(paneId: pane.paneId, text: badge) }
                }
            }
            Spacer()
            let age = relativeAge(ms: pane.activity?.ts ?? 0, now: now)
            if !age.isEmpty { Text(age).font(HerdrFont.meta).foregroundStyle(ageColor) }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .contentShape(.rect)
        .onLongPressGesture(perform: onActions)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Actions", onActions)
        .accessibilityIdentifier("row-\(pane.paneId)")
    }
}

private struct BottomBar: View {
    let workspaces: [WorkspaceNode]
    let fabEnabled: Bool
    let onSwitcher: () -> Void
    let onNew: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onSwitcher) {
                HStack(spacing: 12) {
                    ZStack(alignment: .leading) {
                        ForEach(Array(workspaces.prefix(4).enumerated()), id: \.offset) { i, w in
                            WorkspaceBadge(number: w.workspace.number, size: 20).padding(1)
                                .background(c.surface0, in: .rect(cornerRadius: 7)).offset(x: CGFloat(14 * i))
                        }
                    }
                    .frame(width: CGFloat(22 + 14 * max(0, min(workspaces.count, 4) - 1)), alignment: .leading)
                    Text("Workspaces").font(HerdrFont.body).foregroundStyle(c.text)
                    Spacer()
                    Text("\(workspaces.count)").font(HerdrFont.meta).foregroundStyle(c.overlay2)
                }
                .padding(.horizontal, 20)
                .frame(height: 52)
                .background(c.surface0, in: .capsule)
                .shadow(radius: 8)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("workspacesButton")
            Button(action: onNew) {
                Text("+").font(.system(size: 24)).foregroundStyle(fabEnabled ? c.crust : c.overlay0)
                    .frame(width: 52, height: 52)
                    .background(fabEnabled ? c.mauve : c.surface0, in: .circle)
                    .shadow(color: fabEnabled ? c.mauve.opacity(0.5) : .clear, radius: 8)
            }
            .buttonStyle(.plain)
            .disabled(!fabEnabled)
            .accessibilityLabel("New workspace")
            .accessibilityIdentifier("newWorkspaceButton")
        }
    }
}

private struct EmptyState: View {
    let onNew: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TopBar(connected: true, count: 0)
            Spacer()
            VStack(alignment: .leading, spacing: 24) {
                Text("The herd is resting").font(HerdrFont.display).foregroundStyle(c.text)
                Text("Start an agent in herdr on your desktop and it appears here, or start one from your phone.")
                    .font(HerdrFont.body).foregroundStyle(c.subtext1)
                Text("\(Text("❯").foregroundStyle(c.mauve))\(Text(" herdr").foregroundStyle(c.subtext1))")
                    .font(.system(size: 13, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(c.base, in: .rect(cornerRadius: HerdrRadius.field))
            }
            .padding(.horizontal, 32)
            Spacer()
            Button("+ New workspace", action: onNew).buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("emptyNewWorkspace")
                .padding(24)
        }
        .accessibilityIdentifier("emptyState")
    }
}

private struct ConnectingState: View {
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(spacing: 12) {
            Spinner(font: HerdrFont.stat).foregroundStyle(c.yellow)
            Text("Connecting…").font(HerdrFont.title).foregroundStyle(c.text)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("connecting")
    }
}

/// Chips that wrap onto as many rows as they need.
struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(width: bounds.width, subviews: subviews) {
            for (i, x) in zip(row.indices, row.xs) {
                subviews[i].place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + row.y), proposal: .unspecified)
            }
        }
    }

    private struct Row { var indices: [Int] = []; var xs: [CGFloat] = []; var y: CGFloat = 0; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows = [Row()]
        for (i, view) in subviews.enumerated() {
            let size = view.sizeThatFits(.unspecified)
            if rows[rows.count - 1].width > 0 && rows[rows.count - 1].width + spacing + size.width > width {
                let last = rows[rows.count - 1]
                rows.append(Row(y: last.y + last.height + spacing))
            }
            var row = rows.removeLast()
            let x = row.width > 0 ? row.width + spacing : 0
            row.indices.append(i)
            row.xs.append(x)
            row.width = x + size.width
            row.height = max(row.height, size.height)
            rows.append(row)
        }
        return rows
    }
}
