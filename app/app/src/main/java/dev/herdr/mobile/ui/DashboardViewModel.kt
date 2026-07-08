package dev.herdr.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val client: CompanionClient,
    private val repo: PaneRepository,
) : ViewModel() {
    val panes: StateFlow<List<Pane>> = repo.panes
    val connected: StateFlow<Boolean> = client.connected

    val tree: StateFlow<List<WorkspaceNode>> =
        combine(repo.workspaces, repo.tabs, repo.panes) { ws, tabs, panes -> buildTree(ws, tabs, panes) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ids the user has COLLAPSED; a node is expanded unless its id is here.
    private val _collapsed = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = _collapsed
    fun toggleExpanded(id: String) = _collapsed.update { if (id in it) it - id else it + id }

    private val _lastOpenedPaneId = MutableStateFlow<String?>(null)
    val lastOpenedPaneId: StateFlow<String?> = _lastOpenedPaneId

    fun start(url: String) {
        viewModelScope.launch { client.frames.collect { repo.onFrame(it) } }
        client.connect(url)
    }

    fun registerPush(endpoint: String) = client.registerPush(endpoint)

    suspend fun openTerminal(target: String, cols: Int, rows: Int): String {
        _lastOpenedPaneId.value = target
        return client.openTerminal(target, cols, rows)
    }
    fun termInput(termId: String, data: ByteArray) = client.sendTermInput(termId, data)
    fun termResize(termId: String, cols: Int, rows: Int) = client.sendTermResize(termId, cols, rows)
    fun closeTerminal(termId: String) = client.closeTerminal(termId)
    val frames get() = client.frames

    override fun onCleared() { client.close() }
}
