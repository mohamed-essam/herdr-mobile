package dev.herdr.mobile

import dev.herdr.mobile.ui.ModState
import dev.herdr.mobile.ui.ModifierKeys
import dev.herdr.mobile.ui.TermKey
import dev.herdr.mobile.ui.bytesFor
import org.junit.Assert.*
import org.junit.Test

class TerminalKeysTest {
    private fun esc(vararg tail: Char) =
        byteArrayOf(0x1b) + tail.map { it.code.toByte() }.toByteArray()

    @Test fun bytesForControlKeys() {
        assertArrayEquals(byteArrayOf(0x1b), bytesFor(TermKey.ESC))
        assertArrayEquals(byteArrayOf(0x09), bytesFor(TermKey.TAB))
    }

    @Test fun bytesForArrows() {
        assertArrayEquals(esc('[', 'A'), bytesFor(TermKey.UP))
        assertArrayEquals(esc('[', 'B'), bytesFor(TermKey.DOWN))
        assertArrayEquals(esc('[', 'C'), bytesFor(TermKey.RIGHT))
        assertArrayEquals(esc('[', 'D'), bytesFor(TermKey.LEFT))
    }

    @Test fun bytesForNav() {
        assertArrayEquals(esc('[', 'H'), bytesFor(TermKey.HOME))
        assertArrayEquals(esc('[', 'F'), bytesFor(TermKey.END))
        assertArrayEquals(esc('[', '5', '~'), bytesFor(TermKey.PGUP))
        assertArrayEquals(esc('[', '6', '~'), bytesFor(TermKey.PGDN))
    }

    @Test fun modifierOneShotCycle() {
        val m = ModifierKeys()
        assertEquals(ModState.OFF, m.ctrl)
        assertFalse(m.readCtrl())
        m.tapCtrl()
        assertEquals(ModState.ONE_SHOT, m.ctrl)
        assertTrue(m.readCtrl())
        m.tapCtrl()
        assertEquals(ModState.OFF, m.ctrl)
    }

    @Test fun modifierLockThenTapOff() {
        val m = ModifierKeys()
        m.lockCtrl()
        assertEquals(ModState.LOCKED, m.ctrl)
        assertTrue(m.readCtrl())
        m.tapCtrl()
        assertEquals(ModState.OFF, m.ctrl)
    }

    @Test fun consumeOneShotClearsOneShotNotLocked() {
        val m = ModifierKeys()
        m.tapCtrl()   // ONE_SHOT
        m.lockAlt()   // LOCKED
        m.consumeOneShot()
        assertEquals(ModState.OFF, m.ctrl)
        assertEquals(ModState.LOCKED, m.alt)
    }

    @Test fun ctrlAndAltIndependent() {
        val m = ModifierKeys()
        m.tapCtrl()
        assertEquals(ModState.ONE_SHOT, m.ctrl)
        assertEquals(ModState.OFF, m.alt)
    }
}
