package app.swifttv.player

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class ChannelNumberPadTest {
    private fun ch(id: String, name: String = id.uppercase(), playable: Boolean = true) =
        Channel(id, name, "央视", if (playable) listOf(Entry(id, name, "央视", "https://example.test/$id.m3u8")) else emptyList(),
            unavailableReason = if (playable) "" else "4K HEVC · 本机无硬解")

    private val lineup = listOf(
        ch("cctv-1"), ch("cctv-2"), ch("cctv-3"), ch("cctv-4"), ch("cctv-5"), ch("cctv-5+"),
        ch("cctv-6"), ch("cctv-7"), ch("cctv-8"), ch("cctv-9"), ch("cctv-10"), ch("cctv-11"),
        ch("cctv-12"), ch("cctv-13"), ch("cctv-14"), ch("cctv-15"), ch("cctv-16", playable = false), ch("cctv-17"),
        ch("hunan", "湖南卫视"), ch("zhejiang", "浙江卫视")
    )

    @Test fun numberedCctvMatchesStationNumberEvenAfterFivePlus() {
        val pad = ChannelNumberPad()
        assertEquals("cctv-6", pad.appendDigit(6, lineup)?.matchedChannel?.id)
        pad.clear()
        pad.appendDigit(1, lineup)
        val thirteen = pad.appendDigit(3, lineup)!!
        assertEquals("13", thirteen.digits)
        assertEquals("cctv-13", thirteen.matchedChannel?.id)
        assertTrue(thirteen.playable)
    }

    @Test fun unplayableStationReportsUnplayableWithoutSilentlyTuningWrongChannel() {
        val pad = ChannelNumberPad()
        pad.appendDigit(1, lineup)
        val sixteen = pad.appendDigit(6, lineup)!!
        assertEquals("cctv-16", sixteen.matchedChannel?.id)
        assertFalse(sixteen.playable)
    }

    @Test fun satelliteAndCustomChannelsResolveByOneBasedPlayableIndex() {
        val pad = ChannelNumberPad()
        pad.appendDigit(1, lineup)
        val eighteen = pad.appendDigit(8, lineup)!!
        // 17 playable CCTV stations (1..5, 5+, 6..15, 17) precede Hunan (#18)
        assertEquals("hunan", eighteen.matchedChannel?.id)
        assertTrue(eighteen.playable)

        val custom = (1..5).map { ch("id:custom-$it", "频道 $it") }
        val customPad = ChannelNumberPad()
        assertEquals("id:custom-3", customPad.appendDigit(3, custom)?.matchedChannel?.id)
    }

    @Test fun ignoresLeadingZeroAndMapsRemoteAndNumpadKeyCodes() {
        val pad = ChannelNumberPad()
        assertNull(pad.appendDigit(0, lineup))
        assertFalse(pad.active)
        assertEquals(7, ChannelNumberPad.digitFromKeyCode(KeyEvent.KEYCODE_7))
        assertEquals(4, ChannelNumberPad.digitFromKeyCode(KeyEvent.KEYCODE_NUMPAD_4))
        assertNull(ChannelNumberPad.digitFromKeyCode(KeyEvent.KEYCODE_DPAD_UP))
    }
}
