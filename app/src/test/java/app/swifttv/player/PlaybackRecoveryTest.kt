package app.swifttv.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun behindLiveWindowResyncsUpToTwoTimesBeforeFailing() {
        assertEquals(
            RecoveryAction.RESYNC_LIVE_EDGE,
            PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null, true, 2_000L, 0, 0, false)
        )
        assertEquals(
            RecoveryAction.RESYNC_LIVE_EDGE,
            PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null, false, 0L, 1, 0, false)
        )
        assertEquals(
            RecoveryAction.FAIL_AND_COOL,
            PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null, false, 0L, 2, 0, false)
        )
    }

    @Test fun sustainedPlaybackAllowsOneTransientReconnectExceptOnPermanentErrors() {
        assertEquals(
            RecoveryAction.RECONNECT_SAME_SOURCE,
            PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null, true, 15_000L, 0, 0, false)
        )
        // Second drop on the same attempt escalates to backup/cooldown
        assertEquals(
            RecoveryAction.FAIL_AND_COOL,
            PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null, true, 15_000L, 0, 1, false)
        )
        // Permanent HTTP 403/404 or decoder incapability never wastes time reconnecting the same URL
        assertEquals(
            RecoveryAction.FAIL_AND_COOL,
            PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, true, 60_000L, 0, 0, false)
        )
        assertEquals(
            RecoveryAction.FAIL_AND_COOL,
            PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null, true, 60_000L, 0, 0, false)
        )
        // Probing never auto-reconnects
        assertEquals(
            RecoveryAction.FAIL_AND_COOL,
            PlaybackRecoveryPolicy.decide(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null, true, 30_000L, 0, 0, true)
        )
    }

    @Test fun streamSpecBadgeFormatsResolutionCodecRouteAndFirstFrameCleanly() {
        val hd = StreamSpec(1920, 1080, MimeTypes.VIDEO_H264, 25f, MimeTypes.AUDIO_AAC, "河南移动", 420L)
        assertEquals("1080p · H.264 · 河南移动 · 420ms", hd.badge())
        val uhd = StreamSpec(3840, 2160, MimeTypes.VIDEO_H265, 50f, MimeTypes.AUDIO_AAC, "河南移动", 0L)
        assertEquals("4K · HEVC · 河南移动", uhd.badge())
        assertEquals("", StreamSpec().badge())
    }
}
