package dev.herdr.mobile

import dev.herdr.mobile.ui.keysLive
import dev.herdr.mobile.ui.terminalExitCopy
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalExitTest {
    @Test fun endedClosedUnknownAreNeutral() {
        for (r in listOf("ended", "closed", "", "weird-future-value", "takeover")) {
            assertEquals("session ended", terminalExitCopy(r, 0).title)
        }
    }
    @Test fun errorShowsCode() {
        val c = terminalExitCopy("error", 137)
        assertEquals("terminal disconnected", c.title)
        assertEquals(true, c.detail.contains("137"))
    }
    @Test fun keysLiveTruthTable() {
        assertEquals(true,  keysLive(true,  "t1", false))
        assertEquals(false, keysLive(false, "t1", false)) // disconnected
        assertEquals(false, keysLive(true,  null, false)) // no attach
        assertEquals(false, keysLive(true,  "t1", true))  // taken over
    }
}
