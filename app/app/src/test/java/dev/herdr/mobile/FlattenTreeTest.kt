package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.RepoNode
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.TreeRow
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.buildRepoTree
import dev.herdr.mobile.ui.buildTree
import dev.herdr.mobile.ui.flattenTree
import dev.herdr.mobile.ui.treeRowKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlattenTreeTest {
    private fun pane(id: String, ws: String, tab: String) =
        Pane(paneId = id, workspaceId = ws, tabId = tab, terminalId = "term-$id")
    private fun tnode(id: String, ws: String, num: Int, panes: List<Pane>) =
        TabNode(Tab(tabId = id, workspaceId = ws, number = num), panes)
    private fun wnode(id: String, num: Int, tabs: List<TabNode>) =
        WorkspaceNode(Workspace(workspaceId = id, label = id, number = num), tabs)

    // repoA: one ws, one tab (elided → 2 promoted panes)
    private val p1 = pane("p1", "wA", "tA"); private val p2 = pane("p2", "wA", "tA")
    private val tA = tnode("tA", "wA", 1, listOf(p1, p2))
    private val wA = wnode("wA", 1, listOf(tA))
    // repoB: one ws, two tabs — tB1 visible (2 nested panes), tB2 elided (1 promoted pane)
    private val p3 = pane("p3", "wB", "tB1"); private val p4 = pane("p4", "wB", "tB1")
    private val p5 = pane("p5", "wB", "tB2")
    private val tB1 = tnode("tB1", "wB", 1, listOf(p3, p4))
    private val tB2 = tnode("tB2", "wB", 2, listOf(p5))
    private val wB = wnode("wB", 2, listOf(tB1, tB2))
    private val repos = listOf(
        RepoNode("repoA", "repoA", listOf(wA)),
        RepoNode("repoB", "repoB", listOf(wB)),
    )

    @Test fun flattensInRepoThenWorkspaceOrderWithPromotion() {
        val rows = flattenTree(repos, emptySet())
        // repoA: Repo, Ws, 2 promoted panes; repoB: Repo, Ws, Tab(tB1), p3, p4, promoted p5
        assertTrue(rows[0] is TreeRow.Repo && (rows[0] as TreeRow.Repo).node.repoKey == "repoA")
        assertEquals(2, (rows[0] as TreeRow.Repo).paneCount)
        assertTrue(rows[1] is TreeRow.Ws && (rows[1] as TreeRow.Ws).node.ws.workspaceId == "wA")
        val a1 = rows[2] as TreeRow.PaneItem; val a2 = rows[3] as TreeRow.PaneItem
        assertEquals("p1", a1.pane.paneId); assertTrue(a1.promoted); assertEquals("tA", a1.parentTab?.tab?.tabId)
        assertEquals("p2", a2.pane.paneId); assertTrue(a2.promoted)
        assertTrue(rows[4] is TreeRow.Repo && (rows[4] as TreeRow.Repo).node.repoKey == "repoB")
        assertTrue(rows[5] is TreeRow.Ws && (rows[5] as TreeRow.Ws).node.ws.workspaceId == "wB")
        assertTrue(rows[6] is TreeRow.Tab && (rows[6] as TreeRow.Tab).node.tab.tabId == "tB1")
        val n3 = rows[7] as TreeRow.PaneItem; val n4 = rows[8] as TreeRow.PaneItem
        assertEquals("p3", n3.pane.paneId); assertTrue(!n3.promoted); assertEquals(null, n3.parentTab)
        assertEquals("p4", n4.pane.paneId)
        val prom5 = rows[9] as TreeRow.PaneItem
        assertEquals("p5", prom5.pane.paneId); assertTrue(prom5.promoted); assertEquals("tB2", prom5.parentTab?.tab?.tabId)
        assertEquals(10, rows.size)
    }

    @Test fun collapsedRepoHidesSubtree() {
        val rows = flattenTree(repos, setOf("repo:repoA"))
        // repoA collapsed → only its Repo row; repoB fully expanded after it
        assertTrue(rows[0] is TreeRow.Repo && (rows[0] as TreeRow.Repo).node.repoKey == "repoA")
        assertTrue((rows[0] as TreeRow.Repo).expanded.not())
        assertTrue(rows[1] is TreeRow.Repo && (rows[1] as TreeRow.Repo).node.repoKey == "repoB")
    }

    @Test fun collapsedWorkspaceHidesChildren() {
        val rows = flattenTree(repos, setOf("wB"))
        // wB present but none of its tabs/panes
        assertTrue(rows.any { it is TreeRow.Ws && it.node.ws.workspaceId == "wB" && !it.expanded })
        assertTrue(rows.none { it is TreeRow.PaneItem && it.pane.paneId in setOf("p3", "p4", "p5") })
        assertTrue(rows.none { it is TreeRow.Tab && it.node.tab.tabId == "tB1" })
    }

    @Test fun collapsedTabHidesItsPanesOnly() {
        val rows = flattenTree(repos, setOf("tB1"))
        assertTrue(rows.any { it is TreeRow.Tab && it.node.tab.tabId == "tB1" && !it.expanded })
        assertTrue(rows.none { it is TreeRow.PaneItem && it.pane.paneId in setOf("p3", "p4") })
        assertTrue(rows.any { it is TreeRow.PaneItem && it.pane.paneId == "p5" }) // tB2 promoted, unaffected
    }

    @Test fun rowKeysAreUnique() {
        val keys = flattenTree(repos, emptySet()).map { treeRowKey(it) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun orphanTreeHasUniqueKeysAndUnknownRepo() {
        // A pane whose workspace/tab aren't in the lists surfaces under a synthetic "(unknown)".
        val real = Workspace(workspaceId = "w1", label = "repo", number = 1)
        val realTab = Tab(tabId = "t1", workspaceId = "w1", number = 1)
        val panes = listOf(
            Pane(paneId = "p1", workspaceId = "w1", tabId = "t1", terminalId = "x1"),
            Pane(paneId = "orphan", workspaceId = "gone", tabId = "gone", terminalId = "x2"),
        )
        val repoTree = buildRepoTree(buildTree(listOf(real), listOf(realTab), panes))
        val rows = flattenTree(repoTree, emptySet())
        val keys = rows.map { treeRowKey(it) }
        assertEquals(keys.size, keys.toSet().size)               // no key collisions incl. orphan
        assertTrue(rows.any { it is TreeRow.RepoWs && it.repo.repoKey == "(unknown)" })
        assertTrue(rows.any { it is TreeRow.PaneItem && it.pane.paneId == "orphan" })
    }

    // ws label == repo displayName → foldable
    private fun foldedRepo(key: String, wsNum: Int, panes: List<Pane>): RepoNode {
        val ws = WorkspaceNode(
            Workspace(workspaceId = "ws-$key", label = key, number = wsNum),
            listOf(TabNode(Tab(tabId = "tab-$key", workspaceId = "ws-$key", number = 1), panes)),
        )
        return RepoNode(key, key, listOf(ws))
    }

    @Test fun singleWorkspaceRepoWithRedundantLabelFolds() {
        val rows = flattenTree(listOf(foldedRepo("r", 3, listOf(pane("p1", "ws-r", "tab-r")))), emptySet())
        val rw = rows[0] as TreeRow.RepoWs
        assertEquals("r", rw.repo.repoKey); assertEquals(3, rw.wsNode.ws.number); assertEquals(1, rw.paneCount)
        assertTrue(rows.none { it is TreeRow.Repo || it is TreeRow.Ws })   // no separate repo/ws rows
        val pi = rows[1] as TreeRow.PaneItem                                // single tab elided → promoted
        assertTrue(pi.promoted); assertEquals("r", pi.repoLabel)
    }

    @Test fun singleWorkspaceRepoWithDistinctLabelDoesNotFold() {
        val ws = WorkspaceNode(
            Workspace(workspaceId = "ws-r", label = "feature-x", number = 1),
            listOf(TabNode(Tab(tabId = "tab-r", workspaceId = "ws-r", number = 1), listOf(pane("p1", "ws-r", "tab-r")))),
        )
        val rows = flattenTree(listOf(RepoNode("r", "r", listOf(ws))), emptySet())
        assertTrue(rows[0] is TreeRow.Repo); assertTrue(rows[1] is TreeRow.Ws)
    }

    @Test fun twoWorkspaceRepoDoesNotFold() {
        val w1 = WorkspaceNode(Workspace(workspaceId = "w1", label = "r", number = 1),
            listOf(TabNode(Tab(tabId = "t1", workspaceId = "w1", number = 1), listOf(pane("p1", "w1", "t1")))))
        val w2 = WorkspaceNode(Workspace(workspaceId = "w2", label = "r", number = 2),
            listOf(TabNode(Tab(tabId = "t2", workspaceId = "w2", number = 1), listOf(pane("p2", "w2", "t2")))))
        val rows = flattenTree(listOf(RepoNode("r", "r", listOf(w1, w2))), emptySet())
        assertTrue(rows[0] is TreeRow.Repo); assertEquals(2, rows.count { it is TreeRow.Ws })
    }

    @Test fun collapsedRepoWsHidesChildren() {
        val rows = flattenTree(listOf(foldedRepo("r", 1, listOf(pane("p1", "ws-r", "tab-r")))), setOf("ws-r"))
        assertTrue(rows[0] is TreeRow.RepoWs && !(rows[0] as TreeRow.RepoWs).expanded)
        assertTrue(rows.none { it is TreeRow.PaneItem })
    }

    @Test fun blankLabelWorkspaceFolds() {
        // foldableWorkspace's other branch: a blank ws label is also redundant → folds.
        val ws = WorkspaceNode(
            Workspace(workspaceId = "ws-r", label = "", number = 1),
            listOf(TabNode(Tab(tabId = "tab-r", workspaceId = "ws-r", number = 1), listOf(pane("p1", "ws-r", "tab-r")))),
        )
        val rows = flattenTree(listOf(RepoNode("r", "r", listOf(ws))), emptySet())
        assertTrue(rows[0] is TreeRow.RepoWs)
        assertTrue(rows.none { it is TreeRow.Repo || it is TreeRow.Ws })
    }

    @Test fun paneItemCarriesRepoLabelInMultiWorkspaceRepo() {
        // non-folded (multi-workspace) path also stamps repoLabel = repo.displayName.
        val w1 = WorkspaceNode(
            Workspace(workspaceId = "w1", label = "r", number = 1),
            listOf(TabNode(Tab(tabId = "t1", workspaceId = "w1", number = 1), listOf(pane("p1", "w1", "t1")))),
        )
        val w2 = WorkspaceNode(
            Workspace(workspaceId = "w2", label = "r", number = 2),
            listOf(TabNode(Tab(tabId = "t2", workspaceId = "w2", number = 1), listOf(pane("p2", "w2", "t2")))),
        )
        val rows = flattenTree(listOf(RepoNode("myrepo", "myrepo", listOf(w1, w2))), emptySet())
        assertTrue(rows[0] is TreeRow.Repo)   // multi-ws → not folded
        rows.filterIsInstance<TreeRow.PaneItem>().forEach { assertEquals("myrepo", it.repoLabel) }
    }
}
