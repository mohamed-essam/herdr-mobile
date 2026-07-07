package dev.herdr.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.mobile.data.PaneRepository
import dev.herdr.mobile.net.CompanionClient
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val client: CompanionClient,
    private val repo: PaneRepository,
) : ViewModel() {
    val panes: StateFlow<List<Pane>> = repo.panes
    val connected: StateFlow<Boolean> = client.connected

    fun start(url: String) {
        viewModelScope.launch { client.frames.collect { repo.onFrame(it) } }
        client.connect(url)
    }

    suspend fun peek(paneId: String): String = client.readPane(paneId)

    suspend fun reply(paneId: String, text: String, sendEnter: Boolean) {
        if (text.isNotEmpty()) client.sendText(paneId, text)
        if (sendEnter) client.sendKeys(paneId, "enter")
    }

    suspend fun quickKey(paneId: String, key: String) = client.sendKeys(paneId, key)

    fun registerPush(endpoint: String) = client.registerPush(endpoint)

    override fun onCleared() { client.close() }
}
