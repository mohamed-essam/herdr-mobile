package dev.herdr.mobile

import dev.herdr.mobile.ui.TextSegment
import dev.herdr.mobile.ui.splitFences
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextTest {
    @Test fun plainTextIsOneProseSegment() {
        assertEquals(listOf(TextSegment.Prose("hello\nworld")), splitFences("hello\nworld"))
    }

    @Test fun fencesSplitOutCodeKeepingIndentation() {
        val text = "Run:\n```bash\n  npm test\n```\nthen check."
        assertEquals(
            listOf(TextSegment.Prose("Run:"), TextSegment.Code("  npm test"), TextSegment.Prose("then check.")),
            splitFences(text),
        )
    }

    @Test fun unclosedFenceIsCode() {
        assertEquals(listOf(TextSegment.Prose("a"), TextSegment.Code("b")), splitFences("a\n```\nb"))
    }

    @Test fun blankSegmentsAreDropped() {
        assertEquals(listOf(TextSegment.Code("x")), splitFences("```\nx\n```\n\n"))
    }
}
