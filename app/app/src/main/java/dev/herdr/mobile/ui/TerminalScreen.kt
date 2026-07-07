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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var termId by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<RemoteTerminalSession?>(null) }
    var status by remember { mutableStateOf("connecting…") }
    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }

    // Feed incoming term_data into the emulator; react to exit.
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
                    setTextSize(36)
                    val client = TerminalViewClientImpl(this)
                    setTerminalViewClient(client)
                    val sessionClient = terminalSessionClient(this)
                    val sess = RemoteTerminalSession(sessionClient, object : RemoteTerminalSession.Io {
                        override fun sendInput(data: ByteArray) { termId?.let { vm.termInput(it, data) } }
                        override fun sendResize(cols: Int, rows: Int) { termId?.let { vm.termResize(it, cols, rows) } }
                    })
                    session = sess
                    attachSession(sess)

                    // RemoteTerminalSession.feed() silently drops bytes if they arrive before
                    // the emulator exists. The emulator is only created lazily, once the view
                    // has a real (non-zero) width/height during layout (see
                    // TerminalView#updateSize()/#onSizeChanged()) - attachSession() above is a
                    // no-op for that purpose since the view has no size yet. So we must NOT open
                    // the remote terminal (which makes the companion start streaming term_data)
                    // until mEmulator is non-null - otherwise the earliest bytes (e.g. the shell
                    // banner/prompt) would be lost. doOnLayout fires once layout has happened, by
                    // which point onSizeChanged() has already run and created the emulator; as a
                    // defensive fallback (in case layout produced a still-zero size, e.g. a
                    // momentarily hidden pane) we retry via post() until mEmulator appears, then
                    // open using its real cols/rows instead of a guessed 80x24.
                    fun openWhenEmulatorReady() {
                        val emu = mEmulator
                        if (emu == null) {
                            post { openWhenEmulatorReady() }
                            return
                        }
                        scope.launch {
                            runCatching { vm.openTerminal(pane.paneId, emu.mColumns, emu.mRows) }
                                .onSuccess { termId = it; status = "connected" }
                                .onFailure { status = "failed: ${it.message}" }
                        }
                    }
                    doOnLayout { openWhenEmulatorReady() }
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
