package dev.herdr.mobile

import dev.herdr.mobile.ui.showReconnectOverlay
import org.junit.Assert.*
import org.junit.Test

class ReconnectOverlayTest {
    @Test fun overlayVisibilityTruthTable() {
        // not ready yet -> never show, even if disconnected
        assertFalse(showReconnectOverlay(emulatorReady = false, takenOver = false, status = "reconnecting…"))
        // taken over owns the screen -> reconnect scrim suppressed
        assertFalse(showReconnectOverlay(emulatorReady = true, takenOver = true, status = "reconnecting…"))
        // live -> no scrim
        assertFalse(showReconnectOverlay(emulatorReady = true, takenOver = false, status = "connected"))
        // WS dropped -> scrim
        assertTrue(showReconnectOverlay(emulatorReady = true, takenOver = false, status = "reconnecting…"))
        // connecting / re-attaching -> scrim
        assertTrue(showReconnectOverlay(emulatorReady = true, takenOver = false, status = "connecting…"))
        // released on the way to the background -> scrim, never a stale live screen
        assertTrue(showReconnectOverlay(emulatorReady = true, takenOver = false, status = "paused"))
    }
}
