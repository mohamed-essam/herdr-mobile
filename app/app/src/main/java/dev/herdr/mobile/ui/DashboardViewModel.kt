package dev.herdr.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val client: CompanionClient,
    private val repo: PaneRepository,
    fontSizeStore: Flow<Int?> = MutableStateFlow(null),
    private val persistFontSize: (Int) -> Unit = {},
    recentAgentsStore: Flow<List<String>> = MutableStateFlow(emptyList()),
    private val persistRecentAgent: (String) -> Unit = {},
) : ViewModel() {
    val panes: StateFlow<List<Pane>> = repo.panes
    val connected: StateFlow<Boolean> = client.connected

    val terminalFontSize: StateFlow<Int?> =
        fontSizeStore.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun setTerminalFontSize(px: Int) = persistFontSize(px)

    val recentAgents: StateFlow<List<String>> =
        recentAgentsStore.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun recordRecentAgent(name: String) = persistRecentAgent(name.lowercase())

    val tree: StateFlow<List<WorkspaceNode>> =
        combine(repo.workspaces, repo.tabs, repo.panes) { ws, tabs, panes -> buildTree(ws, tabs, panes) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val repoTree: StateFlow<List<RepoNode>> =
        tree.map { buildRepoTree(it) }
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

    private val _actionErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val actionErrors: SharedFlow<String> = _actionErrors.asSharedFlow()

    fun renameNode(kind: String, id: String, label: String) {
        viewModelScope.launch {
            runCatching { client.sendAction("rename", kind, id, label) }
                .onFailure { _actionErrors.tryEmit(it.message ?: "rename failed") }
        }
    }

    fun closeNode(kind: String, id: String) {
        viewModelScope.launch {
            runCatching { client.sendAction("close", kind, id) }
                .onFailure { _actionErrors.tryEmit(it.message ?: "close failed") }
        }
    }

    private val _autoOpen = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val autoOpen: SharedFlow<String> = _autoOpen.asSharedFlow()

    private val _agents = MutableStateFlow<List<String>>(emptyList())
    val agents: StateFlow<List<String>> = _agents.asStateFlow()

    fun refreshAgents() {
        viewModelScope.launch { runCatching { client.listAgents() }.onSuccess { _agents.value = it } }
    }

    fun createNode(
        what: String, workspaceId: String? = null, tabId: String? = null, paneId: String? = null,
        direction: String? = null, agentName: String? = null, argv: List<String>? = null,
    ) {
        viewModelScope.launch {
            runCatching { client.sendCreate(what, workspaceId, tabId, paneId, direction, agentName, argv) }
                .onSuccess { terminalId -> if (terminalId.isNotEmpty()) _autoOpen.tryEmit(terminalId) }
                .onFailure { _actionErrors.tryEmit(it.message ?: "create failed") }
        }
    }

    fun moveNode(paneId: String, dest: String, tabId: String? = null, direction: String? = null) {
        viewModelScope.launch {
            runCatching { client.sendMove(paneId, dest, tabId, direction) }
                .onFailure { _actionErrors.tryEmit(it.message ?: "move failed") }
        }
    }

    /** Sibling labels that will also close with this workspace; [] on error. */
    suspend fun closeImpact(workspaceId: String): List<String> =
        runCatching { client.closeImpact(workspaceId).map { it.label } }.getOrDefault(emptyList())

    suspend fun openTerminal(pane: Pane, cols: Int, rows: Int): String {
        _lastOpenedPaneId.value = pane.paneId
        return client.openTerminal(pane.terminalId, cols, rows)
    }
    fun termInput(termId: String, data: ByteArray) = client.sendTermInput(termId, data)
    fun termResize(termId: String, cols: Int, rows: Int) = client.sendTermResize(termId, cols, rows)
    fun closeTerminal(termId: String) = client.closeTerminal(termId)
    val frames get() = client.frames

    override fun onCleared() { client.close() }
}
