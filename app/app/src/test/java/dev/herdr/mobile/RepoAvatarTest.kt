package dev.herdr.mobile

import dev.herdr.mobile.ui.monogram
import dev.herdr.mobile.ui.theme.colorIndexFor
import org.junit.Assert.*
import org.junit.Test

class RepoAvatarTest {
    @Test fun monogramRules() {
        assertEquals("GE", monogram("getpaseo/paseo"))
        assertEquals("K", monogram("k"))
        assertEquals("12", monogram("123repo"))
        assertEquals("?", monogram("/-_"))
        assertEquals("?", monogram(""))
    }

    @Test fun colorIndexIsStableAndInRange() {
        assertEquals(colorIndexFor("ops", 6), colorIndexFor("ops", 6)) // deterministic
        for (seed in listOf("ops", "core", "omega3", "", "a/b/c")) {
            val i = colorIndexFor(seed, 6)
            assertTrue("index $i out of range for '$seed'", i in 0 until 6)
        }
        assertEquals(0, colorIndexFor("anything", 0)) // guard: size <= 0
    }
}
