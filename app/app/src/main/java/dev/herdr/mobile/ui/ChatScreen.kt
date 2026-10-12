package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.data.slashMatches
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.SlashCommand
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import kotlinx.coroutines.delay

/**
 * A pane's chat: the header, the timeline ([ChatTimelineList]) and the
 * composer (with Interrupt while the agent works) at the bottom; a pending
 * question replaces it as a pinned sheet. [listState] is hoisted to the pane,
 * so a thread opened on top leaves the scroll position alone.
 */
@Composable
fun ChatScreen(
    vm: DashboardViewModel,
    pane: Pane,
    onExit: () -> Unit,
    onTerminal: () -> Unit,
    onOpenThread: (String) -> Unit,
    listState: LazyListState,
) {
    val c = Herdr.colors
    val view by remember(pane.paneId) { vm.chatView(pane.paneId) }.collectAsState()
    val connected by vm.connected.collectAsState()
    val limits by vm.limits.collectAsState()
    val now = rememberNow()
    val repoTree by vm.repoTree.collectAsState()
    // Backed by the view model, so leaving the pane (or flipping to the
    // terminal) keeps it; the field's own state keeps the cursor.
    var field by remember(pane.paneId) { vm.drafts[pane.paneId].let { mutableStateOf(TextFieldValue(it, TextRange(it.length))) } }
    fun setField(v: TextFieldValue) {
        field = v
        vm.drafts[pane.paneId] = v.text
    }
    val draft = field.text
    val snackbarHostState = remember { SnackbarHostState() }
    var showTasks by rememberSaveable(pane.paneId) { mutableStateOf(false) }
    var scrollToToolUse by remember { mutableStateOf<String?>(null) }

    // PaneScreen holds the chat subscription, so it stays open under a thread.
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
    // Minimized, the sheet folds to a bar so the chat can be read at full size;
    // the picks made so far wait for it. A new question opens expanded.
    var askMinimized by rememberSaveable(asking?.toolUseId) { mutableStateOf(false) }
    var askInputs by remember(asking?.toolUseId, asking?.questions?.size) {
        mutableStateOf(List(asking?.questions?.size ?: 0) { QuestionInput() })
    }
    val sheetOpen = asking != null && !askMinimized
    val matches = remember(draft, view.commands) { slashMatches(draft, view.commands) }
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
                usage = pane.context, limits = limits, now = now,
            )
            TasksStrip(view.tasks) { showTasks = true }
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
                        tasks = view.tasks,
                        agentLabel = agent,
                        listState = listState,
                        readOnly = false,
                        onOpenThread = onOpenThread,
                        modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (sheetOpen) 0.45f else 1f),
                        scrollToToolUse = scrollToToolUse,
                        onScrolledToToolUse = { scrollToToolUse = null },
                    )
                    if (asking != null && !askMinimized) {
                        QuestionSheet(
                            asking,
                            agent = agent,
                            enabled = connected && pane.chat,
                            sending = asking.toolUseId in view.answering,
                            inputs = askInputs,
                            onInputs = { askInputs = it },
                            onMinimize = { askMinimized = true },
                            modifier = Modifier.heightIn(max = sheetMax),
                        ) { vm.answerChat(pane.paneId, asking.toolUseId, it) }
                    } else if (asking != null) {
                        QuestionBar(
                            agent = agent,
                            count = asking.questions.size,
                            sending = asking.toolUseId in view.answering,
                            onExpand = { askMinimized = false },
                        )
                    } else {
                        if (!matches.isNullOrEmpty() && pane.chat) {
                            SlashPicker(matches, Modifier.heightIn(max = sheetMax * 0.5f)) {
                                val text = "/${it.name} "
                                setField(TextFieldValue(text, TextRange(text.length)))
                            }
                        }
                        Composer(
                            field = field,
                            onField = ::setField,
                            placeholder = if (working) "Queue a message…" else "Message $agent…",
                            canSend = connected && pane.chat && draft.isNotBlank(),
                            onSend = { vm.sendChat(pane.paneId, draft); setField(TextFieldValue()) },
                            interrupt = if (working) ({ vm.interruptChat(pane.paneId) }) else null,
                            interruptEnabled = connected,
                        )
                    }
                }
            }
        }
        if (showTasks) {
            TasksSheet(
                tasks = view.tasks,
                now = System.currentTimeMillis(),
                onPick = { t ->
                    scrollToToolUse = t.toolUseId
                    showTasks = false
                },
                onDismiss = { showTasks = false },
            )
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
/**
 * The session's slash commands matching what's typed after "/", above the
 * composer: a tap fills in `/name ` for the arguments.
 */
@Composable
private fun SlashPicker(commands: List<SlashCommand>, modifier: Modifier = Modifier, onPick: (SlashCommand) -> Unit) {
    val c = Herdr.colors
    LazyColumn(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).clip(HerdrRadius.tile).background(c.base),
    ) {
        items(commands, key = { it.name }) { cmd ->
            Column(
                Modifier.fillMaxWidth().clickable { onPick(cmd) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("/${cmd.name}", style = HerdrType.code, color = c.mauve, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (cmd.source.isNotEmpty() && cmd.source != "builtin") {
                        Spacer(Modifier.width(8.dp))
                        Text(cmd.source, style = HerdrType.meta, color = c.overlay2)
                    }
                }
                if (cmd.description.isNotEmpty()) {
                    Text(cmd.description, style = HerdrType.small, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A minimized question: one bar where the composer was; a tap brings the sheet back. */
@Composable
private fun QuestionBar(agent: String, count: Int, sending: Boolean, onExpand: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(HerdrRadius.tile).background(c.red.copy(alpha = 0.12f)).clickable(onClick = onExpand)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (sending) "● sending answer…" else "● $agent asks" + if (count > 1) " · $count questions" else "",
            style = HerdrType.button, color = c.red, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("Answer ▴", style = HerdrType.button, color = c.mauve)
    }
}

@Composable
private fun Composer(
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    placeholder: String,
    canSend: Boolean,
    onSend: () -> Unit,
    interrupt: (() -> Unit)?,
    interruptEnabled: Boolean,
) {
    val c = Herdr.colors
    val draft = field.text
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = field,
            onValueChange = onField,
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
