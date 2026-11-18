package app.swifttv.player

import android.content.Context
import androidx.core.content.edit

/** Only reviewed, bundled streams are default channels. Raw discovery lists never auto-promote into playback. */
object BuiltinPolicy {
    const val PROFILE_4K_HEVC50 = "4k-hevc50"
    val channelOrder = ChannelOrdering.numberedCctvIds + listOf("hunan", "zhejiang", "dragon", "jiangsu", "beijing", "henan")
    val names = ChannelOrdering.numberedCctvIds.associateWith { it.uppercase(java.util.Locale.ROOT) } + mapOf(
        "hunan" to "湖南卫视", "zhejiang" to "浙江卫视", "dragon" to "东方卫视", "jiangsu" to "江苏卫视", "beijing" to "北京卫视", "henan" to "河南卫视"
    )

    fun requires4kHevc50(entry: Entry): Boolean =
        entry.profile.equals(PROFILE_4K_HEVC50, true)

    fun curate(parsed: ParsedPlaylist): ParsedPlaylist {
        val entries = parsed.entries.filter { it.channelId in channelOrder }.map {
            it.copy(group = if (it.channelId.startsWith("cctv-")) "央视" else "卫视")
        }.sortedBy { channelOrder.indexOf(it.channelId) }.distinctBy { it.channelId to it.url }
        require(entries.isNotEmpty()) { "内置目录未返回目标频道，已保留原频道表" }
        return ParsedPlaylist(entries, parsed.skipped, parsed.epgUrls)
    }

    fun migrate(state: CatalogState, subscription: Subscription, parsed: ParsedPlaylist, revision: Int): CatalogState {
        val fresh = curate(parsed).entries.map { it.copy(subscriptionId = subscription.id) }
        return state.copy(
            entries = state.entries.filterNot { it.subscriptionId.startsWith("builtin-") } + fresh,
            subscriptions = state.subscriptions.filterNot { it.id.startsWith("builtin-") } + subscription,
            builtinRevision = revision
        )
    }

    /**
     * Projects target channels while gating 4K HEVC-only built-in streams against actual device
     * hardware decoder capabilities. Low-RAM / 1080p Android 6 TVs safely skip 4K HEVC defaults,
     * while 4K HEVC TVs unlock all 24 channels.
     */
    fun withMissingChannels(channels: List<Channel>, supports4kHevc50: Boolean = false): List<Channel> {
        val gated = channels.map { channel ->
            val compatible = channel.sources.filter { supports4kHevc50 || !requires4kHevc50(it) }
            val gatedOut4k = compatible.isEmpty() && channel.sources.any { requires4kHevc50(it) }
            channel.copy(
                sources = compatible,
                unavailableReason = if (gatedOut4k) "4K HEVC · 本机无硬解" else channel.unavailableReason
            )
        }
        val targets = channelOrder.map { id ->
            gated.firstOrNull { it.id == id }
                ?: Channel(id, names.getValue(id), if (id.startsWith("cctv-")) "央视" else "卫视", emptyList())
        }
        return targets + gated.filterNot { it.id in channelOrder }
    }

    fun chooseStart(channels: List<Channel>, last: String, stats: Map<String, SourceStat>): Channel? {
        val playable = channels.filter { it.sources.isNotEmpty() }
        playable.firstOrNull { it.id == last }?.let { return it }
        playable.firstOrNull { c -> c.sources.any { (stats[sourceId(it.url)]?.successes ?: 0) > 0 && stats[sourceId(it.url)]?.error.isNullOrEmpty() } }?.let { return it }
        return playable.firstOrNull { it.id == "hunan" } ?: playable.firstOrNull()
    }

    fun note(entry: Entry): String = if (!entry.subscriptionId.startsWith("builtin-")) "" else when {
        requires4kHevc50(entry) -> "4K 50fps HEVC · 需要设备支持"
        SourceRoutes.isHenan(entry.url) -> "河南移动宽带专用 · 1080p H.264/AAC"
        else -> ""
    }

    fun coverage(channels: List<Channel>): String = channelOrder.joinToString("\n") { id ->
        val ch = channels.firstOrNull { it.id == id }
        val suffix = when {
            ch != null && ch.sources.any { requires4kHevc50(it) } -> " · 河南移动 4K HEVC（本机硬解已开启）"
            ch != null && ch.sources.any { SourceRoutes.isHenan(it.url) } -> " · 河南移动 1080p 线路"
            ch != null && ch.sources.isNotEmpty() -> " · 有可用线路"
            ch != null && ch.unavailableReason.isNotEmpty() -> " · " + ch.unavailableReason
            else -> " · 暂无已验证线路"
        }
        names.getValue(id) + suffix
    }
}

object BuiltinCatalog {
    const val REVISION = 5
    const val ID = "builtin-reviewed"
    fun preferHenan(context: Context) = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("preferHenan", true)
    fun setPreferHenan(context: Context, enabled: Boolean) {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit { putBoolean("preferHenan", enabled) }
    }
    fun enabled(context: Context) = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("builtinsEnabled", true)
    @Synchronized fun setEnabled(context: Context, store: CatalogStore, enabled: Boolean) {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit { putBoolean("builtinsEnabled", enabled) }
        if (enabled) installBundled(context, store)
        else store.snapshot().subscriptions.filter { it.id.startsWith("builtin-") }.forEach { store.removeSubscription(it.id) }
    }
    @Synchronized fun installBundled(context: Context, store: CatalogStore) {
        if (!BuildConfig.HAS_BUNDLED_CATALOG || !enabled(context)) return
        val state = store.snapshot()
        if (state.builtinRevision == REVISION && state.subscriptions.any { it.id == ID }) return
        val raw = context.assets.open("builtin-reviewed.m3u").bufferedReader(Charsets.UTF_8).use { it.readText() }
        store.installBuiltins(Subscription(ID, "内置 · 河南移动与公网备用", "", System.currentTimeMillis()), M3uParser.parse(raw), REVISION)
    }
}
