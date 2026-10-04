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
