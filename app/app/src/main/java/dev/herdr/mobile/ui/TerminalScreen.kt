package dev.herdr.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Base64
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.Font
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
    var exit by remember { mutableStateOf<ExitCopy?>(null) }
    val scope = rememberCoroutineScope()
    val mods = remember { ModifierKeys() }
    val rootView = LocalView.current
    // The native TerminalView opens the IME when focused; leaving the terminal
    // (back button, or any disposal) must dismiss it or it lingers over the
    // dashboard. Compose's keyboard controller doesn't reliably hide an IME a
    // native view opened, so hide via the window token directly.
    fun hideKeyboard() {
        (rootView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(rootView.windowToken, 0)
    }
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
                    val copy = terminalExitCopy(f.reason, f.code)
                    exit = copy
                    status = copy.title
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
        onDispose { hideKeyboard(); termId?.let { vm.closeTerminal(it) } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = {
                    IconButton(onClick = { hideKeyboard(); onExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
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
                            val c = TerminalViewClientImpl(this, initialPx, bounds, mods) { vm.setTerminalFontSize(it) }
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
                        Text(exit?.title ?: "session ended",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(exit?.detail ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { exit = null; scope.launch { attachOnce() } }, shape = MaterialTheme.shapes.small) {
                            Text("Reattach")
                        }
                    }
                }
                if (showReconnectOverlay(emulatorReady, takenOver, status)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                            // Swallow taps so a dead terminal doesn't pop the soft keyboard.
                            .pointerInput(Unit) { detectTapGestures {} },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                spinnerFrame(),
                                color = statusColor("working", isSystemInDarkTheme()),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                status,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            session?.let { KeyToolbar(it, mods, enabled = keysLive(connected, termId, takenOver)) }
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

/** The key bar's taps only reach the PTY when we hold a live attach on a live
 *  socket; otherwise sendInput no-ops. Gate the bar's interactivity on this so a
 *  dead bar reads as dead instead of silently swallowing keystrokes. */
fun keysLive(connected: Boolean, termId: String?, takenOver: Boolean): Boolean =
    connected && termId != null && !takenOver

/** Overlay/subtitle copy for a terminal that ended, keyed by the companion's
 *  reason. Unknown/empty reason falls back to the neutral "session ended" — we
 *  never claim a takeover we can't prove. */
data class ExitCopy(val title: String, val detail: String)

fun terminalExitCopy(reason: String, code: Int): ExitCopy = when (reason) {
    "error" -> ExitCopy("terminal disconnected", "ended unexpectedly (code $code)")
    else    -> ExitCopy("session ended", "the terminal process exited")
}

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

// Tactile cap language (the bar is always the dark terminal surface).
private val CapShape = RoundedCornerShape(9.dp)
private val CapLip = Color(0xFF15151F)
private val CapBrush = Brush.verticalGradient(listOf(Color(0xFF3E4056), Color(0xFF2A2C3D)))
private val ArrowBrush = Brush.verticalGradient(listOf(Color(0xFF4E5168), Color(0xFF363849)))
private val DpadWell = Color(0xFF11111B)

@Composable
private fun KeyToolbar(session: RemoteTerminalSession, mods: ModifierKeys, enabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val ctx = LocalContext.current
    val mono = remember { FontFamily(Font("fonts/JetBrainsMono-Regular.ttf", ctx.assets)) }
    fun send(key: TermKey) {
        val b = bytesFor(key)
        session.write(b, 0, b.size)
        mods.consumeOneShot()   // bar keys don't combine with a modifier; drop a lingering one-shot
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        if (!expanded) {
            ExpandHandle { expanded = true }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(6.dp).alpha(if (enabled) 1f else 0.4f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ModifierKey("ctrl", mods.ctrl, mono, Modifier.weight(1f), enabled, { mods.tapCtrl() }, { mods.lockCtrl() })
                        ModifierKey("alt", mods.alt, mono, Modifier.weight(1f), enabled, { mods.tapAlt() }, { mods.lockAlt() })
                        KeyCap("esc", mono, Modifier.weight(1f), enabled) { send(TermKey.ESC) }
                        KeyCap("tab", mono, Modifier.weight(1f), enabled) { send(TermKey.TAB) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyCap("home", mono, Modifier.weight(1f), enabled) { send(TermKey.HOME) }
                        KeyCap("end", mono, Modifier.weight(1f), enabled) { send(TermKey.END) }
                        KeyCap("pgup", mono, Modifier.weight(1f), enabled) { send(TermKey.PGUP) }
                        KeyCap("pgdn", mono, Modifier.weight(1f), enabled) { send(TermKey.PGDN) }
                    }
                }
                VerticalDivider(
                    Modifier.height(72.dp).padding(horizontal = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                DPad(mono, enabled) { send(it) }
                CollapseTab { expanded = false }
            }
        }
    }
}

/** Recessed well holding ↑ over ← ↓ →. */
@Composable
private fun DPad(mono: FontFamily, enabled: Boolean, onKey: (TermKey) -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(DpadWell).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArrowCap("↑", mono, enabled) { onKey(TermKey.UP) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ArrowCap("←", mono, enabled) { onKey(TermKey.LEFT) }
            ArrowCap("↓", mono, enabled) { onKey(TermKey.DOWN) }
            ArrowCap("→", mono, enabled) { onKey(TermKey.RIGHT) }
        }
    }
}

/** Slim full-height tab that collapses the bar; costs width, not height. */
@Composable
private fun CollapseTab(onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 2.dp).width(22.dp).height(72.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "hide keys" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⌄", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium)
    }
}

/** Thin handle shown while collapsed; tap to restore the bar. */
@Composable
private fun ExpandHandle(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(20.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "show keys" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⌃", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium)
    }
}

/** Raised keycap: dark lip base + gradient face inset 2dp at the bottom.
 *  [outer] carries the row weight (specials) or fixed width (arrows);
 *  [click] is the caller's clickable/combinedClickable modifier. */
@Composable
private fun Keycap(outer: Modifier, face: Brush, click: Modifier, border: BorderStroke? = null, content: @Composable BoxScope.() -> Unit) {
    Box(
        outer.height(34.dp).clip(CapShape).background(CapLip),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.fillMaxWidth().height(32.dp).clip(CapShape).background(face)
                .then(if (border != null) Modifier.border(border, CapShape) else Modifier)
                .then(click),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** Tactile filled cap with ripple; [modifier] carries the row weight. */
@Composable
private fun KeyCap(label: String, mono: FontFamily, modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) {
    Keycap(modifier, CapBrush, Modifier.clickable(enabled = enabled, onClick = onClick)) {
        Text(label, fontFamily = mono, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Fixed-size tactile arrow cap for the d-pad. */
@Composable
private fun ArrowCap(label: String, mono: FontFamily, enabled: Boolean, onClick: () -> Unit) {
    Keycap(Modifier.width(30.dp), ArrowBrush, Modifier.clickable(enabled = enabled, onClick = onClick)) {
        Text(label, fontFamily = mono, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall)
    }
}

/** Sticky modifier cap: face/text reflect [state]; tap arms, long-press locks.
 *  ONE_SHOT gets a primary ring (mauve wash otherwise reads too close to OFF);
 *  LOCKED is a solid fill with an uppercased label. [stateDescription] carries
 *  off/armed/locked to TalkBack since the visual-only distinction wouldn't. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKey(label: String, state: ModState, mono: FontFamily, modifier: Modifier = Modifier, enabled: Boolean, onTap: () -> Unit, onLock: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val face = when (state) {
        ModState.OFF -> CapBrush
        ModState.ONE_SHOT -> SolidColor(primary.copy(alpha = 0.22f))
        ModState.LOCKED -> SolidColor(primary)
    }
    val fg = when (state) {
        ModState.LOCKED -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = if (state == ModState.ONE_SHOT) BorderStroke(1.5.dp, primary) else null
    val text = if (state == ModState.LOCKED) label.uppercase() else label
    val desc = when (state) { ModState.OFF -> "off"; ModState.ONE_SHOT -> "armed"; ModState.LOCKED -> "locked" }
    val click = Modifier
        .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLock)
        .semantics { stateDescription = desc }
    Keycap(modifier, face, click, border) {
        Text(text, fontFamily = mono, style = MaterialTheme.typography.labelMedium,
            color = fg, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
