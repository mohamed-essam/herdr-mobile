package dev.herdr.mobile

import dev.herdr.mobile.ui.NodeKind
import dev.herdr.mobile.ui.RowAction
import dev.herdr.mobile.ui.closeConfirmMessage
import dev.herdr.mobile.ui.closeConfirmMessageWith
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

    @Test fun confirmCopyNamesTargetAndBlastRadius() {
        assertTrue(closeConfirmMessage(RowAction(NodeKind.PANE, "w6:p1", "claude", isAgent = true)).contains("agent"))
        assertTrue(closeConfirmMessage(RowAction(NodeKind.TAB, "w7:t1", "build", paneCount = 3)).let {
            it.contains("build") && it.contains("3")
        })
        assertTrue(closeConfirmMessage(RowAction(NodeKind.WORKSPACE, "w7", "omega3", paneCount = 4, tabCount = 2)).let {
            it.contains("omega3") && it.contains("4") && it.contains("2")
        })
    }

    @Test fun nodeKindWireStringsAreStable() {
        assertEquals("workspace", NodeKind.WORKSPACE.wire)
        assertEquals("tab", NodeKind.TAB.wire)
        assertEquals("pane", NodeKind.PANE.wire)
    }

    @Test fun closeConfirmMessageWithNoSiblingsIsBaseCopy() {
        val a = RowAction(NodeKind.WORKSPACE, "w1", "main", paneCount = 2, tabCount = 1)
        assertEquals(closeConfirmMessage(a), closeConfirmMessageWith(a, emptyList()))
    }

    @Test fun closeConfirmMessageWithSiblingsAppendsLine() {
        val a = RowAction(NodeKind.WORKSPACE, "w1", "main", paneCount = 2, tabCount = 1)
        val msg = closeConfirmMessageWith(a, listOf("ops", "feat/a"))
        assertTrue(msg.startsWith(closeConfirmMessage(a)))
        assertTrue(msg.contains("ops"))
        assertTrue(msg.contains("feat/a"))
    }
}
