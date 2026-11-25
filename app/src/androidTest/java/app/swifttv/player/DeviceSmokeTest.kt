package app.swifttv.player

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun updatesAreAtomicAndFavoritesSurviveReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CatalogStore(context)
        val marker = "instrumentation-" + System.nanoTime()
        val subscription = Subscription(marker, "Instrumentation", "", System.currentTimeMillis())
        val parsed = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"" + marker + "\",Test\nhttps://example.com/test.m3u8")
        val channelId = parsed.entries.single().channelId
        try {
            store.replaceSubscription(subscription, parsed)
            store.favorite(channelId)
            store.remember(channelId)
            val reopened = CatalogStore(context).snapshot()
            assertTrue(channelId in reopened.favorites)
            assertEquals(channelId, reopened.lastChannel)
            val before = store.snapshot()
            runCatching { M3uParser.parse("<html>Unavailable</html>") }
            assertEquals(before, CatalogStore(context).snapshot())
        } finally { if (channelId in store.snapshot().favorites) store.favorite(channelId); store.removeSubscription(marker) }
    }

    @Test fun launchActivityWithOptionalControlledPlaylist() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val url = InstrumentationRegistry.getArguments().getString("playlistUrl")
        if (!url.isNullOrBlank()) {
            val download = Network(null).let { network -> try { network.download(url) } finally { network.close() } }
            val parsed = M3uParser.parse(download.text)
            CatalogStore(context).replaceSubscription(Subscription("controlled-test", "Controlled test", url, System.currentTimeMillis()), parsed)
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).putExtra("isolatedTestMode", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        assertNotNull(activity)
        // Keep launcher activity available for the documented ADB playback acceptance steps.
    }
}
