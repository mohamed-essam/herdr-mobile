package dev.herdr.mobile.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ServerFrame>>()

    @Volatile private var url: String? = null
    @Volatile private var manualClose = false
    @Volatile private var lastPushEndpoint: String? = null
    private var reconnectJob: Job? = null
    private var backoffMs = 1000L

    fun connect(url: String) {
        this.url = url
        manualClose = false
        openSocket()
    }

    private fun openSocket() {
        val target = url ?: return
        val req = Request.Builder().url(target).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000L // reset backoff on a healthy connection
                _connected.value = true
                webSocket.send(ClientMsg.hello())
                // re-assert our push endpoint after a (re)connect — the companion
                // holds it in memory and loses it across restarts.
                lastPushEndpoint?.let { webSocket.send(ClientMsg.registerPush(it)) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = parseServerFrame(text)
                when (frame) {
                    is ServerFrame.PaneRead -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.Ack -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.ErrorFrame -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.TermOpened -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.TermError -> if (frame.reqId.isNotEmpty()) pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.ActionResult -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.Created -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.Agents -> pending.remove(frame.reqId)?.complete(frame)
                    is ServerFrame.CloseImpact -> pending.remove(frame.reqId)?.complete(frame)
                    else -> {}
                }
                _frames.tryEmit(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // A server-initiated close arrives here; finish the handshake and
                // reconnect (onClosed may not fire until we complete the close).
                _connected.value = false
                webSocket.close(1000, null)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (manualClose) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            if (!manualClose) openSocket()
        }
    }

    fun send(raw: String) { ws?.send(raw) }

    private suspend fun request(reqId: String, raw: String): ServerFrame {
        val d = CompletableDeferred<ServerFrame>()
        pending[reqId] = d
        try {
            ws?.send(raw)
            return withTimeout(8000) { d.await() }
        } finally {
            pending.remove(reqId)
        }
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

    suspend fun sendAction(op: String, kind: String, id: String, label: String? = null) {
        val reqId = "a${seq.incrementAndGet()}"
        when (val f = request(reqId, ClientMsg.action(reqId, op, kind, id, label))) {
            is ServerFrame.ActionResult -> if (!f.ok) throw RuntimeException(f.error ?: "action failed")
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to action")
        }
    }

    /** Returns the new pane's terminalId (for auto-open); throws on failure. */
    suspend fun sendCreate(
        what: String, workspaceId: String? = null, tabId: String? = null, paneId: String? = null,
        direction: String? = null, agentName: String? = null, argv: List<String>? = null,
    ): String {
        val reqId = "n${seq.incrementAndGet()}"
        val raw = ClientMsg.create(reqId, what, workspaceId, tabId, paneId, direction, agentName, argv)
        return when (val f = request(reqId, raw)) {
            is ServerFrame.Created -> if (f.ok) (f.terminalId ?: "") else throw RuntimeException(f.error ?: "create failed")
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to create")
        }
    }

    suspend fun sendMove(paneId: String, dest: String, tabId: String? = null, direction: String? = null) {
        val reqId = "v${seq.incrementAndGet()}"
        when (val f = request(reqId, ClientMsg.move(reqId, paneId, dest, tabId, direction))) {
            is ServerFrame.ActionResult -> if (!f.ok) throw RuntimeException(f.error ?: "move failed")
            is ServerFrame.ErrorFrame -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to move")
        }
    }

    suspend fun listAgents(): List<String> {
        val reqId = "g${seq.incrementAndGet()}"
        return when (val f = request(reqId, ClientMsg.listAgents(reqId))) {
            is ServerFrame.Agents -> f.agents
            else -> emptyList()
        }
    }

    /** Returns the sibling workspaces that will also close; [] on error/timeout. */
    suspend fun closeImpact(workspaceId: String): List<AlsoClose> {
        val reqId = "i${seq.incrementAndGet()}"
        return try {
            when (val f = request(reqId, ClientMsg.closeImpact(reqId, workspaceId))) {
                is ServerFrame.CloseImpact -> f.alsoCloses
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList() // timeout or transport failure → fall back to plain confirm
        }
    }

    suspend fun openTerminal(target: String, cols: Int, rows: Int): String {
        val id = "r${seq.incrementAndGet()}"
        return when (val f = request(id, ClientMsg.termOpen(id, target, cols, rows))) {
            is ServerFrame.TermOpened -> f.termId
            is ServerFrame.TermError -> throw RuntimeException(f.message)
            else -> throw RuntimeException("unexpected reply to term_open")
        }
    }

    fun sendTermInput(termId: String, data: ByteArray) {
        val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        ws?.send(ClientMsg.termInput(termId, b64))
    }

    fun sendTermResize(termId: String, cols: Int, rows: Int) { ws?.send(ClientMsg.termResize(termId, cols, rows)) }

    fun closeTerminal(termId: String) { ws?.send(ClientMsg.termClose(termId)) }

    fun registerPush(endpoint: String) {
        lastPushEndpoint = endpoint
        ws?.send(ClientMsg.registerPush(endpoint))
    }

    fun close() {
        manualClose = true
        reconnectJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
    }
}
