package app.swifttv.player

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

enum class RoutingMode(val key: String, val title: String) {
    AUTO("auto", "智能选路（推荐）"),
    IPV6_FIRST("ipv6_first", "IPv6 优先"),
    IPV4_FIRST("ipv4_first", "IPv4 优先"),
    PLAYBACK_HISTORY("playback_history", "仅按实测排序");

    fun next(): RoutingMode = entries[(ordinal + 1) % entries.size]

    companion object {
        internal fun resolve(saved: String?): RoutingMode =
            entries.firstOrNull { it.key == saved } ?: AUTO

        fun read(context: Context): RoutingMode {
            val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            return resolve(prefs.getString("routingMode", null))
        }

        fun save(context: Context, mode: RoutingMode) {
            context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit {
                putString("routingMode", mode.key)
            }
        }
    }
}

/** Pure protocol-level route ranking and parallel race batch selection with zero third-party source knowledge. */
object SourceRoutes {
    fun hostOf(url: String): String =
        runCatching { httpUri(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")

    fun isIpv6Literal(url: String): Boolean {
        val host = hostOf(url)
        return host.startsWith("[") && host.endsWith("]") && host.contains(':')
    }

    fun group(entry: Entry): String = if (isIpv6Literal(entry.url)) "ipv6" else "ipv4"

    fun label(entry: Entry): String = if (isIpv6Literal(entry.url)) "IPv6 线路" else "IPv4 线路"

    fun rank(
        sources: List<Entry>,
        stats: Map<String, SourceStat>,
        now: Long,
        mode: RoutingMode,
        preferred: Entry? = null,
        ipv6Available: Boolean = true
    ): List<Entry> {
        val base = SourceRanking.rank(sources, stats, now)
        val filtered = if (ipv6Available) {
            base
        } else {
            val (v6, v4) = base.partition { isIpv6Literal(it.url) }
            v4 + v6
        }
        val routed = prioritize(filtered, mode, ipv6Available)
        return if (preferred == null) routed else listOf(preferred) + routed.filterNot { it.url == preferred.url }
    }

    internal fun prioritize(ranked: List<Entry>, mode: RoutingMode, ipv6Available: Boolean = true): List<Entry> {
        if (ranked.size <= 1) return ranked
        return when (mode) {
            RoutingMode.IPV6_FIRST -> {
                if (!ipv6Available) ranked
                else {
                    val (v6, v4) = ranked.partition { isIpv6Literal(it.url) }
                    v6 + v4
                }
            }
            RoutingMode.IPV4_FIRST -> {
                val (v4, v6) = ranked.partition { !isIpv6Literal(it.url) }
                v4 + v6
            }
            RoutingMode.AUTO, RoutingMode.PLAYBACK_HISTORY -> ranked
        }
    }

    /**
     * Picks up to [maxCount] candidates with distinct hosts for concurrent fast-race probing.
     * Literal IPv6 URLs are skipped from race slots when [ipv6Available] is false unless only IPv6 exists.
     */
    fun pickRaceBatch(
        remaining: List<Entry>,
        maxCount: Int = 3,
        ipv6Available: Boolean = true
    ): List<Entry> {
        if (remaining.isEmpty() || maxCount <= 0) return emptyList()
        val eligible = if (ipv6Available) remaining else remaining.filterNot { isIpv6Literal(it.url) }.ifEmpty { remaining }
        val picked = mutableListOf<Entry>()
        val seenHosts = mutableSetOf<String>()
        for (entry in eligible) {
            val host = hostOf(entry.url)
            if (host.isEmpty() || seenHosts.add(host)) {
                picked += entry
                if (picked.size >= maxCount) return picked
            }
        }
        for (entry in eligible) {
            if (picked.size >= maxCount) break
            if (picked.none { it.url == entry.url }) picked += entry
        }
        return picked
    }
}
