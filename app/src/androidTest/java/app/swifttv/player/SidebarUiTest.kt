package app.swifttv.player

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Native UI integration tests for Scheme C (CinemaRibbonView) + Frosted Sheets + Numeric Pad. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SidebarUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var inputDisplayId = -1

    @Test fun remoteAndPointerNavigationPreservePlaybackUntilConfirmation() {
        val playlistUrl = InstrumentationRegistry.getArguments().getString("playlistUrl")
        assumeTrue("Pass -e playlistUrl with a controlled HLS M3U to run playback UI validation", !playlistUrl.isNullOrBlank())
        val network = Network(null)
        val source = try { M3uParser.parse(network.download(playlistUrl!!).text).entries.firstOrNull() }
            finally { network.close() }
        requireNotNull(source) { "Controlled playlist must contain at least one playable HLS entry" }
        val marker = "cinema-ui-" + UUID.randomUUID().toString()
        val ids = (1..24).map { "id:controlled-$marker-$it" }
        val entries = ids.mapIndexed { index, id ->
            Entry(id, "测试频道 " + (index + 1).toString().padStart(2, '0'), if (index < 12) "央视" else "卫视",
                android.net.Uri.parse(source.url).buildUpon().appendQueryParameter("cinemaUi", "$marker-$index").build().toString(), source.headers)
        }
        val testStatKeys = entries.map { sourceId(it.url) }.toSet()
        val store = CatalogStore(context)
        val before = store.snapshot()
        val hiddenFavorites = before.favorites.filter { it.startsWith("id:controlled-") || it == "id:failover" }.toSet()
        var activity: MainActivity? = null
        try {
            hiddenFavorites.forEach { store.favorite(it) }
            store.replaceSubscription(Subscription(marker, "Cinema UI controlled test", "", System.currentTimeMillis()), ParsedPlaylist(entries, 0))
            store.remember(ids.first())
            val screen = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .putExtra("skipBuiltinsForTest", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = screen
            await("The test activity owns window focus") { onMain {
                screen.hasWindowFocus() && screen.window.decorView.display != null
            } }
            inputDisplayId = onMain { screen.window.decorView.display.displayId }
            Log.i("SwiftTVSidebarTest", "OS display-targeted shell injection active; display=$inputDisplayId")
            awaitPlaying(screen, ids.first())
            val baseline = onMain { capture(screen) }

            // Open bottom Cinema Ribbon on currently playing card
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Cinema ribbon opens on the playing card") { onMain {
                mode(screen) == "channels" && ribbon(screen).hasCardFocus() && ribbon(screen).selectedChannel()?.id == ids.first()
            } }
            assertUnchanged(screen, baseline)

            // Horizontal card navigation (Left/Right) does not reload player
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitSelection(screen, ids[1])
            assertUnchanged(screen, baseline)
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            awaitSelection(screen, ids[0])
            assertUnchanged(screen, baseline)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitSelection(screen, ids[1])

            // Menu opens floating Inspector Sheet on focused card; favorite toggles in-place keeping focus
            press(KeyEvent.KEYCODE_MENU)
            await("Menu opens inspector targeting the focused card") { onMain {
                mode(screen) == "options" && read<String?>(screen, "optionsChannelId") == ids[1] && screen.currentFocus != null
            } }
            val favRowBefore = onMain { screen.currentFocus!! }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Favorite toggles in-place keeping focus") { onMain {
                screen.currentFocus === favRowBefore && ids[1] in store.snapshot().favorites
            } }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Favorite untoggles in-place keeping focus") { onMain {
                screen.currentFocus === favRowBefore && ids[1] !in store.snapshot().favorites
            } }
            assertUnchanged(screen, baseline)

            press(KeyEvent.KEYCODE_BACK)
            await("Back restores the focused card in ribbon") { onMain {
                mode(screen) == "channels" && ribbon(screen).selectedChannel()?.id == ids[1]
            } }
            assertUnchanged(screen, baseline)

            // Exercise real horizontal RecyclerView scrolling across 18 cards
            for (index in 2..18) { press(KeyEvent.KEYCODE_DPAD_RIGHT); awaitSelection(screen, ids[index]) }
            assertUnchanged(screen, baseline)

            // Up enters the frosted category pill strip
            press(KeyEvent.KEYCODE_DPAD_UP)
            await("Up focuses active category pill") { onMain { (screen.currentFocus as? TextView)?.text?.toString() == "全部" } }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right navigates to Favorites pill") { onMain { (screen.currentFocus as? TextView)?.text?.toString() == "收藏" } }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Empty favorites state is displayed") { onMain {
                ribbon(screen).selectedGroup() == "收藏" &&
                    descendants(ribbon(screen)).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains("还没有收藏频道") }
            } }
            assertUnchanged(screen, baseline)

            press(KeyEvent.KEYCODE_BACK)
            awaitClosed(screen)
            assertUnchanged(screen, baseline)

            // Primary mouse click on video opens the same Cinema Ribbon
            clickPlaybackWithMouse(screen)
            await("Mouse click opens the ribbon on playing channel") { onMain {
                mode(screen) == "channels" && ribbon(screen).selectedChannel()?.id == ids.first()
            } }
            assertUnchanged(screen, baseline)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitSelection(screen, ids[1])
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Confirm switches only the selected channel") { onMain {
                val controller = controller(screen)
                mode(screen).isEmpty() && controller.current?.id == ids[1] && controller.player.currentMediaItem?.mediaId != baseline.mediaId
            } }
            awaitPlaying(screen, ids[1])
            onMain { assertSame("Same ExoPlayer handles confirmed switching", baseline.player, controller(screen).player) }

            // Direct remote numeric keypad tuning ('3' + OK -> ids[2])
            press(KeyEvent.KEYCODE_3)
            await("Numeric OSD previews channel 3") { onMain {
                read<NumberPadOsdView>(screen, "numberOsd").visibility == View.VISIBLE
            } }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlaying(screen, ids[2])
            onMain { assertSame("Numeric keypad reuses the same ExoPlayer", baseline.player, controller(screen).player) }
            Log.i("SwiftTVSidebarTest", "PASS CinemaRibbonView horizontal cards/pills/inspector/numeric-pad; stable player; channels=24")
        } finally {
            activity?.let { screen ->
                onMain {
                    read<CinemaBlurEngine>(screen, "blurEngine").stop()
                    read<PlaybackController?>(screen, "controller")?.close()
                    writeField(screen, "controller", null)
                }
                await("Test activity releases its player", 5000) { onMain { read<PlaybackController?>(screen, "controller") == null } }
            }
            val cleanup = CatalogStore(context)
            val latest = cleanup.snapshot()
            val next = latest.copy(
                entries = latest.entries.filterNot { it.subscriptionId == marker },
                subscriptions = latest.subscriptions.filterNot { it.id == marker },
                favorites = (latest.favorites - ids.toSet()) + hiddenFavorites,
                lastChannel = if (latest.lastChannel in ids) before.lastChannel else latest.lastChannel,
                stats = latest.stats.filterKeys { it !in testStatKeys }
            )
            CatalogStore::class.java.getDeclaredMethod("persist", CatalogState::class.java).apply { isAccessible = true }.invoke(cleanup, next)
        }
    }

    @Test fun actualNativeRibbonKeepsTopBroadcastClearAcrossViewportsAndFonts() {
        val samples = listOf(1280 to 720, 1920 to 1080, 3840 to 2160, 640 to 360, 480 to 320)
        val channels = (1..24).map { index ->
            val id = "id:controlled-measure-$index"
            Channel(id, if (index == 2) "自定义频道 · 较长名称" else "CCTV-$index", if (index < 13) "央视" else "卫视",
                listOf(Entry(id, "频道 $index", "测试", "http://127.0.0.1/measure-$index.m3u8")))
        }
        onMain {
            for ((width, height) in samples) for (scale in listOf(1f, 1.3f, 2f)) {
                val configuration = Configuration(context.resources.configuration).apply {
                    fontScale = scale; densityDpi = DisplayMetrics.DENSITY_DEFAULT
                    screenWidthDp = width; screenHeightDp = height
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
                val configured = ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.AppTheme)
                val artwork = ChannelLogos(configured)
                try {
                    val root = TvOverlayLayout(configured)
                    val view = CinemaRibbonView(configured, artwork, null, object : CinemaRibbonView.Actions {
                        override fun play(channel: Channel) { fail("Measurement must never submit a playback request") }
                        override fun more(channel: Channel?) {}
                        override fun settings() {}
                        override fun groupChanged(group: String) {}
                    })
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    view.updateCatalog(channels, emptySet())
                    val density = configured.resources.displayMetrics.density
                    val widthPx = (width * density).toInt()
                    val heightPx = (height * density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, widthPx, heightPx)
                    val dock = view.getChildAt(0)
                    val label = "${width}x$height font=$scale"
                    assertTrue("Bottom dock stays within viewport: $label", dock.top >= 0 && dock.bottom <= heightPx)
                    assertEquals(24, view.channelGallery.adapter?.itemCount)
                    root.removeAllViews()
                } finally { artwork.close() }
            }
        }
    }

    private data class PlaybackSnapshot(val controller: PlaybackController, val player: ExoPlayer, val channelId: String?, val mediaId: String?)
    private fun capture(activity: MainActivity): PlaybackSnapshot {
        val controller = controller(activity)
        return PlaybackSnapshot(controller, controller.player, controller.current?.id, controller.player.currentMediaItem?.mediaId)
    }
    private fun assertUnchanged(activity: MainActivity, baseline: PlaybackSnapshot) = onMain {
        val now = capture(activity)
        assertSame("UI navigation must not recreate its playback controller", baseline.controller, now.controller)
        assertSame("UI navigation must not recreate ExoPlayer", baseline.player, now.player)
        assertEquals("UI navigation must not tune another channel", baseline.channelId, now.channelId)
        assertEquals("UI navigation must not reload the media item", baseline.mediaId, now.mediaId)
    }
    private fun awaitPlaying(activity: MainActivity, channelId: String) = await("Controlled video renders its first frame and plays", 20000) { onMain {
        val controller = read<PlaybackController?>(activity, "controller")
        controller != null && controller.current?.id == channelId && controller.player.isPlaying && read<Boolean>(controller, "firstFrame")
    } }
    private fun awaitSelection(activity: MainActivity, channelId: String) = await("Focused card $channelId") { onMain {
        mode(activity) == "channels" && ribbon(activity).selectedChannel()?.id == channelId
    } }
    private fun awaitClosed(activity: MainActivity) {
        await("Back returns to video") { onMain {
            mode(activity).isEmpty() && read<FrameLayout>(activity, "overlay").visibility != View.VISIBLE
        } }
    }
    private fun controller(activity: MainActivity) = read<PlaybackController?>(activity, "controller") ?: error("Activity has no active controller")
    private fun ribbon(activity: MainActivity) = read<CinemaRibbonView?>(activity, "ribbon") ?: error("Activity has no CinemaRibbonView")
    private fun mode(activity: MainActivity) = read<String>(activity, "overlayMode")
    @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, field: String): T = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner) as T
    private fun writeField(owner: Any, field: String, value: Any?) { owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(owner, value) }
    private fun press(key: Int) = shellInput("-d $inputDisplayId keyevent $key")
    private fun shellInput(arguments: String) {
        check(inputDisplayId >= 0) { "The activity display must be known before OS input injection" }
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input $arguments")
        ).bufferedReader().use { it.readText() }
        assertTrue("OS input command failed: input $arguments\n$output", output.isBlank())
        instrumentation.waitForIdleSync()
    }
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(message: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Timed out: $message", condition())
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun clickPlaybackWithMouse(activity: MainActivity) {
        val point = onMain {
            val root = read<FrameLayout>(activity, "root")
            val origin = IntArray(2).also { root.getLocationOnScreen(it) }
            (origin[0] + root.width * .5f) to (origin[1] + root.height * .35f)
        }
        shellInput("mouse -d $inputDisplayId tap ${point.first.toInt()} ${point.second.toInt()}")
    }
}
