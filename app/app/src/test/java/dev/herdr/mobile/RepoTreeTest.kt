package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.net.Worktree
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.attentionTier
import dev.herdr.mobile.ui.buildRepoTree
import dev.herdr.mobile.ui.repoKeyFor
import dev.herdr.mobile.ui.workspaceTier
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
        // intra-group order is now tier→activity→number; a(#4) & b(#2) are equal
        // tier/activity, so number wins: w2 before w1.
        assertEquals(listOf("w2", "w1"), ops.workspaces.map { it.ws.workspaceId })
        assertEquals("ops", ops.displayName)
    }

    @Test fun attentionTierRanks() {
        assertEquals(2, attentionTier("blocked"))
        assertEquals(1, attentionTier("done"))
        assertEquals(0, attentionTier("working"))
        assertEquals(0, attentionTier("idle"))
        assertEquals(0, attentionTier(null))
        assertEquals(0, attentionTier("unknown"))
    }

    @Test fun workspaceTierIsMaxOverPanes() {
        // a workspace with a working pane and a blocked pane → tier 2 (blocked wins)
        val ws = Workspace(workspaceId = "w1", label = "x", number = 1)
        val panes = listOf(
            Pane(paneId = "w1:p1", workspaceId = "w1", tabId = "w1:t1", agent = "claude", agentStatus = "working"),
            Pane(paneId = "w1:p2", workspaceId = "w1", tabId = "w1:t1", agent = "codex", agentStatus = "blocked"),
        )
        val node = WorkspaceNode(ws, listOf(TabNode(Tab(tabId = "w1:t1", workspaceId = "w1"), panes)))
        assertEquals(2, workspaceTier(node))
        // no panes → 0
        assertEquals(0, workspaceTier(WorkspaceNode(ws, emptyList())))
    }

    private fun wsNodeStatus(id: String, number: Int, status: String?, lastActivity: Long = 0, repoName: String? = null): WorkspaceNode {
        val ws = Workspace(
            workspaceId = id, label = id, number = number, lastActivity = lastActivity,
            worktree = repoName?.let { Worktree(repoName = it, isLinkedWorktree = true) },
        )
        val pane = Pane(paneId = "$id:p1", workspaceId = id, tabId = "$id:t1", agent = "claude", agentStatus = status)
        return WorkspaceNode(ws, listOf(TabNode(Tab(tabId = "$id:t1", workspaceId = id), listOf(pane))))
    }

    @Test fun workspacesSortByTierThenActivity() {
        val working = wsNodeStatus("w1", number = 1, status = "working", lastActivity = 100, repoName = "r")
        val done = wsNodeStatus("w2", number = 2, status = "done", lastActivity = 100, repoName = "r")
        val blocked = wsNodeStatus("w3", number = 3, status = "blocked", lastActivity = 100, repoName = "r")
        val workingNewer = wsNodeStatus("w4", number = 4, status = "working", lastActivity = 999, repoName = "r")

        val repo = buildRepoTree(listOf(working, done, blocked, workingNewer)).single { it.repoKey == "r" }
        // blocked > done > (working ordered by activity: w4 newer before w1)
        assertEquals(listOf("w3", "w2", "w4", "w1"), repo.workspaces.map { it.ws.workspaceId })
    }

    @Test fun reposSortByBestWorkspace() {
        val calm = wsNodeStatus("w1", number = 1, status = "working", lastActivity = 100, repoName = "calm")
        val urgent = wsNodeStatus("w2", number = 2, status = "blocked", lastActivity = 50, repoName = "urgent")
        val repos = buildRepoTree(listOf(calm, urgent)).map { it.repoKey }
        // "urgent" has a blocked workspace → sorts above "calm" despite older activity/higher number
        assertEquals(listOf("urgent", "calm"), repos)
    }
}
