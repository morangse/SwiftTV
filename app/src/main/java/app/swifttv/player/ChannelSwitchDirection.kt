package app.swifttv.player

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit

enum class ChannelSwitchDirection(val key: String, val title: String) {
    NATURAL("natural", "↓ 下台 / ↑ 上台"),
    INVERTED("inverted", "↑ 下台 / ↓ 上台");

    fun next(): ChannelSwitchDirection = entries[(ordinal + 1) % entries.size]

    fun deltaForKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP -> 1
        KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
        KeyEvent.KEYCODE_DPAD_DOWN -> if (this == NATURAL) 1 else -1
        KeyEvent.KEYCODE_DPAD_UP -> if (this == NATURAL) -1 else 1
        else -> null
    }

    companion object {
        fun resolve(saved: String?): ChannelSwitchDirection =
            entries.firstOrNull { it.key == saved } ?: NATURAL

        fun read(context: Context): ChannelSwitchDirection {
            val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            return resolve(prefs.getString("channelSwitchDirection", null))
        }

        fun save(context: Context, mode: ChannelSwitchDirection) {
            context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit {
                putString("channelSwitchDirection", mode.key)
            }
        }

        fun stepCursor(
            currentCursor: Int,
            playingIndex: Int,
            size: Int,
            delta: Int,
            rapidZapping: Boolean
        ): Int {
            if (size <= 0) return -1
            if (size == 1) return 0
            val base = if (rapidZapping && currentCursor in 0 until size) {
                currentCursor
            } else {
                playingIndex.coerceIn(0, size - 1)
            }
            return (base + delta + size) % size
        }
    }
}
