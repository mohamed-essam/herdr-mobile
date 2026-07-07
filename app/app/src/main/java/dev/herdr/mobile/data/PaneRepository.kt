package dev.herdr.mobile.data

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PaneRepository {
    private val map = LinkedHashMap<String, Pane>()
    private val _panes = MutableStateFlow<List<Pane>>(emptyList())
    val panes: StateFlow<List<Pane>> = _panes.asStateFlow()

    fun onFrame(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Panes -> {
                map.clear()
                frame.panes.forEach { map[it.paneId] = it }
            }
            is ServerFrame.PaneUpdate -> map[frame.pane.paneId] = frame.pane
            is ServerFrame.PaneRemoved -> map.remove(frame.paneId)
            else -> return
        }
        _panes.value = map.values.sortedWith(comparator)
    }

    private val rank = mapOf("blocked" to 0, "working" to 1, "idle" to 2, "done" to 3)
    private val comparator = compareBy<Pane>({ rank[it.agentStatus] ?: 4 }, { it.paneId })
}
