package dev.herdr.mobile

import dev.herdr.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class ServersTest {
    private val a = Server("a", "laptop", "ws://100.1.1.1:8787")
    private val b = Server("b", "devbox", "ws://100.2.2.2:8787")

    @Test fun codecRoundTrips() {
        assertEquals(listOf(a, b), decodeServers(encodeServers(listOf(a, b))))
    }

    @Test fun missingOrCorruptListDecodesEmpty() {
        assertEquals(emptyList<Server>(), decodeServers(null))
        assertEquals(emptyList<Server>(), decodeServers(""))
        assertEquals(emptyList<Server>(), decodeServers("{not json"))
    }

    @Test fun migratesUrlAndEndpointToTheDefaultInstance() {
        val m = migrateLegacy("ws://100.1.1.1:8787", "https://up.example/x")
        assertEquals(listOf(Server("default", "100.1.1.1:8787", "ws://100.1.1.1:8787")), m.servers)
        assertEquals("default", m.activeId)
        assertEquals("https://up.example/x", m.endpoint)
    }

    @Test fun migratesUrlWithoutEndpoint() {
        val m = migrateLegacy("ws://100.1.1.1:8787", null)
        assertEquals(1, m.servers.size)
        assertNull(m.endpoint)
    }

    @Test fun freshInstallMigratesToNothing() {
        assertEquals(Migration(emptyList(), null, null), migrateLegacy(null, null))
    }

    @Test fun renameTrimsAndBlankFallsBackToHost() {
        assertEquals("work", listOf(a, b).withRenamed("a", "  work ")[0].name)
        assertEquals("100.1.1.1:8787", listOf(a, b).withRenamed("a", "   ")[0].name)
        assertEquals(b, listOf(a, b).withRenamed("a", "x")[1])
    }

    @Test fun removingAnInactiveServerKeepsTheActiveOne() {
        assertEquals(Removal(listOf(a), "a"), listOf(a, b).withRemoved("b", activeId = "a"))
    }

    @Test fun removingTheActiveServerActivatesTheFirstLeft() {
        assertEquals(Removal(listOf(b), "b"), listOf(a, b).withRemoved("a", activeId = "a"))
    }

    @Test fun removingTheLastServerLeavesNoneActive() {
        assertEquals(Removal(emptyList(), null), listOf(a).withRemoved("a", activeId = "a"))
    }

    @Test fun savedAtMatchesHostPortIgnoringSchemeAndCase() {
        assertEquals(a, listOf(a, b).savedAt("ws://100.1.1.1:8787"))
        val named = Server("c", "box", "ws://MyBox:8787")
        assertEquals(named, listOf(named).savedAt("ws://mybox:8787/"))
        assertNull(listOf(a).savedAt("ws://100.1.1.1:8788"))
    }

    @Test fun openOnTheActiveOrLegacyServerStays() {
        assertEquals(ServerOpen.Stay, resolveServerOpen("a", "a", listOf(a, b)))
        assertEquals(ServerOpen.Stay, resolveServerOpen(null, "a", listOf(a, b)))
    }

    @Test fun openOnAnotherServerSwitches() {
        assertEquals(ServerOpen.Switch(b), resolveServerOpen("b", "a", listOf(a, b)))
    }

    @Test fun openOnARemovedServerIsUnknown() {
        assertEquals(ServerOpen.Unknown, resolveServerOpen("gone", "a", listOf(a, b)))
    }
}
