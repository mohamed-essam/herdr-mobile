package dev.herdr.mobile

import dev.herdr.mobile.net.ChatEntry
import dev.herdr.mobile.net.AgentSummary
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.QuestionItem
import dev.herdr.mobile.ui.ChatStatus
import dev.herdr.mobile.ui.ChatStatusKind
import dev.herdr.mobile.ui.Speaker
import dev.herdr.mobile.ui.TimelineItem
import dev.herdr.mobile.ui.buildTimeline
import dev.herdr.mobile.ui.agentsFor
import dev.herdr.mobile.ui.chatStatus
import dev.herdr.mobile.ui.workflowGroups
import dev.herdr.mobile.ui.pendingQuestion
import dev.herdr.mobile.ui.runningTools
import dev.herdr.mobile.ui.toolLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {
    private var seq = 0
    private fun e(ev: ChatEvent) = ChatEntry(++seq, ev)
    private fun user(t: String) = e(ChatEvent.UserText("u$seq", t))
    private fun said(t: String) = e(ChatEvent.AssistantText("a$seq", t))
    private fun tool(id: String, name: String = "Read") = e(ChatEvent.ToolUse("t$seq", id, name, "$name: x"))
    private fun result(id: String) = e(ChatEvent.ToolResult(id, false, "ok"))
    private fun question(id: String) = e(ChatEvent.Question("q$seq", id, listOf(QuestionItem("Which?", "H"))))
    private fun notice() = e(ChatEvent.TaskNotice("n$seq", "completed", "done"))

    @Test fun consecutiveToolCallsFoldIntoOneGroup() {
        val entries = listOf(said("look"), tool("1"), result("1"), tool("2"), tool("3"), result("2"), said("done"))
        val items = buildTimeline(entries, 0)
        assertEquals(3, items.size)
        val group = items[1] as TimelineItem.Tools
        assertEquals(listOf("1", "2", "3"), group.tools.map { (it.event as ChatEvent.ToolUse).toolUseId })
        assertEquals("e0-${entries[1].seq}", group.key)
    }

    // Paging back prepends older entries. The rows already shown must keep
    // their keys (the list's scroll anchor) and their head (label + spacing,
    // i.e. their height), or the list jumps each time a page lands.
    @Test fun aPrependedPageLeavesTheShownRowsUnchanged() {
        val older = listOf(user("go"), said("ok"), tool("1"), result("1"), tool("2"))
        val shown = listOf(result("2"), tool("3"), result("3"), said("more"), tool("4"))
        val before = buildTimeline(shown, 0)
        val after = buildTimeline(older + shown, 0, pageStarts = setOf(shown.first().seq))
        assertEquals(before, after.takeLast(before.size))
        // Without the seam, call 3 would fold into the older run and lose its key.
        val merged = buildTimeline(older + shown, 0)
        assertTrue(merged.none { it.key == before.first().key })
    }

    @Test fun messagesSplitToolRuns() {
        val items = buildTimeline(listOf(tool("1"), said("mid"), tool("2")), 0)
        assertEquals(listOf(TimelineItem.Tools::class, TimelineItem.Event::class, TimelineItem.Tools::class), items.map { it::class })
    }

    @Test fun headMarksTheFirstRowOfEachSpeakerBlock() {
        val items = buildTimeline(listOf(user("hi"), said("a"), tool("1"), said("b"), user("ok"), user("more")), 0)
        assertEquals(listOf(true, true, false, false, true, false), items.map { it.head })
        assertEquals(listOf(Speaker.User, Speaker.Agent, Speaker.Agent, Speaker.Agent, Speaker.User, Speaker.User), items.map { it.speaker })
    }

    @Test fun noticesAreAlwaysAHeadAndBreakTheBlock() {
        val items = buildTimeline(listOf(said("a"), notice(), notice(), said("b")), 0)
        assertEquals(listOf(true, true, true, true), items.map { it.head })
    }

    @Test fun questionToolCallShowsOnlyAsTheQuestion() {
        val items = buildTimeline(listOf(tool("q1", "AskUserQuestion"), question("q1"), result("q1")), 3)
        assertEquals(1, items.size)
        val ev = (items[0] as TimelineItem.Event).entry.event
        assertTrue(ev is ChatEvent.Question)
        assertEquals("e3-2", items[0].key)
    }

    @Test fun groupKeyStaysWhenCallsAreAppended() {
        val base = listOf(user("go"), tool("1"))
        val before = buildTimeline(base, 0).last().key
        val after = buildTimeline(base + tool("2") + result("2"), 0).last().key
        assertEquals(before, after)
    }

    @Test fun runningToolsAreTheUnresultedCallsOfTheNewestGroupWhileWorking() {
        val items = buildTimeline(listOf(tool("old"), said("x"), tool("1"), result("1"), tool("2")), 0)
        assertEquals(setOf("2"), runningTools(items, setOf("1"), working = true))
        assertEquals(emptySet<String>(), runningTools(items, setOf("1"), working = false))
        val ended = buildTimeline(listOf(tool("1"), said("x")), 0)
        assertEquals(emptySet<String>(), runningTools(ended, emptySet(), working = true))
    }

    @Test fun toolLineDropsTheColonAndShortensFilePaths() {
        assertEquals("Read ui/TerminalScreen.kt", toolLine("Read", "Read: /home/me/app/ui/TerminalScreen.kt"))
        assertEquals("Edit a/b.kt", toolLine("Edit", "Edit: a/b.kt"))
        assertEquals("Bash ./gradlew test", toolLine("Bash", "Bash: ./gradlew test"))
        assertEquals("TodoWrite", toolLine("TodoWrite", "TodoWrite"))
        assertEquals("Grep", toolLine("Grep", ""))
    }

    @Test fun pendingQuestionIsTheNewestUnansweredWithNothingSaidAfter() {
        val q = question("q1")
        assertEquals("q1", pendingQuestion(listOf(said("hm"), tool("q1", "AskUserQuestion"), q), emptySet())?.toolUseId)
        assertNull(pendingQuestion(listOf(q), setOf("q1")))
        assertNull(pendingQuestion(listOf(q, said("moving on")), emptySet()))
        assertNull(pendingQuestion(listOf(q, user("never mind")), emptySet()))
        assertEquals("q1", pendingQuestion(listOf(q, notice()), emptySet())?.toolUseId)
    }

    @Test fun statusPrefersOfflineThenWaitingThenWorking() {
        assertEquals(ChatStatus("reconnecting…", ChatStatusKind.Offline), chatStatus(false, "working", true))
        assertEquals(ChatStatus("waiting on you", ChatStatusKind.Waiting), chatStatus(true, "working", true))
        assertEquals(ChatStatus("working", ChatStatusKind.Working), chatStatus(true, "working", false))
        assertEquals(ChatStatus("idle", ChatStatusKind.Idle), chatStatus(true, "idle", false))
    }

    private fun agent(id: String, parent: String, ts: Long, phase: String? = null) =
        AgentSummary(id, parent, null, "subagent", id, null, phase, "running", null, ts)

    @Test fun agentCallFlushesTheRailAndResumesAfter() {
        val items = buildTimeline(listOf(tool("1"), tool("A", "Agent"), tool("2", "Bash")), 0)
        assertEquals(3, items.size)
        assertTrue(items[0] is TimelineItem.Tools)
        val card = items[1] as TimelineItem.AgentCard
        assertEquals("A", card.call.toolUseId)
        assertEquals(Speaker.Agent, card.speaker)
        assertEquals("Bash", ((items[2] as TimelineItem.Tools).tools.single().event as ChatEvent.ToolUse).tool)
    }

    @Test fun agentCardKeyIsStableAcrossEpochs() {
        val entries = listOf(tool("toolu_A", "Agent"))
        assertEquals("a:toolu_A", (buildTimeline(entries, 1).single()).key)
        assertEquals("a:toolu_A", (buildTimeline(entries, 2).single()).key)
    }

    @Test fun workflowCallBecomesACard() {
        assertTrue(buildTimeline(listOf(tool("W", "Workflow")), 0).single() is TimelineItem.AgentCard)
    }

    @Test fun askUserQuestionCallStillDropped() {
        val items = buildTimeline(listOf(tool("Q", "AskUserQuestion"), question("Q")), 0)
        assertTrue(items.none { it is TimelineItem.AgentCard })
        assertEquals(1, items.size)
    }

    @Test fun pageSeamBeforeAgentCallKeepsItsKey() {
        val entries = listOf(said("x"), tool("A", "Agent"))
        val plain = buildTimeline(entries, 0).last().key
        val seamed = buildTimeline(entries, 0, setOf(entries[1].seq)).last().key
        assertEquals(plain, seamed)
    }

    @Test fun agentsForFiltersByParentAndSortsByTs() {
        val call = ChatEvent.ToolUse("u", "A", "Agent", "go")
        val map = mapOf("x" to agent("x", "A", 30), "y" to agent("y", "B", 5), "z" to agent("z", "A", 10))
        assertEquals(listOf("z", "x"), agentsFor(call, map).map { it.agentId })
    }

    @Test fun workflowGroupsKeepFirstSeenPhaseOrder() {
        val groups = workflowGroups(listOf(
            agent("a", "W", 1, "build"), agent("b", "W", 2, "test"), agent("c", "W", 3, "build"), agent("d", "W", 4),
        ))
        assertEquals(listOf<String?>("build", "test", null), groups.map { it.first })
        assertEquals(listOf("a", "c"), groups[0].second.map { it.agentId })
    }
}
