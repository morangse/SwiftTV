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
    GUOVIN_IPV6("Guovin IPv6 国内优先", listOf(
        "https://guovin.github.io/iptv-api/ipv6.m3u",
        "https://fastly.jsdelivr.net/gh/Guovin/iptv-api@gd/output/ipv6/result.m3u",
        "https://cdn.jsdelivr.net/gh/Guovin/iptv-api@gd/output/ipv6/result.m3u"
    )),
    GUOVIN_ALL("Guovin 双栈全量 (IPv6+IPv4)", listOf(
        "https://guovin.github.io/iptv-api/result.m3u",
        "https://fastly.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u",
        "https://cdn.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u"
    )),
    FANMINGMING_IPV6("范明明精选 IPv6 (82台)", listOf(
        "https://fastly.jsdelivr.net/gh/fanmingming/live@main/tv/m3u/ipv6.m3u",
        "https://cdn.jsdelivr.net/gh/fanmingming/live@main/tv/m3u/ipv6.m3u",
        "https://live.fanmingming.cn/tv/m3u/ipv6.m3u"
    )),
    GUOVIN("Guovin IPv4 (iptv-api)", listOf(
        "https://guovin.github.io/iptv-api/ipv4.m3u",
        "https://fastly.jsdelivr.net/gh/Guovin/iptv-api@gd/output/ipv4/result.m3u",
        "https://cdn.jsdelivr.net/gh/Guovin/iptv-api@gd/output/ipv4/result.m3u"
    ));

    val subscriptionIds = urls.map { sourceId("url:$it") }.toSet()
}

/** Keeps explicit preferences separate from measured line health. */
object SourceRoutes {
    const val HENAN_HOST = "iptv.cdn.ha.chinamobile.com"
    private val domesticDomainSuffixes = listOf(
        ".cn",
        "chinamobile.com",
        "mgtv.com",
        "cztv.com",
        "cztvcloud.com",
        "kankanlive.com",
        "kwimgs.com",
        "miguvideo.com",
        "51kandianshi.com",
        "jdshipin.com",
        "qingting.fm",
        "cctv.com",
        "snrtv.com",
        "hntv.tv"
    )
    // Known overseas bare-IPv4 relay blocks frequently ranked first by US GitHub Actions runners.
    private val overseasRelayIpv4Prefixes = listOf(
        "38.", "63.", "69.", "74.", "107.", "154.", "173.", "192.151.", "198.", "204.", "207."
    )
    private val ipv4Literal = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

    fun isHenan(url: String): Boolean =
        runCatching { httpUri(url).host.equals(HENAN_HOST, true) }.getOrDefault(false)

    fun hostOf(url: String): String =
        runCatching { httpUri(url).host.orEmpty().lowercase(java.util.Locale.ROOT) }.getOrDefault("")

    fun isIpv6Literal(url: String): Boolean {
        val host = hostOf(url)
        return host.startsWith("[") && host.endsWith("]") && host.contains(':')
    }

    fun isChinaCarrierIpv6(url: String): Boolean {
        val host = hostOf(url)
        if (!host.startsWith("[") || !host.endsWith("]")) return false
        val addr = host.substring(1, host.length - 1)
        return addr.startsWith("2408:") || addr.startsWith("2409:") || addr.startsWith("240e:") ||
            addr.startsWith("2400:") || addr.startsWith("2001:da8:")
    }

    fun isDomesticHint(url: String): Boolean {
        if (isChinaCarrierIpv6(url)) return true
        val host = hostOf(url)
        if (host.isEmpty() || isIpv6Literal(url)) return false
        return domesticDomainSuffixes.any { suffix ->
            if (suffix.startsWith(".")) host.endsWith(suffix)
            else host == suffix || host.endsWith(".$suffix")
        }
    }

    fun isOverseasRelayHint(url: String): Boolean {
        val host = hostOf(url)
        if (!ipv4Literal.matches(host)) return false
        return overseasRelayIpv4Prefixes.any { host.startsWith(it) }
    }

    internal fun networkTier(url: String, ipv6Available: Boolean): Int {
        val v6 = isIpv6Literal(url)
        if (v6 && !ipv6Available) return 3
        if (v6 && isChinaCarrierIpv6(url)) return 0
        if (isDomesticHint(url)) return 0
        if (isOverseasRelayHint(url)) return 2
        return 1
    }

    fun group(entry: Entry): String = when {
        isHenan(entry.url) -> "henan_cmcc"
        entry.subscriptionId in PlaylistPreset.GUOVIN_IPV6.subscriptionIds -> "guovin_ipv6"
        entry.subscriptionId in PlaylistPreset.GUOVIN_ALL.subscriptionIds -> "guovin_dual"
        entry.subscriptionId in PlaylistPreset.FANMINGMING_IPV6.subscriptionIds -> "fanmingming_ipv6"
        entry.subscriptionId in PlaylistPreset.GUOVIN.subscriptionIds -> "guovin_ipv4"
        entry.subscriptionId.startsWith("builtin-") -> "public_fallback"
        else -> "custom"
    }

    fun label(entry: Entry): String = when {
        isHenan(entry.url) -> "河南移动"
        isChinaCarrierIpv6(entry.url) -> "国内 IPv6"
        isIpv6Literal(entry.url) -> "IPv6 线路"
        else -> when (group(entry)) {
            "guovin_ipv6" -> "Guovin IPv6"
            "guovin_dual" -> "Guovin 双栈"
            "fanmingming_ipv6" -> "范明明 IPv6"
            "guovin_ipv4" -> "Guovin IPv4"
            "public_fallback" -> "公网线路"
            else -> "自定义"
        }
    }

    fun rank(
        sources: List<Entry>,
        stats: Map<String, SourceStat>,
        now: Long,
        mode: RoutingMode,
        preferred: Entry? = null,
        ipv6Available: Boolean = true
    ): List<Entry> {
        val base = SourceRanking.rank(sources, stats, now)
        val affinity = applyNetworkAffinity(base, stats, ipv6Available)
        val routed = prioritize(affinity, mode)
        // A deliberate manual choice always wins; only its backups respect cooldown and routing.
        return if (preferred == null) routed else listOf(preferred) + routed.filterNot { it.url == preferred.url }
    }

    /**
     * Reorders only untested sources (no measured success/failure yet) by domestic/IPv6 affinity,
     * and always moves literal IPv6 URLs behind IPv4 sources when the device lacks global IPv6.
     * Measured playback history always dominates once a source has been tried locally.
     */
    internal fun applyNetworkAffinity(
        ranked: List<Entry>,
        stats: Map<String, SourceStat>,
        ipv6Available: Boolean
    ): List<Entry> {
        if (ranked.size <= 1) return ranked
        val reachable = if (ipv6Available) ranked else ranked.filterNot { isIpv6Literal(it.url) }
        val unreachableV6 = if (ipv6Available) emptyList() else ranked.filter { isIpv6Literal(it.url) }

        val out = reachable.toMutableList()
        var i = 0
        while (i < out.size) {
            val st = stats[sourceId(out[i].url)]
            val untested = st == null || (st.successes == 0 && st.failures == 0)
            if (!untested) {
                i++
                continue
            }
            var j = i + 1
            while (j < out.size) {
                val sj = stats[sourceId(out[j].url)]
                if (sj != null && (sj.successes > 0 || sj.failures > 0)) break
                j++
            }
            if (j - i > 1) {
                val sortedSlice = out.subList(i, j).sortedBy { networkTier(it.url, ipv6Available) }
                for (k in sortedSlice.indices) out[i + k] = sortedSlice[k]
            }
            i = j
        }
        return out + unreachableV6
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
