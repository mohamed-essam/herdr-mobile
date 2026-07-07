package dev.herdr.mobile.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReplySheet(vm: DashboardViewModel, pane: Pane, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var reply by remember { mutableStateOf("") }
    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }

    LaunchedEffect(pane.paneId) {
        loading = true
        output = runCatching { vm.peek(pane.paneId) }
            .getOrElse { "failed to read pane: ${it.message}" }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            // header: name + workspace·agent + live status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOf(pane.workspaceId, pane.agent ?: "shell").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusIndicator(pane.agentStatus)
            }

            Spacer(Modifier.height(12.dp))
            TerminalPeek(text = if (loading) "…" else cleanTerminal(output))
            Spacer(Modifier.height(14.dp))

            // key chips styled as terminal keycaps
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("y", "n", "enter", "esc", "ctrl+c").forEach { key ->
                    KeyCap(key) {
                        scope.launch {
                            runCatching {
                                if (key == "y" || key == "n") vm.reply(pane.paneId, key, sendEnter = true)
                                else vm.quickKey(pane.paneId, key)
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            // reply line with a ❯ prompt and a send action
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    prefix = { Text("❯ ", color = MaterialTheme.colorScheme.primary) },
                    placeholder = { Text("reply…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
                Spacer(Modifier.width(10.dp))
                FilledIconButton(
                    onClick = { scope.launch { runCatching { vm.reply(pane.paneId, reply, sendEnter = true) }; reply = "" } },
                    shape = MaterialTheme.shapes.small,
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "send") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TerminalPeek(text: String) {
    val vScroll = rememberScrollState()
    // auto-scroll to the newest output (bottom) once it lays out
    LaunchedEffect(text) { vScroll.scrollTo(vScroll.maxValue) }
    Surface(
        color = MaterialTheme.colorScheme.background, // deepest crust — the "screen"
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().height(240.dp),
    ) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxSize()
                    .verticalScroll(vScroll)
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun KeyCap(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * herdr's pane capture is a full screen of fixed rows, so it arrives padded with
 * blank lines. Drop leading/trailing blanks and collapse interior blank runs so
 * the peek shows the actual conversation tail, not a field of whitespace.
 */
private fun cleanTerminal(raw: String): String {
    val lines = raw.split('\n').map { it.trimEnd() }
    val out = ArrayList<String>(lines.size)
    var blanks = 0
    for (l in lines) {
        if (l.isBlank()) { blanks++; if (blanks <= 1) out.add("") } else { blanks = 0; out.add(l) }
    }
    while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
    while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
    return out.joinToString("\n").ifBlank { "(no output)" }
}
