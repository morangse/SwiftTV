package app.swifttv.player

import android.content.Context
import androidx.core.content.edit

enum class RoutingMode(val key: String, val title: String) {
    AUTO("auto", "自动识别"),
    HENAN_FIRST("henan_first", "河南移动优先"),
    PUBLIC_FIRST("public_first", "公网优先"),
    PLAYBACK_HISTORY("playback_history", "按实测表现");

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        internal fun resolve(saved: String?, legacyHenan: Boolean?): RoutingMode =
            entries.firstOrNull { it.key == saved } ?: when (legacyHenan) {
                true -> HENAN_FIRST
                false -> PLAYBACK_HISTORY
                null -> AUTO
            }

        fun read(context: Context): RoutingMode {
            val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            val legacy = if (prefs.contains("preferHenan")) prefs.getBoolean("preferHenan", true) else null
            return resolve(prefs.getString("routingMode", null), legacy)
        }

        fun save(context: Context, mode: RoutingMode) {
            context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit { putString("routingMode", mode.key) }
        }
    }
}

internal enum class PlaylistPreset(val title: String, val urls: List<String>) {
    HENAN("河南移动全量扩展 (Elykia)", listOf(
        "https://elykia093.github.io/HA-CMCC-IPTV/index.m3u",
        "https://cdn.jsdelivr.net/gh/Elykia093/HA-CMCC-IPTV@main/public/index.m3u",
        "https://fastly.jsdelivr.net/gh/Elykia093/HA-CMCC-IPTV@main/public/index.m3u"
    )),
    GUOVIN("Guovin IPv4 (iptv-api)", listOf("https://guovin.github.io/iptv-api/ipv4.m3u"));

    val subscriptionIds = urls.map { sourceId("url:$it") }.toSet()
}

/** Keeps explicit preferences separate from measured line health. */
object SourceRoutes {
    const val HENAN_HOST = "iptv.cdn.ha.chinamobile.com"
    fun isHenan(url: String): Boolean =
        runCatching { httpUri(url).host.equals(HENAN_HOST, true) }.getOrDefault(false)

    fun group(entry: Entry): String = when {
        isHenan(entry.url) -> "henan_cmcc"
        entry.subscriptionId in PlaylistPreset.GUOVIN.subscriptionIds -> "guovin_ipv4"
        entry.subscriptionId.startsWith("builtin-") -> "public_fallback"
        else -> "custom"
    }

    fun label(entry: Entry): String = when (group(entry)) {
        "henan_cmcc" -> "河南移动"
        "guovin_ipv4" -> "Guovin IPv4"
        "public_fallback" -> "公网线路"
        else -> "自定义"
    }

    fun rank(sources: List<Entry>, stats: Map<String, SourceStat>, now: Long, mode: RoutingMode, preferred: Entry? = null): List<Entry> {
        val routed = prioritize(SourceRanking.rank(sources, stats, now), mode)
        // A deliberate manual choice always wins; only its backups respect cooldown and routing.
        return if (preferred == null) routed else listOf(preferred) + routed.filterNot { it.url == preferred.url }
    }

    internal fun prioritize(ranked: List<Entry>, mode: RoutingMode): List<Entry> {
        if (mode == RoutingMode.AUTO || mode == RoutingMode.PLAYBACK_HISTORY) return ranked
        val (henan, others) = ranked.partition { isHenan(it.url) }
        return if (mode == RoutingMode.HENAN_FIRST) henan + others else others + henan
    }
}

/** Uses only observations on the current Android network. No location lookup is needed. */
internal class AutomaticSourceRouting {
    private var network: Long? = null
    private var preference = RoutingMode.PLAYBACK_HISTORY
    private val regionalFailures = mutableSetOf<String>()

    fun effective(networkId: Long?, mode: RoutingMode): RoutingMode {
        observe(networkId)
        return if (mode == RoutingMode.AUTO) preference else mode
    }

    fun regionalFailure(networkId: Long, sourceUrl: String) {
        observe(networkId)
        if (preference == RoutingMode.PUBLIC_FIRST) return
        regionalFailures += sourceUrl
        if (regionalFailures.size >= 2) preference = RoutingMode.PUBLIC_FIRST
    }

    fun rendered(networkId: Long, regional: Boolean) {
        observe(networkId)
        if (regional) {
            regionalFailures.clear()
            preference = RoutingMode.HENAN_FIRST
        } else if (regionalFailures.isNotEmpty() && preference != RoutingMode.HENAN_FIRST) {
            preference = RoutingMode.PUBLIC_FIRST
        }
    }

    private fun observe(networkId: Long?) {
        if (network == networkId) return
        network = networkId
        regionalFailures.clear()
        preference = RoutingMode.PLAYBACK_HISTORY
    }
}
