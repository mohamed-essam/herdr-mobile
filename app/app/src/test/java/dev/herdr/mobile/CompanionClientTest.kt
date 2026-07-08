package dev.herdr.mobile

import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class CompanionClientTest {
    @Test fun receivesWelcomeAndPanes() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"t":"welcome","herdrVersion":"0.7.1","herdrProtocol":14}""")
                webSocket.send("""{"t":"panes","panes":[]}""")
            }
        }))
        server.start()
        val client = CompanionClient()
        val collected = mutableListOf<ServerFrame>()
        val job = launch(Dispatchers.Default) { client.frames.collect { collected.add(it) } }
        client.connect(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) {
            while (collected.none { it is ServerFrame.Panes }) delay(20)
        }
        assertTrue(collected.any { it is ServerFrame.Welcome })
        assertTrue(collected.any { it is ServerFrame.Panes })
        job.cancel(); client.close(); server.shutdown()
    }

    @Test fun reconnectsAfterServerClose() = runBlocking {
        val server = MockWebServer()
        // first connection: open then immediately close from the server side
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(1000, "bye")
            }
        }))
        // second connection (after the client auto-reconnects): stays open, sends welcome
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"t":"welcome"}""")
            }
        }))
        server.start()
        val client = CompanionClient()
        val collected = mutableListOf<ServerFrame>()
        val job = launch(Dispatchers.Default) { client.frames.collect { collected.add(it) } }
        client.connect(server.url("/").toString().replace("http", "ws"))
        // welcome only arrives on the SECOND connection, so seeing it proves reconnect
        withTimeout(8000) {
            while (collected.none { it is ServerFrame.Welcome }) delay(50)
        }
        assertTrue(collected.any { it is ServerFrame.Welcome })
        job.cancel(); client.close(); server.shutdown()
    }

    @Test fun closeImpactReturnsSiblings() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!text.contains("\"reqId\"")) return
                val reqId = Regex("\"reqId\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                if (text.contains("\"close_impact\"")) {
                    webSocket.send("""{"t":"close_impact","reqId":"$reqId","workspaceId":"w1","alsoCloses":[{"workspaceId":"w2","label":"ops"}]}""")
                }
            }
        }))
        server.start()
        val client = CompanionClient()
        client.connect(server.url("/").toString().replace("http", "ws"))
        val result = withTimeout(3000) { client.closeImpact("w1") }
        assertEquals(1, result.size)
        assertEquals("ops", result.single().label)
        client.close(); server.shutdown()
    }

    @Test fun closeImpactReturnsEmptyOnError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!text.contains("\"reqId\"")) return
                val reqId = Regex("\"reqId\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                if (text.contains("\"close_impact\"")) {
                    webSocket.send("""{"t":"error","reqId":"$reqId","code":"close_impact_failed","message":"boom"}""")
                }
            }
        }))
        server.start()
        val client = CompanionClient()
        client.connect(server.url("/").toString().replace("http", "ws"))
        val result = withTimeout(3000) { client.closeImpact("w1") }
        assertTrue(result.isEmpty())
        client.close(); server.shutdown()
    }
}
