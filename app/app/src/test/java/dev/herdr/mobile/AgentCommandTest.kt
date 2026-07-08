package dev.herdr.mobile

import dev.herdr.mobile.ui.parseAgentCommand
import org.junit.Assert.*
import org.junit.Test

class AgentCommandTest {
    @Test fun singleBinary() {
        val c = parseAgentCommand("claude")
        assertEquals("claude", c.name)
        assertEquals(listOf("claude"), c.argv)
    }

    @Test fun binaryWithArgs() {
        val c = parseAgentCommand("claude --model opus")
        assertEquals("claude", c.name)
        assertEquals(listOf("claude", "--model", "opus"), c.argv)
    }

    @Test fun trimsAndCollapsesWhitespaceAndUsesBasename() {
        val c = parseAgentCommand("  /usr/bin/htop  -d 5 ")
        assertEquals("htop", c.name)
        assertEquals(listOf("/usr/bin/htop", "-d", "5"), c.argv)
    }
}
