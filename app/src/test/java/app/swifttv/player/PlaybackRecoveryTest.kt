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
        val hd = StreamSpec(1920, 1080, MimeTypes.VIDEO_H264, 25f, MimeTypes.AUDIO_AAC, "IPv4 线路", 420L)
        assertEquals("1080p · H.264 · IPv4 线路 · 420ms", hd.badge())
        val uhd = StreamSpec(3840, 2160, MimeTypes.VIDEO_H265, 50f, MimeTypes.AUDIO_AAC, "IPv6 线路", 0L)
        assertEquals("4K · HEVC · IPv6 线路", uhd.badge())
        assertEquals("", StreamSpec().badge())
    }

    @Test fun fastFailoverTimeoutsAndExponentialCooldownAreBounded() {
        assertEquals(3_500L, PlaybackRecoveryPolicy.NO_TRACK_TIMEOUT_MS)
        assertEquals(7_500L, PlaybackRecoveryPolicy.FIRST_FRAME_TIMEOUT_MS)
        assertEquals(5_000L, PlaybackRecoveryPolicy.REBUFFER_TIMEOUT_MS)
        assertEquals(6_000L, PlaybackRecoveryPolicy.NO_PROGRESS_TIMEOUT_MS)
        assertEquals(120_000L, PlaybackRecoveryPolicy.cooldownMs(1))
        assertEquals(600_000L, PlaybackRecoveryPolicy.cooldownMs(2))
        assertEquals(1_800_000L, PlaybackRecoveryPolicy.cooldownMs(3))
        assertEquals(1_800_000L, PlaybackRecoveryPolicy.cooldownMs(8))
        assertEquals(PlaybackRecoveryPolicy.UDP_NO_TRACK_TIMEOUT_MS, PlaybackRecoveryPolicy.noTrackTimeoutMs("udp://239.1.1.1:5000"))
        assertEquals(PlaybackRecoveryPolicy.UDP_FIRST_FRAME_TIMEOUT_MS, PlaybackRecoveryPolicy.firstFrameTimeoutMs("http://192.168.1.1:4022/rtp/239.1.1.1:5000"))
        assertEquals(PlaybackRecoveryPolicy.NO_TRACK_TIMEOUT_MS, PlaybackRecoveryPolicy.noTrackTimeoutMs("https://cdn.example/live.m3u8"))
    }

    @Test fun rtpPacketExtractsCleanMpegTsPayloadAndPassesRawTsThrough() {
        val tsPayload = byteArrayOf(0x47, 0x40, 0x00, 0x10, 0x01, 0x02)
        assertArrayEquals(tsPayload, RtpPacket.extractPayload(tsPayload, tsPayload.size, "rtp"))

        val rtpHeader = ByteArray(12).apply {
            this[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
            this[1] = 33.toByte()   // MP2T payload type
        }
        val combined = rtpHeader + tsPayload
        assertArrayEquals(tsPayload, RtpPacket.extractPayload(combined, combined.size, "rtp"))
        assertArrayEquals(tsPayload, RtpPacket.extractPayload(combined, combined.size, "udp"))
    }
}
