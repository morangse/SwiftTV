package app.swifttv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView

enum class TvGlyph {
    SOURCES,
    PLAYBACK,
    APPEARANCE,
    DIAGNOSTICS,
    ABOUT,
    QR_SCAN,
    LINK,
    FOLDER,
    REFRESH,
    SPEED,
    EXPORT,
    SWEEP,
    SETTINGS,
    CONTRAST,
    STATION_LOGO,
    ROUTE,
    UPDOWN,
    POWER,
    CHIP,
    GUIDE,
    STAR_OUTLINE,
    STAR_SOLID,
    UNDO,
    SIGNAL,
    LOCATE
}

/**
 * Minimalist thin-stroke monochrome vector drawable (24x24 virtual grid, zero emojis).
 * Automatically adapts stroke color between cool alabaster on obsidian glass and deep obsidian ink on porcelain focus.
 */
class TvVectorIconDrawable(
    private var glyph: TvGlyph,
    private val strokePx: Float,
    initialColor: Int = TvStyle.ink
) : Drawable() {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = initialColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = initialColor
    }
    private val path = Path()
    private val rect = RectF()

    fun setTintColor(color: Int) {
        if (strokePaint.color != color) {
            strokePaint.color = color
            fillPaint.color = color
            invalidateSelf()
        }
    }

    fun setGlyph(next: TvGlyph) {
        if (glyph != next) {
            glyph = next
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val s = minOf(b.width(), b.height()) / 24f
        val ox = b.left + (b.width() - 24f * s) / 2f
        val oy = b.top + (b.height() - 24f * s) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        strokePaint.strokeWidth = (strokePx / s).coerceIn(1.35f, 2.2f)
        path.reset()

        when (glyph) {
            TvGlyph.SOURCES -> {
                path.moveTo(12f, 3.2f); path.lineTo(3.2f, 7.6f); path.lineTo(12f, 12f); path.lineTo(20.8f, 7.6f); path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(3.2f, 12.2f); path.lineTo(12f, 16.6f); path.lineTo(20.8f, 12.2f)
                path.moveTo(3.2f, 16.8f); path.lineTo(12f, 21.2f); path.lineTo(20.8f, 16.8f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.PLAYBACK -> {
                canvas.drawCircle(12f, 12f, 8.8f, strokePaint)
                path.moveTo(10f, 8.4f); path.lineTo(16f, 12f); path.lineTo(10f, 15.6f); path.close()
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.APPEARANCE -> {
                rect.set(3f, 4.5f, 21f, 17.5f)
                canvas.drawRoundRect(rect, 2.2f, 2.2f, strokePaint)
                canvas.drawLine(8.2f, 20.5f, 15.8f, 20.5f, strokePaint)
                path.moveTo(9.2f, 13.8f); path.lineTo(12f, 7.8f); path.lineTo(14.8f, 13.8f)
                path.moveTo(10.1f, 12.1f); path.lineTo(13.9f, 12.1f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.DIAGNOSTICS -> {
                path.moveTo(3.5f, 12f); path.lineTo(7.3f, 12f); path.lineTo(9.8f, 6.2f)
                path.lineTo(14.1f, 17.8f); path.lineTo(16.7f, 12f); path.lineTo(20.5f, 12f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.ABOUT -> {
                canvas.drawCircle(12f, 12f, 8.8f, strokePaint)
                canvas.drawLine(12f, 11.2f, 12f, 16.2f, strokePaint)
                canvas.drawCircle(12f, 8f, 1.05f, fillPaint)
            }
            TvGlyph.QR_SCAN -> {
                // Four minimalist corner scan brackets
                path.moveTo(3.8f, 8.2f); path.lineTo(3.8f, 5.2f); path.quadTo(3.8f, 3.8f, 5.2f, 3.8f); path.lineTo(8.2f, 3.8f)
                path.moveTo(15.8f, 3.8f); path.lineTo(18.8f, 3.8f); path.quadTo(20.2f, 3.8f, 20.2f, 5.2f); path.lineTo(20.2f, 8.2f)
                path.moveTo(3.8f, 15.8f); path.lineTo(3.8f, 18.8f); path.quadTo(3.8f, 20.2f, 5.2f, 20.2f); path.lineTo(8.2f, 20.2f)
                path.moveTo(15.8f, 20.2f); path.lineTo(18.8f, 20.2f); path.quadTo(20.2f, 20.2f, 20.2f, 18.8f); path.lineTo(20.2f, 15.8f)
                canvas.drawPath(path, strokePaint)
                // Clean inner QR geometry
                rect.set(7.4f, 7.4f, 10.9f, 10.9f); canvas.drawRoundRect(rect, 0.7f, 0.7f, strokePaint)
                rect.set(13.1f, 7.4f, 16.6f, 10.9f); canvas.drawRoundRect(rect, 0.7f, 0.7f, strokePaint)
                rect.set(7.4f, 13.1f, 10.9f, 16.6f); canvas.drawRoundRect(rect, 0.7f, 0.7f, strokePaint)
                path.reset()
                path.moveTo(13.4f, 13.5f); path.lineTo(16.4f, 13.5f); path.lineTo(16.4f, 16.5f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.LINK -> {
                path.moveTo(9.8f, 14.2f); path.lineTo(14.2f, 9.8f)
                canvas.drawPath(path, strokePaint)
                rect.set(3.8f, 11.2f, 12.6f, 17.6f)
                canvas.save()
                canvas.rotate(-45f, 8.2f, 14.4f)
                canvas.drawRoundRect(rect, 3.2f, 3.2f, strokePaint)
                canvas.restore()
                rect.set(11.4f, 6.4f, 20.2f, 12.8f)
                canvas.save()
                canvas.rotate(-45f, 15.8f, 9.6f)
                canvas.drawRoundRect(rect, 3.2f, 3.2f, strokePaint)
                canvas.restore()
            }
            TvGlyph.FOLDER -> {
                path.moveTo(3.5f, 7.5f)
                path.quadTo(3.5f, 5.5f, 5.5f, 5.5f)
                path.lineTo(9.6f, 5.5f)
                path.lineTo(11.6f, 7.7f)
                path.lineTo(18.5f, 7.7f)
                path.quadTo(20.5f, 7.7f, 20.5f, 9.7f)
                path.lineTo(20.5f, 17.2f)
                path.quadTo(20.5f, 19.2f, 18.5f, 19.2f)
                path.lineTo(5.5f, 19.2f)
                path.quadTo(3.5f, 19.2f, 3.5f, 17.2f)
                path.close()
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.REFRESH -> {
                rect.set(4.4f, 4.4f, 19.6f, 19.6f)
                canvas.drawArc(rect, -150f, 130f, false, strokePaint)
                canvas.drawArc(rect, 30f, 130f, false, strokePaint)
                path.moveTo(4.2f, 5.0f); path.lineTo(4.4f, 8.8f); path.lineTo(8.2f, 8.4f)
                path.moveTo(19.8f, 19.0f); path.lineTo(19.6f, 15.2f); path.lineTo(15.8f, 15.6f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.SPEED -> {
                rect.set(3.5f, 5.5f, 20.5f, 22.5f)
                canvas.drawArc(rect, 195f, 150f, false, strokePaint)
                canvas.drawLine(12f, 14.5f, 16.2f, 10.2f, strokePaint)
                canvas.drawLine(6.8f, 17.5f, 17.2f, 17.5f, strokePaint)
            }
            TvGlyph.EXPORT -> {
                path.moveTo(14f, 3.5f); path.lineTo(7f, 3.5f); path.quadTo(5f, 3.5f, 5f, 5.5f)
                path.lineTo(5f, 18.5f); path.quadTo(5f, 20.5f, 7f, 20.5f)
                path.lineTo(17f, 20.5f); path.quadTo(19f, 20.5f, 19f, 18.5f)
                path.lineTo(19f, 8.5f); path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(14f, 3.5f); path.lineTo(14f, 8.5f); path.lineTo(19f, 8.5f)
                path.moveTo(12f, 16.5f); path.lineTo(12f, 11.2f)
                path.moveTo(9.6f, 13.6f); path.lineTo(12f, 11.2f); path.lineTo(14.4f, 13.6f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.SWEEP -> {
                canvas.drawLine(4.2f, 7.2f, 19.8f, 7.2f, strokePaint)
                canvas.drawLine(9.5f, 4.2f, 14.5f, 4.2f, strokePaint)
                path.moveTo(6.6f, 7.2f); path.lineTo(7.6f, 18.4f)
                path.quadTo(7.8f, 20f, 9.4f, 20f); path.lineTo(14.6f, 20f)
                path.quadTo(16.2f, 20f, 16.4f, 18.4f); path.lineTo(17.4f, 7.2f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3.2f, strokePaint)
                canvas.drawCircle(12f, 12f, 7.8f, strokePaint)
                for (deg in 0 until 360 step 45) {
                    val rad = Math.toRadians(deg.toDouble())
                    val x1 = (12.0 + Math.cos(rad) * 7.8).toFloat()
                    val y1 = (12.0 + Math.sin(rad) * 7.8).toFloat()
                    val x2 = (12.0 + Math.cos(rad) * 9.8).toFloat()
                    val y2 = (12.0 + Math.sin(rad) * 9.8).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                }
            }
            TvGlyph.CONTRAST -> {
                canvas.drawCircle(12f, 12f, 8.8f, strokePaint)
                path.moveTo(12f, 3.2f)
                rect.set(3.2f, 3.2f, 20.8f, 20.8f)
                path.arcTo(rect, -90f, 180f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            TvGlyph.STATION_LOGO -> {
                rect.set(3f, 5.5f, 21f, 17.5f)
                canvas.drawRoundRect(rect, 2.2f, 2.2f, strokePaint)
                canvas.drawLine(8.2f, 20.5f, 15.8f, 20.5f, strokePaint)
                rect.set(6.3f, 8.6f, 11.3f, 13.4f)
                canvas.drawRoundRect(rect, 1f, 1f, strokePaint)
                canvas.drawLine(13.4f, 9.8f, 17.8f, 9.8f, strokePaint)
                canvas.drawLine(13.4f, 12.4f, 16.4f, 12.4f, strokePaint)
            }
            TvGlyph.ROUTE -> {
                canvas.drawLine(12f, 20.5f, 12f, 13f, strokePaint)
                canvas.drawLine(12f, 13f, 6.2f, 6.6f, strokePaint)
                canvas.drawLine(12f, 13f, 17.8f, 6.6f, strokePaint)
                path.moveTo(6.2f, 10.2f); path.lineTo(6.2f, 6.6f); path.lineTo(9.8f, 6.6f)
                path.moveTo(17.8f, 10.2f); path.lineTo(17.8f, 6.6f); path.lineTo(14.2f, 6.6f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.UPDOWN -> {
                canvas.drawLine(8.5f, 19f, 8.5f, 5f, strokePaint)
                canvas.drawLine(15.5f, 5f, 15.5f, 19f, strokePaint)
                path.moveTo(5.6f, 7.9f); path.lineTo(8.5f, 5f); path.lineTo(11.4f, 7.9f)
                path.moveTo(12.6f, 16.1f); path.lineTo(15.5f, 19f); path.lineTo(18.4f, 16.1f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.POWER -> {
                rect.set(4.6f, 5f, 19.4f, 19.8f)
                canvas.drawArc(rect, -55f, 290f, false, strokePaint)
                canvas.drawLine(12f, 3.4f, 12f, 11.2f, strokePaint)
            }
            TvGlyph.CHIP -> {
                rect.set(6.5f, 6.5f, 17.5f, 17.5f)
                canvas.drawRoundRect(rect, 2f, 2f, strokePaint)
                rect.set(9.6f, 9.6f, 14.4f, 14.4f)
                canvas.drawRoundRect(rect, 0.8f, 0.8f, strokePaint)
                val pins = floatArrayOf(9.5f, 12f, 14.5f)
                for (v in pins) {
                    canvas.drawLine(v, 3.6f, v, 6.5f, strokePaint)
                    canvas.drawLine(v, 17.5f, v, 20.4f, strokePaint)
                    canvas.drawLine(3.6f, v, 6.5f, v, strokePaint)
                    canvas.drawLine(17.5f, v, 20.4f, v, strokePaint)
                }
            }
            TvGlyph.GUIDE -> {
                rect.set(3.5f, 5f, 20.5f, 20f)
                canvas.drawRoundRect(rect, 2.2f, 2.2f, strokePaint)
                canvas.drawLine(3.5f, 9.6f, 20.5f, 9.6f, strokePaint)
                canvas.drawLine(8f, 3.2f, 8f, 6.6f, strokePaint)
                canvas.drawLine(16f, 3.2f, 16f, 6.6f, strokePaint)
                canvas.drawLine(7f, 13.3f, 17f, 13.3f, strokePaint)
                canvas.drawLine(7f, 16.6f, 13.4f, 16.6f, strokePaint)
            }
            TvGlyph.STAR_OUTLINE, TvGlyph.STAR_SOLID -> {
                for (i in 0 until 10) {
                    val angle = Math.toRadians((-90 + i * 36).toDouble())
                    val r = if (i % 2 == 0) 8.4 else 3.6
                    val px = (12.0 + Math.cos(angle) * r).toFloat()
                    val py = (12.2 + Math.sin(angle) * r).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                if (glyph == TvGlyph.STAR_SOLID) canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.UNDO -> {
                path.moveTo(8.5f, 6.2f); path.lineTo(4.5f, 10.2f); path.lineTo(8.5f, 14.2f)
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(4.8f, 10.2f); path.lineTo(14.8f, 10.2f)
                rect.set(10f, 10.2f, 19.6f, 18.8f)
                path.arcTo(rect, -90f, 180f)
                path.lineTo(9.5f, 18.8f)
                canvas.drawPath(path, strokePaint)
            }
            TvGlyph.SIGNAL -> {
                canvas.drawCircle(12f, 12f, 1.8f, fillPaint)
                rect.set(7.2f, 7.2f, 16.8f, 16.8f)
                canvas.drawArc(rect, 135f, 90f, false, strokePaint)
                canvas.drawArc(rect, -45f, 90f, false, strokePaint)
                rect.set(3.6f, 3.6f, 20.4f, 20.4f)
                canvas.drawArc(rect, 135f, 90f, false, strokePaint)
                canvas.drawArc(rect, -45f, 90f, false, strokePaint)
            }
            TvGlyph.LOCATE -> {
                canvas.drawCircle(12f, 12f, 6.8f, strokePaint)
                canvas.drawLine(12f, 2.8f, 12f, 5.2f, strokePaint)
                canvas.drawLine(12f, 18.8f, 12f, 21.2f, strokePaint)
                canvas.drawLine(2.8f, 12f, 5.2f, 12f, strokePaint)
                canvas.drawLine(18.8f, 12f, 21.2f, 12f, strokePaint)
                canvas.drawCircle(12f, 12f, 1.8f, fillPaint)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        fillPaint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        strokePaint.colorFilter = filter
        fillPaint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Framed thin-stroke icon well matching the control center quick-import cards (black-edge ink on porcelain focus). */
@SuppressLint("ViewConstructor")
class TvIconWellView(context: Context, glyph: TvGlyph, boxDp: Int = 34, iconDp: Int = 18) : FrameLayout(context) {
    private val boxBg = GradientDrawable().apply {
        cornerRadius = TvStyle.dpF(context, 10f)
    }
    private val iconDrawable = TvVectorIconDrawable(glyph, TvStyle.dpF(context, 1.65f), TvStyle.ink)
    private val imageView = ImageView(context).apply {
        setImageDrawable(iconDrawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        val boxPx = TvStyle.dp(context, boxDp)
        val iconPx = TvStyle.dp(context, iconDp)
        layoutParams = LayoutParams(boxPx, boxPx)
        background = boxBg
        addView(imageView, LayoutParams(iconPx, iconPx, Gravity.CENTER))
        setOnPorcelain(false)
    }

    fun setOnPorcelain(focused: Boolean) {
        if (focused) {
            boxBg.setColor(Color.argb(16, 10, 13, 19))
            boxBg.setStroke(TvStyle.dp(context, 1).coerceAtLeast(1), Color.argb(66, 10, 13, 19))
            iconDrawable.setTintColor(TvStyle.inkOnPorcelain)
        } else {
            boxBg.setColor(Color.argb(14, 240, 246, 255))
            boxBg.setStroke(TvStyle.dp(context, 1).coerceAtLeast(1), Color.argb(42, 240, 246, 255))
            iconDrawable.setTintColor(TvStyle.ink)
        }
    }

    fun setGlyph(glyph: TvGlyph) {
        iconDrawable.setGlyph(glyph)
    }
}
