package app.swifttv.player

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.core.content.edit

/**
 * Obsidian Crystal & Porcelain Luminance Design Tokens (tvOS / visionOS Optical System):
 * Pure achromatic dark crystal surfaces + real backdrop vibrancy + crisp porcelain focus.
 * Zero yellow/gold/beige tint and zero neon AI colors — directly aligned with the SwiftTV icon.
 */
object TvStyle {
    val canvas = Color.rgb(7, 9, 13)              // #07090D Pure Cinema Pitch
    val ink = Color.rgb(246, 248, 252)            // #F6F8FC Crisp Optical Alabaster
    val inkOnPorcelain = Color.rgb(10, 13, 19)    // #0A0D13 Deep Obsidian Ink on Focused Pills/Rows
    val muted = Color.argb(168, 228, 235, 246)    // 66% Cool Silver Mist
    val mutedOnPorcelain = Color.argb(165, 20, 26, 38) // 65% Crisp Slate on Porcelain Focus
    val subtle = Color.argb(105, 224, 232, 245)   // 41% Quiet Slate Numerals
    val porcelain = Color.rgb(243, 246, 251)      // #F3F6FB Pure Tactile Porcelain Focus
    val accent = porcelain                        // Achromatic luminous white
    val accentInk = inkOnPorcelain
    val sage = Color.rgb(62, 196, 130)            // #3EC482 Studio Signal Emerald (dots & ON switch only)
    val danger = Color.rgb(242, 116, 116)         // #F27474 Soft Signal Coral (errors & delete only)

    const val PANEL_RADIUS = 24
    const val CARD_RADIUS = 18
    const val ROW_RADIUS = 13
    const val PILL_RADIUS = 999
    const val ENTER_MS = 230L
    const val EXIT_MS = 175L
    const val FOCUS_MS = 165L

    val springOut: Interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    val easeIn: Interpolator = PathInterpolator(0.32f, 0f, 0.67f, 0f)

    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).toInt()
    fun dpF(c: Context, n: Float) = n * c.resources.displayMetrics.density

    fun lightweight(c: Context): Boolean {
        val p = c.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        if (p.contains("lightweight")) return p.getBoolean("lightweight", false)
        val manager = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.isLowRamDevice || manager.memoryClass <= 96
    }

    fun setLightweight(c: Context, value: Boolean) {
        c.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit { putBoolean("lightweight", value) }
    }

    /** Directional 135-degree optical crystal surface matching the SwiftTV obsidian icon. */
    fun specularSurface(
        c: Context,
        fillTopLeft: Int,
        fillBottomRight: Int = fillTopLeft,
        radiusDp: Float,
        rimTopLeft: Int = Color.argb(54, 240, 246, 255),
        rimBottomRight: Int = Color.argb(14, 255, 255, 255),
        strokeDp: Float = 1f
    ): Drawable = SpecularRoundedDrawable(
        fillStart = fillTopLeft,
        fillEnd = fillBottomRight,
        radiusPx = dpF(c, radiusDp),
        rimStart = rimTopLeft,
        rimEnd = rimBottomRight,
        strokePx = dpF(c, strokeDp).coerceAtLeast(1f)
    )

    /** Tactile tvOS Porcelain White surface for focused list rows, tabs, and primary actions. */
    fun porcelainSurface(c: Context, radiusDp: Float = ROW_RADIUS.toFloat()): Drawable = SpecularRoundedDrawable(
        fillStart = Color.rgb(248, 250, 253),
        fillEnd = Color.rgb(232, 237, 245),
        radiusPx = dpF(c, radiusDp),
        rimStart = Color.WHITE,
        rimEnd = Color.rgb(220, 227, 238),
        strokePx = dpF(c, 1f).coerceAtLeast(1f),
        drawTopSheen = false
    )

    /** Subtle translucent crystal pill for active category indicator or metadata chips. */
    fun activeCrystalPill(c: Context, radiusDp: Float = PILL_RADIUS.toFloat()): Drawable = specularSurface(
        c,
        fillTopLeft = Color.argb(46, 244, 248, 255),
        fillBottomRight = Color.argb(28, 226, 234, 248),
        radiusDp = radiusDp,
        rimTopLeft = Color.argb(76, 255, 255, 255),
        rimBottomRight = Color.argb(24, 255, 255, 255),
        strokeDp = 1f
    )

    fun badge(c: Context, onPorcelain: Boolean = false): Drawable = GradientDrawable().apply {
        setColor(if (onPorcelain) Color.argb(22, 10, 13, 19) else Color.argb(28, 244, 248, 255))
        cornerRadius = dpF(c, 7f)
    }

    /** Borderless whisper-soft idle row surface (zero box-in-box clutter). */
    fun quietRowIdle(c: Context, radius: Int = ROW_RADIUS): Drawable = GradientDrawable().apply {
        setColor(Color.argb(12, 244, 248, 255))
        cornerRadius = dpF(c, radius.toFloat())
    }

    /** tvOS-style row selector: whisper-quiet when idle, crisp porcelain white when focused. */
    fun porcelainRowSelector(c: Context, radius: Int = ROW_RADIUS): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), porcelainSurface(c, radius.toFloat()))
        addState(intArrayOf(android.R.attr.state_focused), porcelainSurface(c, radius.toFloat()))
        addState(intArrayOf(), quietRowIdle(c, radius))
    }

    /** Clean segmented capsule action button: borderless inside its parent bar when idle, porcelain when focused. */
    fun capsuleActionSelector(c: Context): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), porcelainSurface(c, PILL_RADIUS.toFloat()))
        addState(intArrayOf(android.R.attr.state_focused), porcelainSurface(c, PILL_RADIUS.toFloat()))
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dpF(c, PILL_RADIUS.toFloat())
        })
    }

    /** Standalone pill button (e.g. Back button in sheets/modals). */
    fun pillButton(c: Context): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), porcelainSurface(c, PILL_RADIUS.toFloat()))
        addState(intArrayOf(android.R.attr.state_focused), porcelainSurface(c, PILL_RADIUS.toFloat()))
        addState(intArrayOf(), specularSurface(
            c,
            fillTopLeft = Color.argb(24, 244, 248, 255),
            fillBottomRight = Color.argb(16, 226, 234, 248),
            radiusDp = PILL_RADIUS.toFloat(),
            rimTopLeft = Color.argb(48, 255, 255, 255),
            rimBottomRight = Color.argb(16, 255, 255, 255)
        ))
    }
}

private class SpecularRoundedDrawable(
    private val fillStart: Int,
    private val fillEnd: Int,
    private val radiusPx: Float,
    private val rimStart: Int,
    private val rimEnd: Int,
    private val strokePx: Float,
    private val drawTopSheen: Boolean = true
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokePx }
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        val inset = strokePx / 2f
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        fillPaint.shader = if (fillStart == fillEnd) null else LinearGradient(
            0f, 0f, w * 0.6f, h,
            fillStart, fillEnd, Shader.TileMode.CLAMP
        )
        if (fillStart == fillEnd) fillPaint.color = fillStart
        if (drawTopSheen) {
            sheenPaint.shader = LinearGradient(
                0f, 0f, w * 0.35f, h * 0.5f,
                Color.argb(18, 240, 246, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        rimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            rimStart, rimEnd, Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val r = minOf(radiusPx, rect.width() / 2f, rect.height() / 2f)
        canvas.drawRoundRect(rect, r, r, fillPaint)
        if (drawTopSheen) canvas.drawRoundRect(rect, r, r, sheenPaint)
        canvas.drawRoundRect(rect, r, r, rimPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        rimPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
