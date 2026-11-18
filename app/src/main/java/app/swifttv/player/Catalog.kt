package app.swifttv.player

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

data class Entry(
    val channelId: String, val name: String, val group: String, val url: String,
    val headers: Map<String, String> = emptyMap(), val subscriptionId: String = "",
    val profile: String = "", val logoUrl: String = ""
)
data class Channel(
    val id: String, val name: String, val group: String, val sources: List<Entry>,
    val unavailableReason: String = "", val logoUrl: String = ""
)
data class ParsedPlaylist(
    val entries: List<Entry>, val skipped: Int, val epgUrls: List<String> = emptyList()
)
data class SourceStat(
    val attempts: Int = 0, val successes: Int = 0, val firstFrameMs: Long = 0,
    val rebufferMs: Long = 0, val playedMs: Long = 0, val checkedAt: Long = 0,
    val error: String = "", val cooldownUntil: Long = 0,
    val width: Int = 0, val height: Int = 0, val codec: String = "", val audioStarted: Boolean = false,
    val failures: Int = 0, val cancelled: Int = 0, val lastOutcome: String = ""
)
data class Subscription(
    val id: String, val name: String, val url: String, val updatedAt: Long,
    val etag: String = "", val lastModified: String = "", val epgUrl: String = ""
)
data class CatalogState(
    val entries: List<Entry> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val lastChannel: String = "",
    val stats: Map<String, SourceStat> = emptyMap(),
    val builtinRevision: Int = 0,
    val customEpgUrl: String = ""
) {
    fun channels(): List<Channel> = ChannelOrdering.order(entries.groupBy { it.channelId }.map { (id, list) ->
        val logo = list.firstNotNullOfOrNull { it.logoUrl.takeIf(String::isNotBlank) }.orEmpty()
        Channel(id, list.first().name, list.first().group, list.distinctBy { it.url }, logoUrl = logo)
    })

    fun allEpgUrls(): List<String> = buildList {
        if (customEpgUrl.isNotBlank()) add(customEpgUrl.trim())
        subscriptions.forEach { sub ->
            if (sub.epgUrl.isNotBlank()) add(sub.epgUrl.trim())
        }
    }.distinct()
}

fun sourceId(url: String): String = MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

fun httpUri(value: String): URI {
    require(value.length <= 8192 && value.none { it <= '\u001f' }) { "线路地址包含无效字符" }
    val uri = URI(value.trim())
    require(uri.scheme?.lowercase(Locale.ROOT) in listOf("http", "https") && !uri.host.isNullOrBlank()) {
        "只支持 HTTP 或 HTTPS 地址"
    }
    require(uri.userInfo == null) { "请使用不含用户名和密码的订阅地址" }
    return uri
}

object ChannelIdentity {
    private val aliases = mapOf(
        "henantv" to ("henan" to "河南卫视"),
        "henansatellitetv" to ("henan" to "河南卫视"),
        "河南卫视" to ("henan" to "河南卫视"),
        "hunantv" to ("hunan" to "湖南卫视"),
        "湖南卫视" to ("hunan" to "湖南卫视"),
        "zhejiang satellitetv" to ("zhejiang" to "浙江卫视"),
        "zhejiang satellitetv".replace(" ", "") to ("zhejiang" to "浙江卫视"),
        "zhejiang衛視" to ("zhejiang" to "浙江卫视"),
        "浙江卫视" to ("zhejiang" to "浙江卫视"),
        "jiangsusatellitetv" to ("jiangsu" to "江苏卫视"),
        "江苏卫视" to ("jiangsu" to "江苏卫视"),
        "dragontv" to ("dragon" to "东方卫视"),
        "东方卫视" to ("dragon" to "东方卫视"),
        "beijingsatellitetv" to ("beijing" to "北京卫视"),
        "brtv北京卫视" to ("beijing" to "北京卫视"),
        "北京卫视" to ("beijing" to "北京卫视")
    )
    private val cctvTitles = mapOf(
        1 to setOf("综合"), 2 to setOf("财经"), 3 to setOf("综艺"), 4 to setOf("中文国际"),
        5 to setOf("体育"), 6 to setOf("电影"), 7 to setOf("国防军事", "军事农业", "军事"),
        8 to setOf("电视剧"), 9 to setOf("纪录", "记录"), 10 to setOf("科教"), 11 to setOf("戏曲"),
        12 to setOf("社会与法"), 13 to setOf("新闻"), 14 to setOf("少儿"), 15 to setOf("音乐"),
        16 to setOf("奥林匹克"), 17 to setOf("农业农村")
    )
    private val cctvNumber = Regex("^cctv-?(\\d{1,2})(plus|\\+)?(.*)$")
    private val quality = Regex("(?i)(?:fhd|hd|sd|1080[pi]|720p|576[pi]|高清|超清|标清)$")
    private val qualityOnly = Regex("(?i)^(?:fhd|fullhd|hd|sd|\\d{3,4}[pi]|\\d{3,4}\\s*[x×]\\s*\\d{3,4}|高清|超清|标清)$")
    private val brackets = Regex("\\(([^)]*)\\)|\\[([^]]*)]|（([^）]*)）")

    private fun cleanName(name: String): String = name.replace(brackets) { match ->
        val content = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty().trim()
        if (qualityOnly.matches(content)) "" else match.value
    }.replace(Regex("(?i)\\s+(?:HD|SD|FHD)$"), "").trim()

    private fun key(value: String) = value.lowercase(Locale.ROOT).replace(Regex("\\s+"), "").replace('＋', '+')

    private fun cctvIdentity(value: String): Pair<String, String>? {
        var candidate = value
        while (quality.containsMatchIn(candidate)) candidate = candidate.replace(quality, "")
        val match = cctvNumber.matchEntire(candidate) ?: return null
        val number = match.groupValues[1].toInt()
        if (number !in 1..17) return null
        val plus = match.groupValues[2].isNotEmpty()
        if (plus && number != 5) return null
        val title = match.groupValues[3]
        val knownTitles = if (plus) setOf("体育赛事", "体育") else cctvTitles.getValue(number)
        if (title.isNotEmpty() && title !in knownTitles) return null
        val suffix = if (plus) "+" else ""
        return "cctv-$number$suffix" to "CCTV-$number$suffix"
    }

    fun resolve(tvgId: String, name: String): Pair<String, String> {
        val id = tvgId.substringBefore('@').lowercase(Locale.ROOT).removeSuffix(".cn")
        val cleanedName = cleanName(name)
        val candidates = listOf(key(id), key(cleanedName))
        val distinctEdition = candidates.any { it.startsWith("cgtn") || (it.startsWith("cctv") && cctvIdentity(it) == null) }
        if (!distinctEdition) {
            candidates.forEach { candidate -> cctvIdentity(candidate)?.let { return it } }
        }
        for (candidate in candidates) aliases[candidate]?.let { return it }
        val unknownId = if (distinctEdition && cctvIdentity(key(id)) != null) "" else id
        return (if (unknownId.isNotBlank()) "id:$unknownId" else "name:" + cleanedName.lowercase(Locale.ROOT)) to cleanedName
    }
}

object M3uParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""")
    fun parse(text: String): ParsedPlaylist {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "频道表不能超过 2 MB" }
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        val headerLine = lines.firstOrNull { it.isNotEmpty() }
        require(headerLine?.startsWith("#EXTM3U") == true) {
            "不是有效的 M3U 频道表，文件需要以 #EXTM3U 开头"
        }
        require(lines.none { it.startsWith("#EXT-X-") }) { "这是单频道 HLS 视频清单，请导入频道表而非视频分片清单" }
        val headerAttrs = attributes.findAll(headerLine!!).associate { m ->
            m.groupValues[1].lowercase(Locale.ROOT) to m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        val epgUrls = listOfNotNull(headerAttrs["x-tvg-url"], headerAttrs["url-tvg"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() && runCatching { httpUri(it) }.isSuccess }
            .distinct()
            .take(4)

        val result = mutableListOf<Entry>()
        var metadata: Map<String, String>? = null
        var name = ""
        var headers = linkedMapOf<String, String>()
        var profile = ""
        var skipped = 0
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> {
                    if (metadata != null) skipped++
                    var quoted = '\u0000'
                    val comma = line.indexOfFirstWithIndex { index, c ->
                        if (c == '"' || c == '\'') {
                            if (quoted == c) quoted = '\u0000' else if (quoted == '\u0000') quoted = c
                        }
                        c == ',' && quoted == '\u0000' && index > 8
                    }
                    if (comma < 0) { metadata = null; skipped++; continue }
                    metadata = attributes.findAll(line.substring(0, comma)).associate { m ->
                        m.groupValues[1].lowercase(Locale.ROOT) to m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
                    }
                    name = line.substring(comma + 1).trim().take(200)
                    headers = linkedMapOf()
                    profile = ""
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val value = line.substringAfter(':')
                    val key = value.substringBefore('=').lowercase(Locale.ROOT)
                    val optValue = value.substringAfter('=', "").trim()
                    if (key == "swifttv-profile" && optValue.length <= 64 && optValue.none { it <= '\u001f' }) {
                        profile = optValue
                    } else {
                        val header = when (key) { "http-user-agent" -> "User-Agent"; "http-referrer", "http-referer" -> "Referer"; else -> null }
                        if (header != null && optValue.length < 2048 && optValue.none { it <= '\u001f' }) headers[header] = optValue
                    }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val meta = metadata
                    if (meta == null || name.isBlank()) { skipped++; continue }
                    try {
                        httpUri(line)
                        val identity = ChannelIdentity.resolve(meta["tvg-id"].orEmpty(), meta["tvg-name"].orEmpty().ifBlank { name })
                        val rawLogo = meta["tvg-logo"].orEmpty().trim()
                        val validLogo = if (rawLogo.isNotEmpty() && runCatching { httpUri(rawLogo) }.isSuccess) rawLogo else ""
                        result += Entry(
                            identity.first,
                            identity.second,
                            meta["group-title"].orEmpty().ifBlank { "其他频道" },
                            line,
                            headers.toMap(),
                            profile = profile,
                            logoUrl = validLogo
                        )
                    } catch (_: Exception) { skipped++ }
                    metadata = null
                    profile = ""
                    require(result.size <= 10000) { "频道表最多支持 10000 条线路" }
                }
            }
        }
        require(result.isNotEmpty()) { "频道表中没有有效的 HTTP/HTTPS 线路" }
        return ParsedPlaylist(result.distinctBy { it.channelId to it.url }, skipped, epgUrls)
    }

    private inline fun String.indexOfFirstWithIndex(predicate: (Int, Char) -> Boolean): Int {
        for (i in indices) if (predicate(i, this[i])) return i
        return -1
    }
}

object SourceRanking {
    private fun score(entry: Entry, stats: Map<String, SourceStat>): Double {
        val s = stats[sourceId(entry.url)] ?: SourceStat()
        val completed = s.successes + s.failures
        val reliability = if (completed == 0) 0.65 else s.successes.toDouble() / completed
        return reliability * 10000 - s.firstFrameMs * 0.2 -
            (if (s.playedMs > 0) s.rebufferMs.toDouble() / s.playedMs * 2000 else 0.0) +
            minOf(s.playedMs, 60000) * 0.01
    }

    fun rank(sources: List<Entry>, stats: Map<String, SourceStat>, now: Long): List<Entry> {
        val distinct = sources.distinctBy { it.url }
        if (distinct.isEmpty()) return emptyList()
        val uncooled = distinct.filter { (stats[sourceId(it.url)]?.cooldownUntil ?: 0) <= now }
        val sorted = if (uncooled.isNotEmpty()) {
            uncooled.sortedByDescending { score(it, stats) }
        } else {
            distinct.sortedWith(
                compareBy<Entry> { stats[sourceId(it.url)]?.cooldownUntil ?: 0L }
                    .thenByDescending { score(it, stats) }
            )
        }
        val independent = mutableListOf<Entry>()
        val repeated = mutableListOf<Entry>()
        val hosts = mutableSetOf<String>()
        sorted.forEach { if (hosts.add(URI(it.url).host.lowercase(Locale.ROOT))) independent += it else repeated += it }
        return independent + repeated
    }
}

object DiagnosticRedaction {
    fun origin(url: String): String = try {
        val uri = httpUri(url)
        uri.scheme + "://" + uri.host + (if (uri.port > 0) ":" + uri.port else "") + "/…#" + sourceId(url)
    } catch (_: Exception) { "[invalid URL]" }
    fun message(value: String): String = value.replace(Regex("https?://[^\\s\"<>]+")) { origin(it.value) }.take(400)
}

object SourceHealth {
    fun describe(s: SourceStat): String = when {
        s.lastOutcome == "connecting" -> "in_progress"
        s.error.isNotEmpty() -> "failed"
        s.playedMs >= 120 * 60 * 1000L -> "accumulated_two_hours"
        s.playedMs >= 60000 -> "sustained_playback"
        s.successes > 0 -> "first_frame_only"
        s.lastOutcome == "cancelled" -> "cancelled"
        s.attempts > 0 -> "incomplete"
        else -> "untested"
    }
}
