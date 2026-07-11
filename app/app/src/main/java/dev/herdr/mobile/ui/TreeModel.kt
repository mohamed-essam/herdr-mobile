package dev.herdr.mobile.ui

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace

data class TabNode(val tab: Tab, val panes: List<Pane>)
data class WorkspaceNode(val ws: Workspace, val tabs: List<TabNode>)

/**
 * Joins the flat workspace/tab/pane lists into a workspace → tab → pane tree.
 * Ordering: workspaces by number, tabs by number, panes by paneId. Panes whose
 * workspace or tab is missing from the lists surface under a synthetic
 * "(unknown)" workspace (sorted last) so nothing is silently dropped.
 */
fun buildTree(
    workspaces: List<Workspace>,
    tabs: List<Tab>,
    panes: List<Pane>,
): List<WorkspaceNode> {
    val wsById = workspaces.associateBy { it.workspaceId }
    val tabById = tabs.associateBy { it.tabId }
    val panesByTab = LinkedHashMap<String, MutableList<Pane>>()
    val orphanPanes = mutableListOf<Pane>()
    for (p in panes) {
        if (wsById.containsKey(p.workspaceId) && tabById.containsKey(p.tabId)) {
            panesByTab.getOrPut(p.tabId) { mutableListOf() }.add(p)
        } else {
            orphanPanes.add(p)
        }
    }

    val tabsByWs = tabs.groupBy { it.workspaceId }
    val nodes = workspaces.sortedBy { it.number }.map { ws ->
        val tabNodes = (tabsByWs[ws.workspaceId] ?: emptyList())
            .sortedBy { it.number }
            .map { t -> TabNode(t, (panesByTab[t.tabId] ?: emptyList()).sortedBy { it.paneId }) }
        WorkspaceNode(ws, tabNodes)
    }.toMutableList()

    if (orphanPanes.isNotEmpty()) {
        val unknownWs = Workspace(workspaceId = "", label = "(unknown)", number = Int.MAX_VALUE)
        val unknownTab = Tab(tabId = "", label = "", number = 0, workspaceId = "")
        nodes.add(WorkspaceNode(unknownWs, listOf(TabNode(unknownTab, orphanPanes.sortedBy { it.paneId }))))
    }
    return nodes
}

/** Attention rank for sorting: blocked (needs you) > done (finished) > rest. */
fun attentionTier(status: String?): Int = when (status) {
    "blocked" -> 2
    "done" -> 1
    else -> 0
}

/** A workspace's attention tier is the max over its panes (robust vs. herdr's aggregate). */
fun workspaceTier(node: WorkspaceNode): Int =
    node.tabs.flatMap { it.panes }.maxOfOrNull { attentionTier(it.agentStatus) } ?: 0

data class RepoNode(val repoKey: String, val displayName: String, val workspaces: List<WorkspaceNode>)

/**
 * Repo key for a workspace: worktree.repoName > first pane's cwd basename >
 * label > workspaceId. The synthetic orphan workspace (blank workspaceId,
 * emitted by buildTree) buckets under "(unknown)".
 */
fun repoKeyFor(node: WorkspaceNode): String {
    if (node.ws.workspaceId.isBlank()) return "(unknown)"
    node.ws.worktree?.repoName?.takeIf { it.isNotBlank() }?.let { return it }
    node.tabs.asSequence().flatMap { it.panes.asSequence() }
        .map { it.cwd }.firstOrNull { it.isNotBlank() }
        ?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { return it }
    node.ws.label.takeIf { it.isNotBlank() }?.let { return it }
    return node.ws.workspaceId
}

/**
 * Groups workspace nodes by repo key. Workspaces within a repo, and the repos
 * themselves, sort by attention tier (blocked > done > rest), then recent
 * activity, then number. The "(unknown)" group always sorts last.
 */
fun buildRepoTree(nodes: List<WorkspaceNode>): List<RepoNode> {
    val groups = LinkedHashMap<String, MutableList<WorkspaceNode>>()
    for (n in nodes) groups.getOrPut(repoKeyFor(n)) { mutableListOf() }.add(n)

    val wsOrder = compareByDescending<WorkspaceNode> { workspaceTier(it) }
        .thenByDescending { it.ws.lastActivity }
        .thenBy { it.ws.number }

    return groups.entries
        .map { (key, ws) -> RepoNode(key, key, ws.sortedWith(wsOrder)) }
        .sortedWith(
            compareBy<RepoNode> { if (it.repoKey == "(unknown)") 1 else 0 }
                .thenByDescending { r -> r.workspaces.maxOf { workspaceTier(it) } }
                .thenByDescending { r -> r.workspaces.maxOf { it.ws.lastActivity } }
                .thenBy { r -> r.workspaces.minOf { it.ws.number } }
                .thenBy { it.displayName },
        )
}

/**
 * A tab row is elided (its panes promoted directly under the workspace) when it
 * carries no grouping information: it is the workspace's only tab, or it holds a
 * single pane. Empty tabs keep their row so the workspace is never silently blank.
 */
fun tabElided(ws: WorkspaceNode, tab: TabNode): Boolean =
    tab.panes.isNotEmpty() && (ws.tabs.size == 1 || tab.panes.size == 1)

/**
 * A workspace's display children after tab elision: either a visible tab (render
 * its row + its panes) or a promoted pane (its tab was elided; the pane is hoisted
 * to a workspace-direct child). Order follows the workspace's tab order.
 */
sealed interface WsChild {
    data class TabGroup(val tab: TabNode) : WsChild
    data class PromotedPane(val pane: Pane, val parentTab: TabNode) : WsChild
}

fun workspaceChildren(ws: WorkspaceNode): List<WsChild> =
    ws.tabs.flatMap { t ->
        if (tabElided(ws, t)) t.panes.map { WsChild.PromotedPane(it, t) }
        else listOf(WsChild.TabGroup(t))
    }

/** One flattened, renderable row of the repo→workspace→tab→pane tree, shared by
 *  the dashboard and the sidebar so the two views can never disagree on order or
 *  structure. Each screen renders these rows in its own chrome. */
sealed interface TreeRow {
    data class Repo(val node: RepoNode, val expanded: Boolean, val paneCount: Int) : TreeRow
    // A repo folded with its sole, label-redundant workspace into one header row.
    data class RepoWs(val repo: RepoNode, val wsNode: WorkspaceNode, val expanded: Boolean, val paneCount: Int) : TreeRow
    data class Ws(val node: WorkspaceNode, val expanded: Boolean) : TreeRow
    data class Tab(val node: TabNode, val expanded: Boolean) : TreeRow
    // promoted: its tab was elided and it's hoisted to a workspace-direct child.
    // parentTab: that elided tab (for the sidebar's tab-action pivot); null otherwise.
    data class PaneItem(val pane: Pane, val promoted: Boolean, val parentTab: TabNode?, val repoLabel: String) : TreeRow
}

/** When a repo has exactly one workspace whose label adds nothing over the repo
 *  name, the repo and workspace header rows are redundant — return that workspace
 *  so they fold into one. Null for multi-workspace repos or a sole workspace with
 *  a distinct label (folding would drop that label). */
fun foldableWorkspace(repo: RepoNode): WorkspaceNode? {
    if (repo.workspaces.size != 1) return null
    val w = repo.workspaces[0]
    return if (w.ws.label.isBlank() || w.ws.label == repo.displayName) w else null
}

private fun MutableList<TreeRow>.addWorkspaceChildren(
    w: WorkspaceNode, repoLabel: String, collapsed: Set<String>,
) {
    for (child in workspaceChildren(w)) {
        when (child) {
            is WsChild.TabGroup -> {
                val t = child.tab
                val tOpen = t.tab.tabId !in collapsed
                add(TreeRow.Tab(t, tOpen))
                if (tOpen) t.panes.forEach {
                    add(TreeRow.PaneItem(it, promoted = false, parentTab = null, repoLabel = repoLabel))
                }
            }
            is WsChild.PromotedPane ->
                add(TreeRow.PaneItem(child.pane, promoted = true, parentTab = child.parentTab, repoLabel = repoLabel))
        }
    }
}

/** A node is expanded unless its id is in [collapsed]; repos key on "repo:<key>". */
fun flattenTree(repos: List<RepoNode>, collapsed: Set<String>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    for (r in repos) {
        val count = r.workspaces.sumOf { w -> w.tabs.sumOf { it.panes.size } }
        val fold = foldableWorkspace(r)
        if (fold != null) {
            val wOpen = fold.ws.workspaceId !in collapsed
            rows.add(TreeRow.RepoWs(r, fold, wOpen, count))
            if (wOpen) rows.addWorkspaceChildren(fold, r.displayName, collapsed)
        } else {
            val rOpen = "repo:${r.repoKey}" !in collapsed
            rows.add(TreeRow.Repo(r, rOpen, count))
            if (!rOpen) continue
            for (w in r.workspaces) {
                val wOpen = w.ws.workspaceId !in collapsed
                rows.add(TreeRow.Ws(w, wOpen))
                if (wOpen) rows.addWorkspaceChildren(w, r.displayName, collapsed)
            }
        }
    }
    return rows
}

fun treeRowKey(row: TreeRow): String = when (row) {
    is TreeRow.Repo -> "r:" + row.node.repoKey
    is TreeRow.RepoWs -> "rw:" + row.repo.repoKey
    is TreeRow.Ws -> "w:" + row.node.ws.workspaceId
    is TreeRow.Tab -> "t:" + row.node.tab.tabId
    is TreeRow.PaneItem -> "p:" + row.pane.paneId
}
