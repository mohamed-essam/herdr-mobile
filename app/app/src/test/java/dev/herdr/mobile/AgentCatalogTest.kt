package dev.herdr.mobile

import dev.herdr.mobile.data.updatedMru
import dev.herdr.mobile.ui.describeAgent
import dev.herdr.mobile.ui.filterAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentCatalogTest {
    @Test fun describeKnownUnknownCaseInsensitive() {
        assertEquals("OpenAI Codex CLI", describeAgent("codex"))
        assertEquals("OpenAI Codex CLI", describeAgent("CODEX"))
        assertNull(describeAgent("agy"))
    }
    @Test fun filterBlankReturnsAll() {
        val all = listOf("claude", "codex", "gemini")
        assertEquals(all, filterAgents(all, "  "))
    }
    @Test fun filterSubstringCaseInsensitive() {
        assertEquals(listOf("codex"), filterAgents(listOf("claude", "codex", "gemini"), "ODE"))
        assertEquals(emptyList<String>(), filterAgents(listOf("claude"), "zzz"))
    }
    @Test fun mruPrependsDedupesCaps() {
        assertEquals(listOf("codex", "claude"), updatedMru(listOf("claude"), "codex"))
        assertEquals(listOf("claude", "codex"), updatedMru(listOf("codex", "claude"), "claude")) // move to front
        assertEquals(5, updatedMru(listOf("a", "b", "c", "d", "e"), "f").size)                    // cap
        assertEquals("f", updatedMru(listOf("a", "b", "c", "d", "e"), "f").first())
    }
}
