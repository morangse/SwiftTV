package app.swifttv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.lang.ref.WeakReference

/**
 * Zero-allocation real-time optical dark vibrancy sampler (tvOS systemChromeMaterialDark recipe).
 * Active ONLY while HUD or an overlay sheet/gallery is visible (0% cost during fullscreen playback).
 * Downsamples live TextureView to 128x72, runs a 3-pass separable Gaussian-convergent blur, and
 * applies chroma vibrancy boost + highlight knee compression so glass panels let rich live broadcast
 * colors glow darkly through without flat grey muddiness or subtitle bleed-through.
 */
@UnstableApi
class CinemaBlurEngine(private val context: Context) {
    private val bufW = 128
    private val bufH = 72
    private val bitmap: Bitmap = createBitmap(bufW, bufH)
    private val pixels = IntArray(bufW * bufH)
    private val scratch = IntArray(bufW * bufH)
    private val handler = Handler(Looper.getMainLooper())
    private val consumers = mutableListOf<WeakReference<View>>()
    private val rootLoc = IntArray(2)
    private var playerView: PlayerView? = null
    private var rootWidth = 0
    private var rootHeight = 0
    private var hasValidFrame = false
    private var running = false

    fun attach(video: PlayerView) {
        playerView = video
    }

    fun registerConsumer(view: View) {
        if (consumers.none { it.get() === view }) {
            consumers.add(WeakReference(view))
        }
        syncState()
    }

    fun syncState() {
        consumers.removeAll { it.get() == null }
        val shouldRun = !TvStyle.lightweight(context) && consumers.any { ref ->
            val v = ref.get()
            v != null && v.isShown && v.width > 0 && v.height > 0
        }
        if (shouldRun && !running) {
            running = true
            handler.post(sampleTick)
        } else if (!shouldRun && running) {
            running = false
            handler.removeCallbacks(sampleTick)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(sampleTick)
    }

    private val sampleTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (TvStyle.lightweight(context)) {
                hasValidFrame = false
                running = false
                return
            }
            val pv = playerView
            val tv = pv?.videoSurfaceView as? TextureView
            if (tv != null && tv.isAvailable && pv.width > 0 && pv.height > 0) {
                pv.getLocationOnScreen(rootLoc)
                rootWidth = pv.width
                rootHeight = pv.height
                val captured = runCatching { tv.getBitmap(bitmap) }.getOrNull()
                if (captured != null) {
                    bitmap.getPixels(pixels, 0, bufW, 0, 0, bufW, bufH)
                    blurSeparable(pixels, scratch, bufW, bufH, radius = 6)
                    blurSeparable(pixels, scratch, bufW, bufH, radius = 5)
                    blurSeparable(pixels, scratch, bufW, bufH, radius = 3)
                    applyDarkGlassMaterial(pixels)
                    bitmap.setPixels(pixels, 0, bufW, 0, 0, bufW, bufH)
                    hasValidFrame = true
                    consumers.forEach { it.get()?.takeIf(View::isShown)?.invalidate() }
                }
            }
            if (running) handler.postDelayed(this, 58L)
        }
    }

    fun drawBlurredSlice(canvas: Canvas, targetView: View, viewLoc: IntArray, srcRect: Rect, dstRect: RectF, paint: Paint): Boolean {
        if (!hasValidFrame || TvStyle.lightweight(context) || rootWidth <= 0 || rootHeight <= 0) return false
        targetView.getLocationOnScreen(viewLoc)
        val relLeft = (viewLoc[0] - rootLoc[0]).toFloat() / rootWidth
        val relTop = (viewLoc[1] - rootLoc[1]).toFloat() / rootHeight
        val relRight = (viewLoc[0] + targetView.width - rootLoc[0]).toFloat() / rootWidth
        val relBottom = (viewLoc[1] + targetView.height - rootLoc[1]).toFloat() / rootHeight
        val sl = (relLeft * bufW).toInt().coerceIn(0, bufW - 1)
        val st = (relTop * bufH).toInt().coerceIn(0, bufH - 1)
        val sr = (relRight * bufW).toInt().coerceIn(sl + 1, bufW)
        val sb = (relBottom * bufH).toInt().coerceIn(st + 1, bufH)
        srcRect.set(sl, st, sr, sb)
        dstRect.set(0f, 0f, targetView.width.toFloat(), targetView.height.toFloat())
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        return true
    }

    companion object {
        internal fun blurSeparable(data: IntArray, tmp: IntArray, w: Int, h: Int, radius: Int) {
            val div = radius * 2 + 1
            for (y in 0 until h) {
                val row = y * w
                var rSum = 0; var gSum = 0; var bSum = 0
                for (i in -radius..radius) {
                    val p = data[row + i.coerceIn(0, w - 1)]
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                }
                for (x in 0 until w) {
                    tmp[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                    val outP = data[row + (x - radius).coerceAtLeast(0)]
                    val inP = data[row + (x + radius + 1).coerceAtMost(w - 1)]
                    rSum += ((inP shr 16) and 0xFF) - ((outP shr 16) and 0xFF)
                    gSum += ((inP shr 8) and 0xFF) - ((outP shr 8) and 0xFF)
                    bSum += (inP and 0xFF) - (outP and 0xFF)
                }
            }
            for (x in 0 until w) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (i in -radius..radius) {
                    val p = tmp[i.coerceIn(0, h - 1) * w + x]
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                }
                for (y in 0 until h) {
                    data[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                    val outP = tmp[(y - radius).coerceAtLeast(0) * w + x]
                    val inP = tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x]
                    rSum += ((inP shr 16) and 0xFF) - ((outP shr 16) and 0xFF)
                    gSum += ((inP shr 8) and 0xFF) - ((outP shr 8) and 0xFF)
                    bSum += (inP and 0xFF) - (outP and 0xFF)
                }
            }
        }

        /**
         * Apple-inspired dark vibrancy material shader:
         * Boosts chroma saturation 1.35x before applying a highlight-compressed dark exposure curve,
         * preserving rich ambient broadcast color while guaranteeing >8:1 white text contrast.
         */
        internal fun applyDarkGlassMaterial(data: IntArray) {
            for (i in data.indices) {
                val p = data[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = (77 * r + 150 * g + 29 * b) shr 8
                // 1.35x saturation boost (346 / 256)
                val rs = (luma + (((r - luma) * 346) shr 8)).coerceIn(0, 255)
                val gs = (luma + (((g - luma) * 346) shr 8)).coerceIn(0, 255)
                val bs = (luma + (((b - luma) * 346) shr 8)).coerceIn(0, 255)
                // Soft highlight knee: 0.34x in shadows/mids tapering to 0.22x on pure white subtitles
                val gain = 88 - ((luma * 30) shr 8)
                val ro = (10 + ((rs * gain) shr 8)).coerceIn(0, 88)
                val go = (12 + ((gs * gain) shr 8)).coerceIn(0, 92)
                val bo = (17 + ((bs * gain) shr 8)).coerceIn(0, 98)
                data[i] = (0xFF shl 24) or (ro shl 16) or (go shl 8) or bo
            }
        }
    }
}

/**
 * Reusable Obsidian Optical Crystal LinearLayout.
 * Renders real vibrancy-mapped video blur (or deep directional obsidian crystal fallback)
 * finished with a 135-degree optical specular facet and 1dp ice-silver rim.
 */
@UnstableApi
@SuppressLint("ViewConstructor")
open class FrostedContainer(
    context: Context,
    private val blurEngine: CinemaBlurEngine?,
    private val cornerRadiusDp: Float = TvStyle.PANEL_RADIUS.toFloat(),
    private var veilAlpha: Int = 68
) : LinearLayout(context) {
    private val clipPath = Path()
    private val boundsRect = RectF()
    private val strokeRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val loc = IntArray(2)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvStyle.dpF(context, 1f).coerceAtLeast(1f)
    }

    init {
        setWillNotDraw(false)
        blurEngine?.registerConsumer(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blurEngine?.registerConsumer(this)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        blurEngine?.syncState()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = TvStyle.dpF(context, cornerRadiusDp)
        boundsRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val inset = rimPaint.strokeWidth / 2f
        strokeRect.set(inset, inset, w - inset, h - inset)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, r, r, Path.Direction.CW)
        fallbackPaint.shader = LinearGradient(
            0f, 0f, w * 0.7f, h.toFloat(),
            Color.argb(if (TvStyle.lightweight(context)) 248 else 238, 19, 23, 32),
            Color.argb(if (TvStyle.lightweight(context)) 250 else 244, 10, 13, 19),
            Shader.TileMode.CLAMP
        )
        sheenPaint.shader = LinearGradient(
            0f, 0f, w * 0.42f, h * 0.48f,
            Color.argb(24, 236, 244, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        rimPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.argb(66, 240, 246, 255), Color.argb(16, 255, 255, 255), Shader.TileMode.CLAMP
        )
        blurEngine?.syncState()
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.withClip(clipPath) {
            val drewBlur = blurEngine?.drawBlurredSlice(this, this@FrostedContainer, loc, srcRect, dstRect, bitmapPaint) == true
            if (drewBlur) {
                veilPaint.color = Color.argb(veilAlpha, 9, 12, 18)
                drawRect(boundsRect, veilPaint)
            } else {
                drawRect(boundsRect, fallbackPaint)
            }
            drawRect(boundsRect, sheenPaint)
            super.dispatchDraw(this)
            val r = minOf(TvStyle.dpF(context, cornerRadiusDp), strokeRect.width() / 2f, strokeRect.height() / 2f)
            drawRoundRect(strokeRect, r, r, rimPaint)
        }
    }
}
