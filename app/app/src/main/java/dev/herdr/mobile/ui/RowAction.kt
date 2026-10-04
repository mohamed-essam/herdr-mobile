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
    // For a pane, its tab's action so the pane's sheet can pivot to tab
    // operations. Null when the tab is unknown.
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
