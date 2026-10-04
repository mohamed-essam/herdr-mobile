package dev.herdr.mobile

import dev.herdr.mobile.ui.TerminalHeaderStatus
import dev.herdr.mobile.ui.terminalHeaderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalHeaderStatusTest {
    @Test fun mapsTerminalStates() {
        assertEquals(TerminalHeaderStatus("attached", "done"), terminalHeaderStatus("connected", takenOver = false))
        assertEquals(TerminalHeaderStatus("connecting…", "working"), terminalHeaderStatus("connecting…", takenOver = false))
        assertEquals(TerminalHeaderStatus("reconnecting…", "working"), terminalHeaderStatus("reconnecting…", takenOver = false))
        assertEquals(TerminalHeaderStatus("paused", "idle"), terminalHeaderStatus("paused", takenOver = false))
        assertEquals(TerminalHeaderStatus("failed", "blocked"), terminalHeaderStatus("failed: no such pane", takenOver = false))
        assertEquals(TerminalHeaderStatus("session ended", "blocked"), terminalHeaderStatus("session ended", takenOver = true))
    }
}
