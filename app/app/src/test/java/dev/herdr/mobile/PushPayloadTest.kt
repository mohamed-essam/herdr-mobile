package dev.herdr.mobile

import dev.herdr.mobile.push.parsePush
import org.junit.Assert.*
import org.junit.Test

class PushPayloadTest {
    @Test fun parsesBlockedPayload() {
        val p = parsePush("""{"kind":"blocked","paneId":"w6:p1","workspaceId":"w6","title":"w6 needs you","body":"Proceed? (y/n)"}""".toByteArray())!!
        assertEquals("blocked", p.kind)
        assertEquals("w6:p1", p.paneId)
        assertEquals("Proceed? (y/n)", p.body)
    }

    @Test fun returnsNullOnGarbage() {
        assertNull(parsePush("not json".toByteArray()))
    }
}
