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
}
