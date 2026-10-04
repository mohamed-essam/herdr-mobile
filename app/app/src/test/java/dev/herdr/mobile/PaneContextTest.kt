package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.RepoNode
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.paneBreadcrumb
import dev.herdr.mobile.ui.paneContexts
import dev.herdr.mobile.ui.paneMeta
import dev.herdr.mobile.ui.relativeAge
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneContextTest {
    private val pane = Pane(paneId = "p1", workspaceId = "w1", tabId = "t1", agent = "claude", agentStatus = "working")
    private val repos = listOf(
        RepoNode(
            "herdr-mobile", "herdr-mobile",
            listOf(WorkspaceNode(Workspace("w1", label = "chat-view", number = 1), listOf(TabNode(Tab("t1", label = "main", number = 1), listOf(pane))))),
        ),
    )

    @Test fun contextNamesPaneByWorkspace() {
        val ctx = paneContexts(repos).getValue("p1")
        assertEquals("chat-view", ctx.workspaceLabel)
        assertEquals(1, ctx.workspaceNumber)
        assertEquals("herdr-mobile › tab 1 › claude", paneBreadcrumb(ctx))
        assertEquals("herdr-mobile › tab 1", paneBreadcrumb(ctx, includeAgent = false))
        assertEquals("herdr-mobile · claude", paneMeta(ctx))
    }

    @Test fun shellMetaSaysShell() {
        val ctx = paneContexts(repos).getValue("p1").let { it.copy(pane = it.pane.copy(agent = null)) }
        assertEquals("herdr-mobile · shell", paneMeta(ctx))
    }

    @Test fun relativeAgeBuckets() {
        val now = 10_000_000_000L
        assertEquals("", relativeAge(0, now))
        assertEquals("now", relativeAge(now - 59_000, now))
        assertEquals("now", relativeAge(now + 5_000, now))   // clock skew
        assertEquals("2m", relativeAge(now - 120_000, now))
        assertEquals("3h", relativeAge(now - 3 * 3_600_000, now))
        assertEquals("2d", relativeAge(now - 2 * 86_400_000L, now))
    }
}
