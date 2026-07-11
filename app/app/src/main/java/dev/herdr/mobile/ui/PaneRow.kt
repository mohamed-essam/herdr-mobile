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

/** Pane's primary label, de-duplicated against its enclosing [repoLabel]. Agent
 *  panes lead with the agent; a shell leads with a differentiating cwd subdir,
 *  else the generic "shell". Never repeats the repo name. */
fun panePrimaryLabel(pane: Pane, repoLabel: String): String {
    pane.agent?.let { return it }
    val base = pane.cwd.substringAfterLast('/')
    return if (base.isNotBlank() && base != repoLabel) base else "shell"
}

/** Secondary (dim) line: the cwd subdir when it adds info beyond the repo label
 *  for an agent pane; null when redundant or already carried by the title. */
fun paneSecondaryLabel(pane: Pane, repoLabel: String): String? {
    if (pane.agent == null) return null
    val base = pane.cwd.substringAfterLast('/')
    return if (base.isNotBlank() && base != repoLabel) base else null
}

/**
 * A pane rendered as a herdr "pane block": a rectangular card fronted by a
 * status-colored bar, the project name in bold mono, and a dim workspace·agent
 * subline. Tapping opens the pane's terminal.
 */
@Composable
fun PaneRow(pane: Pane, repoLabel: String, onClick: (Pane) -> Unit) {
    val dark = isSystemInDarkTheme()
    val status = paneStatusLabel(pane)
    val accent = statusColor(status, dark)
    val title = panePrimaryLabel(pane, repoLabel)
    val secondary = paneSecondaryLabel(pane, repoLabel)

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
                if (secondary != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StatusIndicator(status, Modifier.padding(end = 14.dp))
        }
    }
}
