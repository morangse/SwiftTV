package app.swifttv.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class CinemaBlurMaterialTest {
    @Test fun darkGlassMaterialClampsWhiteSubtitlesWhilePreservingColorVibrancy() {
        val pixels = intArrayOf(
            0xFFFFFFFF.toInt(), // Pure glaring white subtitle
            0xFF000000.toInt(), // Pitch black letterbox
            0xFF5A963C.toInt()  // Broadcast foliage green
        )
        CinemaBlurEngine.applyDarkGlassMaterial(pixels)

        // Pure white subtitle is compressed into calm dark slate (< 100 per channel) for >8:1 white ink contrast
        val wr = (pixels[0] shr 16) and 0xFF
        val wg = (pixels[0] shr 8) and 0xFF
        val wb = pixels[0] and 0xFF
        assertTrue("Expected white highlight to be compressed below 100, got ($wr,$wg,$wb)", wr in 50..96 && wg in 50..96 && wb in 50..99)

        // Pitch black lifts gently to deep obsidian crystal
        val br = (pixels[1] shr 16) and 0xFF
        val bg = (pixels[1] shr 8) and 0xFF
        val bb = pixels[1] and 0xFF
        assertTrue("Expected deep cool-neutral obsidian base, got ($br,$bg,$bb)", br in 8..16 && bb >= br)

        // Foliage green retains distinct green dominance instead of turning flat neutral grey
        val gr = (pixels[2] shr 16) and 0xFF
        val gg = (pixels[2] shr 8) and 0xFF
        val gb = pixels[2] and 0xFF
        assertTrue("Expected vibrant green dominance ($gr, $gg, $gb)", gg > gr + 12 && gg > gb + 8)
    }

    @Test fun threePassSeparableBlurSmoothsSharpSubtitleImpulse() {
        val w = 16
        val h = 16
        val data = IntArray(w * h) { 0xFF10141C.toInt() }
        val tmp = IntArray(w * h)
        data[8 * w + 8] = 0xFFFFFFFF.toInt()

        CinemaBlurEngine.blurSeparable(data, tmp, w, h, radius = 3)
        CinemaBlurEngine.blurSeparable(data, tmp, w, h, radius = 2)
        CinemaBlurEngine.blurSeparable(data, tmp, w, h, radius = 2)

        val peakR = (data[8 * w + 8] shr 16) and 0xFF
        val neighborR = (data[8 * w + 9] shr 16) and 0xFF
        assertTrue("Sharp single-pixel impulse should diffuse smoothly: peak=$peakR neighbor=$neighborR", peakR < 60 && neighborR >= 16)
    }
}
