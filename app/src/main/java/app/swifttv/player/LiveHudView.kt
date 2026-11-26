package app.swifttv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Obsidian Optical Crystal Broadcast HUD Capsule:
 * Clean box-free floating station logo, crystal vertical divider, tabular channel number,
 * silver mist stream spec chip, and pure alabaster EPG progress hairline.
 */
@UnstableApi
@SuppressLint("ViewConstructor")
class LiveHudView(context: Context, blurEngine: CinemaBlurEngine? = null) : FrostedContainer(context, blurEngine, cornerRadiusDp = 22f, veilAlpha = 64) {
    private fun dp(n: Int) = TvStyle.dp(context, n)
    private fun sp(base: Float) = TvStyle.sp(context, base)
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.ROOT)

    private val numberView = TextView(context).apply {
        textSize = sp(20f)
        setTextColor(TvStyle.subtle)
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val logo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val divider = View(context).apply {
        setBackgroundColor(Color.argb(30, 255, 255, 255))
    }
    private val titleView = TextView(context).apply {
        textSize = sp(18.5f)
        setTextColor(TvStyle.ink)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val specBadge = TextView(context).apply {
        textSize = sp(11f)
        setTextColor(TvStyle.muted)
        maxLines = 1
        includeFontPadding = false
        background = TvStyle.badge(context, onPorcelain = false)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        fontFeatureSettings = "tnum"
        visibility = GONE
    }
    private val clockView = TextView(context).apply {
        textSize = sp(12.5f)
        setTextColor(TvStyle.subtle)
        includeFontPadding = false
        fontFeatureSettings = "tnum"
    }
    private val messageView = TextView(context).apply {
        textSize = sp(13f)
        setTextColor(TvStyle.muted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        val bg = GradientDrawable().apply {
            setColor(Color.argb(38, 255, 255, 255))
            cornerRadius = dp(2).toFloat()
        }
        val fg = ClipDrawable(GradientDrawable().apply {
            setColor(TvStyle.ink)
            cornerRadius = dp(2).toFloat()
        }, Gravity.START, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf(bg, fg)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        visibility = GONE
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(22), dp(14))
        minimumWidth = dp((450f * (1f + 0.32f * (TvStyle.fontScale(context) - 1f).coerceAtLeast(0f))).toInt())
        elevation = dp(8).toFloat()
        visibility = GONE

        addView(numberView, LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        addView(logo, LayoutParams(dp(68), dp(34)).apply { rightMargin = dp(14) })
        addView(divider, LayoutParams(dp(1).coerceAtLeast(1), dp(28)).apply { rightMargin = dp(14) })

        val rightCol = LinearLayout(context).apply {
            orientation = VERTICAL
            val topRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleView, LayoutParams(0, -2, 1f))
                addView(specBadge, LayoutParams(-2, -2).apply { leftMargin = dp(10) })
                addView(clockView, LayoutParams(-2, -2).apply { leftMargin = dp(12) })
            }
            addView(topRow, LayoutParams(-1, -2))
            addView(messageView, LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(progressBar, LayoutParams(-1, dp(2).coerceAtLeast(2)).apply { topMargin = dp(7) })
        }
        addView(rightCol, LayoutParams(0, -2, 1f))
    }

    fun refreshSurface() {
        invalidate()
    }

    fun showAnimated() {
        if (isVisible && alpha > 0.95f) return
        animate().cancel()
        visibility = VISIBLE
        if (TvStyle.lightweight(context)) {
            alpha = 1f; translationY = 0f; return
        }
        if (alpha < 0.1f) {
            translationY = dp(12).toFloat()
            alpha = 0f
        }
        animate().alpha(1f).translationY(0f).setDuration(200L).setInterpolator(TvStyle.springOut).start()
    }

    fun hideAnimated() {
        if (!isVisible) return
        animate().cancel()
        if (TvStyle.lightweight(context)) {
            visibility = GONE; return
        }
        animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(160L).setInterpolator(TvStyle.easeIn)
            .withEndAction { visibility = GONE; alpha = 0f }.start()
    }

    fun bind(
        channel: Channel?,
        channelNumber: String,
        message: String,
        spec: StreamSpec,
        schedule: ChannelSchedule?,
        artwork: ChannelLogos,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (channel != null) {
            numberView.text = channelNumber
            numberView.visibility = if (channelNumber.isNotEmpty()) VISIBLE else GONE
            artwork.bind(logo, channel)
            logo.visibility = VISIBLE
            divider.visibility = VISIBLE
        } else {
            numberView.visibility = GONE
            logo.visibility = GONE
            divider.visibility = GONE
        }
        titleView.text = channel?.name ?: "SwiftTV"
        clockView.text = clockFormat.format(Date(nowMs))

        val badgeText = spec.badge()
        specBadge.text = badgeText
        specBadge.visibility = if (badgeText.isNotEmpty() && channel != null) VISIBLE else GONE

        val live = schedule?.current
        val next = schedule?.next
        val isSteadyPlaying = message == "正在播放"
        messageView.text = when {
            isSteadyPlaying && live != null && next != null -> "正在播出：${live.title}  ·  即将：${next.title}"
            isSteadyPlaying && live != null -> "正在播出：${live.title}"
            !isSteadyPlaying && live != null -> "$message  ·  ${live.title}"
            else -> message
        }

        if (channel != null && live != null) {
            progressBar.progress = (live.progress(nowMs) * 1000f).toInt().coerceIn(0, 1000)
            progressBar.visibility = VISIBLE
        } else {
            progressBar.visibility = GONE
        }
    }
}

/** Top-right optical crystal capsule for 0–9 remote direct numeric channel tuning. */
@UnstableApi
@SuppressLint("ViewConstructor")
class NumberPadOsdView(context: Context, blurEngine: CinemaBlurEngine? = null) : FrostedContainer(context, blurEngine, cornerRadiusDp = 18f, veilAlpha = 68) {
    private fun dp(n: Int) = TvStyle.dp(context, n)
    private fun sp(base: Float) = TvStyle.sp(context, base)
    private val digitsView = TextView(context).apply {
        textSize = sp(26f)
        setTextColor(TvStyle.ink)
        includeFontPadding = false
        fontFeatureSettings = "tnum"
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val nameView = TextView(context).apply {
        textSize = sp(14.5f)
        setTextColor(TvStyle.muted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(dp(12), 0, 0, 0)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(10))
        elevation = dp(8).toFloat()
        visibility = GONE
        addView(digitsView, LayoutParams(-2, -2))
        addView(nameView, LayoutParams(-2, -2))
    }

    fun refreshSurface() = invalidate()

    fun showPreview(preview: NumberPadPreview) {
        digitsView.text = preview.digits
        val ch = preview.matchedChannel
        nameView.text = when {
            ch == null -> "未匹配频道"
            !preview.playable -> ch.name + " · " + ch.unavailableReason.ifEmpty { "暂无线路" }
            else -> ch.name + " · OK 切台"
        }
        nameView.setTextColor(if (preview.playable) TvStyle.ink else TvStyle.subtle)
        if (!isVisible) {
            visibility = VISIBLE
            if (!TvStyle.lightweight(context)) {
                alpha = 0f; scaleX = 0.95f; scaleY = 0.95f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(TvStyle.springOut).start()
            }
        }
    }
}

/** Top-center floating frosted crystal banner replacing raw Android system Toasts. */
@UnstableApi
@SuppressLint("ViewConstructor")
class CinemaToastBanner(context: Context, blurEngine: CinemaBlurEngine? = null) : FrostedContainer(context, blurEngine, cornerRadiusDp = 999f, veilAlpha = 74) {
    private fun dp(n: Int) = TvStyle.dp(context, n)
    private val dot = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(TvStyle.ink)
        }
    }
    private val label = TextView(context).apply {
        textSize = TvStyle.sp(context, 13.5f)
        setTextColor(TvStyle.ink)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val dismissTask = Runnable { hideBanner() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(10), dp(20), dp(10))
        elevation = dp(10).toFloat()
        visibility = GONE
        isFocusable = false
        isClickable = false
        addView(dot, LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(10) })
        addView(label, LayoutParams(-2, -2))
    }

    fun show(message: String) {
        removeCallbacks(dismissTask)
        animate().cancel()
        label.text = message
        visibility = VISIBLE
        if (TvStyle.lightweight(context)) {
            alpha = 1f; translationY = 0f
        } else {
            alpha = 0f; translationY = -dp(14).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(210L).setInterpolator(TvStyle.springOut).start()
        }
        postDelayed(dismissTask, 3000L)
    }

    private fun hideBanner() {
        animate().cancel()
        if (TvStyle.lightweight(context)) {
            visibility = GONE; return
        }
        animate().alpha(0f).translationY(-dp(10).toFloat()).setDuration(160L).setInterpolator(TvStyle.easeIn)
            .withEndAction { visibility = GONE }.start()
    }
}
