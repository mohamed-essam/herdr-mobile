package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import kotlinx.coroutines.delay

/**
 * A pane's chat: the header, the timeline ([ChatTimelineList]) and the
 * composer (with Interrupt while the agent works) at the bottom; a pending
 * question replaces it as a pinned sheet.
 */
@Composable
fun ChatScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit, onTerminal: () -> Unit, onOpenThread: (String) -> Unit) {
    val c = Herdr.colors
    val view by remember(pane.paneId) { vm.chatView(pane.paneId) }.collectAsState()
    val connected by vm.connected.collectAsState()
    val repoTree by vm.repoTree.collectAsState()
    var draft by rememberSaveable(pane.paneId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(pane.paneId) {
        vm.openChat(pane.paneId)
        onDispose { vm.closeChat(pane.paneId) }
    }
    LaunchedEffect(pane.paneId) {
        while (true) { delay(10_000); vm.expireChat() }
    }
    // The dashboard (and its snackbar) isn't composed while a pane is open.
    LaunchedEffect(Unit) {
        vm.actionErrors.collect { snackbarHostState.showSnackbar(it) }
    }

    val ctx = remember(repoTree, pane) { paneContexts(repoTree)[pane.paneId] ?: fallbackContext(pane) }
    val agent = pane.agent?.takeIf { it.isNotBlank() } ?: "claude"
    val working = view.state == "working"
    val asking = remember(view.entries, view.answered) { pendingQuestion(view.entries, view.answered) }
    val status = chatStatus(connected, view.state, asking != null && asking.toolUseId !in view.answering)
    val statusColor = when (status.kind) {
        ChatStatusKind.Waiting -> c.red
        ChatStatusKind.Working -> c.yellow
        ChatStatusKind.Offline, ChatStatusKind.Idle -> c.overlay2
    }

    Box(Modifier.fillMaxSize().background(c.crust)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            PaneHeader(
                ctx = ctx,
                breadcrumb = paneBreadcrumb(ctx),
                status = status.label,
                statusColor = statusColor,
                onBack = onExit,
                toggleLabel = ">_ terminal",
                onToggle = onTerminal,
            )
            if (!pane.chat) UnavailableBanner(onTerminal)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val sheetMax = maxHeight * 0.8f
                Column(Modifier.fillMaxSize()) {
                    ChatTimelineList(
                        vm = vm,
                        paneId = pane.paneId,
                        agentId = null,
                        view = view,
                        agents = view.agents,
                        agentLabel = agent,
                        listState = listState,
                        readOnly = false,
                        onOpenThread = onOpenThread,
                        modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (asking != null) 0.45f else 1f),
                    )
                    if (asking != null) {
                        QuestionSheet(
                            asking,
                            agent = agent,
                            enabled = connected && pane.chat,
                            sending = asking.toolUseId in view.answering,
                            modifier = Modifier.heightIn(max = sheetMax),
                        ) { vm.answerChat(pane.paneId, asking.toolUseId, it) }
                    } else {
                        Composer(
                            draft = draft,
                            onDraft = { draft = it },
                            placeholder = if (working) "Queue a message…" else "Message $agent…",
                            canSend = connected && pane.chat && draft.isNotBlank(),
                            onSend = { vm.sendChat(pane.paneId, draft); draft = "" },
                            interrupt = if (working) ({ vm.interruptChat(pane.paneId) }) else null,
                            interruptEnabled = connected,
                        )
                    }
                }
            }
        }
        SnackbarHost(
            snackbarHostState,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 72.dp),
        )
    }
}

@Composable
private fun UnavailableBanner(onTerminal: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().background(c.red.copy(alpha = 0.12f)).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("chat unavailable — mod not reporting", Modifier.weight(1f), style = HerdrType.small, color = c.red)
        Text(
            "Open terminal",
            style = HerdrType.button,
            color = c.mauve,
            modifier = Modifier.clip(HerdrRadius.badge).clickable(onClick = onTerminal).padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/**
 * The bottom row: the message field, a send button once there's text, and
 * Interrupt (esc to the pane) while the agent works. With a draft, Interrupt
 * shrinks to its keycap to leave the field room.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    onSend: () -> Unit,
    interrupt: (() -> Unit)?,
    interruptEnabled: Boolean,
) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            maxLines = 5,
            textStyle = HerdrType.body.copy(color = c.text),
            cursorBrush = SolidColor(c.mauve),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(HerdrRadius.tile).background(c.base)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) Text(placeholder, style = HerdrType.body, color = c.overlay0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (draft.isNotBlank()) {
            Box(
                Modifier.size(48.dp).clip(HerdrRadius.tile).background(if (canSend) c.mauve else c.surface0)
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Text("↑", style = HerdrType.title, color = if (canSend) c.crust else c.overlay0) }
        }
        if (interrupt != null) {
            val ink = if (interruptEnabled) c.red else c.overlay0
            Row(
                Modifier.height(48.dp).clip(HerdrRadius.tile).background(c.red.copy(alpha = 0.12f))
                    .clickable(enabled = interruptEnabled, onClick = interrupt)
                    .padding(horizontal = if (draft.isNotBlank()) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "esc",
                    style = HerdrType.meta,
                    color = ink,
                    modifier = Modifier.border(1.dp, ink.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                if (draft.isBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text("Interrupt", style = HerdrType.button, color = ink)
                }
            }
        }
    }
}
