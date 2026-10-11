package dev.herdr.mobile

import dev.herdr.mobile.push.notificationTitle
import dev.herdr.mobile.push.openRequestCode
import org.junit.Assert.*
import org.junit.Test

class NotificationsTest {
    @Test fun titleIsPrefixedOnlyWithAServerName() {
        assertEquals("herdr needs you", notificationTitle("herdr needs you", null))
        assertEquals("devbox · herdr needs you", notificationTitle("herdr needs you", "devbox"))
    }

    @Test fun samePaneOnTwoServersGetsDistinctRequestCodes() {
        assertNotEquals(openRequestCode("a", "w1:p1"), openRequestCode("b", "w1:p1"))
        assertEquals(openRequestCode("a", "w1:p1"), openRequestCode("a", "w1:p1"))
    }
}
