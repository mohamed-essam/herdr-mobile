package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.buildTree
import org.junit.Assert.*
import org.junit.Test

class TreeModelTest {
    private fun ws(id: String, num: Int) = Workspace(workspaceId = id, label = id, number = num)
    private fun tab(id: String, ws: String, num: Int) = Tab(tabId = id, label = "$num", number = num, workspaceId = ws)
    private fun pane(id: String, ws: String, tab: String) = Pane(paneId = id, workspaceId = ws, tabId = tab, agent = "claude", agentStatus = "idle")

    @Test fun joinsAndOrdersByNumberThenPaneId() {
        val tree = buildTree(
            workspaces = listOf(ws("w7", 4), ws("w3", 1)),
            tabs = listOf(tab("w7:t2", "w7", 2), tab("w7:t1", "w7", 1), tab("w3:t1", "w3", 1)),
            panes = listOf(
                pane("w7:p2", "w7", "w7:t1"), pane("w7:p1", "w7", "w7:t1"),
                pane("w3:p1", "w3", "w3:t1")),
        )
        // workspaces ordered by number: w3 (1) then w7 (4)
        assertEquals(listOf("w3", "w7"), tree.map { it.ws.workspaceId })
        val w7 = tree[1]
        // tabs ordered by number: t1 then t2
        assertEquals(listOf("w7:t1", "w7:t2"), w7.tabs.map { it.tab.tabId })
        // panes under w7:t1 ordered by paneId
        assertEquals(listOf("w7:p1", "w7:p2"), w7.tabs[0].panes.map { it.paneId })
    }

    @Test fun orphanPanesGoUnderUnknownGroupSortedLast() {
        val tree = buildTree(
            workspaces = listOf(ws("w3", 1)),
            tabs = listOf(tab("w3:t1", "w3", 1)),
            panes = listOf(pane("w3:p1", "w3", "w3:t1"), pane("wX:p9", "wX", "wX:tZ")),
        )
        assertEquals("", tree.last().ws.workspaceId)
        assertEquals("(unknown)", tree.last().ws.label)
        assertEquals("wX:p9", tree.last().tabs.single().panes.single().paneId)
    }

    @Test fun emptyInputsYieldEmptyTree() {
        assertTrue(buildTree(emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
