import Foundation
import Observation

/// The dashboard's state: the herd as the companion last described it, answers sent
/// from the dashboard, and the agents the New Agent picker offers. Feed it frames with
/// `apply(_:)`; its actions go out through `request`, usually `CompanionClient.request`.
@MainActor @Observable
public final class DashboardStore {
    public private(set) var answers = InlineAnswers()
    /// What `list_agents` last returned.
    public private(set) var agents: [String] = []
    /// Most recent first, lowercased.
    public private(set) var recentAgents: [String]
    /// Why the last action failed, until `clearError()`.
    public private(set) var error: String?

    private var panesById: [String: Pane] = [:]
    private var workspaces: [Workspace] = []
    private var tabs: [Tab] = []

    @ObservationIgnored private let request: @MainActor (ClientRequest) async throws -> ServerFrame
    @ObservationIgnored private let saveRecentAgents: ([String]) -> Void
    @ObservationIgnored private let resumedTTL: Duration

    /// `saveRecentAgents` persists the list after each change; `resumedTTL` is how long
    /// a "resumed" row shows after an answer lands.
    public init(recentAgents: [String] = [], resumedTTL: Duration = .seconds(4),
                saveRecentAgents: @escaping ([String]) -> Void = { _ in },
                request: @escaping @MainActor (ClientRequest) async throws -> ServerFrame) {
        self.recentAgents = recentAgents
        self.resumedTTL = resumedTTL
        self.saveRecentAgents = saveRecentAgents
        self.request = request
    }

    /// By id.
    public var panes: [Pane] { panesById.values.sorted { $0.paneId < $1.paneId } }
    public var tree: [WorkspaceNode] { buildTree(workspaces: workspaces, tabs: tabs, panes: panes) }
    public var repos: [RepoNode] { buildRepoTree(tree) }
    public var sections: DashboardSections { DashboardSections(panes: panes, answers: answers) }

    public func apply(_ frame: ServerFrame) {
        switch frame {
        case .panes(let all): panesById = Dictionary(all.map { ($0.paneId, $0) }, uniquingKeysWith: { $1 })
        case .paneUpdate(let p): panesById[p.paneId] = p
        case .paneRemoved(let id): panesById[id] = nil
        case .workspaces(let all): workspaces = all
        case .tabs(let all): tabs = all
        default: break
        }
    }

    public func clearError() { error = nil }

    // MARK: Answering in place

    /// Answers `pane`'s inline choice with `label`. A tap while the pane's answer is
    /// still sending does nothing.
    public func answer(_ pane: Pane, label: String) async {
        guard let ask = pane.ask, let item = ask.inlineChoice,
              answers.start(paneId: pane.paneId, toolUseId: ask.toolUseId, label: label) else { return }
        do {
            _ = try await request(.chatAnswer(paneId: pane.paneId, toolUseId: ask.toolUseId, answers: item.answer(label)))
            answers.sent(paneId: pane.paneId)
            Task { [resumedTTL] in
                try? await Task.sleep(for: resumedTTL)
                answers.expire(paneId: pane.paneId, toolUseId: ask.toolUseId)
            }
        } catch {
            answers.failed(paneId: pane.paneId)
            self.error = answerErrorLabel(error)
        }
    }

    // MARK: Structural actions

    public func rename(_ target: RowAction, to label: String) async {
        await perform(.action(op: .rename, kind: target.kind, id: target.id, label: label))
    }

    public func close(_ target: RowAction) async {
        await perform(.action(op: .close, kind: target.kind, id: target.id))
    }

    public func newWorkspace() async { await perform(.create(what: .workspace)) }

    public func newTab(in workspace: RowAction) async { await perform(.create(what: .tab, workspaceId: workspace.id)) }

    /// A shell in `tab`'s workspace.
    public func newShell(beside tab: RowAction) async { await perform(.create(what: .shell, workspaceId: tab.workspaceId)) }

    public func split(_ pane: RowAction, _ direction: SplitDirection) async {
        await perform(.create(what: .shell, paneId: pane.id, direction: direction))
    }

    /// To a new tab or a new workspace.
    public func move(_ pane: RowAction, to dest: MoveDest) async { await perform(.move(paneId: pane.id, dest: dest)) }

    /// Below the panes of an existing tab.
    public func move(_ pane: RowAction, toTab tabId: String) async {
        await perform(.move(paneId: pane.id, dest: .tab, tabId: tabId, direction: .down))
    }

    /// In a new tab of a workspace, or split down in a tab, and remembered as recent.
    public func startAgent(_ command: AgentCommand, in target: RowAction) async {
        guard !command.argv.isEmpty else { return }
        if !command.name.isEmpty {
            recentAgents = updatedMru(recentAgents, picked: command.name.lowercased())
            saveRecentAgents(recentAgents)
        }
        await perform(target.kind == .tab
            ? .create(what: .agent, tabId: target.id, direction: .down, agentName: command.name, argv: command.argv)
            : .create(what: .agent, workspaceId: target.id, agentName: command.name, argv: command.argv))
    }

    /// Refreshes `agents`; on failure they stay as they were.
    public func refreshAgents() async {
        if case .agents(_, let names)? = try? await request(.listAgents) { agents = names }
    }

    /// The labels of the sibling workspaces herdr closes along with this one; [] on error.
    public func closeImpact(workspaceId: String) async -> [String] {
        guard case .closeImpact(_, _, let also)? = try? await request(.closeImpact(workspaceId: workspaceId)) else { return [] }
        return also.map(\.label)
    }

    private func perform(_ r: ClientRequest) async {
        do { _ = try await request(r) } catch { self.error = Self.label(error) }
    }

    private static func label(_ error: any Error) -> String {
        switch error as? CompanionError {
        case .rejected(let message): message
        case .timedOut: "timed out"
        case .disconnected: "disconnected"
        case .notConnected: "not connected"
        case nil: error.localizedDescription
        }
    }

    private func answerErrorLabel(_ error: any Error) -> String {
        switch error as? CompanionError {
        case .rejected("no_mod"): "Claude isn't connected"
        case .rejected("no_question"): "the question is no longer waiting"
        case .rejected("empty"): "empty answer"
        default: "answer failed: \(Self.label(error))"
        }
    }
}
