package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.WsChild
import dev.herdr.mobile.ui.tabElided
import dev.herdr.mobile.ui.workspaceChildren
import org.junit.Assert.*
import org.junit.Test

class TreeCollapseTest {
    private fun pane(id: String, ws: String, tab: String) =
        Pane(paneId = id, workspaceId = ws, tabId = tab)

    private fun tabNode(tabId: String, ws: String, panes: List<Pane>) =
        TabNode(Tab(tabId = tabId, workspaceId = ws), panes)

    private fun wsNode(id: String, tabs: List<TabNode>) =
        WorkspaceNode(Workspace(workspaceId = id, label = id, number = 1), tabs)

    @Test fun singleTabWorkspaceElidesEvenWithManyPanes() {
        val t = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1"), pane("p2", "w1", "t1")))
        val ws = wsNode("w1", listOf(t))
        assertTrue(tabElided(ws, t))
    }

    @Test fun singlePaneTabElidesAmongSiblings() {
        val big = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1"), pane("p2", "w1", "t1")))
        val lone = tabNode("t2", "w1", listOf(pane("p3", "w1", "t2")))
        val ws = wsNode("w1", listOf(big, lone))
        assertFalse(tabElided(ws, big))
        assertTrue(tabElided(ws, lone))
    }

    @Test fun emptyTabNeverElides() {
        val empty = tabNode("t1", "w1", emptyList())
        val other = tabNode("t2", "w1", listOf(pane("p1", "w1", "t2"), pane("p2", "w1", "t2")))
        val ws = wsNode("w1", listOf(empty, other))
        assertFalse(tabElided(ws, empty))
        val soloEmpty = wsNode("w2", listOf(tabNode("t3", "w2", emptyList())))
        assertFalse(tabElided(soloEmpty, soloEmpty.tabs[0]))
    }

    @Test fun workspaceChildrenPromotesSingleTabPanes() {
        val t = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1"), pane("p2", "w1", "t1"), pane("p3", "w1", "t1")))
        val children = workspaceChildren(wsNode("w1", listOf(t)))
        assertEquals(3, children.size)
        assertTrue(children.all { it is WsChild.PromotedPane })
        assertEquals(listOf("p1", "p2", "p3"), children.map { (it as WsChild.PromotedPane).pane.paneId })
        assertTrue(children.all { (it as WsChild.PromotedPane).parentTab.tab.tabId == "t1" })
    }

    @Test fun workspaceChildrenMixesGroupsAndPromotedInOrder() {
        val big = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1"), pane("p2", "w1", "t1")))
        val lone = tabNode("t2", "w1", listOf(pane("p3", "w1", "t2")))
        val children = workspaceChildren(wsNode("w1", listOf(big, lone)))
        assertEquals(2, children.size)
        assertTrue(children[0] is WsChild.TabGroup)
        assertEquals("t1", (children[0] as WsChild.TabGroup).tab.tab.tabId)
        assertTrue(children[1] is WsChild.PromotedPane)
        assertEquals("p3", (children[1] as WsChild.PromotedPane).pane.paneId)
    }

    @Test fun unknownWorkspacePromotesOrphans() {
        val blankTab = tabNode("", "", listOf(pane("o1", "", ""), pane("o2", "", "")))
        val unknown = WorkspaceNode(
            Workspace(workspaceId = "", label = "(unknown)", number = Int.MAX_VALUE),
            listOf(blankTab),
        )
        val children = workspaceChildren(unknown)
        assertEquals(2, children.size)
        assertTrue(children.all { it is WsChild.PromotedPane })
    }

    @Test fun paneActionSuppressesPivotForBlankIdTab() {
        val blankTab = tabNode("", "", listOf(pane("o1", "", "")))
        val action = dev.herdr.mobile.ui.paneAction(pane("o1", "", ""), blankTab)
        assertNull(action.mergedTab)
    }

    @Test fun paneActionAttachesPivotForRealTab() {
        val realTab = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1")))
        val action = dev.herdr.mobile.ui.paneAction(pane("p1", "w1", "t1"), realTab)
        assertNotNull(action.mergedTab)
    }
}
