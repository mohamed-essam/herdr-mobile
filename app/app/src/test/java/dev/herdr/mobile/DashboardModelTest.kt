package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.PaneActivity
import dev.herdr.mobile.net.PaneAsk
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.AnswerPhase
import dev.herdr.mobile.ui.NodeKind
import dev.herdr.mobile.ui.RepoNode
import dev.herdr.mobile.ui.RowAction
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.actionHeader
import dev.herdr.mobile.ui.activityLine
import dev.herdr.mobile.ui.answerFailed
import dev.herdr.mobile.ui.answerSent
import dev.herdr.mobile.ui.closeSummary
import dev.herdr.mobile.ui.dashboardSections
import dev.herdr.mobile.ui.expireAnswers
import dev.herdr.mobile.ui.filterRepos
import dev.herdr.mobile.ui.hostPort
import dev.herdr.mobile.ui.inlineAnswerMap
import dev.herdr.mobile.ui.inlineChoice
import dev.herdr.mobile.ui.mostRelevantPane
import dev.herdr.mobile.ui.nextAnswerExpiry
import dev.herdr.mobile.ui.OpenRequest
import dev.herdr.mobile.ui.resolveOpenRequest
import dev.herdr.mobile.ui.staleLabel
import dev.herdr.mobile.ui.startAnswer
import dev.herdr.mobile.ui.statusCounts
import dev.herdr.mobile.ui.tabOf
import dev.herdr.mobile.ui.workspaceStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class DashboardModelTest {
    private fun agent(id: String, status: String, ts: Long = 0, ask: PaneAsk? = null, ws: String = "w1", tab: String = "t1") =
        Pane(paneId = id, workspaceId = ws, tabId = tab, agent = "claude", agentStatus = status,
            activity = if (ts > 0) PaneActivity(kind = "text", text = "x", ts = ts) else null, ask = ask)

    private fun shell(id: String, ws: String = "w1", tab: String = "t1") =
        Pane(paneId = id, workspaceId = ws, tabId = tab, agentStatus = "unknown", cwd = "/home/u/$id")

    private fun ask(id: String, json: String) =
        PaneAsk(id, Json.parseToJsonElement(json) as JsonArray)

    private val choice = """[{"question":"Which layout?","header":"Layout","options":[{"label":"Compact"},{"label":"Well"}]}]"""

    @Test fun sectionsOrderEachStatus() {
        val panes = listOf(
            agent("b-new", "blocked", 300), agent("b-old", "blocked", 100), agent("b-unknown", "blocked"),
            agent("w-old", "working", 100), agent("w-unknown", "working"), agent("w-new", "working", 500),
            agent("d1", "done", 50), agent("i1", "idle"), shell("s1"),
        )
        val s = dashboardSections(panes)
        assertEquals(listOf("b-old", "b-new", "b-unknown"), s.needsYou.map { it.paneId })
        assertEquals(listOf("w-new", "w-old", "w-unknown"), s.working.map { it.paneId })
        assertEquals(listOf("d1"), s.done.map { it.paneId })
        assertEquals(listOf("i1", "s1"), s.idle.map { it.paneId })
    }

    @Test fun aPendingQuestionNeedsYouWhateverHerdrSays() {
        val panes = listOf(agent("w", "working", 100, ask("q", choice)), agent("i", "idle", ask = ask("q2", choice)), agent("d", "done"))
        val s = dashboardSections(panes)
        assertEquals(listOf("w", "i"), s.needsYou.map { it.paneId })
        assertTrue(s.working.isEmpty())
        assertEquals(listOf("d"), s.done.map { it.paneId })
        assertTrue(s.idle.isEmpty())
        val c = statusCounts(panes)
        assertEquals(listOf(2, 0, 1), listOf(c.blocked, c.working, c.done))
    }

    @Test fun noPaneDisappears() {
        val panes = listOf(agent("a", "blocked"), agent("b", "unknown"), shell("c"), agent("d", "working"))
        val s = dashboardSections(panes)
        assertEquals(panes.map { it.paneId }.toSet(), (s.needsYou + s.working + s.done + s.idle).map { it.paneId }.toSet())
    }

    @Test fun answeredPaneHidesBehindResumedRowUntilANewQuestion() {
        val q = ask("tu1", choice)
        val sent = answerSent(startAnswer(emptyMap(), "a", "tu1", "Compact", 0)!!, "a", 10)
        assertTrue(dashboardSections(listOf(agent("a", "blocked", ask = q)), sent).needsYou.isEmpty())
        assertTrue(dashboardSections(listOf(agent("a", "blocked", ask = null)), sent).needsYou.isEmpty())
        val next = ask("tu2", choice)
        assertEquals(1, dashboardSections(listOf(agent("a", "blocked", ask = next)), sent).needsYou.size)
        // while still sending the card stays (showing "sending…")
        val sending = startAnswer(emptyMap(), "a", "tu1", "Compact", 0)!!
        assertEquals(1, dashboardSections(listOf(agent("a", "blocked", ask = q)), sending).needsYou.size)
    }

    @Test fun inlineAnswerStateMachine() {
        val s1 = startAnswer(emptyMap(), "a", "tu1", "Compact", 1000)!!
        assertEquals(AnswerPhase.Sending, s1["a"]!!.phase)
        assertNull("no double send", startAnswer(s1, "a", "tu1", "Well", 1001))
        assertTrue(answerFailed(s1, "a").isEmpty())
        val s2 = answerSent(s1, "a", 2000)
        assertEquals(AnswerPhase.Sent, s2["a"]!!.phase)
        assertEquals(2000L, nextAnswerExpiry(s2, 4000))
        assertEquals(s2, expireAnswers(s2, 5999))
        assertTrue(expireAnswers(s2, 6000).isEmpty())
        assertEquals("sending entries never expire", s1, expireAnswers(s1, 1_000_000))
        assertNull(nextAnswerExpiry(s1, 0))
        assertEquals("unknown pane is a no-op", s1, answerSent(s1, "zzz", 1))
    }

    @Test fun inlineChoiceOnlyForALoneSingleSelectChoice() {
        val one = inlineChoice(ask("t", choice))!!
        assertEquals(mapOf("Which layout?" to "Compact"), inlineAnswerMap(one, "Compact"))
        assertNull(inlineChoice(null))
        assertNull(inlineChoice(ask("t", """[{"question":"a","header":"h","multiSelect":true,"options":[{"label":"x"}]}]""")))
        assertNull(inlineChoice(ask("t", """[{"question":"a","header":"h","kind":"text"}]""")))
        assertNull(inlineChoice(ask("t", """[{"question":"a","header":"h","options":[]}]""")))
        assertNull(inlineChoice(ask("t", choice.dropLast(1) + """,{"question":"b","header":"h","options":[{"label":"x"}]}]""")))
    }

    private fun ws(id: String, number: Int, label: String, vararg tabs: TabNode) =
        WorkspaceNode(Workspace(workspaceId = id, label = label, number = number), tabs.toList())

    private fun tab(id: String, ws: String, number: Int, vararg panes: Pane) =
        TabNode(Tab(tabId = id, workspaceId = ws, number = number), panes.toList())

    @Test fun workspaceStatusAndMostRelevantPane() {
        val w = ws("w1", 1, "chat-view",
            tab("t1", "w1", 1, shell("s1"), agent("d", "done")),
            tab("t2", "w1", 2, agent("wk", "working"), agent("bl", "blocked")))
        assertEquals("blocked", workspaceStatus(w))
        assertEquals("bl", mostRelevantPane(w)?.paneId)
        val w2 = ws("w2", 2, "x", tab("t3", "w2", 1, shell("s2"), agent("wk2", "working")))
        assertEquals("working", workspaceStatus(w2))
        assertEquals("wk2", mostRelevantPane(w2)?.paneId)
        val w3 = ws("w3", 3, "y", tab("t4", "w3", 1, shell("s3"), agent("d3", "done")))
        assertEquals("done", workspaceStatus(w3))
        assertEquals("s3", mostRelevantPane(w3)?.paneId)
        assertEquals("idle", workspaceStatus(ws("w4", 4, "z", tab("t5", "w4", 1, shell("s4")))))
        assertNull(mostRelevantPane(ws("w5", 5, "e")))
    }

    @Test fun filterReposMatchesLabelAgentPathAndRepo() {
        val repos = listOf(
            RepoNode("herdr-mobile", "herdr-mobile", listOf(
                ws("w1", 1, "chat-view", tab("t1", "w1", 1, agent("p1", "working"))),
                ws("w2", 2, "keybar", tab("t2", "w2", 1, shell("deploy-dir", "w2", "t2"))),
            )),
            RepoNode("infra", "infra", listOf(ws("w3", 3, "ops", tab("t3", "w3", 1, shell("s3", "w3", "t3"))))),
        )
        assertEquals(repos, filterRepos(repos, "  "))
        assertEquals(listOf("w1"), filterRepos(repos, "CHAT").flatMap { r -> r.workspaces.map { it.ws.workspaceId } })
        assertEquals(listOf("w1"), filterRepos(repos, "claude").flatMap { r -> r.workspaces.map { it.ws.workspaceId } })
        assertEquals(listOf("w2"), filterRepos(repos, "deploy-dir").flatMap { r -> r.workspaces.map { it.ws.workspaceId } })
        assertEquals(listOf("w3"), filterRepos(repos, "infra").flatMap { r -> r.workspaces.map { it.ws.workspaceId } })
        assertTrue(filterRepos(repos, "nothing").isEmpty())
    }

    private val tree = listOf(
        ws("w1", 1, "chat-view",
            tab("t1", "w1", 1, agent("p1", "blocked")),
            tab("t2", "w1", 2, shell("s1"), shell("s2"))),
    )

    @Test fun closeSummaryForEachKind() {
        val w = closeSummary(RowAction(NodeKind.WORKSPACE, "w1", "chat-view"), tree)
        assertEquals("chat-view ends 3 panes across 2 tabs", w.headline)
        assertEquals(listOf("claude", "shell", "shell"), w.panes.map { it.name })
        assertEquals(listOf("tab 1", "tab 2", "tab 2"), w.panes.map { it.tab })
        assertEquals("blocked", w.panes[0].status)

        val t = closeSummary(RowAction(NodeKind.TAB, "t2", "2", paneCount = 2), tree)
        assertEquals("tab 2 ends 2 panes", t.headline)

        val p = closeSummary(RowAction(NodeKind.PANE, "p1", "claude", isAgent = true), tree)
        assertEquals("Closing this pane terminates claude", p.headline)
        assertEquals(1, p.panes.size)

        val missing = closeSummary(RowAction(NodeKind.WORKSPACE, "gone", "old", paneCount = 1, tabCount = 1), tree)
        assertEquals("old ends 1 pane", missing.headline)
    }

    @Test fun actionHeaderAndTabPivot() {
        val repos = listOf(RepoNode("herdr-mobile", "herdr-mobile", tree))
        val h = actionHeader(RowAction(NodeKind.PANE, "p1", "claude", isAgent = true), repos)
        assertEquals(1, h.number)
        assertEquals("claude", h.title)
        assertEquals("herdr-mobile › chat-view › tab 1", h.path)
        assertEquals("blocked", h.status)
        assertEquals("chat-view", actionHeader(RowAction(NodeKind.WORKSPACE, "w1", "chat-view"), repos).title)
        assertEquals("herdr-mobile › chat-view", actionHeader(RowAction(NodeKind.TAB, "t2", "2"), repos).path)
        assertEquals("t2", tabOf("s2", tree)?.tab?.tabId)
        assertNull(tabOf("nope", tree))
    }

    @Test fun formatters() {
        assertEquals("▸ Bash npm run build", activityLine(PaneActivity("tool", "Bash", "npm run build", 1)))
        assertEquals("▸ Read", activityLine(PaneActivity("tool", "Read", "", 1)))
        assertEquals("▸ Edit ui/TerminalKeys.kt", activityLine(PaneActivity("tool", "Edit", "Edit: /home/u/app/ui/TerminalKeys.kt", 1)))
        assertEquals("▸ Bash npm test", activityLine(PaneActivity("tool", "Bash", "Bash: npm test", 1)))
        assertEquals("first line", activityLine(PaneActivity("text", null, "\n first line \nsecond", 1)))
        assertNull(activityLine(PaneActivity("text", null, "  ", 1)))
        assertNull(activityLine(null))
        assertEquals("32s ago", staleLabel(0, 32_400))
        assertEquals("5m ago", staleLabel(0, 300_000))
        assertEquals("2h ago", staleLabel(0, 7_200_000))
        assertEquals("100.101.7.12:8787", hostPort("ws://100.101.7.12:8787/ws?token=abc"))
        assertEquals("host:1", hostPort("wss://user@host:1"))
        assertEquals("host", hostPort("host"))
        val c = statusCounts(listOf(agent("a", "blocked"), agent("b", "working"), agent("c", "working"), shell("s")))
        assertEquals(1, c.blocked); assertEquals(2, c.working); assertEquals(0, c.done)
    }

    @Test fun openRequestIsResolvedOnce() {
        val p = agent("p1", "blocked").copy(terminalId = "t-1")
        // No snapshot yet: keep waiting.
        assertEquals(OpenRequest.Wait, resolveOpenRequest("p1", emptyList()))
        assertEquals(OpenRequest.Open(p), resolveOpenRequest("p1", listOf(p, shell("s"))))
        // Gone, or nothing to show: drop it rather than open it on some later update.
        assertEquals(OpenRequest.Drop, resolveOpenRequest("gone", listOf(p)))
        assertEquals(OpenRequest.Drop, resolveOpenRequest("p1", listOf(agent("p1", "blocked"))))
        assertEquals(OpenRequest.Open(p.copy(terminalId = "", chat = true)),
            resolveOpenRequest("p1", listOf(p.copy(terminalId = "", chat = true))))
    }
}
