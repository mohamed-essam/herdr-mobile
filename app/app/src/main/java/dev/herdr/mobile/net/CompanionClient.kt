package dev.herdr.mobile.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CompanionClient(private val http: OkHttpClient = OkHttpClient()) {
    // replay lets a collector that subscribes just after connect() still receive
    // the immediate welcome/panes frames (avoids a subscribe-vs-onMessage race).
    private val _frames = MutableSharedFlow<ServerFrame>(replay = 16, extraBufferCapacity = 64)
    val frames: SharedFlow<ServerFrame> = _frames.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var ws: WebSocket? = null
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ServerFrame>>()

    fun connect(url: String) {
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                webSocket.send(ClientMsg.hello())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = parseServerFrame(text)
                when (frame) {
                    is ServerFrame.PaneRead -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.Ack -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.ErrorFrame -> pending.remove(frame.reqId)?.complete(frame)
                    else -> {}
                }
                _frames.tryEmit(frame)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _connected.value = false }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { _connected.value = false }
        })
    }

    fun send(raw: String) { ws?.send(raw) }

    private suspend fun request(reqId: String, raw: String): ServerFrame {
        val d = CompletableDeferred<ServerFrame>()
        pending[reqId] = d
        ws?.send(raw)
        return withTimeout(8000) { d.await() }
    }

    suspend fun readPane(paneId: String, source: String = "detection", lines: Int = 40): String {
        val id = "r${seq.incrementAndGet()}"
        return when (val f = request(id, ClientMsg.readPane(id, paneId, source, lines))) {
            is ServerFrame.PaneRead -> f.text
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply")
        }
    }

    suspend fun sendText(paneId: String, text: String) {
        val id = "r${seq.incrementAndGet()}"
        val f = request(id, ClientMsg.sendText(id, paneId, text))
        if (f is ServerFrame.ErrorFrame) throw RuntimeException(f.message)
    }

    suspend fun sendKeys(paneId: String, keys: String) {
        val id = "r${seq.incrementAndGet()}"
        val f = request(id, ClientMsg.sendKeys(id, paneId, keys))
        if (f is ServerFrame.ErrorFrame) throw RuntimeException(f.message)
    }

    fun registerPush(endpoint: String) { ws?.send(ClientMsg.registerPush(endpoint)) }

    fun close() { ws?.close(1000, "bye"); ws = null }
}
