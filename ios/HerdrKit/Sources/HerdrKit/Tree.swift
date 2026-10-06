import Foundation

// The companion's flat workspace, tab and pane lists joined into a tree, and the
// tree grouped by repo for the workspace switcher. Ported from Android's TreeModel.kt.

public struct TabNode: Equatable, Sendable {
    public let tab: Tab
    public let panes: [Pane]
}

public struct WorkspaceNode: Equatable, Sendable {
    public let workspace: Workspace
    public let tabs: [TabNode]

    /// Every pane, in tab order.
    public var panes: [Pane] { tabs.flatMap(\.panes) }

    /// The synthetic "(unknown)" workspace that holds panes whose workspace or tab isn't known.
    public var isOrphans: Bool { workspace.workspaceId.isEmpty }

    /// The headline status from its agents: blocked, then working, then done, else idle.
    public var status: AgentStatus {
        let statuses = Set(panes.filter { $0.agent != nil }.compactMap(\.agentStatus))
        return [AgentStatus.blocked, .working, .done].first(where: statuses.contains) ?? .idle
    }

    /// Where tapping the workspace goes: a blocked pane, else a working one, else the first.
    public var mostRelevantPane: Pane? {
        panes.first { $0.agent != nil && $0.agentStatus == .blocked }
            ?? panes.first { $0.agent != nil && $0.agentStatus == .working }
            ?? panes.first
    }

    /// Blocked (2) beats done (1) beats the rest (0), over every pane.
    var attentionTier: Int { panes.map { tier($0.agentStatus) }.max() ?? 0 }
}

public struct RepoNode: Equatable, Sendable {
    public let name: String
    public let workspaces: [WorkspaceNode]
}

/// The synthetic orphans workspace's name, and any unlabelled workspace's.
let orphansName = "(unknown)"

extension Workspace {
    /// Its label, else "(unknown)".
    public var displayLabel: String { label.isEmpty ? orphansName : label }
}

/// Workspaces by number, tabs by number, panes by id. Panes whose workspace or tab
/// is missing go under a synthetic "(unknown)" workspace, last, so none are dropped.
public func buildTree(workspaces: [Workspace], tabs: [Tab], panes: [Pane]) -> [WorkspaceNode] {
    let workspaceIds = Set(workspaces.map(\.workspaceId))
    let tabIds = Set(tabs.map(\.tabId))
    let (placed, orphans) = panes.sorted { $0.paneId < $1.paneId }
        .reduce(into: ([String: [Pane]](), [Pane]())) { acc, p in
            if workspaceIds.contains(p.workspaceId) && tabIds.contains(p.tabId) {
                acc.0[p.tabId, default: []].append(p)
            } else {
                acc.1.append(p)
            }
        }
    let tabsByWorkspace = Dictionary(grouping: tabs, by: \.workspaceId)
    var nodes = workspaces.sorted { $0.number < $1.number }.map { ws in
        WorkspaceNode(workspace: ws, tabs: (tabsByWorkspace[ws.workspaceId] ?? []).sorted { $0.number < $1.number }
            .map { TabNode(tab: $0, panes: placed[$0.tabId] ?? []) })
    }
    if !orphans.isEmpty {
        let ws = Workspace(workspaceId: "", label: orphansName, number: .max, agentStatus: nil, focused: false,
                           paneCount: orphans.count, tabCount: 1, lastActivity: 0, worktree: nil)
        let tab = Tab(tabId: "", label: "", number: 0, workspaceId: "", agentStatus: nil, focused: false, paneCount: orphans.count)
        nodes.append(WorkspaceNode(workspace: ws, tabs: [TabNode(tab: tab, panes: orphans)]))
    }
    return nodes
}

private func tier(_ status: AgentStatus?) -> Int {
    switch status {
    case .blocked: 2
    case .done: 1
    default: 0
    }
}

/// A workspace's repo: its worktree's repo name, else its first pane's cwd basename,
/// else its label, else its id. The orphans workspace is "(unknown)".
func repoKey(for node: WorkspaceNode) -> String {
    if node.isOrphans { return orphansName }
    if let repo = node.workspace.worktree?.repoName, !repo.isEmpty { return repo }
    if let cwd = node.panes.map(\.cwd).first(where: { !$0.isEmpty }) {
        let base = pathBase(cwd)
        if !base.isEmpty { return base }
    }
    return node.workspace.label.isEmpty ? node.workspace.workspaceId : node.workspace.label
}

/// Workspaces grouped by repo. Within a repo, and between repos, attention comes first
/// (blocked, then done), then recent activity, then number; "(unknown)" is always last.
public func buildRepoTree(_ nodes: [WorkspaceNode]) -> [RepoNode] {
    // Each sort key is computed once, not per comparison.
    var order: [String] = []
    var groups: [String: [(node: WorkspaceNode, tier: Int)]] = [:]
    for node in nodes {
        let name = repoKey(for: node)
        if groups[name] == nil { order.append(name) }
        groups[name, default: []].append((node, node.attentionTier))
    }
    let repos = order.map { name in
        let ws = groups[name]!.sorted {
            ($0.tier, $0.node.workspace.lastActivity, -$0.node.workspace.number)
                > ($1.tier, $1.node.workspace.lastActivity, -$1.node.workspace.number)
        }
        let rank = (orphans: name == orphansName ? 1 : 0, tier: ws.map(\.tier).max()!,
                    last: ws.map(\.node.workspace.lastActivity).max()!, number: ws.map(\.node.workspace.number).min()!)
        return (repo: RepoNode(name: name, workspaces: ws.map(\.node)), rank: rank)
    }
    return repos.sorted { a, b in
        (a.rank.orphans, -a.rank.tier, -a.rank.last, a.rank.number, a.repo.name)
            < (b.rank.orphans, -b.rank.tier, -b.rank.last, b.rank.number, b.repo.name)
    }.map(\.repo)
}

/// Repos narrowed to the workspaces matching `query` (case-insensitive) on the repo
/// name, the workspace label, or a pane's agent or cwd. A blank query changes nothing.
public func filterRepos(_ repos: [RepoNode], query: String) -> [RepoNode] {
    let q = query.trimmingCharacters(in: .whitespaces)
    guard !q.isEmpty else { return repos }
    func hit(_ s: String?) -> Bool { s?.localizedCaseInsensitiveContains(q) ?? false }
    return repos.compactMap { r in
        let ws = hit(r.name) ? r.workspaces : r.workspaces.filter { w in
            hit(w.workspace.label) || w.panes.contains { hit($0.agent) || hit($0.cwd) }
        }
        return ws.isEmpty ? nil : RepoNode(name: r.name, workspaces: ws)
    }
}

/// The last path component; "" for "" or "/".
func pathBase(_ path: String) -> String {
    String(path.split(separator: "/", omittingEmptySubsequences: false).last ?? "")
}
