package dev.herdr.mobile

import dev.herdr.mobile.ui.terminalExitCopy
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalExitTest {
    @Test fun takeover() {
        assertEquals("taken over on another client", terminalExitCopy("takeover", 0).title)
    }
    @Test fun endedClosedUnknownAreNeutral() {
        for (r in listOf("ended", "closed", "", "weird-future-value")) {
            assertEquals("session ended", terminalExitCopy(r, 0).title)
        }
    }
    @Test fun errorShowsCode() {
        val c = terminalExitCopy("error", 137)
        assertEquals("terminal disconnected", c.title)
        assertEquals(true, c.detail.contains("137"))
    }
}
