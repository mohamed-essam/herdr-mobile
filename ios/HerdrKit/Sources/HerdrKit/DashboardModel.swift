import Foundation

// The logic behind the attention-first dashboard: its sections, the in-place answer
// to a pane's question, and the display strings of its rows. Ported from Android's
// DashboardModel.kt, PaneContext.kt and PaneRow.kt.

// MARK: Sections

/// Every pane lands in one section, except one answered here: it leaves `needsYou`
/// while its "resumed" row shows.
public struct DashboardSections: Equatable, Sendable {
    public let needsYou: [Pane]
    public let working: [Pane]
    public let done: [Pane]
    public let idle: [Pane]

    /// Needs-you is oldest-waiting first; working and done most recent first; panes without
    /// a timestamp go last. Idle holds idle agents, then shells.
    public init(panes: [Pane], answers: InlineAnswers = InlineAnswers()) {
        let hidden = Set(panes.filter { $0.needsYou && answers.hides($0) }.map(\.paneId))
        needsYou = panes.filter { $0.needsYou && !hidden.contains($0.paneId) }
            .sorted { ($0.untimed, $0.activityTs, $0.paneId) < ($1.untimed, $1.activityTs, $1.paneId) }
        func recentFirst(_ status: AgentStatus) -> [Pane] {
            panes.filter { $0.agent != nil && !$0.needsYou && $0.agentStatus == status }
                .sorted { ($0.untimed, -$0.activityTs, $0.paneId) < ($1.untimed, -$1.activityTs, $1.paneId) }
        }
        working = recentFirst(.working)
        done = recentFirst(.done)
        let placed = hidden.union((needsYou + working + done).map(\.paneId))
        idle = panes.filter { !placed.contains($0.paneId) }
            .sorted { ($0.agent == nil ? 1 : 0, $0.paneId) < ($1.agent == nil ? 1 : 0, $1.paneId) }
    }
}

/// How many agents need you, work, or are done: the dashboard's stat tiles.
public struct StatusCounts: Equatable, Sendable {
    public let blocked: Int
    public let working: Int
    public let done: Int

    init(blocked: Int, working: Int, done: Int) {
        (self.blocked, self.working, self.done) = (blocked, working, done)
    }

    public init(panes: [Pane]) {
        let agents = panes.filter { $0.agent != nil }
        blocked = agents.count(where: \.needsYou)
        working = agents.count { !$0.needsYou && $0.agentStatus == .working }
        done = agents.count { !$0.needsYou && $0.agentStatus == .done }
    }
}

extension Pane {
    /// Blocked, or asking a question herdr's status detection hasn't flagged (yet).
    public var needsYou: Bool { agent != nil && (agentStatus == .blocked || ask != nil) }

    /// The status a row shows: a shell says "shell" rather than herdr's "unknown".
    /// Nil for an agent herdr gives no status.
    public var rowStatus: RowStatus? { agent == nil ? .shell : agentStatus.map(RowStatus.agent) }

    /// Its agent, else "shell".
    public var displayName: String { agent ?? "shell" }

    /// "⟳ 3" while the pane has background tasks running.
    public var bgBadge: String? { bgRunning > 0 ? "⟳ \(bgRunning)" : nil }

    /// It has a terminal or a chat to open.
    public var canOpen: Bool { !terminalId.trimmed.isEmpty || chat }

    var activityTs: Int { activity?.ts ?? 0 }
    var untimed: Int { activityTs > 0 ? 0 : 1 }
}

/// What a row shows as a pane's status: its agent's, or "shell".
public enum RowStatus: Equatable, Sendable {
    case agent(AgentStatus)
    case shell

    /// "working", "shell", …
    public var label: String {
        switch self {
        case .agent(let s): s.raw
        case .shell: "shell"
        }
    }

    /// Nil for a shell.
    public var agentStatus: AgentStatus? { if case .agent(let s) = self { s } else { nil } }
}

// MARK: Answering in place

extension PaneAsk {
    /// The one kind of question the dashboard answers in place: a lone single-select
    /// choice with options. Anything else needs the chat.
    public var inlineChoice: QuestionItem? {
        guard questions.count == 1, let q = questions.first,
              q.kind == .choice, !q.multiSelect, !q.options.isEmpty else { return nil }
        return q
    }
}

extension QuestionItem {
    /// The `chat_answer` answers for picking `label`.
    public func answer(_ label: String) -> [String: String] { [question: label] }
}

public struct InlineAnswer: Equatable, Sendable {
    public enum Phase: Sendable { case sending, sent }

    public let paneId: String
    public let toolUseId: String
    public let label: String
    public var phase: Phase
}

/// Answers sent from the dashboard, by pane: sending, or sent and showing a "resumed"
/// row for a while. A pane with no entry has live chips.
public struct InlineAnswers: Equatable, Sendable {
    private var byPane: [String: InlineAnswer] = [:]

    public init() {}

    public subscript(paneId: String) -> InlineAnswer? { byPane[paneId] }

    /// The sent answers, for their "resumed" rows.
    public var resumed: [InlineAnswer] { byPane.values.filter { $0.phase == .sent }.sorted { $0.paneId < $1.paneId } }

    /// Idle → sending. False, and no change, while that pane already has an answer sending.
    public mutating func start(paneId: String, toolUseId: String, label: String) -> Bool {
        guard byPane[paneId]?.phase != .sending else { return false }
        byPane[paneId] = InlineAnswer(paneId: paneId, toolUseId: toolUseId, label: label, phase: .sending)
        return true
    }

    /// Sending → sent.
    public mutating func sent(paneId: String) { byPane[paneId]?.phase = .sent }

    /// Sending → idle: the chips work again.
    public mutating func failed(paneId: String) { byPane[paneId] = nil }

    /// Drops the sent answer to `toolUseId`, once its "resumed" row has shown long enough.
    public mutating func expire(paneId: String, toolUseId: String) {
        if let a = byPane[paneId], a.phase == .sent, a.toolUseId == toolUseId { byPane[paneId] = nil }
    }

    /// A pane answered here hides behind its "resumed" row, until a new question arrives.
    func hides(_ pane: Pane) -> Bool {
        guard let a = byPane[pane.paneId], a.phase == .sent else { return false }
        return pane.ask == nil || pane.ask?.toolUseId == a.toolUseId
    }
}

// MARK: Close and action sheets

/// One pane a close will end, for the confirmation list.
public struct ClosingPane: Equatable, Sendable {
    public let name: String
    public let status: RowStatus?
    public let tab: String
}

public struct CloseSummary: Equatable, Sendable {
    public let headline: String
    public let panes: [ClosingPane]
}

/// "1 pane", "3 panes".
public func counted(_ n: Int, _ noun: String) -> String { "\(n) \(noun)\(n == 1 ? "" : "s")" }

/// "tab 2", else "tab <label>", else "".
func tabName(_ t: TabNode) -> String {
    if t.tab.number > 0 { return "tab \(t.tab.number)" }
    return t.tab.label.isEmpty ? "" : "tab \(t.tab.label)"
}

private func closing(_ p: Pane, _ t: TabNode) -> ClosingPane {
    ClosingPane(name: p.displayName, status: p.rowStatus, tab: tabName(t))
}

/// What closing `target` ends: "chat-view ends 3 panes across 2 tabs", and the panes.
/// Falls back to the target's own counts when `tree` doesn't hold it (yet).
public func closeSummary(for target: RowAction, in tree: [WorkspaceNode]) -> CloseSummary {
    switch target.kind {
    case .workspace:
        let w = tree.first { $0.workspace.workspaceId == target.id }
        let panes = w?.tabs.flatMap { t in t.panes.map { closing($0, t) } } ?? []
        let paneCount = w == nil ? target.paneCount : panes.count
        let tabCount = w?.tabs.count { !$0.panes.isEmpty } ?? target.tabCount
        let across = tabCount > 1 ? " across \(counted(tabCount, "tab"))" : ""
        return CloseSummary(headline: "\(target.label) ends \(counted(paneCount, "pane"))\(across)", panes: panes)
    case .tab:
        let t = tree.flatMap(\.tabs).first { $0.tab.tabId == target.id }
        let name = t.map(tabName).flatMap { $0.isEmpty ? nil : $0 } ?? "tab \(target.label)"
        let panes = t.map { t in t.panes.map { closing($0, t) } } ?? []
        return CloseSummary(headline: "\(name) ends \(counted(t == nil ? target.paneCount : panes.count, "pane"))", panes: panes)
    case .pane:
        let hit = tree.flatMap(\.tabs).lazy.compactMap { t in t.panes.first { $0.paneId == target.id }.map { closing($0, t) } }.first
        let what = target.isAgent ? "terminates \(target.label)" : "ends this shell"
        return CloseSummary(headline: "Closing this pane \(what)", panes: hit.map { [$0] } ?? [])
    }
}

/// An action sheet's header: badge number, title, path and status.
public struct ActionHeader: Equatable, Sendable {
    public let number: Int
    public let title: String
    public let path: String
    public let status: RowStatus?
}

/// The header for `target`; a pane names its agent, with "repo › workspace › tab N".
public func actionHeader(for target: RowAction, in repos: [RepoNode]) -> ActionHeader {
    for r in repos {
        for w in r.workspaces {
            let wsLabel = w.workspace.label.isEmpty ? r.name : w.workspace.label
            if target.kind == .workspace && w.workspace.workspaceId == target.id {
                return ActionHeader(number: w.workspace.number, title: wsLabel, path: r.name, status: .agent(w.status))
            }
            for t in w.tabs {
                if target.kind == .tab && t.tab.tabId == target.id {
                    let status = t.panes.contains { $0.agent != nil } ? RowStatus.agent(WorkspaceNode(workspace: w.workspace, tabs: [t]).status) : nil
                    let title = tabName(t)
                    return ActionHeader(number: w.workspace.number, title: title.isEmpty ? "tab" : title, path: "\(r.name) › \(wsLabel)", status: status)
                }
                if target.kind == .pane, let p = t.panes.first(where: { $0.paneId == target.id }) {
                    let path = [r.name, wsLabel, tabName(t)].filter { !$0.isEmpty }.joined(separator: " › ")
                    return ActionHeader(number: w.workspace.number, title: p.displayName, path: path, status: p.rowStatus)
                }
            }
        }
    }
    return ActionHeader(number: 0, title: target.label, path: "", status: nil)
}

/// The tab a pane sits in.
public func tabOf(paneId: String, in tree: [WorkspaceNode]) -> TabNode? {
    tree.flatMap(\.tabs).first { $0.panes.contains { $0.paneId == paneId } }
}

// MARK: Rows

/// Where a pane lives, flattened for a row: named by its workspace, with the repo and
/// tab as metadata.
public struct PaneContext: Equatable, Sendable {
    public let pane: Pane
    public let workspaceLabel: String
    public let workspaceNumber: Int
    public let repoName: String
    public let tabLabel: String
    public let tabNumber: Int

    /// For a pane the tree doesn't know (yet): its cwd's basename as the title.
    public init(fallback pane: Pane) {
        let base = pathBase(pane.cwd)
        self.init(pane: pane, workspaceLabel: base.isEmpty ? pane.agent ?? "pane" : base, workspaceNumber: 0,
                  repoName: "", tabLabel: "", tabNumber: 0)
    }

    init(pane: Pane, workspaceLabel: String, workspaceNumber: Int, repoName: String, tabLabel: String, tabNumber: Int) {
        self.pane = pane
        self.workspaceLabel = workspaceLabel
        self.workspaceNumber = workspaceNumber
        self.repoName = repoName
        self.tabLabel = tabLabel
        self.tabNumber = tabNumber
    }

    /// "tab 1", else the tab's label, else "".
    public var tabCrumb: String { tabNumber > 0 ? "tab \(tabNumber)" : tabLabel }

    /// The chat header's "shop › tab 1 › claude": repo, tab and agent, the empty ones left out.
    public var breadcrumb: String { [terminalBreadcrumb, pane.agent ?? ""].filter { !$0.isEmpty }.joined(separator: " › ") }

    /// The terminal header's "shop › tab 1": the breadcrumb without the agent.
    public var terminalBreadcrumb: String { [repoName, tabCrumb].filter { !$0.isEmpty }.joined(separator: " › ") }

    /// "herdr-mobile · claude".
    public var meta: String { [repoName, pane.displayName].filter { !$0.isEmpty }.joined(separator: " · ") }
}

/// Every pane in `repos`, by id.
public func paneContexts(_ repos: [RepoNode]) -> [String: PaneContext] {
    var out: [String: PaneContext] = [:]
    for r in repos {
        for w in r.workspaces {
            for t in w.tabs {
                for p in t.panes {
                    out[p.paneId] = PaneContext(pane: p, workspaceLabel: w.workspace.label.isEmpty ? r.name : w.workspace.label,
                                                workspaceNumber: w.workspace.number, repoName: r.name,
                                                tabLabel: t.tab.label, tabNumber: t.tab.number)
                }
            }
        }
    }
    return out
}

/// "▸ Bash npm run build" for a tool call, else the activity's first line; nil when empty.
public func activityLine(_ a: PaneActivity?) -> String? {
    guard let a else { return nil }
    let text = a.text.split(whereSeparator: \.isNewline).lazy
        .map { $0.trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty } ?? ""
    let tool = a.tool ?? ""
    let line = switch a.kind {
    case .tool where !tool.isEmpty && text.hasPrefix("\(tool):"): "▸ \(toolLine(tool: tool, summary: text))"
    case .tool: ["▸", tool, text].filter { !$0.isEmpty }.joined(separator: " ")
    default: text
    }
    return line.isEmpty || line == "▸" ? nil : line
}

private let fileTools: Set = ["Read", "Edit", "Write", "NotebookEdit", "MultiEdit"]

/// A tool call as one line: "Read: /home/me/app/ui/X.kt" becomes "Read ui/X.kt"
/// (file tools keep the last two path segments).
public func toolLine(tool: String, summary: String) -> String {
    guard summary.hasPrefix("\(tool):") else { return summary.isEmpty ? tool : summary }
    let detail = summary.dropFirst(tool.count + 1).trimmingCharacters(in: .whitespaces)
    guard fileTools.contains(tool) else { return "\(tool) \(detail)" }
    let parts = detail.split(separator: "/")
    return "\(tool) \(parts.count > 2 ? parts.suffix(2).joined(separator: "/") : detail)"
}

/// Compact age of `ms` at `now` (epoch ms): "now", "5m", "3h", "2d"; "" when unknown.
public func relativeAge(ms: Int, now: Int) -> String {
    guard ms > 0 else { return "" }
    let s = seconds(since: ms, now: now)
    return s < 60 ? "now" : age(s)
}

/// "32s ago", "5m ago", "2h ago" for how long ago `sinceMs` was at `now`.
public func staleLabel(sinceMs: Int, now: Int) -> String {
    let s = seconds(since: sinceMs, now: now)
    return "\(s < 60 ? "\(s)s" : age(s)) ago"
}

private func seconds(since ms: Int, now: Int) -> Int { max(0, (now - ms) / 1000) }

/// "5m", "3h", "2d" for a minute or more.
private func age(_ s: Int) -> String {
    switch s {
    case ..<3600: "\(s / 60)m"
    case ..<86_400: "\(s / 3600)h"
    default: "\(s / 86_400)d"
    }
}

extension Date {
    /// Epoch milliseconds, the companion's timestamps.
    public var epochMs: Int { Int(timeIntervalSince1970 * 1000) }
}
