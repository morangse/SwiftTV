package app.swifttv.player

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import javax.xml.parsers.SAXParserFactory

data class EpgProgram(val title: String, val startMs: Long, val stopMs: Long) {
    fun isLive(nowMs: Long): Boolean = nowMs in startMs until stopMs
    fun progress(nowMs: Long): Float =
        if (stopMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (stopMs - startMs).toFloat()).coerceIn(0f, 1f)
}

data class ChannelSchedule(val current: EpgProgram?, val next: EpgProgram?)

/**
 * Streaming, XXE-safe XMLTV parser compatible with both Android API 23+ and plain JVM unit tests.
 * Never fabricates placeholder programs: channels without matching real schedule entries return null.
 */
object XmlTvParser {
    private const val MAX_PROGRAMS_PER_CHANNEL = 36
    private const val MAX_TOTAL_PROGRAMS = 8000

    fun parse(
        input: InputStream,
        targetChannels: Map<String, String>,
        windowStartMs: Long,
        windowEndMs: Long
    ): Map<String, List<EpgProgram>> {
        if (targetChannels.isEmpty()) return emptyMap()
        val normalizedNames = targetChannels.entries.associate { (id, name) ->
            normalizeKey(name) to id
        }
        val channelMap = mutableMapOf<String, String>()
        val result = mutableMapOf<String, MutableList<EpgProgram>>()
        var totalStored = 0

        fun matchTarget(rawId: String, displayNames: List<String>): String? {
            val direct = ChannelIdentity.resolve(rawId, displayNames.firstOrNull().orEmpty().ifBlank { rawId }).first
            if (direct in targetChannels) return direct
            for (display in displayNames) {
                val byDisplay = ChannelIdentity.resolve("", display).first
                if (byDisplay in targetChannels) return byDisplay
                normalizedNames[normalizeKey(display)]?.let { return it }
            }
            normalizedNames[normalizeKey(rawId)]?.let { return it }
            return null
        }

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val reader = factory.newSAXParser().xmlReader
        // Block all external DTD/entity resolution (e.g. <!DOCTYPE tv SYSTEM "xmltv.dtd">) without failing valid XMLTV headers.
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }

        reader.contentHandler = object : DefaultHandler() {
            private var inChannelId: String? = null
            private val displayNames = mutableListOf<String>()
            private var progChannelRaw: String? = null
            private var progStartMs: Long = -1L
            private var progStopMs: Long = -1L
            private var capturingText = false
            private val textBuffer = StringBuilder()
            private var currentTitle = ""

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val tag = (qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()).lowercase(Locale.ROOT)
                when (tag) {
                    "channel" -> {
                        inChannelId = attributes.getValue("id")?.trim()
                        displayNames.clear()
                    }
                    "display-name" -> if (inChannelId != null) {
                        capturingText = true; textBuffer.setLength(0)
                    }
                    "programme" -> {
                        val rawCh = attributes.getValue("channel")?.trim().orEmpty()
                        val start = parseTimestamp(attributes.getValue("start"))
                        val stop = parseTimestamp(attributes.getValue("stop"))
                        if (rawCh.isNotEmpty() && start != null && stop != null && stop > start &&
                            stop > windowStartMs && start < windowEndMs && totalStored < MAX_TOTAL_PROGRAMS
                        ) {
                            progChannelRaw = rawCh
                            progStartMs = start
                            progStopMs = stop
                            currentTitle = ""
                        } else {
                            progChannelRaw = null
                        }
                    }
                    "title" -> if (progChannelRaw != null && currentTitle.isEmpty()) {
                        capturingText = true; textBuffer.setLength(0)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capturingText && textBuffer.length < 240) {
                    textBuffer.append(ch, start, minOf(length, 240 - textBuffer.length))
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val tag = (qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()).lowercase(Locale.ROOT)
                when (tag) {
                    "display-name" -> if (capturingText) {
                        capturingText = false
                        val v = textBuffer.toString().trim()
                        if (v.isNotEmpty()) displayNames += v
                    }
                    "channel" -> {
                        val id = inChannelId
                        if (!id.isNullOrEmpty()) {
                            matchTarget(id, displayNames)?.let { channelMap[id] = it }
                        }
                        inChannelId = null
                        displayNames.clear()
                    }
                    "title" -> if (capturingText) {
                        capturingText = false
                        currentTitle = textBuffer.toString().replace(Regex("\\s+"), " ").trim().take(120)
                    }
                    "programme" -> {
                        val raw = progChannelRaw
                        val title = currentTitle
                        if (raw != null && title.isNotEmpty()) {
                            val resolved = channelMap[raw] ?: matchTarget(raw, emptyList())?.also { channelMap[raw] = it }
                            if (resolved != null) {
                                val list = result.getOrPut(resolved) { mutableListOf() }
                                if (list.size < MAX_PROGRAMS_PER_CHANNEL) {
                                    list += EpgProgram(title, progStartMs, progStopMs)
                                    totalStored++
                                }
                            }
                        }
                        progChannelRaw = null
                        currentTitle = ""
                    }
                }
            }
        }

        reader.parse(InputSource(input))
        return result.mapValues { (_, list) ->
            list.sortedBy { it.startMs }.distinctBy { it.startMs to it.title }
        }
    }

    /** Parses XMLTV timestamp `YYYYMMDDHHMMSS [+-HHMM]` into epoch millis using API-23-safe UTC math. */
    internal fun parseTimestamp(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length < 12) return null
        for (i in 0 until minOf(14, trimmed.length)) {
            if (!trimmed[i].isDigit()) {
                if (i < 12) return null
                break
            }
        }
        val digitsLen = trimmed.takeWhile { it.isDigit() }.length
        if (digitsLen < 12) return null
        val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
        val month = trimmed.substring(4, 6).toIntOrNull() ?: return null
        val day = trimmed.substring(6, 8).toIntOrNull() ?: return null
        val hour = trimmed.substring(8, 10).toIntOrNull() ?: return null
        val minute = trimmed.substring(10, 12).toIntOrNull() ?: return null
        val second = if (digitsLen >= 14) trimmed.substring(12, 14).toIntOrNull() ?: 0 else 0
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

        val tzPart = trimmed.substring(digitsLen).trim()
        val offsetMinutes = parseOffsetMinutes(tzPart)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.ROOT).apply {
            isLenient = false
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
        val utcWall = runCatching { cal.timeInMillis }.getOrNull() ?: return null
        return utcWall - offsetMinutes * 60_000L
    }

    private fun parseOffsetMinutes(tz: String): Int {
        if (tz.isEmpty()) return 8 * 60 // Default China Standard Time (+08:00) for bare domestic XMLTV
        if (tz.equals("Z", true) || tz.equals("UTC", true) || tz.equals("GMT", true)) return 0
        val clean = tz.replace(":", "")
        if (clean.length >= 5 && (clean[0] == '+' || clean[0] == '-')) {
            val sign = if (clean[0] == '-') -1 else 1
            val hh = clean.substring(1, 3).toIntOrNull() ?: return 8 * 60
            val mm = clean.substring(3, 5).toIntOrNull() ?: return 8 * 60
            return sign * (hh * 60 + mm)
        }
        return 8 * 60
    }

    private fun normalizeKey(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[\\s\\-_+＋()（）\\[\\]高清超清标清fhdhdsd]"), "")

    fun query(programs: Map<String, List<EpgProgram>>, channelId: String, nowMs: Long): ChannelSchedule? {
        val list = programs[channelId] ?: return null
        val currentIdx = list.indexOfFirst { nowMs in it.startMs until it.stopMs }
        if (currentIdx >= 0) {
            val current = list[currentIdx]
            val next = list.getOrNull(currentIdx + 1)?.takeIf { it.startMs >= current.stopMs - 60_000L }
            return ChannelSchedule(current, next)
        }
        val upcoming = list.firstOrNull { it.startMs in nowMs..(nowMs + 2 * 3600_000L) }
        return if (upcoming != null) ChannelSchedule(null, upcoming) else null
    }
}

class EpgRepository(context: Context, private val network: Network) : AutoCloseable {
    private val cacheFile = AtomicFile(File(context.cacheDir, "epg-v1.json"))
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var schedules: Map<String, List<EpgProgram>> = emptyMap()
    @Volatile private var updatedAt: Long = 0L
    @Volatile private var lastUrlsKey: String = ""
    @Volatile private var refreshing = false
    @Volatile private var closed = false

    init {
        worker.execute { loadDiskCache() }
    }

    fun scheduleFor(channelId: String, nowMs: Long = System.currentTimeMillis()): ChannelSchedule? =
        XmlTvParser.query(schedules, channelId, nowMs)

    fun hasAnyData(): Boolean = schedules.isNotEmpty()

    fun refresh(urls: List<String>, channels: List<Channel>, force: Boolean = false, onUpdated: () -> Unit) {
        val cleanUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3)
        if (cleanUrls.isEmpty()) {
            if (schedules.isNotEmpty()) {
                schedules = emptyMap()
                lastUrlsKey = ""
                runCatching { cacheFile.delete() }
                onUpdated()
            }
            return
        }
        val now = System.currentTimeMillis()
        val key = cleanUrls.joinToString("|")
        if (closed || refreshing) return
        if (!force && key == lastUrlsKey && schedules.isNotEmpty() && now - updatedAt < 4 * 3600_000L) return
        val targets = channels.associate { it.id to it.name }
        if (targets.isEmpty()) return
        refreshing = true
        worker.execute {
            try {
                val merged = mutableMapOf<String, List<EpgProgram>>()
                val start = System.currentTimeMillis() - 2 * 3600_000L
                val end = System.currentTimeMillis() + 18 * 3600_000L
                for (url in cleanUrls) {
                    if (closed) break
                    runCatching {
                        network.streamXml(url) { stream ->
                            XmlTvParser.parse(stream, targets, start, end)
                        }
                    }.onSuccess { parsed ->
                        parsed.forEach { (chId, progs) ->
                            if (chId !in merged && progs.isNotEmpty()) merged[chId] = progs
                        }
                    }
                }
                if (!closed && merged.isNotEmpty()) {
                    schedules = merged
                    updatedAt = System.currentTimeMillis()
                    lastUrlsKey = key
                    saveDiskCache(key, updatedAt, merged)
                    onUpdated()
                }
            } finally {
                refreshing = false
            }
        }
    }

    private fun loadDiskCache() {
        runCatching {
            if (!cacheFile.baseFile.exists()) return
            val root = JSONObject(cacheFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            val savedAt = root.optLong("updatedAt")
            if (System.currentTimeMillis() - savedAt > 24 * 3600_000L) return
            val data = root.optJSONObject("channels") ?: return
            val loaded = mutableMapOf<String, List<EpgProgram>>()
            for (chId in data.keys()) {
                val arr = data.getJSONArray(chId)
                val list = (0 until arr.length()).map { idx ->
                    val o = arr.getJSONObject(idx)
                    EpgProgram(o.getString("t"), o.getLong("s"), o.getLong("e"))
                }
                if (list.isNotEmpty()) loaded[chId] = list
            }
            if (loaded.isNotEmpty() && schedules.isEmpty()) {
                schedules = loaded
                updatedAt = savedAt
                lastUrlsKey = root.optString("urlsKey")
            }
        }
    }

    private fun saveDiskCache(urlsKey: String, at: Long, map: Map<String, List<EpgProgram>>) {
        runCatching {
            val channelsJson = JSONObject()
            map.forEach { (id, list) ->
                channelsJson.put(id, JSONArray(list.map { p ->
                    JSONObject().put("t", p.title).put("s", p.startMs).put("e", p.stopMs)
                }))
            }
            val payload = JSONObject().put("version", 1).put("urlsKey", urlsKey).put("updatedAt", at).put("channels", channelsJson).toString()
            val out = cacheFile.startWrite()
            try {
                out.write(payload.toByteArray(Charsets.UTF_8))
                cacheFile.finishWrite(out)
            } catch (e: Exception) {
                cacheFile.failWrite(out)
            }
        }
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
    }
}
