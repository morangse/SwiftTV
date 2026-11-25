package app.swifttv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupSourceCheckTest {
    @Test fun waitIsMeasuredAcrossAllStartupAttempts() {
        val check = StartupSourceCheck(1000)
        check.attempting()
        assertNull(check.check(9_000, exhausted = false, online = true))
        assertNull(check.check(12_999, exhausted = false, online = true))
        assertEquals(StartupSourceIssue.NO_PICTURE, check.check(13_000, exhausted = false, online = true))
        assertNull(check.check(30_000, exhausted = true, online = true))
    }

    @Test fun definitiveExhaustionDoesNotRequireAnExtraWait() {
        val check = StartupSourceCheck(0)
        assertNull(check.check(2_000, exhausted = true, online = true))
        check.attempting()
        assertEquals(StartupSourceIssue.NO_PICTURE, check.check(2_000, exhausted = true, online = true))
    }

    @Test fun offlineAdviceWaitsUntilReconnectedBeforeSignalingNoPicture() {
        val check = StartupSourceCheck(0)
        check.attempting()
        assertEquals(StartupSourceIssue.OFFLINE, check.check(13_000, exhausted = false, online = false))
        assertNull(check.check(17_000, exhausted = true, online = false))
        assertEquals(StartupSourceIssue.NO_PICTURE, check.check(20_000, exhausted = true, online = true))
    }
}
