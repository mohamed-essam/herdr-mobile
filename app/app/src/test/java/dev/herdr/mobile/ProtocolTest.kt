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

    @Test fun paneChatFlagDefaultsFalse() {
        val p = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1"}}""") as ServerFrame.PaneUpdate).pane
        assertFalse(p.chat)
        val q = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1","chat":true}}""") as ServerFrame.PaneUpdate).pane
        assertTrue(q.chat)
    }

    @Test fun parsesChatSnapshotSkippingUnknownEvents() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"w1:p1","epoch":2,"state":"working","events":[
            {"seq":1,"event":{"type":"user_text","uuid":"u1","text":"hi"}},
            {"seq":2,"event":{"type":"mystery"}},
            {"seq":3,"event":{"type":"tool_use","uuid":"a#1","toolUseId":"t1","tool":"Bash","summary":"Bash: ls"}},
            {"seq":4,"event":{"type":"tool_result","toolUseId":"t1","isError":true,"preview":"boom"}}]}""") as ServerFrame.ChatSnapshot
        assertEquals("w1:p1", f.paneId)
        assertEquals(2, f.epoch)
        assertEquals("working", f.state)
        assertEquals(listOf(1, 3, 4), f.entries.map { it.seq })
        assertEquals(ChatEvent.UserText("u1", "hi"), f.entries[0].event)
        assertEquals(ChatEvent.ToolResult("t1", true, "boom"), f.entries[2].event)
    }

    @Test fun parsesChatEventStateAndSendResult() {
        val e = parseServerFrame("""{"t":"chat_event","paneId":"p","epoch":1,"seq":9,"event":{"type":"assistant_text","uuid":"a","text":"yo"}}""") as ServerFrame.ChatEventFrame
        assertEquals(ChatEntry(9, ChatEvent.AssistantText("a", "yo")), e.entry)
        val unknown = parseServerFrame("""{"t":"chat_event","paneId":"p","epoch":1,"seq":10,"event":{"type":"new_kind"}}""") as ServerFrame.ChatEventFrame
        assertNull(unknown.entry)
        assertEquals(10, unknown.seq)
        val snap = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[{"seq":1,"event":{"type":"assistant_text","uuid":"a","text":"yo"}},{"seq":2,"event":{"type":"new_kind"}}]}""") as ServerFrame.ChatSnapshot
        assertEquals(1, snap.entries.size)
        assertEquals(2, snap.maxSeq)
        val s = parseServerFrame("""{"t":"chat_state","paneId":"p","state":"idle"}""") as ServerFrame.ChatState
        assertEquals("idle", s.state)
        val r = parseServerFrame("""{"t":"chat_send_result","reqId":"c1","ok":false,"error":"no_mod"}""") as ServerFrame.ChatSendResult
        assertFalse(r.ok)
        assertEquals("no_mod", r.error)
    }

    @Test fun parsesTaskNotice() {
        val e = parseServerFrame("""{"t":"chat_event","paneId":"p","epoch":1,"seq":3,"event":{"type":"task_notice","uuid":"d#0","status":"completed","summary":"done"}}""") as ServerFrame.ChatEventFrame
        assertEquals(ChatEntry(3, ChatEvent.TaskNotice("d#0", "completed", "done")), e.entry)
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[
            {"seq":1,"event":{"type":"task_notice","uuid":"d#0"}}]}""") as ServerFrame.ChatSnapshot
        assertEquals(ChatEvent.TaskNotice("d#0", "", ""), f.entries.single().event)
    }

    @Test fun chatClientMessages() {
        assertEquals("""{"t":"chat_open","paneId":"w1:p1"}""", ClientMsg.chatOpen("w1:p1"))
        assertEquals("""{"t":"chat_close","paneId":"w1:p1"}""", ClientMsg.chatClose("w1:p1"))
        assertEquals("""{"t":"chat_send","reqId":"c1","paneId":"w1:p1","text":"hi \"there\""}""", ClientMsg.chatSend("c1", "w1:p1", "hi \"there\""))
    }

    @Test fun jsonNullsAreNotTheStringNull() {
        val r = parseServerFrame("""{"t":"chat_send_result","reqId":"c1","ok":true,"error":null}""") as ServerFrame.ChatSendResult
        assertNull(r.error)
        assertEquals("idle", (parseServerFrame("""{"t":"chat_state","paneId":"p","state":null}""") as ServerFrame.ChatState).state)
        assertEquals("idle", (parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":null,"events":[]}""") as ServerFrame.ChatSnapshot).state)
    }

    // ---- protocol 9 ----

    @Test fun v9FieldsDefaultWhenAbsent() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[
            {"seq":1,"event":{"type":"user_text","uuid":"u1","text":"hi"}},
            {"seq":2,"event":{"type":"assistant_text","uuid":"a1","text":"yo"}},
            {"seq":3,"event":{"type":"tool_result","toolUseId":"t1","isError":false,"preview":"ok"}}]}""") as ServerFrame.ChatSnapshot
        assertFalse(f.hasMore)
        val u = f.entries[0].event as ChatEvent.UserText
        assertNull(u.ts)
        assertEquals(emptyList<String>(), u.images)
        assertNull((f.entries[1].event as ChatEvent.AssistantText).ts)
        assertEquals(emptyList<String>(), (f.entries[2].event as ChatEvent.ToolResult).images)
    }

    @Test fun parsesTimestampsImagesAndHasMore() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","hasMore":true,"events":[
            {"seq":1,"event":{"type":"user_text","uuid":"u1","text":"","ts":1759600000123,"images":["u1#0","u1#1"]}},
            {"seq":2,"event":{"type":"assistant_text","uuid":"a1","text":"yo","ts":1759600000456}},
            {"seq":3,"event":{"type":"tool_use","uuid":"a1#1","toolUseId":"t1","tool":"Read","summary":"Read: x.png","ts":5}},
            {"seq":4,"event":{"type":"tool_result","toolUseId":"t1","isError":false,"preview":"","images":["r#0.0"],"ts":6}},
            {"seq":5,"event":{"type":"task_notice","uuid":"d#0","status":"completed","summary":"done","ts":7}}]}""") as ServerFrame.ChatSnapshot
        assertTrue(f.hasMore)
        assertEquals(ChatEvent.UserText("u1", "", 1759600000123, listOf("u1#0", "u1#1")), f.entries[0].event)
        assertEquals(ChatEvent.AssistantText("a1", "yo", 1759600000456), f.entries[1].event)
        assertEquals(ChatEvent.ToolResult("t1", false, "", listOf("r#0.0"), 6), f.entries[3].event)
        assertEquals(7L, (f.entries[4].event as ChatEvent.TaskNotice).ts)
    }

    @Test fun parsesImageSizesAndDropsBadOnes() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[
            {"seq":1,"event":{"type":"user_text","uuid":"u1","text":"","images":["u1#0","u1#1"],"imageSizes":{"u1#0":[1170,2532],"u1#1":[0,5]}}},
            {"seq":2,"event":{"type":"tool_result","toolUseId":"t1","isError":false,"preview":"","images":["r#0.0"],"imageSizes":{"r#0.0":[640,480],"x":"bad"}}}]}""") as ServerFrame.ChatSnapshot
        assertEquals(mapOf("u1#0" to PixelSize(1170, 2532)), (f.entries[0].event as ChatEvent.UserText).imageSizes)
        assertEquals(mapOf("r#0.0" to PixelSize(640, 480)), (f.entries[1].event as ChatEvent.ToolResult).imageSizes)
    }

    @Test fun parsesQuestionEventKinds() {
        val e = parseServerFrame("""{"t":"chat_event","paneId":"p","epoch":1,"seq":4,"event":{"type":"question","uuid":"a#2","toolUseId":"tq","ts":9,"questions":[
            {"question":"Color?","header":"Color","options":[{"label":"Red","description":"warm"},{"label":"Blue"}],"multiSelect":true},
            {"question":"Name?","header":"Name","kind":"text","options":[],"multiSelect":false,"placeholder":"type","description":"why"},
            {"question":"How many?","header":"N","kind":"number","options":[],"multiSelect":false,"min":1,"max":10.5,"step":0.5,"unit":"GB"}]}}""") as ServerFrame.ChatEventFrame
        val q = e.entry!!.event as ChatEvent.Question
        assertEquals("a#2", q.uuid)
        assertEquals("tq", q.toolUseId)
        assertEquals(9L, q.ts)
        val (c, t, n) = q.questions
        assertEquals(QuestionKind.Choice, c.kind)
        assertTrue(c.multiSelect)
        assertEquals(listOf(QuestionOption("Red", "warm"), QuestionOption("Blue", null)), c.options)
        assertEquals(QuestionKind.Text, t.kind)
        assertEquals("type", t.placeholder)
        assertEquals("why", t.description)
        assertEquals(QuestionKind.Number, n.kind)
        assertEquals(1.0, n.min!!, 0.0)
        assertEquals(10.5, n.max!!, 0.0)
        assertEquals(0.5, n.step!!, 0.0)
        assertEquals("GB", n.unit)
        assertNull(c.min)
        assertNull(c.unit)
    }

    @Test fun unknownQuestionKindFallsBackToChoice() {
        val e = parseServerFrame("""{"t":"chat_event","paneId":"p","epoch":1,"seq":4,"event":{"type":"question","uuid":"u","toolUseId":"tq","questions":[
            {"question":"Q","header":"H","kind":"slider","options":[{"label":"A"}]}]}}""") as ServerFrame.ChatEventFrame
        val q = e.entry!!.event as ChatEvent.Question
        assertNull(q.ts)
        assertEquals(QuestionKind.Choice, q.questions.single().kind)
        assertFalse(q.questions.single().multiSelect)
    }

    @Test fun parsesHistoryPage() {
        val f = parseServerFrame("""{"t":"chat_history_page","reqId":"h1","paneId":"p","epoch":3,"hasMore":true,"events":[
            {"seq":7,"event":{"type":"user_text","uuid":"u7","text":"old"}}]}""") as ServerFrame.ChatHistoryPage
        assertEquals("h1", f.reqId)
        assertEquals("p", f.paneId)
        assertEquals(3, f.epoch)
        assertTrue(f.hasMore)
        assertFalse(f.stale)
        assertEquals(listOf(7), f.entries.map { it.seq })
        val s = parseServerFrame("""{"t":"chat_history_page","reqId":"h2","paneId":"p","epoch":2,"events":[],"hasMore":false,"stale":true}""") as ServerFrame.ChatHistoryPage
        assertTrue(s.stale)
        assertFalse(s.hasMore)
    }

    @Test fun parsesImageDataAndMissing() {
        val d = parseServerFrame("""{"t":"chat_image_data","paneId":"p","id":"u#0","mediaType":"image/png","data":"aGk="}""") as ServerFrame.ChatImageData
        assertEquals(ServerFrame.ChatImageData("p", "u#0", "image/png", "aGk=", false), d)
        val m = parseServerFrame("""{"t":"chat_image_data","paneId":"p","id":"u#1","missing":true}""") as ServerFrame.ChatImageData
        assertTrue(m.missing)
        assertNull(m.data)
    }

    @Test fun parsesAnswerResult() {
        val ok = parseServerFrame("""{"t":"chat_answer_result","reqId":"q1","ok":true}""") as ServerFrame.ChatAnswerResult
        assertEquals(ServerFrame.ChatAnswerResult("q1", true, null), ok)
        val bad = parseServerFrame("""{"t":"chat_answer_result","reqId":"q2","ok":false,"error":"no_question"}""") as ServerFrame.ChatAnswerResult
        assertEquals("no_question", bad.error)
    }

    @Test fun v9ClientMessages() {
        assertEquals("""{"t":"chat_history","reqId":"h1","paneId":"p","epoch":2,"beforeSeq":301,"limit":300}""",
            ClientMsg.chatHistory("h1", "p", 2, 301, 300))
        assertEquals("""{"t":"chat_image","paneId":"p","id":"u#0"}""", ClientMsg.chatImage("p", "u#0"))
        assertEquals("""{"t":"chat_answer","reqId":"q1","paneId":"p","toolUseId":"tq","answers":{"Color?":"Red, Blue","N?":"3"}}""",
            ClientMsg.chatAnswer("q1", "p", "tq", linkedMapOf("Color?" to "Red, Blue", "N?" to "3")))
    }

    @Test fun paneWithoutSummaryHasNullActivityAndAsk() {
        val p = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1","chat":true}}""") as ServerFrame.PaneUpdate).pane
        assertTrue(p.chat)
        assertNull(p.activity)
        assertNull(p.ask)
    }

    @Test fun parsesPaneActivityAndAsk() {
        val f = parseServerFrame("""{"t":"panes","panes":[{"paneId":"w1:p1","chat":true,""" +
            """"activity":{"kind":"tool","tool":"Bash","text":"npm run build","ts":1700000000000},""" +
            """"ask":{"toolUseId":"tq","questions":[{"question":"Which layout?","header":"Layout","options":[{"label":"Grid","description":"2x2"},{"label":"List"}],"multiSelect":true}]}}]}""")
        val p = (f as ServerFrame.Panes).panes.single()
        assertEquals(PaneActivity(kind = "tool", tool = "Bash", text = "npm run build", ts = 1700000000000), p.activity)
        val ask = p.ask!!
        assertEquals("tq", ask.toolUseId)
        val q = ask.items.single()
        assertEquals("Which layout?", q.question)
        assertEquals("Layout", q.header)
        assertTrue(q.multiSelect)
        assertEquals(listOf(QuestionOption("Grid", "2x2"), QuestionOption("List")), q.options)
    }

    @Test fun parsesTextActivityWithoutTool() {
        val p = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1","activity":{"kind":"question","text":"Which layout?","ts":5}}}""") as ServerFrame.PaneUpdate).pane
        assertEquals("question", p.activity!!.kind)
        assertNull(p.activity?.tool)
        assertNull(p.ask)
    }

    // ---- protocol 10: threads, agents, tasks ----

    @Test fun parsesMainSnapshotWithAgentsAndTasks() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":2,"state":"working","events":[],"hasMore":false,
            "agents":[{"agentId":"aa1","parentToolUseId":"tu1","kind":"subagent","label":"Explore","type":"Explore","status":"running","activity":{"kind":"tool","tool":"Read","text":"x.kt","ts":9},"ts":5},
                      {"agentId":"bb2","parentToolUseId":"tu2","parentAgentId":"aa1","kind":"workflow","label":"wf","phase":"build","status":"done","ts":6}],
            "tasks":[{"id":"t1","kind":"shell","label":"sleep","toolUseId":"tu3","status":"running","startedAt":100},
                     {"id":"t2","kind":"monitor","label":"m","toolUseId":"tu4","status":"failed","startedAt":1,"endedAt":2}]}""") as ServerFrame.ChatSnapshot
        assertNull(f.agentId)
        assertFalse(f.missing)
        val a = f.agents!!
        assertEquals(2, a.size)
        assertEquals("aa1", a[0].agentId)
        assertEquals("Read", a[0].activity!!.tool)
        assertEquals("Explore", a[0].type)
        assertNull(a[0].parentAgentId)
        assertEquals("aa1", a[1].parentAgentId)
        assertEquals("build", a[1].phase)
        assertNull(a[1].activity)
        val t = f.tasks!!
        assertEquals(listOf("t1", "t2"), t.map { it.id })
        assertEquals(100L, t[0].startedAt)
        assertNull(t[0].endedAt)
        assertEquals(2L, t[1].endedAt)
    }

    @Test fun parsesThreadSnapshotWithoutAgentsAsNull() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","agentId":"aa1","epoch":1,"state":"idle","events":[],"hasMore":false}""") as ServerFrame.ChatSnapshot
        assertEquals("aa1", f.agentId)
        assertNull(f.agents)
        assertNull(f.tasks)
        val main = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[],"agents":[]}""") as ServerFrame.ChatSnapshot
        assertEquals(emptyList<AgentSummary>(), main.agents)
        assertNull(main.tasks)
    }

    @Test fun parsesMissingThreadSnapshot() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","agentId":"zz","epoch":1,"state":"idle","events":[],"hasMore":false,"missing":true}""") as ServerFrame.ChatSnapshot
        assertTrue(f.missing)
    }

    @Test fun parsesChatAgentUpsertAndRemoval() {
        val up = parseServerFrame("""{"t":"chat_agent","paneId":"p","agent":{"agentId":"aa1","parentToolUseId":"tu1","kind":"subagent","label":"L","status":"done","ts":3}}""") as ServerFrame.ChatAgent
        assertEquals("p", up.paneId)
        assertEquals("aa1", up.agent!!.agentId)
        assertNull(up.removedId)
        val rm = parseServerFrame("""{"t":"chat_agent","paneId":"p","agentId":"aa1","removed":true}""") as ServerFrame.ChatAgent
        assertNull(rm.agent)
        assertEquals("aa1", rm.removedId)
    }

    @Test fun parsesChatTasks() {
        val f = parseServerFrame("""{"t":"chat_tasks","paneId":"p","tasks":[{"id":"t1","kind":"subagent","label":"x","toolUseId":"tu","status":"done","startedAt":1,"endedAt":4}]}""") as ServerFrame.ChatTasks
        assertEquals("p", f.paneId)
        assertEquals("t1", f.tasks.single().id)
        assertEquals(emptyList<BgTask>(), (parseServerFrame("""{"t":"chat_tasks","paneId":"p","tasks":[]}""") as ServerFrame.ChatTasks).tasks)
    }

    @Test fun parsesAgentIdOnEventsAndHistoryPages() {
        val e = parseServerFrame("""{"t":"chat_event","paneId":"p","agentId":"aa1","epoch":1,"seq":3,"event":{"type":"assistant_text","uuid":"u","text":"hi"}}""") as ServerFrame.ChatEventFrame
        assertEquals("aa1", e.agentId)
        assertEquals(3, e.seq)
        assertNull((parseServerFrame("""{"t":"chat_event","paneId":"p","epoch":1,"seq":3,"event":{"type":"assistant_text","uuid":"u","text":"hi"}}""") as ServerFrame.ChatEventFrame).agentId)
        val h = parseServerFrame("""{"t":"chat_history_page","reqId":"h1","paneId":"p","agentId":"aa1","epoch":1,"events":[],"hasMore":false}""") as ServerFrame.ChatHistoryPage
        assertEquals("aa1", h.agentId)
    }

    @Test fun parsesBgRunningOnPane() {
        val p = (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1","bgRunning":3}}""") as ServerFrame.PaneUpdate).pane
        assertEquals(3, p.bgRunning)
        assertEquals(0, (parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w1:p1"}}""") as ServerFrame.PaneUpdate).pane.bgRunning)
    }

    @Test fun skipsMalformedAgentAndTaskElements() {
        val f = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[],
            "agents":[{"label":"no id"},"junk",{"agentId":"ok","parentToolUseId":"tu","kind":"subagent","label":"L","status":"running","ts":1}],
            "tasks":[{"kind":"shell"},7,{"id":"t","kind":"shell","label":"l","toolUseId":"tu","status":"running","startedAt":1}]}""") as ServerFrame.ChatSnapshot
        assertEquals(listOf("ok"), f.agents!!.map { it.agentId })
        assertEquals(listOf("t"), f.tasks!!.map { it.id })
    }

    @Test fun threadAwareClientMessagesAddAgentIdOnlyWhenSet() {
        assertFalse(ClientMsg.chatOpen("p").contains("agentId"))
        assertTrue(ClientMsg.chatOpen("p", "aa1").contains(""""agentId":"aa1""""))
        assertFalse(ClientMsg.chatClose("p").contains("agentId"))
        assertTrue(ClientMsg.chatClose("p", "aa1").contains(""""agentId":"aa1""""))
        assertFalse(ClientMsg.chatHistory("r", "p", 1, 5, 300).contains("agentId"))
        assertTrue(ClientMsg.chatHistory("r", "p", 1, 5, 300, "aa1").contains(""""agentId":"aa1""""))
    }
}
