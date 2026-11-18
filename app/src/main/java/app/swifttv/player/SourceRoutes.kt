package app.swifttv.player

/** User-selected routing preference is separate from measured playback health. */
object SourceRoutes {
    const val HENAN_HOST = "iptv.cdn.ha.chinamobile.com"
    fun isHenan(url: String): Boolean = runCatching { httpUri(url).host.equals(HENAN_HOST, true) }.getOrDefault(false)
    fun group(entry: Entry): String = when {
        isHenan(entry.url) -> "henan_cmcc"
        entry.subscriptionId.startsWith("builtin-") -> "public_fallback"
        else -> "custom"
    }
    fun label(entry: Entry): String = when (group(entry)) {
        "henan_cmcc" -> "河南移动"
        "public_fallback" -> "公网线路"
        else -> "自定义"
    }
    fun rank(sources: List<Entry>, stats: Map<String, SourceStat>, now: Long, preferHenan: Boolean, preferred: Entry? = null): List<Entry> {
        val ranked = SourceRanking.rank(sources, stats, now)
        val routed = if (preferHenan) ranked.filter { isHenan(it.url) } + ranked.filterNot { isHenan(it.url) } else ranked
        // A deliberate manual choice always wins; only its backups respect cooldown and routing.
        return if (preferred == null) routed else listOf(preferred) + routed.filterNot { it.url == preferred.url }
    }
}