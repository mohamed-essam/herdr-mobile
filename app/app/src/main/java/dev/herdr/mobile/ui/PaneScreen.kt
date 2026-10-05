package dev.herdr.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.herdr.mobile.net.Pane

/**
 * Opens a pane in chat when its herdr-chat mod is reporting, else the
 * terminal; the top-bar toggle flips between them for as long as it is open.
 * Subagent threads stack over the chat; back pops one at a time.
 */
@Composable
fun PaneScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit) {
    var showChat by remember(pane.paneId) { mutableStateOf(pane.chat) }
    // Per pane, so leaving the pane drops the stack.
    var threads by remember(pane.paneId) { mutableStateOf(ThreadStack()) }
    // Hoisted with the chat's saved state, so returning from a thread lands
    // where the chat was (with its draft) instead of re-opening at the bottom.
    val chatList = rememberSaveable(pane.paneId, saver = LazyListState.Saver) { LazyListState() }
    val saved = rememberSaveableStateHolder()

    // Composed after the dashboard's handler, so it wins: system back pops a
    // thread first and leaves the pane only from the chat.
    BackHandler(enabled = threads.top != null) { threads = threads.pop() }

    if (showChat) {
        // The main stream stays open under a thread: agent summaries (and so
        // the thread's header and nested cards) only arrive on it.
        DisposableEffect(pane.paneId) {
            vm.openChat(pane.paneId)
            onDispose { vm.closeChat(pane.paneId) }
        }
        val mainView by remember(pane.paneId) { vm.chatView(pane.paneId) }.collectAsState()
        val top = threads.top
        if (top != null) {
            ThreadScreen(
                vm, pane, agentId = top, agents = mainView.agents,
                onOpen = { threads = threads.push(it) },
                onBack = { threads = threads.pop() },
            )
        } else {
            val chatKey = "chat:${pane.paneId}"
            saved.SaveableStateProvider(chatKey) {
                ChatScreen(
                    vm, pane, onExit = onExit,
                    // Back from the terminal is a fresh entry: it opens at the newest item.
                    onTerminal = { saved.removeState(chatKey); showChat = false },
                    onOpenThread = { threads = threads.push(it) }, listState = chatList,
                )
            }
        }
    } else {
        TerminalScreen(vm, pane, onExit, onChat = if (pane.chat) ({ showChat = true }) else null)
    }
}
