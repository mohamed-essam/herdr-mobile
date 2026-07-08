package dev.herdr.mobile

import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
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
    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

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
        val client = CompanionClient()
        val vm = DashboardViewModel(client, PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (vm.panes.value.isEmpty()) delay(20) }
        assertEquals("blocked", vm.panes.value.first().agentStatus)
        val text = client.readPane("w6:p1")
        assertEquals("Proceed? (y/n)", text)
        server.shutdown()
    }

    @Test fun toggleExpandedFlipsCollapsedMembership() {
        val vm = DashboardViewModel(CompanionClient(), PaneRepository())
        assertFalse(vm.collapsed.value.contains("w7"))
        vm.toggleExpanded("w7")
        assertTrue(vm.collapsed.value.contains("w7")) // now collapsed
        vm.toggleExpanded("w7")
        assertFalse(vm.collapsed.value.contains("w7")) // expanded again
    }

    @Test fun terminalFontSizeReflectsStoreAndPersistCallsBack() = runBlocking {
        var persisted: Int? = null
        val vm = DashboardViewModel(
            CompanionClient(), PaneRepository(),
            fontSizeStore = MutableStateFlow(28),
            persistFontSize = { persisted = it },
        )
        withTimeout(1000) { while (vm.terminalFontSize.value == null) delay(10) }
        assertEquals(28, vm.terminalFontSize.value)
        vm.setTerminalFontSize(44)
        assertEquals(44, persisted)
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
        val client = CompanionClient()
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
        server.shutdown()
    }
}
