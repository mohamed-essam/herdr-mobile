package dev.herdr.mobile.ui

import dev.herdr.mobile.net.Pane

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
