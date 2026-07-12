package dev.herdr.mobile

import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CompanionClientTest {
    // A per-test OkHttpClient the test owns, so teardown can forcibly release it.
    // CompanionClient's default ctor makes its own (unreachable) client; injecting
    // one lets @After evict the connection pool and shut the dispatcher BEFORE
    // MockWebServer.shutdown(). Without that, close() only starts an async graceful
    // WS close, the socket lingers, and MockWebServer blocks 5s waiting for its
    // reader task then throws "Gave up waiting for queue to shut down".
    private val http = OkHttpClient()
    private lateinit var server: MockWebServer
    private lateinit var client: CompanionClient

    @After fun teardown() {
        // Order matters: stop the client (graceful close + no reconnect), then FORCE
        // the WebSocket's underlying call shut (cancelAll) so the socket closes now —
        // graceful ws.close() alone depends on thread scheduling that, under
        // cross-test load, loses to MockWebServer.shutdown()'s 5s per-queue wait.
        // evictAll only closes idle pooled connections, not an active WebSocket.
        if (::client.isInitialized) client.close()
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        if (::server.isInitialized) server.shutdownQuietly()
    }

    @Test fun receivesWelcomeAndPanes() = runBlocking {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"t":"welcome","herdrVersion":"0.7.1","herdrProtocol":14}""")
                webSocket.send("""{"t":"panes","panes":[]}""")
            }
        }))
        server.start()
        client = CompanionClient(http)
        // Thread-safe: appended on Dispatchers.Default by the collector while the
        // runBlocking thread iterates it in the withTimeout poll below — a plain
        // mutableListOf races (ConcurrentModificationException under load).
        val collected = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
        val job = launch(Dispatchers.Default) { client.frames.collect { collected.add(it) } }
        client.connect(server.url("/").toString().replace("http", "ws"))
        withTimeout(3000) {
            while (collected.none { it is ServerFrame.Panes }) delay(20)
        }
        assertTrue(collected.any { it is ServerFrame.Welcome })
        assertTrue(collected.any { it is ServerFrame.Panes })
        job.cancel()
    }

    @Test fun reconnectsAfterServerClose() = runBlocking {
        server = MockWebServer()
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
        client = CompanionClient(http)
        // Thread-safe: appended on Dispatchers.Default by the collector while the
        // runBlocking thread iterates it in the withTimeout poll below — a plain
        // mutableListOf races (ConcurrentModificationException under load).
        val collected = java.util.concurrent.CopyOnWriteArrayList<ServerFrame>()
        val job = launch(Dispatchers.Default) { client.frames.collect { collected.add(it) } }
        client.connect(server.url("/").toString().replace("http", "ws"))
        // welcome only arrives on the SECOND connection, so seeing it proves reconnect
        withTimeout(8000) {
            while (collected.none { it is ServerFrame.Welcome }) delay(50)
        }
        assertTrue(collected.any { it is ServerFrame.Welcome })
        job.cancel()
    }

    @Test fun closeImpactReturnsSiblings() = runBlocking {
        server = MockWebServer()
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
        client = CompanionClient(http)
        client.connect(server.url("/").toString().replace("http", "ws"))
        val result = withTimeout(3000) { client.closeImpact("w1") }
        assertEquals(1, result.size)
        assertEquals("ops", result.single().label)
    }

    @Test fun closeImpactReturnsEmptyOnError() = runBlocking {
        server = MockWebServer()
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
        client = CompanionClient(http)
        client.connect(server.url("/").toString().replace("http", "ws"))
        val result = withTimeout(3000) { client.closeImpact("w1") }
        assertTrue(result.isEmpty())
    }
}
