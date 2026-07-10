package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.paneStatusLabel
import org.junit.Assert.*
import org.junit.Test

class PaneStatusTest {
    @Test fun shellShowsShellNotUnknown() {
        // herdr reports agentStatus="unknown" for non-agent (shell) panes.
        assertEquals("shell", paneStatusLabel(Pane(paneId = "wE:p8", agent = null, agentStatus = "unknown")))
        assertEquals("shell", paneStatusLabel(Pane(paneId = "x", agent = null, agentStatus = null)))
    }

    @Test fun agentShowsItsStatus() {
        assertEquals("working", paneStatusLabel(Pane(paneId = "w6:p1", agent = "claude", agentStatus = "working")))
        assertEquals("blocked", paneStatusLabel(Pane(paneId = "w6:p2", agent = "codex", agentStatus = "blocked")))
    }
}
