package app.swifttv.player

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes

data class CodecDescriptor(
    val name: String,
    val isEncoder: Boolean,
    val hardwareFlag: Boolean?,
    val softwareFlag: Boolean?,
    val mimeTypes: List<String>,
    val supports4k50: Boolean,
    val supports4k30: Boolean,
    val supports1080p: Boolean
)

data class DeviceCodecProfile(
    val supports4kHevc50: Boolean,
    val supports4kVideo: Boolean,
    val supports1080pHevc: Boolean,
    val lowRam: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val hevcHardwareDecoders: List<String>
)

/** Pure capability rules so 4K/HEVC gating is deterministic and unit-testable on the JVM. */
object HardwareCodecPolicy {
    private val softwarePrefixes = listOf("omx.google.", "c2.android.", "omx.ffmpeg.", "omx.sec.avc.sw.", "omx.sec.hevc.sw.")

    fun isHardwareDecoder(name: String, isEncoder: Boolean, hardwareFlag: Boolean? = null, softwareFlag: Boolean? = null): Boolean {
        if (isEncoder) return false
        if (softwareFlag == true) return false
        if (hardwareFlag != null) return hardwareFlag
        val lower = name.lowercase(java.util.Locale.ROOT)
        if (softwarePrefixes.any { lower.startsWith(it) }) return false
        if (lower.contains(".sw.") || lower.endsWith(".sw") || lower.contains("software")) return false
        return true
    }

    fun evaluate(codecs: List<CodecDescriptor>, lowRam: Boolean): DeviceCodecProfile {
        if (lowRam) {
            return DeviceCodecProfile(
                supports4kHevc50 = false,
                supports4kVideo = false,
                supports1080pHevc = codecs.any { isHardware(it) && it.mimeTypes.any { m -> m.equals(MimeTypes.VIDEO_H265, true) } && it.supports1080p },
                lowRam = true,
                maxWidth = 1920,
                maxHeight = 1080,
                hevcHardwareDecoders = emptyList()
            )
        }
        val hwDecoders = codecs.filter { isHardware(it) }
        val hevcHw = hwDecoders.filter { d -> d.mimeTypes.any { it.equals(MimeTypes.VIDEO_H265, true) } }
        val supports4kHevc50 = hevcHw.any { it.supports4k50 }
        val supports4kAny = hwDecoders.any { it.supports4k30 || it.supports4k50 }
        val supports1080pHevc = hevcHw.any { it.supports1080p }
        return DeviceCodecProfile(
            supports4kHevc50 = supports4kHevc50,
            supports4kVideo = supports4kAny,
            supports1080pHevc = supports1080pHevc,
            lowRam = false,
            maxWidth = if (supports4kAny) 3840 else 1920,
            maxHeight = if (supports4kAny) 2160 else 1080,
            hevcHardwareDecoders = hevcHw.map { it.name }
        )
    }

    private fun isHardware(d: CodecDescriptor): Boolean =
        isHardwareDecoder(d.name, d.isEncoder, d.hardwareFlag, d.softwareFlag)
}

object HardwareCodecProbe {
    @Volatile private var cached: DeviceCodecProfile? = null

    fun profile(context: Context): DeviceCodecProfile {
        cached?.let { return it }
        val computed = probeDevice(context.applicationContext)
        cached = computed
        return computed
    }

    fun supportsHardwareFormat(mime: String?, width: Int, height: Int, frameRate: Float): Boolean = runCatching {
        if (mime.isNullOrBlank() || width <= 0 || height <= 0) return@runCatching false
        val rate = if (frameRate > 0f && frameRate.isFinite()) frameRate.toDouble() else 25.0
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
            isHardwareInfo(codec) &&
                codec.supportedTypes.any { it.equals(mime, true) } &&
                codec.getCapabilitiesForType(mime).videoCapabilities?.let { vc ->
                    vc.areSizeAndRateSupported(width, height, rate) || vc.isSizeSupported(width, height) && rate <= 30.0
                } == true
        }
    }.getOrDefault(false)

    private fun isHardwareInfo(codec: MediaCodecInfo): Boolean {
        val hw = if (Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated else null
        val sw = if (Build.VERSION.SDK_INT >= 29) codec.isSoftwareOnly else null
        return HardwareCodecPolicy.isHardwareDecoder(codec.name, codec.isEncoder, hw, sw)
    }

    private fun probeDevice(context: Context): DeviceCodecProfile = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am?.let { it.isLowRamDevice || it.memoryClass <= 96 } ?: false
        val descriptors = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.mapNotNull { info ->
            if (info.isEncoder) return@mapNotNull null
            val videoTypes = info.supportedTypes.filter { it.startsWith("video/", true) }
            if (videoTypes.isEmpty()) return@mapNotNull null
            val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else null
            val sw = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else null
            var s4k50 = false
            var s4k30 = false
            var s1080 = false
            for (type in videoTypes) {
                val vc = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() ?: continue
                if (runCatching { vc.areSizeAndRateSupported(1920, 1080, 25.0) }.getOrDefault(false)) s1080 = true
                if (runCatching { vc.areSizeAndRateSupported(3840, 2160, 25.0) }.getOrDefault(false)) s4k30 = true
                if (runCatching { vc.areSizeAndRateSupported(3840, 2160, 50.0) }.getOrDefault(false)) s4k50 = true
            }
            CodecDescriptor(info.name, false, hw, sw, videoTypes, s4k50, s4k30, s1080)
        }
        HardwareCodecPolicy.evaluate(descriptors, lowRam)
    }.getOrElse {
        DeviceCodecProfile(false, false, false, false, 1920, 1080, emptyList())
    }
}
