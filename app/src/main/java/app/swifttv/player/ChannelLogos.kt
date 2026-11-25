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
    private val stripPunct = Regex("[\\s\\-_()（）\\[\\]·•]")
    private val trailingQuality = Regex("(?i)(?:fhd|fullhd|uhd|hd|sd|1080[pi]|720p|576[pi]|高清|超清|标清|直播)$")
    private val genericSuffixes = listOf("频道", "电视台", "广播电视台", "教育台")

    fun normalize(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace('＋', '+').replace(stripPunct, "")

    fun candidateKeys(channel: Channel): List<String> {
        val out = linkedSetOf<String>()
        fun addVariants(raw: String) {
            if (raw.isBlank()) return
            val n = normalize(raw)
            if (n.isEmpty()) return
            out += n
            // Keep 4K exact key first (e.g. cctv4k, 广东卫视4k), then add non-quality fallback
            var stripped = n
            while (trailingQuality.containsMatchIn(stripped) && stripped.length > 2) {
                stripped = stripped.replace(trailingQuality, "")
                if (stripped.isNotEmpty()) out += stripped
            }
            if (stripped.endsWith("4k") && stripped != "cctv4k" && stripped.length > 3) {
                out += stripped.removeSuffix("4k")
            }
            for (suffix in genericSuffixes) {
                if (stripped.endsWith(suffix) && stripped.length > suffix.length + 1) {
                    out += stripped.removeSuffix(suffix)
                }
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
 * Three-tier channel logo engine:
 * 1. Optional bundled offline WebP station logos
 * 2. Persistent disk-cached remote tvg-logo fetcher (bounded <=512KB, downsampled <=256x128)
 * 3. Warm smoked editorial monogram badge fallback
 */
class ChannelLogos(context: Context, private val network: Network? = null) : AutoCloseable {
    private val assets = context.assets
    private val diskCacheDir = File(context.cacheDir, "remote-logos").apply { mkdirs() }
    private val worker = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private val cacheBytes: Int = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null && (am.isLowRamDevice || am.memoryClass <= 96)) 1280 * 1024 else 3 * 1024 * 1024
    }
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val waiting = mutableMapOf<String, MutableList<WeakReference<ImageView>>>()
    private val missing = mutableSetOf<String>()
    @Volatile private var index: Map<String, String> = emptyMap()
    @Volatile private var indexLoaded = false
    @Volatile private var closed = false

    init {
        worker.execute { ensureIndexLoaded() }
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

    fun bind(view: ImageView, channel: Channel) {
        val cacheKey = channel.id + "|" + channel.logoUrl
        view.tag = cacheKey
        cache.get(cacheKey)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(EditorialMonogram(channel.name))
        synchronized(waiting) {
            if (closed || cacheKey in missing) return
            val list = waiting[cacheKey]
            if (list != null) { list.add(WeakReference(view)); return }
            waiting[cacheKey] = mutableListOf(WeakReference(view))
        }
        worker.execute {
            val bitmap = cache.get(cacheKey) ?: loadBitmap(channel)
            if (bitmap != null) cache.put(cacheKey, bitmap)
            val views = synchronized(waiting) {
                if (bitmap == null) missing.add(cacheKey)
                waiting.remove(cacheKey).orEmpty()
            }
            if (!closed && bitmap != null) {
                ui.post {
                    if (!closed) {
                        views.forEach { ref -> ref.get()?.takeIf { it.tag == cacheKey }?.setImageBitmap(bitmap) }
                    }
                }
            }
        }
    }

    private fun loadBitmap(channel: Channel): Bitmap? {
        // Tier 1: Bundled offline WebP assets
        val idx = ensureIndexLoaded()
        val assetFile = ChannelLogoMatcher.resolveAssetFile(channel, idx)
        if (!assetFile.isNullOrEmpty() && '/' !in assetFile && '\\' !in assetFile) {
            decodeAsset(assetFile)?.let { return it }
        }
        // Tier 2: Remote tvg-logo URL with persistent disk WebP cache
        if (channel.logoUrl.isNotBlank()) {
            val hash = sourceId(channel.logoUrl)
            val diskFile = File(diskCacheDir, "$hash.webp")
            if (diskFile.isFile && diskFile.length() > 32) {
                decodeBytes(diskFile.readBytes())?.let { return it }
            }
            val net = network ?: return null
            val downloaded = runCatching { net.downloadLogoBytes(channel.logoUrl) }.getOrNull() ?: return null
            val decoded = decodeBytes(downloaded) ?: return null
            runCatching {
                val tmp = File(diskCacheDir, "$hash.tmp")
                tmp.outputStream().use { out ->
                    val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                    decoded.compress(fmt, 92, out)
                }
                tmp.renameTo(diskFile)
            }
            return decoded
        }
        return null
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
        worker.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        cache.evictAll()
        synchronized(waiting) { waiting.clear() }
    }

    /** Refined cool-crystal monogram badge when neither offline nor remote logo exists. */
    private class EditorialMonogram(name: String) : Drawable() {
        private val value = name.trim().take(6)
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
        override fun onBoundsChange(bounds: Rect) {
            rect.set(bounds.left + 2f, bounds.top + 2f, bounds.right - 2f, bounds.bottom - 2f)
            textPaint.textSize = minOf(bounds.height() * .38f, bounds.width() / maxOf(value.length * .78f, 1.4f))
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
