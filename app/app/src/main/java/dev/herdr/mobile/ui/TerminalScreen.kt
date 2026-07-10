package dev.herdr.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.ServerFrame
import dev.herdr.mobile.ui.theme.statusColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit) {
    val connected by vm.connected.collectAsState()
    var termId by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<RemoteTerminalSession?>(null) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var client by remember { mutableStateOf<TerminalViewClientImpl?>(null) }
    val storedFont by vm.terminalFontSize.collectAsState()
    var emulatorReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("connecting…") }
    var takenOver by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }

    suspend fun attachOnce() {
        if (attaching) return
        attaching = true
        try {
            val emu = view?.mEmulator
            val cols = emu?.mColumns ?: 80
            val rows = emu?.mRows ?: 24
            status = "connecting…"
            runCatching { vm.openTerminal(pane, cols, rows) }
                .onSuccess { termId = it; status = "connected"; takenOver = false }
                .onFailure { status = "failed: ${it.message}" }
        } finally {
            attaching = false
        }
    }

    // Feed incoming term_data for the ACTIVE termId into the emulator; react to exit.
    // Re-subscribes automatically when termId changes (e.g. after a reconnect re-attach).
    LaunchedEffect(termId) {
        val id = termId ?: return@LaunchedEffect
        vm.frames.collect { f ->
            when (f) {
                is ServerFrame.TermData -> if (f.termId == id) {
                    val bytes = Base64.decode(f.data, Base64.NO_WRAP)
                    session?.feed(bytes, bytes.size)
                }
                is ServerFrame.TermExit -> if (f.termId == id) {
                    status = "taken over elsewhere"
                    termId = null
                    takenOver = true
                }
                else -> {}
            }
        }
    }

    // (Re)attach whenever the WS is connected and the emulator exists. On a
    // mid-session WS drop the companion tears down our PTY session (closeAll),
    // so the retained termId is dead: clear it and show a reconnecting state.
    // CompanionClient auto-reconnects; when it does, open a FRESH attach
    // (scrollback from before the drop is not restored). Gating on emulatorReady
    // preserves the no-byte-drop guarantee (feed() drops bytes with no emulator).
    LaunchedEffect(connected, emulatorReady) {
        if (!connected) {
            if (termId != null) termId = null
            if (emulatorReady) status = "reconnecting…"
            return@LaunchedEffect
        }
        if (!emulatorReady || termId != null) return@LaunchedEffect
        attachOnce()
    }

    // Apply a stored font size that arrives after the view was created.
    LaunchedEffect(storedFont) {
        val px = storedFont ?: return@LaunchedEffect
        client?.applyFontSize(px)
    }

    DisposableEffect(Unit) {
        onDispose { termId?.let { vm.closeTerminal(it) } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
                },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            val density = ctx.resources.displayMetrics.density
                            val bounds = fontBounds(density)
                            val initialPx = storedFont ?: bounds.default
                            val c = TerminalViewClientImpl(this, initialPx, bounds) { vm.setTerminalFontSize(it) }
                            client = c
                            setTextSize(initialPx)
                            // Bundled JetBrains Mono (OFL) — the system MONOSPACE on some
                            // devices (Samsung) renders poorly; set our own for consistency.
                            runCatching { Typeface.createFromAsset(ctx.assets, "fonts/JetBrainsMono-Regular.ttf") }
                                .getOrNull()?.let { setTypeface(it) }
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setTerminalViewClient(c)
                            val sess = RemoteTerminalSession(terminalSessionClient(this), object : RemoteTerminalSession.Io {
                                override fun sendInput(data: ByteArray) { termId?.let { vm.termInput(it, data) } }
                                override fun sendResize(cols: Int, rows: Int) { termId?.let { vm.termResize(it, cols, rows) } }
                            })
                            session = sess
                            view = this
                            attachSession(sess)
                            // The emulator is created lazily during layout (onSizeChanged ->
                            // updateSize). Signal readiness once it exists (retry if the first
                            // layout produced a zero size) so the (re)attach effect opens with
                            // real cols/rows and never before the emulator can accept bytes.
                            fun markReadyWhenEmulatorExists() {
                                if (mEmulator != null) emulatorReady = true else post { markReadyWhenEmulatorExists() }
                            }
                            doOnLayout {
                                requestFocus()
                                markReadyWhenEmulatorExists()
                            }
                        }
                    },
                )
                if (takenOver) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("⚠", style = MaterialTheme.typography.headlineMedium, color = statusColor("blocked", isSystemInDarkTheme()))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "terminal ended or was taken over elsewhere",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { scope.launch { attachOnce() } }, shape = MaterialTheme.shapes.small) {
                            Text("Reattach")
                        }
                    }
                }
            }
            session?.let { KeyToolbar(it) }
        }
    }
}

/**
 * The reconnect scrim is shown while the terminal exists but is not live: the
 * WS dropped ("reconnecting…") or we are (re-)attaching ("connecting…"). It is
 * suppressed before the emulator exists and when the terminal was taken over /
 * ended (that opaque overlay owns the screen). "connected" is the sole live
 * status set by attachOnce.
 */
fun showReconnectOverlay(emulatorReady: Boolean, takenOver: Boolean, status: String): Boolean =
    emulatorReady && !takenOver && status != "connected"

/** Minimal TerminalSessionClient (emulator-package callbacks). */
private fun terminalSessionClient(view: TerminalView): TerminalSessionClient =
    object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { view.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        // Select text → "Copy" routes here (via TerminalSession.onCopyTextToClipboard).
        // Put the selected text on the system clipboard so it can be pasted anywhere.
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            val cm = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("herdr terminal", text))
        }
        // Long-press → "Paste" routes here (via TerminalSession.onPasteTextFromClipboard).
        // Read the system clipboard and hand it to the emulator, whose paste()
        // normalizes the text (bracketed-paste, newline→CR) and write()s it out —
        // for a RemoteTerminalSession that goes to Io.sendInput → the remote PTY.
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = (view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.coerceToText(view.context)?.toString()
            if (!text.isNullOrEmpty()) view.mEmulator?.paste(text)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

@Composable
private fun KeyToolbar(session: RemoteTerminalSession) {
    val esc = byteArrayOf(0x1b)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp)) {
            KeyChip("esc") { session.write(esc, 0, 1) }
            KeyChip("tab") { session.write(byteArrayOf(0x09), 0, 1) }
            KeyChip("^C") { session.write(byteArrayOf(0x03), 0, 1) }
            KeyChip("↑") { session.write(esc + "[A".toByteArray(), 0, 3) }
            KeyChip("↓") { session.write(esc + "[B".toByteArray(), 0, 3) }
            KeyChip("←") { session.write(esc + "[D".toByteArray(), 0, 3) }
            KeyChip("→") { session.write(esc + "[C".toByteArray(), 0, 3) }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clickableNoRipple(onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable { onClick() })
