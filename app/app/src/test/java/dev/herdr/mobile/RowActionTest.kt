package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.ui.NodeKind
import dev.herdr.mobile.ui.RowAction
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.paneAction
import dev.herdr.mobile.ui.needsCloseConfirm
import org.junit.Assert.*
import org.junit.Test

class RowActionTest {
    @Test fun confirmRuleMatchesSpecTruthTable() {
        // shell pane alone -> no confirm
        assertFalse(needsCloseConfirm(RowAction(NodeKind.PANE, "w7:p2", "shell", isAgent = false)))
        // agent pane -> confirm
        assertTrue(needsCloseConfirm(RowAction(NodeKind.PANE, "w6:p1", "claude", isAgent = true)))
        // single shell-pane tab -> no confirm
        assertFalse(needsCloseConfirm(RowAction(NodeKind.TAB, "w7:t2", "2", paneCount = 1, hasAgent = false)))
        // single agent-pane tab -> confirm
        assertTrue(needsCloseConfirm(RowAction(NodeKind.TAB, "w6:t1", "1", paneCount = 1, hasAgent = true)))
        // multi-pane tab -> confirm
        assertTrue(needsCloseConfirm(RowAction(NodeKind.TAB, "w7:t1", "1", paneCount = 2, hasAgent = false)))
        // workspace -> always confirm
        assertTrue(needsCloseConfirm(RowAction(NodeKind.WORKSPACE, "w7", "omega3")))
    }

    @Test fun nodeKindWireStringsAreStable() {
        assertEquals("workspace", NodeKind.WORKSPACE.wire)
        assertEquals("tab", NodeKind.TAB.wire)
        assertEquals("pane", NodeKind.PANE.wire)
    }

    private fun tabNode(tabId: String, ws: String, panes: List<Pane>) =
        TabNode(Tab(tabId = tabId, workspaceId = ws), panes)

    @Test fun paneActionSuppressesPivotForBlankIdTab() {
        val blankTab = tabNode("", "", listOf(Pane(paneId = "o1")))
        assertNull(paneAction(Pane(paneId = "o1"), blankTab).mergedTab)
    }

    @Test fun paneActionAttachesPivotForRealTab() {
        val p = Pane(paneId = "p1", workspaceId = "w1", tabId = "t1", agent = "claude")
        val action = paneAction(p, tabNode("t1", "w1", listOf(p)))
        assertEquals(NodeKind.TAB, action.mergedTab?.kind)
        assertEquals("w1", action.mergedTab?.workspaceId)
        assertTrue(action.isAgent)
    }
}
