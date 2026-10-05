package dev.herdr.mobile.ui

import androidx.compose.runtime.*
import dev.herdr.mobile.net.Pane

/**
 * Opens a pane in chat when its herdr-chat mod is reporting, else the
 * terminal; the top-bar toggle flips between them for as long as it is open.
 */
@Composable
fun PaneScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit) {
    var showChat by remember(pane.paneId) { mutableStateOf(pane.chat) }
    if (showChat) {
        ChatScreen(vm, pane, onExit = onExit, onTerminal = { showChat = false }, onOpenThread = {})
    } else {
        TerminalScreen(vm, pane, onExit, onChat = if (pane.chat) ({ showChat = true }) else null)
    }
}
