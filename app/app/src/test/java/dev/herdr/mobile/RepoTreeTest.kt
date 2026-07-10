package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.net.Worktree
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.buildRepoTree
import dev.herdr.mobile.ui.repoKeyFor
import org.junit.Assert.*
import org.junit.Test

class RepoTreeTest {
    private fun wsNode(
        id: String, number: Int = 1, label: String = "", repoName: String? = null, cwd: String = "",
    ): WorkspaceNode {
        val ws = Workspace(
            workspaceId = id, label = label, number = number,
            worktree = repoName?.let { Worktree(repoName = it, isLinkedWorktree = true) },
        )
        val pane = Pane(paneId = "$id:p1", workspaceId = id, tabId = "$id:t1", cwd = cwd)
        val tab = TabNode(Tab(tabId = "$id:t1", workspaceId = id), listOf(pane))
        return WorkspaceNode(ws, listOf(tab))
    }

    @Test fun keyFallbackOrder() {
        assertEquals("ops", repoKeyFor(wsNode("w1", repoName = "ops", cwd = "/home/x/ignored")))
        assertEquals("omega3", repoKeyFor(wsNode("w2", cwd = "/home/x/omega3")))
        assertEquals("L", repoKeyFor(wsNode("w3", label = "L")))
        assertEquals("w4", repoKeyFor(wsNode("w4")))
        // synthetic orphan workspace (blank id) buckets under "(unknown)"
        val orphan = WorkspaceNode(Workspace(workspaceId = "", label = "(unknown)", number = Int.MAX_VALUE), emptyList())
        assertEquals("(unknown)", repoKeyFor(orphan))
    }

    @Test fun groupsSameRepoAndOrders() {
        val a = wsNode("w1", number = 4, repoName = "ops")
        val b = wsNode("w2", number = 2, repoName = "ops")   // same repo, lower number
        val c = wsNode("w3", number = 3, repoName = "core")
        val orphan = WorkspaceNode(Workspace(workspaceId = "", label = "(unknown)", number = Int.MAX_VALUE), emptyList())

        val repos = buildRepoTree(listOf(a, c, b, orphan))

        // core (min#3) before ops (min#2)? No: ops min# is 2 -> ops first, then core (3), unknown last.
        assertEquals(listOf("ops", "core", "(unknown)"), repos.map { it.repoKey })
        val ops = repos.first { it.repoKey == "ops" }
        assertEquals(2, ops.workspaces.size)
        // intra-group order preserved as given (a before b)
        assertEquals(listOf("w1", "w2"), ops.workspaces.map { it.ws.workspaceId })
        assertEquals("ops", ops.displayName)
    }
}
