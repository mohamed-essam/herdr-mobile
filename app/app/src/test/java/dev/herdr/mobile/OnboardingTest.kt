package dev.herdr.mobile

import dev.herdr.mobile.data.pushEnabled
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.FirstConnectPhase
import dev.herdr.mobile.ui.HerdSummary
import dev.herdr.mobile.ui.RepoNode
import dev.herdr.mobile.ui.StepState
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.connectStepStates
import dev.herdr.mobile.ui.firstConnectPhase
import dev.herdr.mobile.ui.herdLine
import dev.herdr.mobile.ui.herdSummary
import dev.herdr.mobile.ui.parseCompanionAddress
import org.junit.Assert.*
import org.junit.Test

class OnboardingTest {
    @Test fun bareHostGetsSchemeAndDefaultPort() {
        val a = parseCompanionAddress("  100.101.7.12 ")!!
        assertEquals("ws://100.101.7.12:8787", a.url)
        assertEquals("100.101.7.12:8787", a.hostPort)
        assertFalse(a.hasScheme)
        assertFalse(a.hasPort)
        assertEquals("ws://my-box.tail1234.ts.net:8787", parseCompanionAddress("my-box.tail1234.ts.net")!!.url)
    }

    @Test fun hostPortKeepsItsPort() {
        val a = parseCompanionAddress("100.101.7.12:9000")!!
        assertEquals("ws://100.101.7.12:9000", a.url)
        assertEquals("100.101.7.12:9000", a.hostPort)
        assertTrue(a.hasPort)
        // A dangling colon is a host still being typed.
        assertEquals("ws://box:8787", parseCompanionAddress("box:")!!.url)
    }

    @Test fun fullUrlIsTakenAsIs() {
        val a = parseCompanionAddress("ws://box:8787")!!
        assertEquals("ws://box:8787", a.url)
        assertEquals("box:8787", a.hostPort)
        assertTrue(a.hasScheme)
        assertTrue(a.hasPort)
        val tls = parseCompanionAddress("wss://box.example/companion")!!
        assertEquals("wss://box.example/companion", tls.url)
        assertEquals("box.example", tls.hostPort)
        assertFalse(tls.hasPort)
    }

    @Test fun ipv6() {
        assertEquals("ws://[fd7a:115c::1]:8787", parseCompanionAddress("fd7a:115c::1")!!.url)
        assertEquals("ws://[fd7a::1]:9000", parseCompanionAddress("[fd7a::1]:9000")!!.url)
        assertEquals("ws://[fd7a::1]:8787", parseCompanionAddress("[fd7a::1]")!!.url)
    }

    @Test fun rejectsJunk() {
        assertNull(parseCompanionAddress(""))
        assertNull(parseCompanionAddress("   "))
        assertNull(parseCompanionAddress("my box"))
        assertNull(parseCompanionAddress("http://box:8787"))
        assertNull(parseCompanionAddress("ws://"))
        assertNull(parseCompanionAddress(":8787"))
    }

    @Test fun stepStates() {
        assertEquals(listOf(StepState.Done, StepState.Current, StepState.Upcoming), connectStepStates(false))
        assertEquals(listOf(StepState.Done, StepState.Done, StepState.Current), connectStepStates(true))
    }

    @Test fun firstConnectPhases() {
        assertEquals(FirstConnectPhase.Connecting, firstConnectPhase(false, 0))
        assertEquals(FirstConnectPhase.Connecting, firstConnectPhase(false, 9_999))
        assertEquals(FirstConnectPhase.Slow, firstConnectPhase(false, 10_000))
        assertEquals(FirstConnectPhase.Connected, firstConnectPhase(true, 30_000))
    }

    @Test fun pushDefaultsOnOnlyForExistingInstalls() {
        assertTrue(pushEnabled(stored = null, hasCompanionUrl = true))
        assertFalse(pushEnabled(stored = null, hasCompanionUrl = false))
        assertFalse(pushEnabled(stored = false, hasCompanionUrl = true))
        assertTrue(pushEnabled(stored = true, hasCompanionUrl = false))
    }

    private fun ws(id: String, vararg statuses: String?) = WorkspaceNode(
        Workspace(id, label = id, number = 1),
        listOf(TabNode(Tab("$id:t", workspaceId = id), statuses.mapIndexed { i, s -> Pane("$id:p$i", workspaceId = id, agentStatus = s) })),
    )

    @Test fun herdCounts() {
        val repos = listOf(
            RepoNode("a", "a", listOf(ws("w1", "blocked", "working"), ws("w2", "done", null))),
            RepoNode("b", "b", listOf(ws("w3", "blocked", "working", "working", "idle"))),
            RepoNode("(unknown)", "(unknown)", listOf(ws("", "done"))),
        )
        val s = herdSummary(repos)
        assertEquals(HerdSummary(repos = 2, workspaces = 3, panes = 9, needYou = 2, working = 3, done = 2), s)
        assertEquals("2 repos · 3 workspaces · 9 panes", herdLine(s))
        assertEquals("1 repo · 1 workspace · 1 pane", herdLine(HerdSummary(1, 1, 1, 0, 0, 0)))
        assertEquals(HerdSummary(0, 0, 0, 0, 0, 0), herdSummary(emptyList()))
    }
}
