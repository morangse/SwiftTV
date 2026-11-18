package app.swifttv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupSourceCheckTest {
    @Test fun waitIsMeasuredAcrossAllStartupAttempts() {
        val check = StartupSourceCheck(1000)
        check.attempting(true)
        assertNull(check.check(9000, exhausted = false, online = true))
        check.attempting(false)
        assertNull(check.check(15_999, exhausted = false, online = true))
        assertEquals(StartupSourceIssue.REGIONAL_NO_PICTURE, check.check(16_000, exhausted = false, online = true))
        assertNull(check.check(30_000, exhausted = true, online = true))
    }

    @Test fun definitiveExhaustionDoesNotRequireAnExtraWait() {
        val check = StartupSourceCheck(0)
        assertNull(check.check(2000, exhausted = true, online = true))
        check.attempting(false)
        assertEquals(StartupSourceIssue.NO_PICTURE, check.check(2000, exhausted = true, online = true))
    }

    @Test fun offlineAdviceDoesNotRecommendAPlaylistUntilReconnected() {
        val check = StartupSourceCheck(0)
        check.attempting(true)
        assertEquals(StartupSourceIssue.OFFLINE, check.check(16_000, exhausted = false, online = false))
        assertNull(check.check(17_000, exhausted = true, online = false))
        assertEquals(StartupSourceIssue.REGIONAL_NO_PICTURE, check.check(20_000, exhausted = true, online = true))
    }
}
