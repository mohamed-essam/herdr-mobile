package dev.herdr.mobile

import dev.herdr.mobile.net.BgTask
import dev.herdr.mobile.net.parseBgTask
import dev.herdr.mobile.ui.TaskCounts
import dev.herdr.mobile.ui.stripLabel
import dev.herdr.mobile.ui.taskCounts
import dev.herdr.mobile.ui.taskDuration
import dev.herdr.mobile.ui.taskFullText
import dev.herdr.mobile.ui.taskGlyph
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TasksModelTest {
    private fun task(status: String, startedAt: Long = 0, endedAt: Long? = null, kind: String = "shell") =
        BgTask("t-$status-$startedAt", kind, "label", "tu", status, startedAt, endedAt)

    @Test fun countsByStatus() {
        val c = taskCounts(listOf(task("running", 1), task("running", 2), task("done", 3), task("failed", 4), task("weird", 5)))
        assertEquals(TaskCounts(running = 2, done = 1, failed = 1), c)
        assertEquals(TaskCounts(0, 0, 0), taskCounts(emptyList()))
    }

    @Test fun stripLabelOmitsZeroParts() {
        assertEquals("⟳ 2 running · 1 done", stripLabel(TaskCounts(2, 1, 0)))
        assertEquals("⟳ 1 failed", stripLabel(TaskCounts(0, 0, 1)))
        assertEquals("⟳ 2 running · 1 done · 1 failed", stripLabel(TaskCounts(2, 1, 1)))
        assertNull(stripLabel(TaskCounts(0, 0, 0)))
    }

    @Test fun durationFormats() {
        assertEquals("4s", taskDuration(task("done", 0, 4_000), now = 99_999))
        assertEquals("2m 05s", taskDuration(task("done", 0, 125_000), now = 0))
        assertEquals("1h 03m", taskDuration(task("failed", 0, 3_780_000), now = 0))
        assertEquals("0s", taskDuration(task("done", 10_000, 9_000), now = 0))
    }

    @Test fun runningCountsToNow() {
        assertEquals("30s", taskDuration(task("running", 1_000), now = 31_000))
        // A finished task without an end time still counts to now.
        assertEquals("1m 00s", taskDuration(task("done", 0, null), now = 60_000))
    }

    @Test fun glyphs() {
        assertEquals("$", taskGlyph("shell"))
        assertEquals("◆", taskGlyph("subagent"))
        assertEquals("⧉", taskGlyph("workflow"))
        assertEquals("◉", taskGlyph("monitor"))
        assertEquals("•", taskGlyph("other"))
    }

    @Test fun parsesTheWholeCommandAndDescription() {
        val t = parseBgTask(Json.parseToJsonElement(
            """{"id":"b1","kind":"shell","label":"bash /a/b…","toolUseId":"tu","status":"running","startedAt":1,""" +
                """"detail":"bash /a/b/c.sh\n  --x","description":"Probe it"}"""))!!
        assertEquals("bash /a/b/c.sh\n  --x", t.detail)
        assertEquals("Probe it", t.description)
        // An older mod sends neither.
        val old = parseBgTask(Json.parseToJsonElement("""{"id":"b1","kind":"shell","label":"ls"}"""))!!
        assertNull(old.detail)
        assertNull(old.description)
    }

    @Test fun fullTextPrefersTheWholeCommand() {
        assertEquals("bash /a/b/c.sh", taskFullText(task("running").copy(label = "bash /a/…", detail = "bash /a/b/c.sh")))
        assertEquals("label", taskFullText(task("running")))
    }
}
