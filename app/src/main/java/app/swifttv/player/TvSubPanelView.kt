package app.swifttv.player

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import kotlin.math.min
import kotlin.math.roundToInt

interface ViewportSizedPanel {
    fun fitToViewport(widthPx: Int, heightPx: Int)
}

/**
 * Overlay container that sizes ViewportSizedPanel children inside onMeasure,
 * completely eliminating second-pass requestLayout() calls during layout.
 */
class TvOverlayLayout(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val availH = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        if (availW > 0 && availH > 0) {
            for (i in 0 until childCount) {
                (getChildAt(i) as? ViewportSizedPanel)?.fitToViewport(availW, availH)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Custom Canvas-drawn animated tvOS pill switch (clean on both dark glass and porcelain focus rows). */
@SuppressLint("ViewConstructor")
class CinemaSwitchView(context: Context, checked: Boolean = false) : View(context) {
    private fun dpF(n: Float) = TvStyle.dpF(context, n)
    private var progress = if (checked) 1f else 0f
    private var parentRowFocused = false
    private var animator: ValueAnimator? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    var isChecked: Boolean = checked
        private set

    fun setRowFocused(focused: Boolean) {
        if (parentRowFocused != focused) {
            parentRowFocused = focused
            invalidate()
        }
    }

    fun setCheckedAnimated(value: Boolean, animate: Boolean = true) {
        if (isChecked == value) return
        isChecked = value
        animator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate || TvStyle.lightweight(context)) {
            progress = target
            invalidate()
        } else {
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 180L
                interpolator = TvStyle.springOut
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(TvStyle.dp(context, 42), TvStyle.dp(context, 24))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        val idleOffColor = if (parentRowFocused) Color.argb(42, 12, 16, 24) else Color.argb(56, 255, 255, 255)
        trackPaint.color = blendColor(idleOffColor, TvStyle.sage, progress)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)

        val pad = dpF(3f)
        val thumbR = (h - pad * 2f) / 2f
        val cx = pad + thumbR + (w - pad * 2f - thumbR * 2f) * progress
        canvas.drawCircle(cx, h / 2f, thumbR, thumbPaint)
    }

    private fun blendColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.argb(a, r, g, b)
    }
}

/**
 * Obsidian Optical Crystal Sheet (tvOS Settings / Control Center Architecture):
 * Borderless whisper-quiet idle rows + tactile Pure Porcelain focus highlight with dynamic ink inversion.
 */
@UnstableApi
@SuppressLint("ViewConstructor", "SetTextI18n")
class CinemaSheetView(
    context: Context,
    blurEngine: CinemaBlurEngine?,
    eyebrowText: String,
    titleText: String,
    subtitleText: String = "",
    private val anchoredRight: Boolean = false,
    private val onBackClick: (() -> Unit)? = null
) : FrostedContainer(context, blurEngine, cornerRadiusDp = 24f, veilAlpha = 74), ViewportSizedPanel {

    private fun dp(n: Int) = TvStyle.dp(context, n)
    private fun sp(base: Float) = TvStyle.sp(context, base)
    private var lastViewportW = -1
    private var lastViewportH = -1
    private var lastFontScale = -1f

    private val eyebrowView = TextView(context).apply {
        text = eyebrowText
        textSize = sp(11.5f)
        setTextColor(TvStyle.subtle)
        includeFontPadding = false
        visibility = if (eyebrowText.isBlank()) GONE else VISIBLE
    }
    private val titleView = TextView(context).apply {
        text = titleText
        textSize = sp(21f)
        setTextColor(TvStyle.ink)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, if (eyebrowText.isBlank()) 0 else dp(2), 0, 0)
    }
    val subtitleView = TextView(context).apply {
        text = subtitleText
        textSize = sp(12.5f)
        setTextColor(TvStyle.muted)
        includeFontPadding = false
        setPadding(0, dp(4), 0, 0)
        visibility = if (subtitleText.isEmpty()) GONE else VISIBLE
    }
    private val headerBlock = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(4), 0, dp(4), dp(12))
        addView(eyebrowView)
        addView(titleView)
        addView(subtitleView)
    }
    private val headerDivider = View(context).apply {
        setBackgroundColor(Color.argb(20, 255, 255, 255))
    }

    val rows = LinearLayout(context).apply {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(4), dp(10), dp(4), dp(8))
    }
    private var maxScrollHeightPx = dp(400)
    private val scrollView = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cappedSpec = MeasureSpec.makeMeasureSpec(maxScrollHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, cappedSpec)
        }

        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
            val maxScroll = (getChildAt(0)?.height ?: 0) - height
            if (maxScroll <= 0) return 0
            val target = rect.top - (height * 0.30f).toInt()
            return target.coerceIn(0, maxScroll) - scrollY
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(18))
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        addView(rows, LayoutParams(-1, -2))
    }

    private val footerDivider = View(context).apply {
        setBackgroundColor(Color.argb(18, 255, 255, 255))
    }
    private val backPill = Button(context).apply {
        id = View.generateViewId()
        text = "返回"
        textSize = sp(13f)
        isAllCaps = false
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = true
        setTextColor(TvStyle.ink)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = 0; minimumWidth = 0; minHeight = dp(30); minimumHeight = dp(30)
        setPadding(dp(14), dp(5), dp(14), dp(5))
        background = TvStyle.pillButton(context)
        stateListAnimator = null
        setOnClickListener { onBackClick?.invoke() }
    }
    private val footer = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(10), dp(4), 0)
        addFooterChip("OK", "确认", trailingGapDp = 14)
        addFooterChip("返回", "上一层", trailingGapDp = 8)
        addView(View(context), LayoutParams(0, 1, 1f))
        if (onBackClick != null) addView(backPill, LayoutParams(-2, -2))
    }

    private fun LinearLayout.addFooterChip(key: String, label: String, trailingGapDp: Int) {
        addView(TextView(context).apply {
            text = key
            textSize = sp(11f)
            setTextColor(TvStyle.ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            background = TvStyle.badge(context)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }, LayoutParams(-2, -2))
        addView(TextView(context).apply {
            text = label
            textSize = sp(12f)
            setTextColor(TvStyle.muted)
            includeFontPadding = false
            maxLines = 1
        }, LayoutParams(-2, -2).apply {
            leftMargin = dp(5)
            rightMargin = dp(trailingGapDp)
        })
    }

    init {
        orientation = VERTICAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        isClickable = true
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        elevation = dp(12).toFloat()
        setPadding(dp(22), dp(20), dp(22), dp(16))
        addView(headerBlock, LayoutParams(-1, -2))
        addView(headerDivider, LayoutParams(-1, 1))
        addView(scrollView, LayoutParams(-1, -2))
        addView(footerDivider, LayoutParams(-1, 1))
        addView(footer, LayoutParams(-1, -2))
    }

    fun refreshSurface() = invalidate()

    fun addSectionHeader(label: String) {
        rows.addView(TextView(context).apply {
            text = label
            textSize = sp(11.5f)
            setTextColor(TvStyle.subtle)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), dp(12), dp(8), dp(5))
        }, LayoutParams(-1, -2))
    }

    fun addInfoBlock(text: String, color: Int = TvStyle.muted): TextView = TextView(context).apply {
        this.text = text
        textSize = sp(13.5f)
        setTextColor(color)
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(8), dp(2), dp(8), dp(10))
    }.also { rows.addView(it, LayoutParams(-1, -2)) }

    /** Structured TV Toggle Switch Row: Title + Subtitle on left, animated pill switch on right. */
    fun addSwitchRow(title: String, subtitle: String, initial: Boolean, onToggle: () -> Boolean): View {
        val switchView = CinemaSwitchView(context, initial)
        val row = createBaseInteractiveRow(
            title = title,
            subtitle = subtitle,
            rightWidget = switchView,
            onFocusChanged = { focused -> switchView.setRowFocused(focused) }
        )
        row.setOnClickListener {
            val next = onToggle()
            switchView.setCheckedAnimated(next)
        }
        return row
    }

    /** Structured TV Segmented Stepper Row: supports OK, Left, or Right keys to cycle cleanly in place. */
    fun addSegmentRow(title: String, subtitle: String, initialValue: String, glyph: TvGlyph? = null, onCycle: () -> String): View {
        val pill = TextView(context).apply {
            text = "$initialValue ›"
            textSize = sp(12.5f)
            setTextColor(TvStyle.ink)
            includeFontPadding = false
            background = TvStyle.badge(context, onPorcelain = false)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val row = createBaseInteractiveRow(
            title = title,
            subtitle = subtitle,
            rightWidget = pill,
            glyph = glyph,
            onFocusChanged = { focused ->
                pill.setTextColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
                pill.background = TvStyle.badge(context, onPorcelain = focused)
            }
        )
        val trigger = {
            val nextVal = onCycle()
            pill.text = "$nextVal ›"
        }
        row.setOnClickListener { trigger() }
        row.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                trigger(); true
            } else false
        }
        return row
    }

    /** Structured Action Row with optional icon well, right-side accessory text, or status dot. */
    fun addActionRow(
        title: String,
        subtitle: String = "",
        accessory: String = "",
        statusColor: Int? = null,
        glyph: TvGlyph? = null,
        accessoryColor: Int? = null,
        onClick: (TextView, TextView) -> Unit
    ): View {
        val rightBox = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (statusColor != null) {
            rightBox.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(statusColor) }
            }, LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(8) })
        }
        val accText = TextView(context).apply {
            text = accessory
            textSize = sp(12.5f)
            setTextColor(accessoryColor ?: TvStyle.muted)
            includeFontPadding = false
            visibility = if (accessory.isEmpty()) GONE else VISIBLE
        }
        rightBox.addView(accText, LayoutParams(-2, -2))
        return createBaseInteractiveRowWithHandles(
            title = title,
            subtitle = subtitle,
            rightWidget = rightBox,
            glyph = glyph,
            onFocusChanged = { focused ->
                val color = when {
                    accessoryColor == null -> if (focused) TvStyle.mutedOnPorcelain else TvStyle.muted
                    focused && accessoryColor == TvStyle.danger -> Color.rgb(198, 42, 42)
                    else -> accessoryColor
                }
                accText.setTextColor(color)
            }
        ) { titleTv, subTv ->
            onClick(titleTv, subTv)
        }
    }

    private fun createBaseInteractiveRow(
        title: String,
        subtitle: String,
        rightWidget: View,
        glyph: TvGlyph? = null,
        onFocusChanged: ((Boolean) -> Unit)? = null
    ): View = createBaseInteractiveRowWithHandles(title, subtitle, rightWidget, glyph, onFocusChanged, null)

    private fun createBaseInteractiveRowWithHandles(
        title: String,
        subtitle: String,
        rightWidget: View,
        glyph: TvGlyph? = null,
        onFocusChanged: ((Boolean) -> Unit)? = null,
        onClickHandles: ((TextView, TextView) -> Unit)?
    ): View {
        val well = glyph?.let { TvIconWellView(context, it, boxDp = 30, iconDp = 15) }
        val titleTv = TextView(context).apply {
            text = title
            textSize = sp(14.5f)
            setTextColor(TvStyle.ink)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val subTv = TextView(context).apply {
            text = subtitle
            textSize = sp(12f)
            setTextColor(TvStyle.muted)
            includeFontPadding = false
            setPadding(0, dp(2), 0, 0)
            visibility = if (subtitle.isEmpty()) GONE else VISIBLE
        }
        return LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(48)
            setPadding(dp(12), dp(10), dp(14), dp(10))
            background = TvStyle.porcelainRowSelector(context)
            if (well != null) {
                addView(well, LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
            }
            val leftCol = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(titleTv)
                addView(subTv)
            }
            addView(leftCol, LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
            addView(rightWidget, LayoutParams(-2, -2))
            setOnFocusChangeListener { v, focused ->
                well?.setOnPorcelain(focused)
                titleTv.setTextColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
                titleTv.typeface = Typeface.create("sans-serif-medium", if (focused) Typeface.BOLD else Typeface.NORMAL)
                subTv.setTextColor(if (focused) TvStyle.mutedOnPorcelain else TvStyle.muted)
                onFocusChanged?.invoke(focused)
                if (!TvStyle.lightweight(context)) {
                    v.animate().cancel()
                    val s = if (focused) 1.022f else 1f
                    v.elevation = 0f
                    v.animate().scaleX(s).scaleY(s).setDuration(140L).setInterpolator(TvStyle.springOut).start()
                }
            }
            if (onClickHandles != null) {
                setOnClickListener { onClickHandles(titleTv, subTv) }
            }
        }.also {
            rows.addView(it, LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    fun focusFirstRow() = focusRow(0)

    fun focusRow(index: Int) {
        post {
            var count = 0
            var firstFocusable: View? = null
            for (i in 0 until rows.childCount) {
                val child = rows.getChildAt(i)
                if (child.isFocusable && child.isShown) {
                    if (firstFocusable == null) firstFocusable = child
                    if (count == index) {
                        child.requestFocus()
                        return@post
                    }
                    count++
                }
            }
            if (firstFocusable != null) {
                firstFocusable.requestFocus()
            } else if (onBackClick != null) {
                backPill.requestFocus()
            }
        }
    }

    override fun fitToViewport(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val fontScale = TvStyle.fontScale(context)
        if (widthPx == lastViewportW && heightPx == lastViewportH && fontScale == lastFontScale && layoutParams != null) return
        lastViewportW = widthPx; lastViewportH = heightPx; lastFontScale = fontScale
        val density = resources.displayMetrics.density
        val vwDp = (widthPx / density).toInt()
        val vhDp = (heightPx / density).toInt()
        val edgeDp = (min(vwDp, vhDp) * 0.04f).roundToInt().coerceIn(12, 32)
        val availW = (vwDp - edgeDp * 2).coerceAtLeast(1)
        val availH = (vhDp - edgeDp * 2).coerceAtLeast(1)
        val extraScale = (fontScale - 1f).coerceIn(0f, 1f)

        val desiredW = if (anchoredRight) {
            min((388 + 95 * extraScale).roundToInt(), (vwDp * (0.35f + 0.09f * extraScale)).roundToInt().coerceAtLeast(320))
        } else {
            min((540 + 130 * extraScale).roundToInt(), (vwDp * (0.52f + 0.14f * extraScale)).roundToInt().coerceAtLeast(360))
        }
        val desiredH = min((580 + 60 * extraScale).roundToInt(), (vhDp * (0.84f + 0.04f * extraScale)).roundToInt())

        val targetW = dp(desiredW.coerceIn(1, availW))
        val maxH = dp(desiredH.coerceIn(1, availH))
        val innerWSpec = MeasureSpec.makeMeasureSpec((targetW - paddingLeft - paddingRight).coerceAtLeast(1), MeasureSpec.EXACTLY)
        val unspecH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        headerBlock.measure(innerWSpec, unspecH)
        footer.measure(innerWSpec, unspecH)
        val chromeH = paddingTop + paddingBottom + headerBlock.measuredHeight + footer.measuredHeight + dp(4)
        maxScrollHeightPx = (maxH - chromeH).coerceAtLeast(dp(120))
        val grav = if (anchoredRight) (Gravity.END or Gravity.CENTER_VERTICAL) else Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(targetW, -2, grav).apply {
            setMargins(dp(edgeDp), dp(edgeDp), dp(edgeDp), dp(edgeDp))
        }
    }

    fun appear() {
        animate().cancel()
        if (TvStyle.lightweight(context)) { alpha = 1f; translationY = 0f; scaleX = 1f; scaleY = 1f; return }
        alpha = 0f
        if (anchoredRight) {
            translationX = dp(18).toFloat()
            animate().alpha(1f).translationX(0f).setDuration(TvStyle.ENTER_MS).setInterpolator(TvStyle.springOut).start()
        } else {
            scaleX = 0.96f; scaleY = 0.96f; translationY = dp(10).toFloat()
            animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(TvStyle.ENTER_MS).setInterpolator(TvStyle.springOut).start()
        }
    }
}

/**
 * Custom Obsidian Optical Crystal Modal Dialog:
 * 100% replaces system android.app.AlertDialog with tvOS porcelain action pills.
 */
@UnstableApi
@SuppressLint("ViewConstructor", "SetTextI18n")
class CinemaModalDialog(
    context: Context,
    blurEngine: CinemaBlurEngine?,
    title: String,
    message: String = "",
    inputHint: String? = null,
    initialInput: String = "",
    primaryLabel: String = "确认",
    secondaryLabel: String? = "取消",
    extraLabel: String? = null,
    onPrimary: (String) -> Boolean = { true },
    onSecondary: () -> Unit = {},
    onExtra: (() -> Unit)? = null
) : FrameLayout(context) {

    private fun dp(n: Int) = TvStyle.dp(context, n)
    private fun sp(base: Float) = TvStyle.sp(context, base)
    private var inputField: EditText? = null

    private val card = FrostedContainer(context, blurEngine, cornerRadiusDp = 24f, veilAlpha = 82).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setPadding(dp(28), dp(24), dp(28), dp(22))
        elevation = dp(18).toFloat()
    }

    init {
        setBackgroundColor(Color.argb(150, 4, 6, 10))
        isClickable = true
        isFocusable = true
        setOnClickListener { onSecondary() }

        card.addView(TextView(context).apply {
            text = title
            textSize = sp(20f)
            setTextColor(TvStyle.ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        if (message.isNotEmpty()) {
            val msgScroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(16))
                addView(TextView(context).apply {
                    text = message
                    textSize = sp(14f)
                    setTextColor(TvStyle.muted)
                    setLineSpacing(dp(4).toFloat(), 1f)
                })
            }
            card.addView(msgScroll, LinearLayout.LayoutParams(-1, -2, 1f).apply { topMargin = dp(12) })
        }

        if (inputHint != null) {
            val edit = EditText(context).apply {
                id = View.generateViewId()
                setText(initialInput)
                setSelection(initialInput.length)
                hint = inputHint
                setHintTextColor(TvStyle.subtle)
                setTextColor(TvStyle.ink)
                textSize = sp(14.5f)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), TvStyle.specularSurface(
                        context, Color.argb(190, 8, 10, 15), radiusDp = 11f,
                        rimTopLeft = Color.argb(210, 246, 248, 252), rimBottomRight = Color.argb(95, 226, 234, 248), strokeDp = 1.3f
                    ))
                    addState(intArrayOf(), TvStyle.specularSurface(
                        context, Color.argb(165, 8, 10, 15), radiusDp = 11f,
                        rimTopLeft = Color.argb(65, 255, 255, 255), rimBottomRight = Color.argb(24, 255, 255, 255)
                    ))
                }
            }
            inputField = edit
            card.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        if (extraLabel != null && onExtra != null) {
            val extraBtn = modalButton(extraLabel, primary = false) { onExtra() }
            btnRow.addView(extraBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        val secBtn = if (secondaryLabel != null) {
            modalButton(secondaryLabel, primary = false) { onSecondary() }.also {
                btnRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        } else null

        val priBtn = modalButton(primaryLabel, primary = true) {
            val value = inputField?.text?.toString().orEmpty()
            onPrimary(value)
        }
        btnRow.addView(priBtn, LinearLayout.LayoutParams(-2, -2))
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        val extraScale = (TvStyle.fontScale(context) - 1f).coerceIn(0f, 1f)
        val cardW = min(dp((460 + 110 * extraScale).roundToInt()), (resources.displayMetrics.widthPixels * 0.88f).toInt())
        val maxH = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        addView(card, LayoutParams(cardW, -2, Gravity.CENTER).apply {
            topMargin = dp(24); bottomMargin = dp(24)
        })
        card.post {
            if (card.height > maxH) {
                card.layoutParams = (card.layoutParams as LayoutParams).apply { height = maxH }
            }
            (inputField ?: secBtn ?: priBtn).requestFocus()
        }

        if (!TvStyle.lightweight(context)) {
            alpha = 0f; card.scaleX = 0.95f; card.scaleY = 0.95f
            animate().alpha(1f).setDuration(180L).setInterpolator(TvStyle.springOut).start()
            card.animate().scaleX(1f).scaleY(1f).setDuration(200L).setInterpolator(TvStyle.springOut).start()
        }
    }

    private fun modalButton(label: String, primary: Boolean, action: () -> Unit) = Button(context).apply {
        id = View.generateViewId()
        text = label
        textSize = sp(14f)
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = dp(96); minimumWidth = dp(96)
        minHeight = dp(40); minimumHeight = dp(40)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setTextColor(TvStyle.ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = if (primary) TvStyle.primaryPillButton(context) else TvStyle.pillButton(context)
        stateListAnimator = null
        setOnFocusChangeListener { v, focused ->
            setTextColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
            typeface = Typeface.create("sans-serif-medium", if (focused) Typeface.BOLD else Typeface.NORMAL)
            if (!TvStyle.lightweight(context)) {
                v.animate().cancel()
                val s = if (focused) 1.04f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(140L).setInterpolator(TvStyle.springOut).start()
            }
        }
        setOnClickListener { action() }
    }
}
