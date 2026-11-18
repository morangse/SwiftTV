package app.swifttv.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

class HardwareCodecPolicyTest {
    @Test fun rejectsGoogleAndAndroidSoftwareDecodersEvenWithoutApi29Flags() {
        assertFalse(HardwareCodecPolicy.isHardwareDecoder("OMX.google.hevc.decoder", false))
        assertFalse(HardwareCodecPolicy.isHardwareDecoder("c2.android.hevc.decoder", false))
        assertFalse(HardwareCodecPolicy.isHardwareDecoder("OMX.sec.hevc.sw.dec", false))
        assertTrue(HardwareCodecPolicy.isHardwareDecoder("OMX.MTK.VIDEO.DECODER.HEVC", false))
        assertTrue(HardwareCodecPolicy.isHardwareDecoder("c2.qti.hevc.decoder", false, hardwareFlag = true, softwareFlag = false))
        assertFalse(HardwareCodecPolicy.isHardwareDecoder("c2.qti.hevc.encoder", true, hardwareFlag = true, softwareFlag = false))
    }

    @Test fun unlocks4kHevc50OnlyOnNonLowRamHardwareCapableDevices() {
        val sony4k = listOf(
            CodecDescriptor("OMX.MTK.VIDEO.DECODER.HEVC", false, true, false, listOf(MimeTypes.VIDEO_H265), supports4k50 = true, supports4k30 = true, supports1080p = true)
        )
        val profile4k = HardwareCodecPolicy.evaluate(sony4k, lowRam = false)
        assertTrue(profile4k.supports4kHevc50)
        assertEquals(3840, profile4k.maxWidth)
        assertEquals(2160, profile4k.maxHeight)

        // Low-RAM devices stay capped at 1080p even if a codec advertises 4K
        val lowRamProfile = HardwareCodecPolicy.evaluate(sony4k, lowRam = true)
        assertFalse(lowRamProfile.supports4kHevc50)
        assertEquals(1920, lowRamProfile.maxWidth)
        assertEquals(1080, lowRamProfile.maxHeight)

        // Software-only 4K HEVC never unlocks 4K hardware profile
        val swOnly = listOf(
            CodecDescriptor("c2.android.hevc.decoder", false, false, true, listOf(MimeTypes.VIDEO_H265), supports4k50 = true, supports4k30 = true, supports1080p = true)
        )
        val swProfile = HardwareCodecPolicy.evaluate(swOnly, lowRam = false)
        assertFalse(swProfile.supports4kHevc50)
        assertEquals(1920, swProfile.maxWidth)
    }
}
