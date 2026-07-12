package dev.herdr.mobile

import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.DashboardViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DashboardViewModelTest {
    // Per-test OkHttpClient injected into every CompanionClient so teardown can
    // force-release it. Without this, each method's client keeps its WebSocket
    // connection + dispatcher threads alive after server.shutdownQuietly(); across the
    // full suite those leftovers starve later WS round-trips past their 3s
    // withTimeout deadlines (a cross-test flake — every method passes in
    // isolation). Mirrors the fix already applied to CompanionClientTest.
    private val http = OkHttpClient()

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() {
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        Dispatchers.resetMain()
    }

    @Test fun pumpsFramesIntoRepoAndReadsPaneViaClient() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send("""{"t":"welcome"}""")
                ws.send("""{"t":"panes","panes":[{"paneId":"w6:p1","workspaceId":"w6","agentStatus":"blocked","agent":"claude"}]}""")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("\"read_pane\"")) {
                    val reqId = Regex("\"reqId\":\"(.*?)\"").find(text)!!.groupValues[1]
                    ws.send("""{"t":"pane_read","reqId":"$reqId","paneId":"w6:p1","source":"detection","text":"Proceed? (y/n)"}""")
                }
            }
        }))
        server.start()
        val client = CompanionClient(http)
        val vm = DashboardViewModel(client, PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (vm.panes.value.isEmpty()) delay(20) }
        assertEquals("blocked", vm.panes.value.first().agentStatus)
        val text = client.readPane("w6:p1")
        assertEquals("Proceed? (y/n)", text)
        server.shutdownQuietly()
    }

    @Test fun toggleExpandedFlipsCollapsedMembership() {
        val vm = DashboardViewModel(CompanionClient(http), PaneRepository())
        assertFalse(vm.collapsed.value.contains("w7"))
        vm.toggleExpanded("w7")
        assertTrue(vm.collapsed.value.contains("w7")) // now collapsed
        vm.toggleExpanded("w7")
        assertFalse(vm.collapsed.value.contains("w7")) // expanded again
    }

    @Test fun terminalFontSizeReflectsStoreAndPersistCallsBack() = runBlocking {
        var persisted: Int? = null
        val vm = DashboardViewModel(
            CompanionClient(http), PaneRepository(),
            fontSizeStore = MutableStateFlow(28),
            persistFontSize = { persisted = it },
        )
        withTimeout(1000) { while (vm.terminalFontSize.value == null) delay(10) }
        assertEquals(28, vm.terminalFontSize.value)
        vm.setTerminalFontSize(44)
        assertEquals(44, persisted)
    }

    @Test fun recordRecentAgentInvokesPersist() {
        var recorded: String? = null
        val vm = DashboardViewModel(
            CompanionClient(), PaneRepository(),
            recentAgentsStore = MutableStateFlow(listOf("claude")),
            persistRecentAgent = { recorded = it },
        )
        vm.recordRecentAgent("Codex")
        assertEquals("codex", recorded)
    }

    @Test fun renameNodeSendsActionAndSurfacesError() = runBlocking {
        val server = MockWebServer()
        val seenOps = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"t":"welcome"}""") }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("\"action\"")) {
                    seenOps.add(text)
                    val reqId = Regex("\"reqId\":\"(.*?)\"").find(text)!!.groupValues[1]
                    // rename -> ok; close -> failure, to exercise both paths
                    if (text.contains("\"op\":\"close\"")) {
                        ws.send("""{"t":"action_result","reqId":"$reqId","ok":false,"error":"cannot close"}""")
                    } else {
                        ws.send("""{"t":"action_result","reqId":"$reqId","ok":true}""")
                    }
                }
            }
        }))
        server.start()
        val client = CompanionClient(http)
        val vm = DashboardViewModel(client, PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (!vm.connected.value) delay(20) }

        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val job = launch { vm.actionErrors.collect { errors.add(it) } }

        vm.renameNode("workspace", "w7", "omega3")
        withTimeout(3000) { while (seenOps.none { it.contains("\"op\":\"rename\"") }) delay(20) }
        assertTrue(seenOps.first { it.contains("rename") }.contains("\"label\":\"omega3\""))

        vm.closeNode("pane", "w7:p2")
        withTimeout(3000) { while (errors.isEmpty()) delay(20) }
        assertEquals("cannot close", errors.first())

        job.cancel()
        server.shutdownQuietly()
    }

    @Test fun openTerminalTargetsTerminalIdButTracksPaneId() = runBlocking {
        val server = MockWebServer()
        val seenFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"t":"welcome"}""") }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("\"term_open\"")) {
                    seenFrames.add(text)
                    val reqId = Regex("\"reqId\":\"(.*?)\"").find(text)!!.groupValues[1]
                    ws.send("""{"t":"term_opened","reqId":"$reqId","termId":"t1"}""")
                }
            }
        }))
        server.start()
        val client = CompanionClient(http)
        val vm = DashboardViewModel(client, PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (!vm.connected.value) delay(20) }

        vm.openTerminal(Pane(paneId = "w7:p2", terminalId = "term_abc"), 80, 24)
        withTimeout(3000) { while (seenFrames.isEmpty()) delay(20) }

        assertTrue(seenFrames.first().contains("\"target\":\"term_abc\""))
        assertEquals("w7:p2", vm.lastOpenedPaneId.value)

        server.shutdownQuietly()
    }

    @Test fun createNodeEmitsAutoOpenAndMoveSurfacesErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { ws.send("""{"t":"welcome"}""") }
            override fun onMessage(ws: WebSocket, text: String) {
                if (!text.contains("\"reqId\"")) return
                val reqId = Regex("\"reqId\":\"(.*?)\"").find(text)!!.groupValues[1]
                when {
                    text.contains("\"t\":\"create\"") ->
                        ws.send("""{"t":"created","reqId":"$reqId","ok":true,"paneId":"w7:pA","terminalId":"term_new"}""")
                    text.contains("\"t\":\"move\"") ->
                        ws.send("""{"t":"action_result","reqId":"$reqId","ok":false,"error":"cannot move"}""")
                    text.contains("\"t\":\"list_agents\"") ->
                        ws.send("""{"t":"agents","reqId":"$reqId","agents":["claude","codex"]}""")
                }
            }
        }))
        server.start()
        val client = CompanionClient(http)
        val vm = DashboardViewModel(client, PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (!vm.connected.value) delay(20) }

        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val errs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val j1 = launch { vm.autoOpen.collect { opened.add(it) } }
        val j2 = launch { vm.actionErrors.collect { errs.add(it) } }

        vm.createNode(what = "agent", tabId = "w7:t1", agentName = "claude", argv = listOf("claude"))
        withTimeout(3000) { while (opened.isEmpty()) delay(20) }
        assertEquals("term_new", opened.first())

        vm.moveNode(paneId = "w7:p2", dest = "new_tab")
        withTimeout(3000) { while (errs.isEmpty()) delay(20) }
        assertEquals("cannot move", errs.first())

        vm.refreshAgents()
        withTimeout(3000) { while (vm.agents.value.isEmpty()) delay(20) }
        assertEquals(listOf("claude", "codex"), vm.agents.value)

        j1.cancel(); j2.cancel(); server.shutdownQuietly()
    }
}
