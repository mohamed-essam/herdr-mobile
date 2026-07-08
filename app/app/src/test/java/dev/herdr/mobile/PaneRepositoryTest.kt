package dev.herdr.mobile

import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.*
import org.junit.Assert.*
import org.junit.Test

class PaneRepositoryTest {
    private fun pane(id: String, status: String?) =
        Pane(paneId = id, workspaceId = id.substringBefore(":"), agentStatus = status, agent = if (status != null) "claude" else null)

    @Test fun snapshotThenUpdateThenRemove() {
        val repo = PaneRepository()
        repo.onFrame(ServerFrame.Panes(listOf(pane("w2:p1", "working"), pane("w6:p1", "idle"))))
        assertEquals(2, repo.panes.value.size)

        repo.onFrame(ServerFrame.PaneUpdate(pane("w6:p1", "blocked")))
        // blocked sorts first
        assertEquals("w6:p1", repo.panes.value.first().paneId)
        assertEquals("blocked", repo.panes.value.first().agentStatus)

        repo.onFrame(ServerFrame.PaneRemoved("w2:p1"))
        assertEquals(1, repo.panes.value.size)
        assertEquals("w6:p1", repo.panes.value.single().paneId)
    }

    @Test fun storesWorkspacesAndTabs() {
        val repo = PaneRepository()
        repo.onFrame(ServerFrame.Workspaces(listOf(
            Workspace(workspaceId = "w7", label = "omega3", number = 4, paneCount = 2, tabCount = 2))))
        repo.onFrame(ServerFrame.Tabs(listOf(
            Tab(tabId = "w7:t1", label = "1", number = 1, workspaceId = "w7"))))
        assertEquals("omega3", repo.workspaces.value.single().label)
        assertEquals("w7:t1", repo.tabs.value.single().tabId)
    }
}
