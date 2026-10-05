package dev.herdr.mobile.ui

/**
 * The subagent threads opened on top of a pane's chat, oldest first. Tapping
 * the card of the thread already on top doesn't stack it twice; back pops one
 * level, so it never skips past a thread to the chat.
 */
data class ThreadStack(val ids: List<String> = emptyList()) {
    val top: String? get() = ids.lastOrNull()
    fun push(id: String) = if (top == id) this else copy(ids = ids + id)
    fun pop() = copy(ids = ids.dropLast(1))
}
