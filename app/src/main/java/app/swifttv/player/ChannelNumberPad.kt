package app.swifttv.player

import android.view.KeyEvent

data class NumberPadPreview(
    val digits: String,
    val matchedChannel: Channel?,
    val playable: Boolean
)

/**
 * Pure numeric keypad channel resolver for TV remotes (0–9).
 * Prioritizes explicit numbered CCTV stations (e.g. "6" -> CCTV-6, "13" -> CCTV-13) when present
 * in the active list, then falls back to 1-based channel list sequence numbering.
 */
class ChannelNumberPad(private val maxDigits: Int = 3) {
    var digits: String = ""
        private set

    val active: Boolean get() = digits.isNotEmpty()

    fun appendDigit(digit: Int, channels: List<Channel>): NumberPadPreview? {
        if (digit !in 0..9) return null
        if (digits.isEmpty() && digit == 0) return null
        if (digits.length >= maxDigits) digits = ""
        digits += digit.toString()
        return resolve(digits, channels)
    }

    fun clear() {
        digits = ""
    }

    fun currentPreview(channels: List<Channel>): NumberPadPreview? =
        if (digits.isEmpty()) null else resolve(digits, channels)

    companion object {
        fun digitFromKeyCode(keyCode: Int): Int? = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }

        fun resolve(digits: String, channels: List<Channel>): NumberPadPreview {
            val number = digits.toIntOrNull()
            if (number == null || number <= 0) return NumberPadPreview(digits, null, false)
            // 1. Direct numbered CCTV match if present in the active channel catalog
            if (number in 1..17) {
                val cctv = channels.firstOrNull { it.id == "cctv-$number" }
                if (cctv != null) return NumberPadPreview(digits, cctv, cctv.sources.isNotEmpty())
            }
            // 2. Fallback to 1-based playable channel sequence, then full channel sequence
            val playable = channels.filter { it.sources.isNotEmpty() }
            val byPlayableIndex = playable.getOrNull(number - 1)
            if (byPlayableIndex != null) return NumberPadPreview(digits, byPlayableIndex, true)
            val byRawIndex = channels.getOrNull(number - 1)
            return NumberPadPreview(digits, byRawIndex, byRawIndex?.sources?.isNotEmpty() == true)
        }
    }
}
