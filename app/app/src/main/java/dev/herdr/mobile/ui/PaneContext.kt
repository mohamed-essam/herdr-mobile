package dev.herdr.mobile.ui

import dev.herdr.mobile.net.Pane

/**
 * Where a pane lives, flattened for display: the v2 UI names a pane by its
 * workspace (badge number + label) and keeps the repo and tab as metadata.
 */
data class PaneContext(
    val pane: Pane,
    val workspaceLabel: String,
    val workspaceNumber: Int,
    val repoName: String,
    val tabLabel: String,
    val tabNumber: Int,
)

/** Every pane in [repos] keyed by paneId. */
fun paneContexts(repos: List<RepoNode>): Map<String, PaneContext> {
    val out = LinkedHashMap<String, PaneContext>()
    for (repo in repos) for (ws in repo.workspaces) for (tab in ws.tabs) for (p in tab.panes) {
        out[p.paneId] = PaneContext(
            pane = p,
            workspaceLabel = ws.ws.label.ifEmpty { repo.displayName },
            workspaceNumber = ws.ws.number,
            repoName = repo.displayName,
            tabLabel = tab.tab.label,
            tabNumber = tab.tab.number,
        )
    }
    return out
}

/** A context for a pane the tree doesn't know (yet): cwd basename as title. */
fun fallbackContext(pane: Pane): PaneContext = PaneContext(
    pane = pane,
    workspaceLabel = pane.cwd.substringAfterLast('/').ifEmpty { pane.agent ?: "pane" },
    workspaceNumber = 0,
    repoName = "",
    tabLabel = "",
    tabNumber = 0,
)

/** "tab 1" from a tab's number, else its label, else empty. */
fun tabCrumb(ctx: PaneContext): String = when {
    ctx.tabNumber > 0 -> "tab ${ctx.tabNumber}"
    ctx.tabLabel.isNotBlank() -> ctx.tabLabel
    else -> ""
}

/** The pane screen breadcrumb before the status: "herdr-mobile › tab 1 › claude". */
fun paneBreadcrumb(ctx: PaneContext, includeAgent: Boolean = true): String =
    listOfNotNull(
        ctx.repoName.takeIf { it.isNotBlank() },
        tabCrumb(ctx).takeIf { it.isNotBlank() },
        ctx.pane.agent?.takeIf { includeAgent && it.isNotBlank() },
    ).joinToString(" › ")

/** The dashboard subline: "herdr-mobile · claude". */
fun paneMeta(ctx: PaneContext): String =
    listOfNotNull(ctx.repoName.takeIf { it.isNotBlank() }, ctx.pane.agent ?: "shell")
        .joinToString(" · ")

/** Compact age of [ts] at [now] (epoch ms): "now", "5m", "3h", "2d"; "" when unknown. */
fun relativeAge(ts: Long, now: Long): String {
    if (ts <= 0) return ""
    val s = ((now - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}
