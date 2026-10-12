package dev.herdr.mobile

import dev.herdr.mobile.net.LimitWindow
import dev.herdr.mobile.net.Limits
import dev.herdr.mobile.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UsageMetersTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-08T17:27:00Z").toEpochMilli()

    @Test fun tones() {
        assertEquals(UsageTone.Normal, usageTone(59.9))
        assertEquals(UsageTone.Warn, usageTone(60.0))
        assertEquals(UsageTone.Warn, usageTone(84.9))
        assertEquals(UsageTone.Critical, usageTone(85.0))
    }

    @Test fun fiveHourCountdown() {
        assertEquals("↻ 2h13m", formatReset("five_hour", "2026-10-08T19:40:00Z", now, utc))
        assertEquals("↻ 13m", formatReset("five_hour", "2026-10-08T17:40:00Z", now, utc))
    }

    @Test fun sevenDayClock() {
        assertEquals("↻ Thu 14:00", formatReset("seven_day", "2026-10-15T14:00:00Z", now, utc))
    }

    @Test fun offsetTimestampsParse() {
        assertEquals("↻ 2h13m", formatReset("five_hour", "2026-10-08T22:40:00+03:00", now, utc))
    }

    @Test fun unknownOrPastResetIsBlank() {
        assertEquals("", formatReset("five_hour", null, now, utc))
        assertEquals("", formatReset("five_hour", "garbage", now, utc))
        assertEquals("", formatReset("five_hour", "2026-10-08T17:00:00Z", now, utc))
    }

    @Test fun expiry() {
        assertTrue(windowExpired("2026-10-08T17:00:00Z", now))
        assertFalse(windowExpired("2026-10-08T19:40:00Z", now))
        assertFalse(windowExpired(null, now))
    }

    @Test fun staleness() {
        assertFalse(limitsStale(now - 5 * 60_000, now))
        assertTrue(limitsStale(now - 5 * 60_000 - 1, now))
    }

    @Test fun windowsAreFiveHourThenSevenDayOnly() {
        val l = Limits(listOf(LimitWindow("seven_day", 18.0), LimitWindow("spend_limit", 3.0), LimitWindow("five_hour", 42.0)), now)
        assertEquals(listOf("five_hour", "seven_day"), limitWindows(l).map { it.kind })
        assertTrue(limitWindows(null).isEmpty())
    }

    @Test fun limitTextRoundsAndShowsReset() {
        assertEquals("5h 42% ↻ 2h13m", limitText(LimitWindow("five_hour", 42.4, "2026-10-08T19:40:00Z"), now, utc))
        assertEquals("7d 18%", limitText(LimitWindow("seven_day", 18.0, null), now, utc))
    }

    @Test fun expiredWindowShowsDash() {
        assertEquals("5h —", limitText(LimitWindow("five_hour", 97.0, "2026-10-08T17:00:00Z"), now, utc))
    }

    @Test fun limitsWorthShowingHidesAnOldFullyExpiredReading() {
        val past5h = LimitWindow("five_hour", 42.0, "2026-10-08T17:00:00Z")
        val past7d = LimitWindow("seven_day", 18.0, "2026-10-08T12:00:00Z")
        val live7d = LimitWindow("seven_day", 18.0, "2026-10-15T14:00:00Z")
        val old = now - 3 * 24 * 3_600_000L
        val fresh = now - 60_000L
        assertFalse(limitsWorthShowing(null, now))
        assertFalse(limitsWorthShowing(Limits(emptyList(), fresh), now))
        assertFalse(limitsWorthShowing(Limits(listOf(past5h, past7d), old), now))
        // Every window expired but the reading is fresh: still shown ("5h —").
        assertTrue(limitsWorthShowing(Limits(listOf(past5h, past7d), fresh), now))
        // Stale but one window still running: shown, dimmed.
        assertTrue(limitsWorthShowing(Limits(listOf(past5h, live7d), old), now))
        // A window with no reset time never counts as expired.
        assertTrue(limitsWorthShowing(Limits(listOf(LimitWindow("five_hour", 10.0, null)), old), now))
    }
}
