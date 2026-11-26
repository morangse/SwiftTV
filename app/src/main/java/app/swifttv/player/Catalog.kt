package app.swifttv.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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

/** Pure channel gating and initial channel selection helpers without any bundled defaults. */
object CatalogSelection {
    const val PROFILE_4K_HEVC50 = "4k-hevc50"

    fun requires4kHevc50(entry: Entry): Boolean =
        entry.profile.equals(PROFILE_4K_HEVC50, true)

    fun gateHardware(channels: List<Channel>, supports4kHevc50: Boolean): List<Channel> =
        channels.map { channel ->
            val compatible = channel.sources.filter { supports4kHevc50 || !requires4kHevc50(it) }
            val gatedOut4k = compatible.isEmpty() && channel.sources.any { requires4kHevc50(it) }
            channel.copy(
                sources = compatible,
                unavailableReason = if (gatedOut4k) "4K HEVC · 本机无硬解" else channel.unavailableReason
            )
        }

    fun chooseStart(channels: List<Channel>, last: String, stats: Map<String, SourceStat>): Channel? {
        val playable = channels.filter { it.sources.isNotEmpty() }
        playable.firstOrNull { it.id == last }?.let { return it }
        playable.firstOrNull { c ->
            c.sources.any { (stats[sourceId(it.url)]?.successes ?: 0) > 0 && stats[sourceId(it.url)]?.error.isNullOrEmpty() }
        }?.let { return it }
        return playable.firstOrNull()
    }
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

private val ALLOWED_STREAM_SCHEMES = setOf("http", "https", "udp", "rtp", "rtsp", "rtmp")

/**
 * Validates playable live stream URIs (HTTP/HTTPS/UDP/RTP/RTSP/RTMP) and normalizes udp://@host:port
 * or rtp://@host:port shorthand commonly exported by IPTV boxes and local UDP scanners.
 */
fun streamUri(value: String): URI {
    val cleaned = value.trim()
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replaceFirst(Regex("^(?i)(udp|rtp)://@"), "$1://")
    require(cleaned.length <= 8192 && cleaned.none { it <= '\u001f' }) { "线路地址包含无效字符" }
    val uri = URI(cleaned)
    val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
    require(scheme in ALLOWED_STREAM_SCHEMES) {
        "只支持 HTTP、HTTPS、UDP、RTP、RTSP 或 RTMP 地址"
    }
    require(uri.userInfo == null) { "请使用不含用户名和密码的地址" }
    if (scheme == "udp" || scheme == "rtp") {
        require(!uri.host.isNullOrBlank() || uri.port in 1..65535) { "UDP/RTP 地址缺少主机或端口" }
    } else {
        require(!uri.host.isNullOrBlank()) { "线路地址缺少主机名" }
    }
    return uri
}

object TextDecoding {
    private val gb18030: Charset by lazy { Charset.forName("GB18030") }

    /** Decodes UTF-8 (with optional BOM) first, falling back cleanly to GB18030 for Chinese ANSI .txt/.m3u files. */
    fun decodePlaylistBytes(bytes: ByteArray): String {
        decodeStrict(bytes, Charsets.UTF_8)?.let { return it.removePrefix("\uFEFF") }
        decodeStrict(bytes, gb18030)?.let { return it.removePrefix("\uFEFF") }
        throw IllegalArgumentException("订阅内容包含无法识别的字符编码")
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}

data class ParsedStreamUrl(val url: String, val headers: Map<String, String>)

/** Splits TVBox `$routeTag` suffixes and Kodi/TVBox `|Key=Value` pipe headers from raw stream URLs. */
object StreamUrlParser {
    fun parse(rawToken: String): ParsedStreamUrl? {
        var token = rawToken.trim().replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        if (token.isEmpty()) return null

        val pipeIdx = token.indexOf('|')
        var urlPart = if (pipeIdx > 0) token.substring(0, pipeIdx).trim() else token
        var headerPart = if (pipeIdx > 0) token.substring(pipeIdx + 1).trim() else ""

        if (headerPart.isNotEmpty()) {
            headerPart = stripRouteTag(headerPart)
        } else {
            urlPart = stripRouteTag(urlPart)
        }

        val uri = runCatching { streamUri(urlPart) }.getOrNull() ?: return null
        val normalizedUrl = uri.toString()
        val parsedHeaders = linkedMapOf<String, String>()
        if (headerPart.isNotEmpty()) {
            headerPart.split('&').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = canonicalHeaderName(pair.substring(0, eq).trim())
                    val value = decodePercent(pair.substring(eq + 1).trim())
                    if (key != null && value.length < 2048 && value.none { it <= '\u001f' }) {
                        parsedHeaders[key] = value
                    }
                }
            }
        }
        return ParsedStreamUrl(normalizedUrl, parsedHeaders)
    }

    internal fun canonicalHeaderName(raw: String): String? = when (raw.lowercase(Locale.ROOT).trim()) {
        "user-agent", "http-user-agent" -> "User-Agent"
        "referer", "referrer", "http-referer", "http-referrer" -> "Referer"
        "origin", "http-origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> null
    }

    private fun stripRouteTag(input: String): String {
        val dollar = input.lastIndexOf('$')
        if (dollar <= 0) return input
        val suffix = input.substring(dollar + 1)
        val before = input.substring(0, dollar)
        val inQueryWithEquals = before.substringAfter('?', "").isNotEmpty() && suffix.contains('=')
        if (!inQueryWithEquals && !suffix.contains('/') && suffix.length <= 48) {
            return before.trim()
        }
        return input
    }

    private fun decodePercent(raw: String): String = runCatching {
        java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(raw)
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
    const val SINGLE_STREAM_GROUP = "单频道直连"
    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""")
    private val genericUrlStems = setOf("index", "live", "playlist", "stream", "master", "chunklist", "manifest", "1", "2")
    private val hlsTags = listOf(
        "#EXT-X-TARGETDURATION",
        "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-STREAM-INF",
        "#EXT-X-VERSION",
        "#EXT-X-PLAYLIST-TYPE",
        "#EXT-X-KEY",
        "#EXT-X-MAP",
        "#EXT-X-PART-INF",
        "#EXT-X-I-FRAME-STREAM-INF",
        "#EXT-X-DISCONTINUITY",
        "#EXT-X-ENDLIST",
        "#EXT-X-BYTERANGE"
    )
    private val multiUrlSplit = Regex("#(?=(?:https?|udp|rtp|rtsp|rtmp)://)", RegexOption.IGNORE_CASE)

    fun isHlsManifest(text: String): Boolean {
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        val headerLine = lines.firstOrNull { it.isNotEmpty() } ?: return false
        if (!headerLine.startsWith("#EXTM3U", ignoreCase = true)) return false
        val hasChannelListExtinf = lines.any { line ->
            line.startsWith("#EXTINF:-1", ignoreCase = true) ||
                line.contains("tvg-id=", ignoreCase = true) ||
                line.contains("tvg-name=", ignoreCase = true) ||
                line.contains("group-title=", ignoreCase = true) ||
                line.contains("http-user-agent=", ignoreCase = true)
        }
        if (hasChannelListExtinf) return false
        return lines.any { line ->
            val upper = line.uppercase(Locale.ROOT)
            hlsTags.any { upper.startsWith(it) }
        }
    }

    /**
     * Parses a user import payload across single-channel HLS (.m3u8), standard/extended M3U
     * (including APTV metadata tags and inline headers), TVBox/DIYP TXT (#genre#), and JSON lists.
     */
    fun parseImport(text: String, sourceUrl: String = "", explicitName: String = ""): ParsedPlaylist {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "频道表不能超过 2 MB" }
        val clean = text.removePrefix("\uFEFF").trimStart()
        val lines = clean.lineSequence().map { it.trim() }.toList()
        val headerLine = lines.firstOrNull { it.isNotEmpty() }
            ?: throw IllegalArgumentException("订阅内容为空")

        if (isHlsManifest(clean)) {
            val trimmedUrl = sourceUrl.trim()
            if (trimmedUrl.isNotEmpty()) {
                return singleStreamPlaylist(trimmedUrl, explicitName)
            }
            // Local .m3u8 file: only master playlists carrying an absolute HTTP/HTTPS/stream variant URL can be imported without a base URL.
            val variantUrl = findAbsoluteHlsVariantUrl(lines)
                ?: error("这是本地 HLS 分片清单，缺少服务器基址；请直接粘贴该 .m3u8 的完整 HTTP/HTTPS 网址导入")
            return singleStreamPlaylist(variantUrl, explicitName)
        }

        if (clean.startsWith("[") || clean.startsWith("{")) {
            return parseJson(clean)
        }

        val isM3u = headerLine.startsWith("#EXTM3U", ignoreCase = true) ||
            lines.any { it.startsWith("#EXTINF:", ignoreCase = true) }
        if (isM3u) {
            return parse(clean)
        }

        if (lines.any { looksLikeTxtLine(it) }) {
            return parseTxt(clean)
        }

        throw IllegalArgumentException("不是有效的直播源订阅（支持 M3U / M3U8、TXT 频道表、JSON 或单频道直连流）")
    }

    fun singleStreamPlaylist(streamUrl: String, explicitName: String = ""): ParsedPlaylist {
        val parsed = StreamUrlParser.parse(streamUrl) ?: ParsedStreamUrl(streamUri(streamUrl).toString(), emptyMap())
        val uri = URI(parsed.url)
        val rawCandidate = explicitName.trim().take(80).ifBlank { inferChannelName(uri) }
        val identity = ChannelIdentity.resolve("", rawCandidate)
        val entry = Entry(identity.first, identity.second, SINGLE_STREAM_GROUP, parsed.url, parsed.headers)
        return ParsedPlaylist(listOf(entry), skipped = 0)
    }

    private fun inferChannelName(uri: URI): String {
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val lastStem = segments.lastOrNull()?.substringBeforeLast('.')?.trim().orEmpty()
        if (lastStem.isNotEmpty() && lastStem.lowercase(Locale.ROOT) !in genericUrlStems) {
            return lastStem.take(40)
        }
        val parent = segments.dropLast(1).lastOrNull()?.trim().orEmpty()
        if (parent.isNotEmpty() && parent.lowercase(Locale.ROOT) !in setOf("live", "hls", "stream", "tv", "video")) {
            return parent.take(40)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if ((scheme == "udp" || scheme == "rtp") && !uri.host.isNullOrBlank()) {
            return if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
        }
        return uri.host.orEmpty().ifBlank { "直播流" }
    }

    private fun findAbsoluteHlsVariantUrl(lines: List<String>): String? {
        var expectVariantUrl = false
        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                expectVariantUrl = true
                continue
            }
            if (line.startsWith("#")) continue
            if (expectVariantUrl) {
                expectVariantUrl = false
                if (runCatching { streamUri(line) }.isSuccess) return line
            }
        }
        return null
    }

    fun parse(text: String): ParsedPlaylist {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "频道表不能超过 2 MB" }
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        val headerLine = lines.firstOrNull { it.isNotEmpty() }
        val hasExtm3u = headerLine?.startsWith("#EXTM3U", ignoreCase = true) == true
        val hasExtinf = lines.any { it.startsWith("#EXTINF:", ignoreCase = true) }
        require(hasExtm3u || hasExtinf) {
            "不是有效的 M3U 频道表，文件需要以 #EXTM3U 开头或包含 #EXTINF 条目"
        }
        require(!isHlsManifest(text)) { "这是单频道 HLS 视频清单，请使用地址导入或导入 M3U 频道表" }
        val headerAttrs = if (hasExtm3u) {
            attributes.findAll(headerLine!!).associate { m ->
                m.groupValues[1].lowercase(Locale.ROOT) to m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            }
        } else emptyMap()
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
        var currentExtGrp = ""
        var skipped = 0
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    if (metadata != null) skipped++
                    var quoted = '\u0000'
                    val comma = line.indexOfFirstWithIndex { index, c ->
                        if (c == '"' || c == '\'') {
                            if (quoted == c) quoted = '\u0000' else if (quoted == '\u0000') quoted = c
                        }
                        (c == ',' || c == '，') && quoted == '\u0000' && index > 8
                    }
                    if (comma < 0) { metadata = null; skipped++; continue }
                    metadata = attributes.findAll(line.substring(0, comma)).associate { m ->
                        m.groupValues[1].lowercase(Locale.ROOT) to m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
                    }
                    name = line.substring(comma + 1).trim().take(200)
                    headers = linkedMapOf()
                    extractInlineHeaders(metadata, headers)
                    profile = ""
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    currentExtGrp = line.substringAfter(':').trim().take(80)
                }
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    val jsonRaw = line.substringAfter(':').trim()
                    extractJsonHeaders(jsonRaw, headers)
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val value = line.substringAfter(':')
                    val key = value.substringBefore('=').lowercase(Locale.ROOT).trim()
                    val optValue = value.substringAfter('=', "").trim()
                    if (key == "swifttv-profile" && optValue.length <= 64 && optValue.none { it <= '\u001f' }) {
                        profile = optValue
                    } else {
                        val header = StreamUrlParser.canonicalHeaderName(key)
                        if (header != null && optValue.length < 2048 && optValue.none { it <= '\u001f' }) {
                            headers[header] = optValue
                        }
                    }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val meta = metadata
                    if (meta == null || name.isBlank()) { skipped++; continue }
                    val parsedUrl = StreamUrlParser.parse(line)
                    if (parsedUrl == null) {
                        skipped++
                    } else {
                        val identity = ChannelIdentity.resolve(meta["tvg-id"].orEmpty(), meta["tvg-name"].orEmpty().ifBlank { name })
                        val rawLogo = meta["tvg-logo"].orEmpty().trim()
                        val validLogo = if (rawLogo.isNotEmpty() && runCatching { httpUri(rawLogo) }.isSuccess) rawLogo else ""
                        val group = meta["group-title"].orEmpty().ifBlank { currentExtGrp }.ifBlank { "其他频道" }
                        val mergedHeaders = LinkedHashMap(headers).apply { putAll(parsedUrl.headers) }
                        result += Entry(
                            identity.first,
                            identity.second,
                            group,
                            parsedUrl.url,
                            mergedHeaders,
                            profile = profile,
                            logoUrl = validLogo
                        )
                    }
                    metadata = null
                    profile = ""
                    require(result.size <= 10000) { "频道表最多支持 10000 条线路" }
                }
            }
        }
        require(result.isNotEmpty()) { "频道表中没有有效的可播线路" }
        return ParsedPlaylist(result.distinctBy { it.channelId to it.url }, skipped, epgUrls)
    }

    /** Parses TVBox / DIYP / iptv-api TXT format: `group,#genre#`, `Channel,url1#url2$tag|Referer=...`. */
    fun parseTxt(text: String): ParsedPlaylist {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "频道表不能超过 2 MB" }
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        val result = mutableListOf<Entry>()
        var currentGroup = "其他频道"
        var skipped = 0

        for (line in lines) {
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("//")) continue
            if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                currentGroup = line.substringAfter(':').trim().ifBlank { "其他频道" }
                continue
            }
            if (line.startsWith("#")) continue

            val genreMatch = Regex("^(.+?)[,，]\\s*#genre#\\s*$", RegexOption.IGNORE_CASE).matchEntire(line)
            if (genreMatch != null) {
                currentGroup = genreMatch.groupValues[1].trim().ifBlank { "其他频道" }
                continue
            }

            val sepIdx = findTxtSeparatorIndex(line)
            if (sepIdx <= 0) continue

            val rawName = line.substring(0, sepIdx).trim().take(200)
            val rhs = line.substring(sepIdx + 1).trim()
            if (rawName.isEmpty() || rhs.isEmpty()) {
                skipped++
                continue
            }

            val identity = ChannelIdentity.resolve("", rawName)
            val candidates = rhs.split(multiUrlSplit)
            var matchedAny = false
            for (candidate in candidates) {
                val parsed = StreamUrlParser.parse(candidate)
                if (parsed == null) {
                    if (candidate.contains("://")) skipped++
                    continue
                }
                matchedAny = true
                result += Entry(
                    channelId = identity.first,
                    name = identity.second,
                    group = currentGroup,
                    url = parsed.url,
                    headers = parsed.headers
                )
                require(result.size <= 10000) { "频道表最多支持 10000 条线路" }
            }
            if (!matchedAny && !looksLikeDateOrInfoRow(rawName)) {
                // Only count genuine malformed stream lines when right-hand side attempted a URL.
                if (rhs.contains("://")) skipped++
            }
        }
        require(result.isNotEmpty()) { "TXT 频道表中没有识别到有效的直播流地址" }
        return ParsedPlaylist(result.distinctBy { it.channelId to it.url }, skipped, emptyList())
    }

    /** Parses simple JSON channel lists (`[{"name":..., "urls":[...]}]` or `{"channels":[...]}` / `{"lives":[...]}`). */
    fun parseJson(text: String): ParsedPlaylist {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "频道表不能超过 2 MB" }
        val result = mutableListOf<Entry>()
        var skipped = 0
        val array = when {
            text.startsWith("[") -> JSONArray(text)
            text.startsWith("{") -> {
                val root = JSONObject(text)
                root.optJSONArray("channels")
                    ?: root.optJSONArray("lives")
                    ?: root.optJSONArray("data")
                    ?: error("JSON 中缺少 channels 或 lives 频道数组")
            }
            else -> error("无效的 JSON 频道表")
        }

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // Support nested TVBox groups [{"name":"央视频道","channels":[...]}] or flat items.
            val nested = item.optJSONArray("channels")
            if (nested != null) {
                val groupName = item.optString("name").trim().ifBlank { "其他频道" }
                for (j in 0 until nested.length()) {
                    val ch = nested.optJSONObject(j) ?: continue
                    skipped += appendJsonChannel(ch, groupName, result)
                }
                continue
            }
            val groupName = item.optString("group").trim().ifBlank { "其他频道" }
            skipped += appendJsonChannel(item, groupName, result)
        }
        require(result.isNotEmpty()) { "JSON 频道表中没有有效的直播流地址" }
        return ParsedPlaylist(result.distinctBy { it.channelId to it.url }, skipped, emptyList())
    }

    private fun appendJsonChannel(item: JSONObject, defaultGroup: String, out: MutableList<Entry>): Int {
        val rawName = item.optString("name").trim().take(200)
        if (rawName.isEmpty()) return 1
        val group = item.optString("group").trim().ifBlank { defaultGroup }
        val logoRaw = item.optString("logo").trim()
        val validLogo = if (logoRaw.isNotEmpty() && runCatching { httpUri(logoRaw) }.isSuccess) logoRaw else ""
        val identity = ChannelIdentity.resolve(item.optString("tvgId"), rawName)
        val extraHeaders = linkedMapOf<String, String>()
        item.optJSONObject("headers")?.let { hObj ->
            hObj.keys().forEach { k ->
                val canon = StreamUrlParser.canonicalHeaderName(k)
                val v = hObj.optString(k).trim()
                if (canon != null && v.isNotEmpty() && v.length < 2048 && v.none { it <= '\u001f' }) {
                    extraHeaders[canon] = v
                }
            }
        }
        val urls = buildList {
            item.optJSONArray("urls")?.let { arr ->
                for (idx in 0 until arr.length()) add(arr.optString(idx))
            }
            item.optString("url").takeIf { it.isNotBlank() }?.let { add(it) }
        }
        var skipped = 0
        for (raw in urls) {
            val parsed = StreamUrlParser.parse(raw)
            if (parsed == null) {
                skipped++
                continue
            }
            val mergedHeaders = LinkedHashMap(extraHeaders).apply { putAll(parsed.headers) }
            out += Entry(
                channelId = identity.first,
                name = identity.second,
                group = group,
                url = parsed.url,
                headers = mergedHeaders,
                logoUrl = validLogo
            )
            require(out.size <= 10000) { "频道表最多支持 10000 条线路" }
        }
        return skipped
    }

    private fun extractInlineHeaders(metadata: Map<String, String>, headers: MutableMap<String, String>) {
        for ((k, v) in metadata) {
            val cleanVal = v.trim()
            if (cleanVal.isEmpty() || cleanVal.length >= 2048 || cleanVal.any { it <= '\u001f' }) continue
            if (k == "http-header") {
                if (cleanVal.startsWith("{")) {
                    extractJsonHeaders(cleanVal, headers)
                } else {
                    cleanVal.split(';').forEach { segment ->
                        val sep = segment.indexOf('=').takeIf { it > 0 } ?: segment.indexOf(':').takeIf { it > 0 }
                        if (sep != null) {
                            val hKey = StreamUrlParser.canonicalHeaderName(segment.substring(0, sep))
                            val hVal = segment.substring(sep + 1).trim()
                            if (hKey != null && hVal.isNotEmpty() && hVal.none { it <= '\u001f' }) {
                                headers[hKey] = hVal
                            }
                        }
                    }
                }
            } else {
                val canon = StreamUrlParser.canonicalHeaderName(k)
                if (canon != null) headers[canon] = cleanVal
            }
        }
    }

    private fun extractJsonHeaders(jsonRaw: String, headers: MutableMap<String, String>) {
        runCatching {
            val obj = JSONObject(jsonRaw)
            obj.keys().forEach { k ->
                val canon = StreamUrlParser.canonicalHeaderName(k)
                val v = obj.optString(k).trim()
                if (canon != null && v.isNotEmpty() && v.length < 2048 && v.none { it <= '\u001f' }) {
                    headers[canon] = v
                }
            }
        }
    }

    private fun looksLikeTxtLine(line: String): Boolean {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return false
        if (Regex("^.+?[,，]\\s*#genre#\\s*$", RegexOption.IGNORE_CASE).matches(line)) return true
        val sep = findTxtSeparatorIndex(line)
        if (sep <= 0) return false
        val rhs = line.substring(sep + 1).trim()
        return Regex("^(?:https?|udp|rtp|rtsp|rtmp)://", RegexOption.IGNORE_CASE).containsMatchIn(rhs)
    }

    private fun findTxtSeparatorIndex(line: String): Int {
        for (i in line.indices) {
            val c = line[i]
            if (c == ',' || c == '，' || c == '\t') return i
        }
        return -1
    }

    private fun looksLikeDateOrInfoRow(name: String): Boolean =
        Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*").matches(name)

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
        sorted.forEach {
            val host = runCatching { streamUri(it.url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            if (host.isNotEmpty() && hosts.add(host)) independent += it else repeated += it
        }
        return independent + repeated
    }
}

object DiagnosticRedaction {
    fun origin(url: String): String = try {
        val uri = streamUri(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host.orEmpty()
        val authority = if (host.isNotEmpty()) host + (if (uri.port > 0) ":" + uri.port else "") else ":" + uri.port
        scheme + "://" + authority + "/…#" + sourceId(url)
    } catch (_: Exception) { "[invalid URL]" }
    fun message(value: String): String =
        value.replace(Regex("(?:https?|udp|rtp|rtsp|rtmp)://[^\\s\"<>]+", RegexOption.IGNORE_CASE)) { origin(it.value) }.take(400)
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
