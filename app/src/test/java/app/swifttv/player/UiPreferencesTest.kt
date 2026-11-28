package app.swifttv.player

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class UiPreferencesTest {
    @Test fun channelSwitchDirectionDefaultsToNaturalDownNextUpPrevAndSupportsInversion() {
        assertEquals(ChannelSwitchDirection.NATURAL, ChannelSwitchDirection.resolve(null))
        assertEquals(ChannelSwitchDirection.NATURAL, ChannelSwitchDirection.resolve("unknown"))
        assertEquals(ChannelSwitchDirection.INVERTED, ChannelSwitchDirection.resolve("inverted"))

        assertEquals(1, ChannelSwitchDirection.NATURAL.deltaForKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(-1, ChannelSwitchDirection.NATURAL.deltaForKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(1, ChannelSwitchDirection.NATURAL.deltaForKey(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(-1, ChannelSwitchDirection.NATURAL.deltaForKey(KeyEvent.KEYCODE_CHANNEL_DOWN))

        assertEquals(-1, ChannelSwitchDirection.INVERTED.deltaForKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(1, ChannelSwitchDirection.INVERTED.deltaForKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(1, ChannelSwitchDirection.INVERTED.deltaForKey(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(-1, ChannelSwitchDirection.INVERTED.deltaForKey(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertNull(ChannelSwitchDirection.NATURAL.deltaForKey(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test fun stepCursorAnchorsToPlayingChannelAfterPauseAndSupportsRapidWrapAround() {
        // Stale cursor (4) ignored when not rapidly zapping -> anchored to playingIndex (1)
        assertEquals(2, ChannelSwitchDirection.stepCursor(4, playingIndex = 1, size = 5, delta = 1, rapidZapping = false))
        assertEquals(0, ChannelSwitchDirection.stepCursor(4, playingIndex = 1, size = 5, delta = -1, rapidZapping = false))

        // Rapid zapping steps continuously from currentCursor
        assertEquals(0, ChannelSwitchDirection.stepCursor(4, playingIndex = 1, size = 5, delta = 1, rapidZapping = true))
        assertEquals(4, ChannelSwitchDirection.stepCursor(0, playingIndex = 2, size = 5, delta = -1, rapidZapping = true))

        // Single-channel or empty lists are bounded
        assertEquals(0, ChannelSwitchDirection.stepCursor(0, playingIndex = 0, size = 1, delta = 1, rapidZapping = true))
        assertEquals(-1, ChannelSwitchDirection.stepCursor(0, playingIndex = 0, size = 0, delta = 1, rapidZapping = false))
    }

    @Test fun fontScaleModeCyclesThroughAllFourElderlyFriendlyTiers() {
        assertEquals(FontScaleMode.STANDARD, FontScaleMode.resolve(null))
        assertEquals(FontScaleMode.STANDARD, FontScaleMode.resolve("bad"))
        assertEquals(FontScaleMode.LARGE, FontScaleMode.STANDARD.next())
        assertEquals(FontScaleMode.ELDERLY, FontScaleMode.LARGE.next())
        assertEquals(FontScaleMode.EXTRA_LARGE, FontScaleMode.ELDERLY.next())
        assertEquals(FontScaleMode.STANDARD, FontScaleMode.EXTRA_LARGE.next())
        assertEquals(1.0f, FontScaleMode.STANDARD.multiplier, 0.001f)
        assertEquals(1.18f, FontScaleMode.LARGE.multiplier, 0.001f)
        assertEquals(1.35f, FontScaleMode.ELDERLY.multiplier, 0.001f)
        assertEquals(1.55f, FontScaleMode.EXTRA_LARGE.multiplier, 0.001f)
    }

    @Test fun subscriptionAutoRefreshPolicyRespectsColdLaunchToggleAndWarmCooldown() {
        val httpFresh = Subscription("http-fresh", "远程源A", "https://example.com/a.m3u", updatedAt = 1_000_000L)
        val httpOld = Subscription("http-old", "远程源B", "http://example.org/b.m3u", updatedAt = 100_000L)
        val local = Subscription("local", "本地推送", "", updatedAt = 0L)
        val subs = listOf(httpFresh, httpOld, local)
        val now = 1_000_000L + 5 * 60 * 1000L // 5 min after httpFresh, >15 min after httpOld

        // Cold launch with toggle ON: refreshes all HTTP/HTTPS subscriptions even if recently updated
        assertEquals(
            listOf("http-fresh", "http-old"),
            SubscriptionRefreshPolicy.selectDue(subs, now, explicit = false, autoRefreshEnabled = true, isColdLaunch = true).map { it.id }
        )
        // Cold launch with toggle OFF: skips automatic refresh
        assertTrue(
            SubscriptionRefreshPolicy.selectDue(subs, now, explicit = false, autoRefreshEnabled = false, isColdLaunch = true).isEmpty()
        )
        // Warm resume with toggle ON: only refreshes subscriptions past the 15-minute cooldown
        assertEquals(
            listOf("http-old"),
            SubscriptionRefreshPolicy.selectDue(subs, now, explicit = false, autoRefreshEnabled = true, isColdLaunch = false).map { it.id }
        )
        // Explicit manual refresh works even when auto-refresh toggle is OFF, still excludes local non-HTTP subscriptions
        assertEquals(
            listOf("http-fresh", "http-old"),
            SubscriptionRefreshPolicy.selectDue(subs, now, explicit = true, autoRefreshEnabled = false, isColdLaunch = false).map { it.id }
        )
    }
}
