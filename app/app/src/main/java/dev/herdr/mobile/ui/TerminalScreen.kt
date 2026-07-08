package dev.herdr.mobile.ui

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.ServerFrame

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
    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }

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
                is ServerFrame.TermExit -> if (f.termId == id) status = "session ended (${f.code})"
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
        val emu = view?.mEmulator
        val cols = emu?.mColumns ?: 80
        val rows = emu?.mRows ?: 24
        status = "connecting…"
        runCatching { vm.openTerminal(pane.paneId, cols, rows) }
            .onSuccess { termId = it; status = "connected" }
            .onFailure { status = "failed: ${it.message}" }
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
        bottomBar = { session?.let { KeyToolbar(it) } },
    ) { pad ->
        AndroidView(
            modifier = Modifier.padding(pad).fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    val density = ctx.resources.displayMetrics.density
                    val bounds = fontBounds(density)
                    val initialPx = storedFont ?: bounds.default
                    val c = TerminalViewClientImpl(this, initialPx, bounds) { vm.setTerminalFontSize(it) }
                    client = c
                    setTextSize(initialPx)
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
    }
}

/** Minimal TerminalSessionClient (emulator-package callbacks). */
private fun terminalSessionClient(view: TerminalView): TerminalSessionClient =
    object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { view.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
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
