package dev.herdr.mobile

import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.ui.DashboardViewModel
import kotlinx.coroutines.*
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

    @Test fun pumpsFramesIntoRepoAndPeeksPane() = runBlocking {
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
        val vm = DashboardViewModel(CompanionClient(), PaneRepository())
        vm.start(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) { while (vm.panes.value.isEmpty()) delay(20) }
        assertEquals("blocked", vm.panes.value.first().agentStatus)
        val text = vm.peek("w6:p1")
        assertEquals("Proceed? (y/n)", text)
        server.shutdown()
    }
}
