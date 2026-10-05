package dev.herdr.mobile

import dev.herdr.mobile.ui.ThreadStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadStackTest {
    @Test fun pushAndPopKeepOrder() {
        val s = ThreadStack().push("a").push("b").push("c")
        assertEquals(listOf("a", "b", "c"), s.ids)
        assertEquals("c", s.top)
        assertEquals(listOf("a", "b"), s.pop().ids)
        assertEquals(listOf("a"), s.pop().pop().ids)
    }

    @Test fun pushingTheTopAgainIsANoOp() {
        val s = ThreadStack().push("a").push("b")
        assertEquals(s, s.push("b"))
        // Only the top is deduped: a deeper id may be pushed again.
        assertEquals(listOf("a", "b", "a"), s.push("a").ids)
    }

    @Test fun poppingEmptyStaysEmpty() {
        val s = ThreadStack().pop()
        assertEquals(emptyList<String>(), s.ids)
        assertNull(s.top)
        assertNull(ThreadStack().push("a").pop().pop().top)
    }

    @Test fun topAfterPushPushPopIsTheFirst() {
        assertEquals("a", ThreadStack().push("a").push("b").pop().top)
    }
}
