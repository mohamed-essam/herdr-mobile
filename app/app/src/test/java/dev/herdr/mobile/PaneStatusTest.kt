package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.panePrimaryLabel
import dev.herdr.mobile.ui.paneSecondaryLabel
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

    @Test fun primaryLabelLeadsWithAgentOrDifferentiatingCwd() {
        // agent → agent name, regardless of cwd
        assertEquals("claude", panePrimaryLabel(Pane(paneId = "1", agent = "claude", cwd = "/home/u/repo"), "repo"))
        // shell in repo root → generic "shell"
        assertEquals("shell", panePrimaryLabel(Pane(paneId = "2", agent = null, cwd = "/home/u/repo"), "repo"))
        // shell in a subdir → the subdir
        assertEquals("src", panePrimaryLabel(Pane(paneId = "3", agent = null, cwd = "/home/u/repo/src"), "repo"))
        // shell with blank cwd → "shell"
        assertEquals("shell", panePrimaryLabel(Pane(paneId = "4", agent = null, cwd = ""), "repo"))
    }

    @Test fun secondaryLabelShowsSubdirForAgentsOnly() {
        // agent in repo root → no secondary (redundant)
        assertNull(paneSecondaryLabel(Pane(paneId = "1", agent = "claude", cwd = "/home/u/repo"), "repo"))
        // agent in a subdir → the subdir
        assertEquals("src", paneSecondaryLabel(Pane(paneId = "2", agent = "claude", cwd = "/home/u/repo/src"), "repo"))
        // shell → no secondary (subdir already carried by the title)
        assertNull(paneSecondaryLabel(Pane(paneId = "3", agent = null, cwd = "/home/u/repo/src"), "repo"))
    }
}
