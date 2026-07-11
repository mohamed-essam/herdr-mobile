package dev.herdr.mobile.ui

/** The three structural node kinds; `wire` is the string the companion expects. */
enum class NodeKind(val wire: String) {
    WORKSPACE("workspace"),
    TAB("tab"),
    PANE("pane"),
}

/**
 * A structural action target built from a tapped tree row. Carries just enough
 * to drive the rename dialog (label), the confirm decision, and the confirm copy.
 */
data class RowAction(
    val kind: NodeKind,
    val id: String,
    val label: String,
    val paneCount: Int = 0,
    val tabCount: Int = 0,
    val isAgent: Boolean = false,
    val hasAgent: Boolean = false,
    val workspaceId: String = "",
    // For a promoted pane (its tab was elided), the parent tab's action so the
    // pane's sheet can pivot to tab operations. Null for normal panes.
    val mergedTab: RowAction? = null,
)

/**
 * Confirm a close when it terminates an agent, closes more than one pane, closes
 * a tab that contains an agent, or closes any workspace.
 */
fun needsCloseConfirm(a: RowAction): Boolean = when (a.kind) {
    NodeKind.PANE -> a.isAgent
    NodeKind.TAB -> a.paneCount > 1 || a.hasAgent
    NodeKind.WORKSPACE -> true
}

/** Confirmation body copy, scaled to the blast radius. */
fun closeConfirmMessage(a: RowAction): String = when (a.kind) {
    NodeKind.PANE -> "Close this agent pane? The running agent will be terminated."
    NodeKind.TAB -> "Close tab '${a.label}'? This ends ${a.paneCount} pane(s)."
    NodeKind.WORKSPACE ->
        "Close workspace '${a.label}'? This ends ${a.paneCount} pane(s) across ${a.tabCount} tab(s)."
}

/**
 * Confirm copy augmented with the sibling worktree-workspaces that herdr will
 * also close (cascade). Falls back to the plain copy when there are none.
 */
fun closeConfirmMessageWith(a: RowAction, alsoCloses: List<String>): String {
    val base = closeConfirmMessage(a)
    if (alsoCloses.isEmpty()) return base
    return base +
        "\n\nAlso closes ${alsoCloses.size} linked worktree workspace(s): ${alsoCloses.joinToString(", ")}."
}
