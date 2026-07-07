package dev.herdr.mobile

import dev.herdr.mobile.net.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun parsesPanesSnapshot() {
        val f = parseServerFrame("""{"t":"panes","panes":[{"paneId":"w6:p1","workspaceId":"w6","tabId":"w6:t1","cwd":"/x","focused":true,"agent":"claude","agentStatus":"working"}]}""")
        assertTrue(f is ServerFrame.Panes)
        val p = (f as ServerFrame.Panes).panes.single()
        assertEquals("w6:p1", p.paneId)
        assertEquals("working", p.agentStatus)
        assertTrue(p.focused)
    }

    @Test fun parsesPaneUpdateWithNullAgent() {
        val f = parseServerFrame("""{"t":"pane_update","pane":{"paneId":"w2:p1","workspaceId":"w2","tabId":"w2:t1","cwd":"/y","focused":false,"agent":null,"agentStatus":null}}""")
        val p = (f as ServerFrame.PaneUpdate).pane
        assertNull(p.agent)
        assertNull(p.agentStatus)
    }

    @Test fun parsesPaneReadAndError() {
        assertTrue(parseServerFrame("""{"t":"pane_read","reqId":"r1","paneId":"w6:p1","source":"detection","text":"hi"}""") is ServerFrame.PaneRead)
        val e = parseServerFrame("""{"t":"error","reqId":"r2","code":"not_found","message":"nope"}""")
        assertEquals("not_found", (e as ServerFrame.ErrorFrame).code)
    }

    @Test fun parsesUnknownAndTypelessFramesSafely() {
        assertTrue(parseServerFrame("""{"t":"bogus"}""") is ServerFrame.Unknown)
        assertTrue(parseServerFrame("""{"x":1}""") is ServerFrame.Unknown)
        assertTrue(parseServerFrame("""{"t":"welcome","herdrVersion":"0.7.1"}""") is ServerFrame.Welcome)
        assertTrue(parseServerFrame("""{"t":"pong"}""") is ServerFrame.Pong)
    }

    @Test fun buildsSendText() {
        val json = ClientMsg.sendText("r2", "w6:p1", "y")
        // simplest assertion: it contains the expected keys
        assertTrue(json.contains("\"t\":\"send_text\""))
        assertTrue(json.contains("\"text\":\"y\""))
        assertTrue(json.contains("\"paneId\":\"w6:p1\""))
    }
}
