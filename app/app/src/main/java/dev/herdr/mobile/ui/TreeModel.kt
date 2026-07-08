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
