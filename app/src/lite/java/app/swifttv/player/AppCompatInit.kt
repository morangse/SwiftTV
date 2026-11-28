package app.swifttv.player

import android.content.Context
import androidx.multidex.MultiDex

internal object AppCompatInit {
    fun attachBaseContext(base: Context) {
        MultiDex.install(base)
    }
}
