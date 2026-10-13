/// The target of an action sheet: a workspace, tab or pane, with what the rename field,
/// the close decision and the close copy need. Ported from Android's RowAction.kt.
public struct RowAction: Equatable, Sendable, Identifiable {
    public let kind: NodeKind
    public let id: String
    public let label: String
    public var paneCount = 0
    public var tabCount = 0
    public var isAgent = false
    public var hasAgent = false
    public var workspaceId = ""
}

/// Confirm a close that ends an agent, ends more than one pane, ends a tab holding an
/// agent, or ends any workspace.
public func needsCloseConfirm(_ a: RowAction) -> Bool {
    switch a.kind {
    case .pane: a.isAgent
    case .tab: a.paneCount > 1 || a.hasAgent
    case .workspace: true
    }
}

public func workspaceAction(_ node: WorkspaceNode) -> RowAction {
    RowAction(kind: .workspace, id: node.workspace.workspaceId, label: node.workspace.displayLabel,
              paneCount: node.panes.count, tabCount: node.tabs.count)
}

public func tabAction(_ node: TabNode) -> RowAction {
    RowAction(kind: .tab, id: node.tab.tabId, label: node.tab.label.isEmpty ? "\(node.tab.number)" : node.tab.label,
              paneCount: node.panes.count, hasAgent: node.panes.contains { $0.agent != nil }, workspaceId: node.tab.workspaceId)
}

public func paneAction(_ pane: Pane) -> RowAction {
    RowAction(kind: .pane, id: pane.paneId, label: pane.displayName, isAgent: pane.agent != nil, workspaceId: pane.workspaceId)
}

/// The pane's own tab, for its sheet's "Tab actions"; nil when the tab is unknown (the orphans tab has no id).
public func tabAction(ofPane paneId: String, in tree: [WorkspaceNode]) -> RowAction? {
    tabOf(paneId: paneId, in: tree).flatMap { $0.tab.tabId.isEmpty ? nil : tabAction($0) }
}
