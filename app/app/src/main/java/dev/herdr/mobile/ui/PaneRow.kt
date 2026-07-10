package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.statusColor

/**
 * Status label for a pane: agent panes show their agentStatus; shells (no agent)
 * show "shell" rather than herdr's "unknown" placeholder (herdr reports
 * agentStatus="unknown" for non-agent panes).
 */
fun paneStatusLabel(pane: Pane): String? = if (pane.agent == null) "shell" else pane.agentStatus

/**
 * A pane rendered as a herdr "pane block": a rectangular card fronted by a
 * status-colored bar, the project name in bold mono, and a dim workspace·agent
 * subline. Tapping opens the pane's terminal.
 */
@Composable
fun PaneRow(pane: Pane, onClick: (Pane) -> Unit) {
    val dark = isSystemInDarkTheme()
    val status = paneStatusLabel(pane)
    val accent = statusColor(status, dark)
    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }
    // agent panes: "workspace · agent"; shells: just the workspace (the status
    // chip already reads "shell", so don't duplicate it here).
    val subtitle = listOfNotNull(pane.workspaceId.ifBlank { null }, pane.agent).joinToString(" · ")

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { onClick(pane) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // status accent bar down the left edge
            Box(
                Modifier
                    .width(4.dp)
                    .height(52.dp)
                    .background(accent),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusIndicator(status, Modifier.padding(end = 14.dp))
        }
    }
}
