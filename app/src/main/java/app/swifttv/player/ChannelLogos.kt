package app.swifttv.player

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

/** Pure multi-tier logo key resolver so user-imported playlists match local & remote logos reliably. */
object ChannelLogoMatcher {
    private val stripPunct = Regex("[\\s\\-_()（）\\[\\]【】·•/\\\\]+")
    private val trailingQuality = Regex(
        "(?i)(?:fullhd|fhd|uhd|hd|sd|1080[pi]?|720p|576[pi]?|50fps|60fps|25fps|hevc|h265|265|hdr|ipv4|ipv6|高清|超高清|超清|标清|蓝光|直播|测试|备用|主线|专线)$"
    )
    private val genericSuffixes = listOf("频道", "电视台", "广播电视台", "教育台", "综合台")
    private val cctvWithSub = Regex("^(?:cctv|中央|央视)(?:电视台)?0?(\\d{1,2})(\\+?)([\\u4e00-\\u9fa5a-z]*)$")
    private val cctvChineseNum = Regex("^(?:cctv|中央|央视)(?:电视台)?([一二三四五六七八九十]+)[套台]?([\\u4e00-\\u9fa5]*)$")
    private val cetvPattern = Regex("^(?:cetv|中国教育)(?:电视台)?0?([1-4])[套台]?$")

    private val traditionalToSimplified = mapOf(
        '鳳' to '凤', '凰' to '凰', '衛' to '卫', '視' to '视', '體' to '体', '育' to '育',
        '電' to '电', '影' to '影', '臺' to '台', '台' to '台', '新' to '新', '聞' to '闻',
        '綜' to '综', '合' to '合', '紀' to '纪', '錄' to '录', '廣' to '广', '東' to '东',
        '華' to '华', '戲' to '戏', '財' to '财', '經' to '经', '動' to '动', '畫' to '画',
        '娛' to '娱', '樂' to '乐', '資' to '资', '訊' to '讯', '頻' to '频', '劇' to '剧',
        '場' to '场', '藝' to '艺', '兒' to '儿', '國' to '国', '際' to '际', '語' to '语',
        '歷' to '历', '農' to '农', '業' to '业', '軍' to '军', '連' to '连', '龍' to '龙',
        '陝' to '陕', '蘇' to '苏', '遼' to '辽', '寧' to '宁', '雲' to '云', '貴' to '贵',
        '慶' to '庆', '灣' to '湾', '區' to '区', '門' to '门'
    )

    private val explicitAliases = mapOf(
        "cgtn西语" to listOf("cgtn西班牙语", "cgtn"),
        "cgtn阿语" to listOf("cgtn阿拉伯语", "cgtn"),
        "cgtn英语" to listOf("cgtn英语", "cgtn"),
        "cgtn英" to listOf("cgtn英语", "cgtn"),
        "cgtn法" to listOf("cgtn法语", "cgtn"),
        "cgtn俄" to listOf("cgtn俄语", "cgtn"),
        "cgtn纪录片" to listOf("cgtn纪录", "cgtn记录"),
        "cctv5plus" to listOf("cctv5+", "cctv-5+"),
        "cctv5p" to listOf("cctv5+", "cctv-5+"),
        "凤凰卫视中文台" to listOf("凤凰中文"),
        "凤凰卫视资讯台" to listOf("凤凰资讯"),
        "凤凰卫视香港台" to listOf("凤凰香港"),
        "tvb翡翠台" to listOf("翡翠台"),
        "无线翡翠台" to listOf("翡翠台"),
        "上海东方卫视" to listOf("东方卫视"),
        "湖南" to listOf("湖南卫视"),
        "浙江" to listOf("浙江卫视"),
        "江苏" to listOf("江苏卫视"),
        "东方" to listOf("东方卫视"),
        "北京" to listOf("北京卫视"),
        "河南" to listOf("河南卫视"),
        "广东" to listOf("广东卫视"),
        "深圳" to listOf("深圳卫视"),
        "山东" to listOf("山东卫视"),
        "四川" to listOf("四川卫视"),
        "安徽" to listOf("安徽卫视"),
        "湖北" to listOf("湖北卫视"),
        "辽宁" to listOf("辽宁卫视"),
        "黑龙江" to listOf("黑龙江卫视")
    )

    private fun toSimplified(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) sb.append(traditionalToSimplified[ch] ?: ch)
        return sb.toString()
    }

    private fun chineseNumeralToInt(s: String): Int? = when (s) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9
        "十" -> 10; "十一" -> 11; "十二" -> 12; "十三" -> 13; "十四" -> 14; "十五" -> 15; "十六" -> 16; "十七" -> 17
        else -> null
    }

    fun normalize(raw: String): String =
        toSimplified(raw.trim()).lowercase(Locale.ROOT).replace('＋', '+').replace(stripPunct, "")

    /** Clean human-readable display stem suitable for public online logo fallback CDNs. */
    fun publicLookupNames(channel: Channel): List<String> {
        val out = linkedSetOf<String>()
        fun addClean(raw: String) {
            var s = toSimplified(raw.trim()).replace('＋', '+')
            s = s.replace(Regex("[\\[\\]【】()（）]"), " ").replace(Regex("\\s+"), " ").trim()
            while (trailingQuality.containsMatchIn(s) && s.length > 2) {
                s = s.replace(trailingQuality, "").trim()
            }
            if (s.length >= 2) out += s
            val compact = s.replace(" ", "")
            if (compact.length >= 2) out += compact
            explicitAliases[normalize(s)]?.forEach { out += it }
        }
        addClean(channel.name)
        return out.toList()
    }

    fun candidateKeys(channel: Channel): List<String> {
        val out = linkedSetOf<String>()
        fun addVariants(raw: String) {
            if (raw.isBlank()) return
            val n = normalize(raw)
            if (n.isEmpty()) return
            out += n
            explicitAliases[n]?.forEach { out += it }

            // Strip repeated trailing quality/codec/network markers while keeping 4K exact key first
            var stripped = n
            while (trailingQuality.containsMatchIn(stripped) && stripped.length > 2) {
                stripped = stripped.replace(trailingQuality, "")
                if (stripped.isNotEmpty()) {
                    out += stripped
                    explicitAliases[stripped]?.forEach { out += it }
                }
            }
            if (stripped.endsWith("4k") && stripped != "cctv4k" && stripped.length > 3) {
                val base = stripped.removeSuffix("4k")
                out += base
                explicitAliases[base]?.forEach { out += it }
            }
            for (suffix in genericSuffixes) {
                if (stripped.endsWith(suffix) && stripped.length > suffix.length + 1) {
                    val base = stripped.removeSuffix(suffix)
                    out += base
                    explicitAliases[base]?.forEach { out += it }
                }
            }

            // CCTV canonicalization: CCTV-01综合 / 中央1台 / CCTV5+体育赛事 / CCTV5PLUS -> cctv1 / cctv5+
            cctvWithSub.matchEntire(stripped)?.let { m ->
                val num = m.groupValues[1].toIntOrNull()
                val plus = m.groupValues[2]
                val tail = m.groupValues[3]
                if (num != null && num in 1..17) {
                    val isPlus = plus == "+" || tail.startsWith("plus") || tail == "p"
                    if (num == 5 && isPlus) {
                        out += "cctv5+"
                        out += "cctv-5+"
                    } else {
                        out += "cctv$num"
                        out += "cctv-$num"
                    }
                }
            }
            cctvChineseNum.matchEntire(stripped)?.let { m ->
                val num = chineseNumeralToInt(m.groupValues[1])
                if (num != null && num in 1..17) {
                    out += "cctv$num"
                    out += "cctv-$num"
                }
            }
            cetvPattern.matchEntire(stripped)?.let { m ->
                val num = m.groupValues[1]
                out += "cetv$num"
                out += "cetv-$num"
            }

            if (stripped.startsWith("cctv") && stripped != "cctv4k" && stripped.getOrNull(4)?.isDigit() != true) {
                val sub = stripped.removePrefix("cctv")
                if (sub.isNotEmpty()) out += sub
            }
        }

        out += channel.id.lowercase(Locale.ROOT)
        if (channel.id.startsWith("id:") || channel.id.startsWith("name:")) {
            addVariants(channel.id.substringAfter(':'))
        }
        addVariants(channel.name)
        if (channel.logoUrl.isNotBlank()) {
            runCatching {
                val path = httpUri(channel.logoUrl).rawPath.orEmpty()
                val fileName = URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
                val stem = fileName.substringBeforeLast('.')
                addVariants(stem)
            }
        }
        return out.filter { it.isNotEmpty() }
    }

    fun resolveAssetFile(channel: Channel, index: Map<String, String>): String? {
        for (key in candidateKeys(channel)) {
            index[key]?.let { return it }
        }
        return null
    }
}

/**
 * Three-tier channel logo engine with isolated local & remote thread pools:
 * 1. Instant offline bundled WebP station logos (localWorker, never blocked by HTTP!)
 * 2. Disk-cached remote tvg-logo fetcher with proxy unwrapping & jsDelivr mirror fallback
 * 2.5. Optional smart public logo CDN fallback when tvg-logo is absent or 404
 * 3. Smoked-crystal EditorialMonogram fallback
 */
class ChannelLogos(context: Context, private val network: Network? = null) : AutoCloseable {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val diskCacheDir = File(appContext.cacheDir, "remote-logos").apply { mkdirs() }
    private val localWorker = Executors.newFixedThreadPool(2)
    private val remoteWorker = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val cacheBytes: Int = run {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null && (am.isLowRamDevice || am.memoryClass <= 96)) 1280 * 1024 else 4 * 1024 * 1024
    }
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val waiting = mutableMapOf<String, MutableList<WeakReference<ImageView>>>()
    private val failedUntilMs = mutableMapOf<String, Long>()
    @Volatile private var index: Map<String, String> = emptyMap()
    @Volatile private var indexLoaded = false
    @Volatile private var closed = false

    init {
        localWorker.execute { ensureIndexLoaded() }
    }

    private fun ensureIndexLoaded(): Map<String, String> {
        if (indexLoaded) return index
        synchronized(this) {
            if (indexLoaded) return index
            index = runCatching {
                val json = JSONObject(assets.open("channel-logos/logos.json").bufferedReader().use { it.readText() })
                buildMap(json.length()) {
                    for (k in json.keys()) put(k.lowercase(Locale.ROOT), json.getString(k))
                }
            }.getOrElse { emptyMap() }
            indexLoaded = true
            return index
        }
    }

    fun cacheSizeBytes(): Long =
        diskCacheDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    fun clearCache(): Long {
        val freed = cacheSizeBytes()
        diskCacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        cache.evictAll()
        synchronized(waiting) { failedUntilMs.clear() }
        return freed
    }

    fun bind(view: ImageView, channel: Channel) {
        val cacheKey = channel.id + "|" + channel.logoUrl
        view.tag = cacheKey
        cache.get(cacheKey)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(EditorialMonogram(channel.name))
        val now = System.currentTimeMillis()
        synchronized(waiting) {
            if (closed) return
            val cooldown = failedUntilMs[cacheKey]
            if (cooldown != null && now < cooldown) return
            val list = waiting[cacheKey]
            if (list != null) { list.add(WeakReference(view)); return }
            waiting[cacheKey] = mutableListOf(WeakReference(view))
        }
        // Step 1: Fast local-only lookup (offline assets + disk cache) on dedicated localWorker
        localWorker.execute {
            val localBitmap = cache.get(cacheKey) ?: loadLocalBitmap(channel)
            if (localBitmap != null) {
                deliverBitmap(cacheKey, localBitmap)
                return@execute
            }
            if (closed || network == null) {
                markFailed(cacheKey)
                return@execute
            }
            // Step 2: Network fetch on separate remoteWorker so local assets never stall
            remoteWorker.execute {
                val remoteBitmap = loadRemoteBitmap(channel)
                if (remoteBitmap != null) {
                    deliverBitmap(cacheKey, remoteBitmap)
                } else {
                    markFailed(cacheKey)
                }
            }
        }
    }

    private fun deliverBitmap(cacheKey: String, bitmap: Bitmap) {
        cache.put(cacheKey, bitmap)
        val views = synchronized(waiting) {
            failedUntilMs.remove(cacheKey)
            waiting.remove(cacheKey).orEmpty()
        }
        if (!closed && views.isNotEmpty()) {
            ui.post {
                if (!closed) {
                    views.forEach { ref -> ref.get()?.takeIf { it.tag == cacheKey }?.setImageBitmap(bitmap) }
                }
            }
        }
    }

    private fun markFailed(cacheKey: String) {
        synchronized(waiting) {
            failedUntilMs[cacheKey] = System.currentTimeMillis() + 60_000L
            waiting.remove(cacheKey)
        }
    }

    private fun diskFileFor(channel: Channel): File {
        val key = if (channel.logoUrl.isNotBlank()) channel.logoUrl else "name:${ChannelLogoMatcher.normalize(channel.name)}"
        return File(diskCacheDir, "${sourceId(key)}.webp")
    }

    private fun loadLocalBitmap(channel: Channel): Bitmap? {
        val idx = ensureIndexLoaded()
        val assetFile = ChannelLogoMatcher.resolveAssetFile(channel, idx)
        if (!assetFile.isNullOrEmpty() && '/' !in assetFile && '\\' !in assetFile) {
            decodeAsset(assetFile)?.let { return it }
        }
        val diskFile = diskFileFor(channel)
        if (diskFile.isFile && diskFile.length() > 32) {
            decodeBytes(diskFile.readBytes())?.let { return it }
        }
        return null
    }

    private fun loadRemoteBitmap(channel: Channel): Bitmap? {
        val net = network ?: return null
        val candidates = linkedSetOf<String>()
        if (channel.logoUrl.isNotBlank()) {
            candidates.addAll(Network.logoCandidates(channel.logoUrl))
        }
        if (TvStyle.smartLogoLookup(appContext)) {
            candidates.addAll(Network.publicLogoFallbackCandidates(ChannelLogoMatcher.publicLookupNames(channel)))
        }
        if (candidates.isEmpty()) return null

        val downloaded = runCatching { net.downloadFirstLogoBytes(candidates.toList()) }.getOrNull() ?: return null
        val decoded = decodeBytes(downloaded) ?: return null
        val diskFile = diskFileFor(channel)
        runCatching {
            val tmp = File(diskCacheDir, "${diskFile.nameWithoutExtension}.tmp")
            tmp.outputStream().use { out ->
                val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                decoded.compress(fmt, 92, out)
            }
            tmp.renameTo(diskFile)
        }
        return decoded
    }

    private fun decodeAsset(file: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open("channel-logos/$file").use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = sampleOptions(bounds.outWidth, bounds.outHeight)
        assets.open("channel-logos/$file").use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun decodeBytes(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = sampleOptions(bounds.outWidth, bounds.outHeight)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private fun sampleOptions(outWidth: Int, outHeight: Int) = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = 1
        while (outWidth / inSampleSize > 256 || outHeight / inSampleSize > 128) inSampleSize *= 2
    }

    override fun close() {
        closed = true
        localWorker.shutdownNow()
        remoteWorker.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        cache.evictAll()
        synchronized(waiting) { waiting.clear(); failedUntilMs.clear() }
    }

    /** Refined smoked-crystal monogram badge when neither offline nor remote logo exists. */
    class EditorialMonogram(name: String) : Drawable() {
        private val value = name.trim().take(4)
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(32, 240, 246, 255) }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f; color = Color.argb(46, 240, 246, 255)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(236, 242, 250)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val rect = RectF()
        private var onPorcelain = false

        fun setOnPorcelain(focused: Boolean) {
            if (onPorcelain == focused) return
            onPorcelain = focused
            if (focused) {
                bgPaint.color = Color.argb(20, 10, 13, 19)
                strokePaint.color = Color.argb(54, 10, 13, 19)
                textPaint.color = TvStyle.inkOnPorcelain
            } else {
                bgPaint.color = Color.argb(32, 240, 246, 255)
                strokePaint.color = Color.argb(46, 240, 246, 255)
                textPaint.color = Color.rgb(236, 242, 250)
            }
            invalidateSelf()
        }

        override fun onBoundsChange(bounds: Rect) {
            rect.set(bounds.left + 2f, bounds.top + 2f, bounds.right - 2f, bounds.bottom - 2f)
            textPaint.textSize = minOf(bounds.height() * .40f, bounds.width() / maxOf(value.length * .82f, 1.4f))
        }
        override fun draw(canvas: Canvas) {
            val r = minOf(rect.height() * 0.28f, 14f)
            canvas.drawRoundRect(rect, r, r, bgPaint)
            canvas.drawRoundRect(rect, r, r, strokePaint)
            val f = textPaint.fontMetrics
            canvas.drawText(value, rect.centerX(), rect.centerY() - (f.ascent + f.descent) / 2f, textPaint)
        }
        override fun setAlpha(alpha: Int) { textPaint.alpha = alpha; bgPaint.alpha = (alpha * 0.25f).toInt() }
        override fun setColorFilter(filter: ColorFilter?) { textPaint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
