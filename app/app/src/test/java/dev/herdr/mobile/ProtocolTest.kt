package dev.herdr.mobile

import dev.herdr.mobile.net.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun parsesPanesSnapshot() {
        val f = parseServerFrame("""{"t":"panes","panes":[{"paneId":"w6:p1","workspaceId":"w6","tabId":"w6:t1","cwd":"/x","focused":true,"agent":"claude","agentStatus":"working"}]}""")
        assertTrue(f is ServerFrame.Panes)
        val p = (f as ServerFrame.Panes).panes.single()
        assertEquals("w6:p1", p.paneId)
        assertEquals("working", p.agentStatus)
        assertTrue(p.focused)
    }

    @Test fun parsesPaneUpdateWithNullAgent() {
        val f = parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w2:p1","workspaceId":"w2","tabId":"w2:t1","cwd":"/y","focused":false,"agent":null,"agentStatus":null}}""")
        val p = (f as ServerFrame.PaneUpdate).pane
        assertNull(p.agent)
        assertNull(p.agentStatus)
    }

    @Test fun parsesPaneReadAndError() {
        assertTrue(parseServerFrame("""{"t":"pane_read","reqId":"r1","paneId":"w6:p1","source":"detection","text":"hi"}""") is ServerFrame.PaneRead)
        val e = parseServerFrame("""{"t":"error","reqId":"r2","code":"not_found","message":"nope"}""")
        assertEquals("not_found", (e as ServerFrame.ErrorFrame).code)
    }

    @Test fun parsesUnknownAndTypelessFramesSafely() {
        assertTrue(parseServerFrame("""{"t":"bogus"}""") is ServerFrame.Unknown)
        assertTrue(parseServerFrame("""{"x":1}""") is ServerFrame.Unknown)
        assertTrue(parseServerFrame("""{"t":"welcome","herdrVersion":"0.7.1"}""") is ServerFrame.Welcome)
        assertTrue(parseServerFrame("""{"t":"pong"}""") is ServerFrame.Pong)
    }

    @Test fun buildsSendText() {
        val json = ClientMsg.sendText("r2", "w6:p1", "y")
        // simplest assertion: it contains the expected keys
        assertTrue(json.contains("\"t\":\"send_text\""))
        assertTrue(json.contains("\"text\":\"y\""))
        assertTrue(json.contains("\"paneId\":\"w6:p1\""))
    }

    @Test fun parsesTermFrames() {
        assertTrue(parseServerFrame("""{"t":"term_opened","reqId":"r1","termId":"t1"}""") is ServerFrame.TermOpened)
        val d = parseServerFrame("""{"t":"term_data","termId":"t1","data":"aGk="}""")
        assertTrue(d is ServerFrame.TermData)
        assertEquals("aGk=", (d as ServerFrame.TermData).data)
        val x = parseServerFrame("""{"t":"term_exit","termId":"t1","code":3}""")
        assertEquals(3, (x as ServerFrame.TermExit).code)
    }

    @Test fun termExitParsesReason() {
        val x = parseServerFrame("""{"t":"term_exit","termId":"t1","code":3,"reason":"takeover"}""")
        x as ServerFrame.TermExit
        assertEquals(3, x.code)
        assertEquals("takeover", x.reason)
        // missing reason defaults to ""
        val y = parseServerFrame("""{"t":"term_exit","termId":"t2","code":0}""") as ServerFrame.TermExit
        assertEquals("", y.reason)
    }

    @Test fun buildsTermClientMessages() {
        assertTrue(ClientMsg.termOpen("r1", "w6:p1", 80, 24).contains("\"term_open\""))
        assertTrue(ClientMsg.termInput("t1", "aGk=").contains("\"aGk=\""))
        assertTrue(ClientMsg.termResize("t1", 100, 40).contains("\"cols\":100"))
        assertTrue(ClientMsg.termClose("t1").contains("\"term_close\""))
    }

    @Test fun parsesWorkspacesFrameWithWorktree() {
        val f = parseServerFrame("""{"t":"workspaces","workspaces":[{"workspaceId":"w5","label":"wt-cost","number":2,"focused":true,"paneCount":1,"tabCount":1,"worktree":{"repoName":"ops","isLinkedWorktree":true}}]}""")
        assertTrue(f is ServerFrame.Workspaces)
        val w = (f as ServerFrame.Workspaces).workspaces.single()
        assertEquals("wt-cost", w.label)
        assertEquals("ops", w.worktree?.repoName)
        assertTrue(w.worktree?.isLinkedWorktree == true)
    }

    @Test fun parsesPaneTerminalId() {
        val f = parseServerFrame("""{"t":"panes","panes":[{"paneId":"w7:p2","workspaceId":"w7","tabId":"w7:t2","terminalId":"term_abc","agent":null,"agentStatus":"unknown"}]}""")
        val p = (f as ServerFrame.Panes).panes.single()
        assertEquals("term_abc", p.terminalId)
        assertNull(p.agent)
    }

    @Test fun parsesWorkspaceWithoutWorktreeAndTabsFrame() {
        val w = (parseServerFrame("""{"t":"workspaces","workspaces":[{"workspaceId":"w3","label":"apollo","number":1,"paneCount":1,"tabCount":1}]}""") as ServerFrame.Workspaces).workspaces.single()
        assertNull(w.worktree)
        val tabs = (parseServerFrame("""{"t":"tabs","tabs":[{"tabId":"w7:t2","label":"2","number":2,"workspaceId":"w7","agentStatus":"unknown","paneCount":1}]}""") as ServerFrame.Tabs).tabs.single()
        assertEquals("w7:t2", tabs.tabId)
        assertEquals("w7", tabs.workspaceId)
    }

    @Test fun parsesActionResult() {
        val ok = parseServerFrame("""{"t":"action_result","reqId":"a1","ok":true}""")
        assertTrue(ok is ServerFrame.ActionResult)
        assertTrue((ok as ServerFrame.ActionResult).ok)
        assertNull(ok.error)
        val bad = parseServerFrame("""{"t":"action_result","reqId":"a2","ok":false,"error":"nope"}""")
        assertFalse((bad as ServerFrame.ActionResult).ok)
        assertEquals("nope", bad.error)
    }

    @Test fun buildsActionMessages() {
        val rn = ClientMsg.action("a1", "rename", "workspace", "w7", "omega3")
        assertTrue(rn.contains("\"t\":\"action\""))
        assertTrue(rn.contains("\"op\":\"rename\""))
        assertTrue(rn.contains("\"kind\":\"workspace\""))
        assertTrue(rn.contains("\"id\":\"w7\""))
        assertTrue(rn.contains("\"label\":\"omega3\""))
        val cl = ClientMsg.action("a2", "close", "pane", "w7:p2", null)
        assertTrue(cl.contains("\"op\":\"close\""))
        assertFalse(cl.contains("\"label\""))
    }

    @Test fun parsesCreatedAndAgents() {
        val ok = parseServerFrame("""{"t":"created","reqId":"c1","ok":true,"paneId":"w7:pA","terminalId":"term_agent"}""")
        assertTrue(ok is ServerFrame.Created)
        ok as ServerFrame.Created
        assertTrue(ok.ok); assertEquals("term_agent", ok.terminalId); assertEquals("w7:pA", ok.paneId)
        val bad = parseServerFrame("""{"t":"created","reqId":"c2","ok":false,"error":"nope"}""") as ServerFrame.Created
        assertFalse(bad.ok); assertEquals("nope", bad.error); assertNull(bad.terminalId)
        val ag = parseServerFrame("""{"t":"agents","reqId":"a1","agents":["claude","codex"]}""") as ServerFrame.Agents
        assertEquals(listOf("claude", "codex"), ag.agents)
    }

    @Test fun buildsCreateAndMove() {
        val cr = ClientMsg.create("c1", "agent", workspaceId = "w7", tabId = "w7:t1", paneId = null,
            direction = "down", agentName = "claude", argv = listOf("claude"))
        assertTrue(cr.contains("\"t\":\"create\""))
        assertTrue(cr.contains("\"what\":\"agent\""))
        assertTrue(cr.contains("\"agentName\":\"claude\""))
        assertTrue(cr.contains("\"argv\":[\"claude\"]"))
        assertTrue(cr.contains("\"tabId\":\"w7:t1\""))
        val shell = ClientMsg.create("c2", "shell", workspaceId = null, tabId = null, paneId = "w7:p2",
            direction = "right", agentName = null, argv = null)
        assertTrue(shell.contains("\"paneId\":\"w7:p2\""))
        assertFalse(shell.contains("\"agentName\""))
        assertFalse(shell.contains("\"argv\""))
        val mv = ClientMsg.move("m1", "w7:p2", "tab", tabId = "w7:t1", direction = "down")
        assertTrue(mv.contains("\"t\":\"move\"") && mv.contains("\"dest\":\"tab\"") && mv.contains("\"tabId\":\"w7:t1\""))
        assertTrue(ClientMsg.listAgents("a1").contains("\"list_agents\""))
    }

    @Test fun parsesCloseImpactWithSiblings() {
        val f = parseServerFrame("""{"t":"close_impact","reqId":"i1","workspaceId":"w1","alsoCloses":[{"workspaceId":"w2","label":"ops"}]}""")
        assertTrue(f is ServerFrame.CloseImpact)
        val ci = f as ServerFrame.CloseImpact
        assertEquals("w1", ci.workspaceId)
        assertEquals(1, ci.alsoCloses.size)
        assertEquals("ops", ci.alsoCloses.single().label)
    }

    @Test fun parsesCloseImpactEmptyAndMissingArray() {
        val empty = parseServerFrame("""{"t":"close_impact","reqId":"i2","workspaceId":"w1","alsoCloses":[]}""")
        assertTrue((empty as ServerFrame.CloseImpact).alsoCloses.isEmpty())
        // missing / null alsoCloses must not throw — same defensiveness as agents
        val missing = parseServerFrame("""{"t":"close_impact","reqId":"i3","workspaceId":"w1"}""")
        assertTrue((missing as ServerFrame.CloseImpact).alsoCloses.isEmpty())
        val nulled = parseServerFrame("""{"t":"close_impact","reqId":"i4","workspaceId":"w1","alsoCloses":null}""")
        assertTrue((nulled as ServerFrame.CloseImpact).alsoCloses.isEmpty())
    }

    @Test fun buildsCloseImpactRequest() {
        val json = ClientMsg.closeImpact("i1", "w1")
        assertTrue(json.contains("\"t\":\"close_impact\""))
        assertTrue(json.contains("\"workspaceId\":\"w1\""))
        assertTrue(json.contains("\"reqId\":\"i1\""))
    }
}
