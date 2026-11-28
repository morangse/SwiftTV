package app.swifttv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min
import kotlin.math.roundToInt

private const val FOCUS_SCALE = 1.03f

/**
 * Unified Obsidian Crystal Left Floating Dual-Pane Drawer:
 * - Left rail: 「设置」 sits cleanly above 「全部」「收藏」 and channel groups, 100% D-pad reachable
 *   with 120ms focus-to-switch & top/bottom wrap-around.
 * - Right column: seamlessly switches between the high-contrast vertical channel list and the
 *   inline redesigned Settings workspace (no separate full-screen/centered modal needed).
 * - Leaves the right ~50% of the live broadcast unobstructed at all times.
 */
@UnstableApi
@SuppressLint("ViewConstructor", "SetTextI18n")
class CinemaRibbonView(
    context: Context,
    private val artwork: ChannelLogos,
    private val blurEngine: CinemaBlurEngine?,
    private val actions: Actions,
    initialGroup: String = "全部",
    private val scheduleFor: (String) -> EpgProgram? = { null },
    private val settingsModelProvider: () -> SettingsModel = { SettingsModel() },
    private val settingsActions: SettingsActions = NoOpSettingsActions,
    private val inspectorActions: InspectorActions = NoOpInspectorActions,
    private val onDismissRequest: () -> Unit = {}
) : FrameLayout(context), ViewportSizedPanel {

    interface Actions {
        fun play(channel: Channel)
        fun more(channel: Channel?)
        fun settings()
        fun groupChanged(group: String)
    }

    data class SettingsModel(
        val versionName: String = "",
        val subscriptionCount: Int = 0,
        val totalChannels: Int = 0,
        val currentGroup: String = "全部",
        val routingLabel: String = "自动",
        val switchDirectionLabel: String = "自然",
        val startupLabel: String = "关闭",
        val fontScaleMode: FontScaleMode = FontScaleMode.STANDARD,
        val lightweight: Boolean = false,
        val smartLogoLookup: Boolean = true,
        val customEpgUrl: String = "",
        val supports4kHevc50: Boolean = false,
        val logoCacheBytes: Long = 0L
    )

    interface SettingsActions {
        fun onOpenQrPairing()
        fun onOpenManualUrl()
        fun onOpenLocalFile()
        fun onSelectFontScale(mode: FontScaleMode)
        fun onToggleLightweight(): Boolean
        fun onToggleSmartLogoLookup(): Boolean
        fun onCycleRouting(): String
        fun onCycleSwitchDirection(): String
        fun onOpenStartupSettings()
        fun onShowHardwareInfo()
        fun onRefreshAll()
        fun onOpenSubscriptions()
        fun onConfigureEpg()
        fun onProbeCurrentGroup()
        fun onExportDiagnostics()
        fun onClearLogoCache(): Long
        fun onShowAboutInfo()
    }

    private object NoOpSettingsActions : SettingsActions {
        override fun onOpenQrPairing() {}
        override fun onOpenManualUrl() {}
        override fun onOpenLocalFile() {}
        override fun onSelectFontScale(mode: FontScaleMode) {}
        override fun onToggleLightweight(): Boolean = false
        override fun onToggleSmartLogoLookup(): Boolean = true
        override fun onCycleRouting(): String = "自动"
        override fun onCycleSwitchDirection(): String = "自然"
        override fun onOpenStartupSettings() {}
        override fun onShowHardwareInfo() {}
        override fun onRefreshAll() {}
        override fun onOpenSubscriptions() {}
        override fun onConfigureEpg() {}
        override fun onProbeCurrentGroup() {}
        override fun onExportDiagnostics() {}
        override fun onClearLogoCache(): Long = 0L
        override fun onShowAboutInfo() {}
    }

    data class RouteOption(
        val source: Entry,
        val title: String,
        val subtitle: String,
        val host: String,
        val dotColor: Int
    )

    data class InspectorModel(
        val channel: Channel,
        val specSummary: String,
        val isFavorite: Boolean,
        val previousChannel: Channel?,
        val routes: List<RouteOption>
    )

    interface InspectorActions {
        fun buildModel(channel: Channel): InspectorModel?
        fun onToggleFavorite(channel: Channel): Boolean
        fun onReconnect(channel: Channel)
        fun onTunePrevious(previous: Channel)
        fun onSelectRoute(channel: Channel, source: Entry)
        fun onProbeChannel(channel: Channel)
    }

    private object NoOpInspectorActions : InspectorActions {
        override fun buildModel(channel: Channel): InspectorModel? = null
        override fun onToggleFavorite(channel: Channel): Boolean = false
        override fun onReconnect(channel: Channel) {}
        override fun onTunePrevious(previous: Channel) {}
        override fun onSelectRoute(channel: Channel, source: Entry) {}
        override fun onProbeChannel(channel: Channel) {}
    }

    companion object {
        private const val SETTINGS_KEY = "__settings__"
        private const val ANCHOR_RATIO = 0.30f
    }

    private fun dp(n: Int) = TvStyle.dp(context, n)
    private fun sp(base: Float) = TvStyle.sp(context, base)

    private val selection = SidebarSelection(initialGroup)
    private var rows: List<Channel> = emptyList()
    private var globalNumbers: Map<String, String> = emptyMap()
    private var settingsMode = false
    private var inspectedChannel: Channel? = null
    private var playbackId: String? = null
    private var playbackMark = ChannelPlaybackMark.NONE
    private var lastFocus: View? = null
    private val settingsFocusViews = linkedMapOf<String, View>()
    private var lastSettingsFocusKey: String? = null
    private val inspectorFocusViews = linkedMapOf<String, View>()
    private var lastInspectorFocusKey: String? = null
    private var pendingSwitchRunnable: Runnable? = null

    private var drawerWidthPx = dp(520)
    private var drawerHeightPx = dp(596)
    private var catRailWidthPx = dp(136)
    private var rowMinHeightPx = dp(58)
    private var numWidthPx = dp((30f * TvStyle.fontScaleMode(context).multiplier).roundToInt().coerceAtLeast(28))

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val railOrder = mutableListOf<String>()
    private val railViews = linkedMapOf<String, LinearLayout>()
    private val railTitleViews = linkedMapOf<String, TextView>()
    private val railCountViews = linkedMapOf<String, TextView>()
    private var settingsGlyph: TvVectorIconDrawable? = null

    private val catListContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(12))
    }
    private val catScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = !BuildConfig.IS_LITE
        if (!BuildConfig.IS_LITE) setFadingEdgeLength(dp(18))
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        clipToPadding = true
        addView(catListContainer, LayoutParams(-1, -2))
    }

    private val colDivider = View(context).apply {
        setBackgroundColor(Color.argb(20, 240, 246, 255))
    }

    private val headerTitle = TextView(context).apply {
        text = "全部频道"
        textSize = sp(16f)
        setTextColor(TvStyle.ink)
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val positionReadout = TextView(context).apply {
        textSize = sp(12f)
        setTextColor(TvStyle.subtle)
        includeFontPadding = false
        if (Build.VERSION.SDK_INT >= 21) fontFeatureSettings = "tnum"
    }
    private val listHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(17), dp(14), dp(11))
        addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        addView(positionReadout, LinearLayout.LayoutParams(-2, -2))
    }
    private val listHeaderDivider = View(context).apply {
        setBackgroundColor(Color.argb(18, 240, 246, 255))
    }

    private val emptyStateLabel = TextView(context).apply {
        text = "还没有收藏频道\n在任意频道按右键即可快速收藏"
        textSize = sp(13.5f)
        setTextColor(TvStyle.muted)
        gravity = Gravity.CENTER
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(20), dp(28), dp(20), dp(28))
        visibility = GONE
    }

    private val layoutMgr = object : LinearLayoutManager(context, VERTICAL, false) {
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView,
            child: View,
            rect: Rect,
            immediate: Boolean,
            focusedChildVisible: Boolean
        ): Boolean {
            val anchorTop = parent.paddingTop +
                ((parent.height - parent.paddingTop - parent.paddingBottom) * ANCHOR_RATIO).toInt()
            val dy = getDecoratedTop(child) - anchorTop
            if (dy == 0) return false
            if (immediate || TvStyle.lightweight(context)) parent.scrollBy(0, dy)
            else parent.smoothScrollBy(0, dy, TvStyle.springOut, 220)
            return true
        }
    }
    val channelGallery = RecyclerView(context).apply {
        id = View.generateViewId()
        layoutManager = layoutMgr
        // Clip to gallery bounds with soft vertical fading edges so scrolled rows melt cleanly
        clipChildren = true
        clipToPadding = false
        isVerticalFadingEdgeEnabled = !BuildConfig.IS_LITE
        if (!BuildConfig.IS_LITE) setFadingEdgeLength(dp(18))
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null
        setPadding(dp(10), dp(8), dp(10), dp(8))
        contentDescription = "左侧双栏频道列表"
    }

    private fun channelAnchorOffset(): Int {
        val avail = channelGallery.height - channelGallery.paddingTop - channelGallery.paddingBottom
        return if (avail > 0) (avail * ANCHOR_RATIO).toInt() else dp(48)
    }

    private inner class AnchoredScrollView : ScrollView(context) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
            if (childCount == 0) return 0
            val maxScroll = (getChildAt(0).height - height + paddingTop + paddingBottom).coerceAtLeast(0)
            val target = rect.top - (height * ANCHOR_RATIO).toInt()
            return target.coerceIn(0, maxScroll) - scrollY
        }
    }

    private val settingsContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(14))
        clipChildren = false
        clipToPadding = false
    }
    private val settingsScroll = AnchoredScrollView().apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = !BuildConfig.IS_LITE
        if (!BuildConfig.IS_LITE) setFadingEdgeLength(dp(18))
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        visibility = GONE
        addView(settingsContent, LayoutParams(-1, -2))
    }

    private val inspectorContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(14))
        clipChildren = false
        clipToPadding = false
    }
    private val inspectorScroll = AnchoredScrollView().apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = !BuildConfig.IS_LITE
        if (!BuildConfig.IS_LITE) setFadingEdgeLength(dp(18))
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        visibility = GONE
        addView(inspectorContent, LayoutParams(-1, -2))
    }

    private val rightBodyHost = FrameLayout(context).apply {
        clipChildren = true
        clipToPadding = true
        addView(channelGallery, LayoutParams(-1, -1))
        addView(emptyStateLabel, LayoutParams(-1, -2, Gravity.CENTER))
        addView(settingsScroll, LayoutParams(-1, -1))
        addView(inspectorScroll, LayoutParams(-1, -1))
    }

    private val rightColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = true
        addView(listHeader, LinearLayout.LayoutParams(-1, -2))
        addView(listHeaderDivider, LinearLayout.LayoutParams(-1, 1))
        addView(rightBodyHost, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private val columnsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = true
        clipToPadding = true
        addView(catScroll, LinearLayout.LayoutParams(catRailWidthPx, -1))
        addView(colDivider, LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), -1))
        addView(rightColumn, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private val footerDivider = View(context).apply {
        setBackgroundColor(Color.argb(18, 240, 246, 255))
    }
    private val footerBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(9), dp(16), dp(10))
    }

    private val drawerShell = FrostedContainer(context, blurEngine, cornerRadiusDp = 24f, veilAlpha = 80).apply {
        orientation = LinearLayout.VERTICAL
        if (!BuildConfig.IS_LITE && Build.VERSION.SDK_INT >= 21) elevation = dp(14).toFloat()
        clipChildren = true
        clipToPadding = true
        addView(columnsRow, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(footerDivider, LinearLayout.LayoutParams(-1, 1))
        addView(footerBar, LinearLayout.LayoutParams(-1, -2))
    }

    private val adapter = ChannelRowAdapter()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        layoutDirection = LAYOUT_DIRECTION_LTR
        isClickable = true
        isFocusable = false
        setOnClickListener { onDismissRequest() }
        drawerShell.isClickable = true
        addView(drawerShell, LayoutParams(drawerWidthPx, drawerHeightPx, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(24); topMargin = dp(20); bottomMargin = dp(20); rightMargin = dp(24)
        })
        channelGallery.adapter = adapter
        setFooterHints("OK" to "播放", "→" to "线路/收藏", "菜单" to "设置")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrimPaint.shader = LinearGradient(
            0f, 0f, w * 0.46f, 0f,
            intArrayOf(Color.argb(150, 5, 7, 11), Color.argb(70, 5, 7, 11), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!TvStyle.lightweight(context)) {
            canvas.drawRect(0f, 0f, width * 0.46f, height.toFloat(), scrimPaint)
        }
    }

    fun isInSettingsMode(): Boolean = settingsMode

    fun refreshSurface() {
        drawerShell.invalidate()
        invalidate()
    }

    fun updateCatalog(channels: List<Channel>, favorites: Set<String>) {
        val prevGroups = selection.groups
        val changed = selection.channels != channels || selection.favorites != favorites
        val focusedRailKey = railViews.entries.firstOrNull { it.value.hasFocus() }?.key
        val galleryHadFocus = channelGallery.hasFocus()
        var playableIdx = 0
        val numMap = HashMap<String, String>(channels.size)
        channels.forEachIndexed { rawIdx, ch ->
            numMap[ch.id] = if (ch.sources.isNotEmpty()) {
                playableIdx++
                String.format(java.util.Locale.ROOT, "%02d", playableIdx)
            } else {
                String.format(java.util.Locale.ROOT, "%02d", rawIdx + 1)
            }
        }
        globalNumbers = numMap
        selection.update(channels, favorites)
        if (railViews.isEmpty() || prevGroups != selection.groups) {
            buildLeftRail()
        } else {
            updateCategoryCounts()
        }
        if (inspectedChannel != null) {
            inspectedChannel = channels.firstOrNull { it.id == inspectedChannel?.id }
            val currentInspected = inspectedChannel
            if (currentInspected != null) {
                inspectorActions.buildModel(currentInspected)?.let { renderInspectorPane(it) }
            } else {
                showGroup()
            }
        } else if (settingsMode) {
            renderSettingsPane()
        } else if (changed || rows != selection.shown) {
            showGroup()
        } else {
            refreshVisibleCards()
        }
        updatePositionReadout()
        if (!settingsMode && inspectedChannel == null && changed && (galleryHadFocus || focusedRailKey != null)) {
            post {
                if (focusedRailKey != null) (railViews[focusedRailKey] ?: railViews[selection.group])?.requestFocus()
                else if (rows.isEmpty()) focusActiveRailItem()
                else focusSelectedCard()
            }
        }
    }

    private fun countForGroup(name: String): Int = when (name) {
        "全部" -> selection.channels.size
        "收藏" -> selection.channels.count { it.id in selection.favorites }
        else -> selection.channels.count { it.group == name }
    }

    private fun updateCategoryCounts() {
        railCountViews.forEach { (key, tv) ->
            if (key != SETTINGS_KEY) {
                val n = countForGroup(key)
                tv.text = if (n == 0) "" else n.toString()
                tv.visibility = if (n == 0) INVISIBLE else VISIBLE
            }
        }
    }

    private fun buildLeftRail() {
        railOrder.clear()
        railViews.clear()
        railTitleViews.clear()
        railCountViews.clear()
        catListContainer.removeAllViews()

        val allKeys = listOf(SETTINGS_KEY) + selection.groups
        railOrder.addAll(allKeys)

        allKeys.forEachIndexed { idx, key ->
            val isSettings = key == SETTINGS_KEY
            val titleTv = TextView(context).apply {
                text = if (isSettings) "设置" else key
                textSize = sp(14f)
                setTextColor(TvStyle.muted)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val countTv = if (!isSettings) {
                val n = countForGroup(key)
                TextView(context).apply {
                    text = if (n == 0) "" else n.toString()
                    visibility = if (n == 0) INVISIBLE else VISIBLE
                    textSize = sp(11f)
                    setTextColor(TvStyle.subtle)
                    includeFontPadding = false
                    if (Build.VERSION.SDK_INT >= 21) fontFeatureSettings = "tnum"
                }
            } else null

            val row = LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                minimumHeight = dp(40)
                setPadding(dp(10), dp(8), dp(9), dp(8))
                background = railRowSelector(selected = isKeySelected(key))

                addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f).apply { if (countTv != null) rightMargin = dp(4) })
                if (isSettings) {
                    val gear = TvVectorIconDrawable(TvGlyph.SETTINGS, TvStyle.dpF(context, 1.55f), TvStyle.muted)
                    settingsGlyph = gear
                    addView(ImageView(context).apply {
                        setImageDrawable(gear)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(4) })
                } else if (countTv != null) {
                    addView(countTv, LinearLayout.LayoutParams(-2, -2))
                }

                setOnClickListener {
                    cancelPendingSwitch()
                    activateRailKeyImmediate(key)
                    focusRightPane()
                }
                setOnFocusChangeListener { v, focused ->
                    if (focused) {
                        lastFocus = v
                        scheduleRailSwitch(key)
                    }
                    syncRailVisuals()
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false else when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            cancelPendingSwitch()
                            activateRailKeyImmediate(key)
                            focusRightPane()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> true
                        KeyEvent.KEYCODE_0 -> {
                            if (!settingsMode && jumpToPlayingChannel()) true else false
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (idx == 0 && railOrder.size > 1) {
                                railViews[railOrder.last()]?.requestFocus()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (idx == railOrder.lastIndex && railOrder.size > 1) {
                                railViews[railOrder.first()]?.requestFocus()
                                true
                            } else false
                        }
                        else -> false
                    }
                }
            }
            railViews[key] = row
            railTitleViews[key] = titleTv
            if (countTv != null) railCountViews[key] = countTv
            catListContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })

            // Subtle divider separating 「设置」 from 「全部」 and channel categories
            if (isSettings) {
                catListContainer.addView(View(context).apply {
                    setBackgroundColor(Color.argb(22, 240, 246, 255))
                }, LinearLayout.LayoutParams(-1, 1).apply {
                    topMargin = dp(4); bottomMargin = dp(8); leftMargin = dp(6); rightMargin = dp(6)
                })
            }
        }
        syncRailVisuals()
    }

    private fun isKeySelected(key: String): Boolean =
        if (settingsMode) key == SETTINGS_KEY else (!settingsMode && key == selection.group)

    private fun railRowSelector(selected: Boolean) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), TvStyle.porcelainSurface(context, 12f))
        addState(intArrayOf(android.R.attr.state_focused), TvStyle.porcelainSurface(context, 12f))
        addState(
            intArrayOf(),
            if (selected) TvStyle.activeCrystalPill(context, 12f)
            else GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = TvStyle.dpF(context, 12f) }
        )
    }

    private fun cancelPendingSwitch() {
        pendingSwitchRunnable?.let { removeCallbacks(it) }
        pendingSwitchRunnable = null
    }

    private fun scheduleRailSwitch(key: String) {
        cancelPendingSwitch()
        if (isKeySelected(key)) return
        val task = Runnable {
            pendingSwitchRunnable = null
            activateRailKeyImmediate(key)
        }
        pendingSwitchRunnable = task
        postDelayed(task, 120L)
    }

    private fun activateRailKeyImmediate(key: String) {
        val wasInspector = inspectedChannel != null
        inspectedChannel = null
        if (key == SETTINGS_KEY) {
            if (!settingsMode || wasInspector) {
                settingsMode = true
                showSettingsPane(animate = true)
            }
        } else {
            val wasSettings = settingsMode
            settingsMode = false
            val changed = selection.group != key
            if (changed) {
                selection.selectGroup(key)
                actions.groupChanged(key)
            }
            if (wasSettings || wasInspector || changed) {
                showGroup(animate = true)
            }
        }
        syncRailVisuals()
    }

    private fun syncRailVisuals() {
        railViews.forEach { (key, row) ->
            val selected = isKeySelected(key)
            val focused = row.hasFocus()
            row.background = railRowSelector(selected)
            val titleTv = railTitleViews[key]
            val countTv = railCountViews[key]
            val inkColor = when {
                focused -> TvStyle.inkOnPorcelain
                selected -> TvStyle.ink
                else -> TvStyle.muted
            }
            titleTv?.setTextColor(inkColor)
            titleTv?.typeface = Typeface.create("sans-serif-medium", if (selected || focused) Typeface.BOLD else Typeface.NORMAL)
            if (key == SETTINGS_KEY) settingsGlyph?.setTintColor(inkColor)
            countTv?.setTextColor(when {
                focused -> TvStyle.mutedOnPorcelain
                selected -> TvStyle.muted
                else -> TvStyle.subtle
            })
        }
    }

    private fun setFooterHints(vararg pairs: Pair<String, String>) {
        footerBar.removeAllViews()
        pairs.forEachIndexed { idx, (key, label) ->
            footerBar.addView(TextView(context).apply {
                text = key
                textSize = sp(11f)
                setTextColor(TvStyle.ink)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                includeFontPadding = false
                maxLines = 1
                background = TvStyle.badge(context)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }, LinearLayout.LayoutParams(-2, -2))
            footerBar.addView(TextView(context).apply {
                text = label
                textSize = sp(12f)
                setTextColor(TvStyle.muted)
                includeFontPadding = false
                maxLines = 1
            }, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(5)
                if (idx < pairs.lastIndex) rightMargin = dp(14)
            })
        }
    }

    private fun playBodySwap() {
        if (TvStyle.lightweight(context)) return
        listOf<View>(rightBodyHost, headerTitle).forEach { v ->
            v.animate().cancel()
            v.alpha = 0.35f
            v.translationX = dp(6).toFloat()
            v.animate().alpha(1f).translationX(0f).setDuration(160L).setInterpolator(TvStyle.springOut).start()
        }
    }

    private fun refreshGroupFooterHints() {
        if (settingsMode || inspectedChannel != null) return
        if (playbackId != null) {
            setFooterHints("OK" to "播放", "→" to "线路/收藏", "0" to "定位当前")
        } else {
            setFooterHints("OK" to "播放", "→" to "线路/收藏", "菜单" to "设置")
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showGroup(animate: Boolean = false) {
        settingsMode = false
        inspectedChannel = null
        rows = selection.shown
        adapter.notifyDataSetChanged()
        syncRailVisuals()

        settingsScroll.visibility = GONE
        inspectorScroll.visibility = GONE
        headerTitle.text = if (selection.group == "全部") "全部频道" else selection.group
        refreshGroupFooterHints()
        emptyStateLabel.text = if (selection.group == "收藏") {
            "还没有收藏频道\n在任意频道按右键即可加入收藏"
        } else {
            "当前分类暂无频道"
        }
        emptyStateLabel.visibility = if (rows.isEmpty()) VISIBLE else GONE
        channelGallery.visibility = if (rows.isEmpty()) GONE else VISIBLE
        updatePositionReadout()

        if (rows.isNotEmpty()) {
            val targetIdx = rows.indexOfFirst { it.id == selection.selectedId }.coerceAtLeast(0)
            layoutMgr.scrollToPositionWithOffset(targetIdx, channelAnchorOffset())
        }
        if (animate) playBodySwap()
    }

    private fun showSettingsPane(animate: Boolean = false) {
        settingsMode = true
        inspectedChannel = null
        channelGallery.visibility = GONE
        emptyStateLabel.visibility = GONE
        inspectorScroll.visibility = GONE
        settingsScroll.visibility = VISIBLE
        setFooterHints("OK" to "调整", "←" to "返回分类", "返回" to "关闭")
        renderSettingsPane()
        syncRailVisuals()
        if (animate) playBodySwap()
    }

    fun renderSettingsPane() {
        val m = settingsModelProvider()
        headerTitle.text = "设置"
        positionReadout.text = "v${m.versionName}"
        settingsFocusViews.clear()
        settingsContent.removeAllViews()

        // 1. Top 3-Column Quick Import Cards (vertical icon + concise title -> fits narrow right column cleanly!)
        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val qrCard = compactImportCard(TvGlyph.QR_SCAN, "手机扫码", focusKey = "qr") { settingsActions.onOpenQrPairing() }
        val urlCard = compactImportCard(TvGlyph.LINK, "输入链接", focusKey = "url") { settingsActions.onOpenManualUrl() }
        val fileCard = compactImportCard(TvGlyph.FOLDER, "本地文件", focusKey = "file") { settingsActions.onOpenLocalFile() }
        attachBackToRailOnLeft(qrCard)
        quickRow.addView(qrCard, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        quickRow.addView(urlCard, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        quickRow.addView(fileCard, LinearLayout.LayoutParams(0, -2, 1f))
        settingsContent.addView(quickRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. Display & Elderly Font Section
        addSettingsSectionLabel("显示与长辈大字")
        val scaleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        FontScaleMode.entries.forEachIndexed { idx, mode ->
            val selected = mode == m.fontScaleMode
            val shortName = when (mode) {
                FontScaleMode.STANDARD -> "标准"
                FontScaleMode.LARGE -> "大号"
                FontScaleMode.ELDERLY -> "长辈"
                FontScaleMode.EXTRA_LARGE -> "超大"
            }
            val tile = fontTierTile(shortName, mode, selected, focusKey = "font:${mode.key}") {
                settingsActions.onSelectFontScale(mode)
            }
            if (idx == 0) attachBackToRailOnLeft(tile)
            scaleRow.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (idx < FontScaleMode.entries.lastIndex) rightMargin = dp(6)
            })
        }
        settingsContent.addView(scaleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        if (!BuildConfig.IS_LITE) {
            addSettingsRow(settingsSwitchRow(TvGlyph.CONTRAST, "轻量纯黑背景", "低性能盒子关闭实时毛玻璃", m.lightweight, focusKey = "lightweight") {
                val next = settingsActions.onToggleLightweight()
                refreshSurface()
                next
            })
        }
        addSettingsRow(settingsSwitchRow(TvGlyph.STATION_LOGO, "智能台标识别", "内置台标与公共兜底自动补全", m.smartLogoLookup, focusKey = "smart_logo") {
            settingsActions.onToggleSmartLogoLookup()
        })

        // 3. Playback & Hardware
        addSettingsSectionLabel("播放与解码")
        addSettingsRow(settingsSegmentRow(TvGlyph.ROUTE, "优先路由策略", "IPv4 / IPv6 与并发择优", m.routingLabel, focusKey = "routing") {
            settingsActions.onCycleRouting()
        }, backOnLeft = false)
        addSettingsRow(settingsSegmentRow(TvGlyph.UPDOWN, "上下键换台方向", "切换全屏上下键顺序", m.switchDirectionLabel, focusKey = "direction") {
            settingsActions.onCycleSwitchDirection()
        }, backOnLeft = false)
        addSettingsRow(settingsActionRow(TvGlyph.POWER, "开机自动看直播", m.startupLabel, focusKey = "startup") { _, _ ->
            settingsActions.onOpenStartupSettings()
        })
        addSettingsRow(settingsActionRow(
            TvGlyph.CHIP,
            "本机硬件解码能力",
            if (m.supports4kHevc50) "支持 4K HEVC 硬件解码" else "1080p 硬件解码优先",
            focusKey = "hardware"
        ) { _, _ -> settingsActions.onShowHardwareInfo() })

        // 4. Sources & Maintenance
        addSettingsSectionLabel("频道源与维护")
        addSettingsRow(settingsActionRow(TvGlyph.REFRESH, "刷新全部订阅与节目单", "立即后台同步最新频道与预告", focusKey = "refresh") { _, _ ->
            settingsActions.onRefreshAll()
        })
        addSettingsRow(settingsActionRow(
            TvGlyph.SOURCES,
            "管理已保存订阅",
            "共 ${m.subscriptionCount} 个源 · ${m.totalChannels} 个频道",
            focusKey = "subs"
        ) { _, _ -> settingsActions.onOpenSubscriptions() })
        addSettingsRow(settingsActionRow(
            TvGlyph.GUIDE,
            "XMLTV 节目单地址",
            if (m.customEpgUrl.isEmpty()) "未配置自定义节目单" else "已配置自定义节目单",
            focusKey = "epg"
        ) { _, _ -> settingsActions.onConfigureEpg() })
        addSettingsRow(settingsActionRow(TvGlyph.SPEED, "批量测速当前分类", "分类：${m.currentGroup}", focusKey = "probe") { _, _ ->
            settingsActions.onProbeCurrentGroup()
        })
        addSettingsRow(settingsActionRow(TvGlyph.EXPORT, "扫码导出脱敏诊断", "排查播放质量与日志", focusKey = "export") { _, _ ->
            settingsActions.onExportDiagnostics()
        })
        val cacheMb = String.format(java.util.Locale.ROOT, "%.2f MB", m.logoCacheBytes / (1024f * 1024f))
        addSettingsRow(settingsActionRow(TvGlyph.SWEEP, "清理台标缓存", "当前占用 $cacheMb", focusKey = "sweep") { _, subTv ->
            val freed = settingsActions.onClearLogoCache()
            subTv.text = String.format(java.util.Locale.ROOT, "已清理 %.2f MB", freed / (1024f * 1024f))
        })
        addSettingsRow(settingsActionRow(
            TvGlyph.ABOUT,
            "关于与按键说明",
            if (BuildConfig.IS_LITE) "SwiftTV Lite ${m.versionName} · 适配 Android 4.4+" else "SwiftTV ${m.versionName} · 遥控器快捷指南",
            focusKey = "about"
        ) { _, _ ->
            settingsActions.onShowAboutInfo()
        })
    }

    fun openChannelInspector(channel: Channel): Boolean {
        val model = inspectorActions.buildModel(channel) ?: return false
        openInspector(model, animate = true)
        return true
    }

    fun openForInspector(channel: Channel) {
        if (railViews.isEmpty()) buildLeftRail()
        cancelPendingSwitch()
        settingsMode = false
        selection.open(channel.id)
        actions.groupChanged(selection.group)
        showGroup(animate = false)
        val model = inspectorActions.buildModel(channel) ?: return
        openInspector(model, animate = false)
    }

    fun handleBack(): Boolean {
        if (inspectedChannel != null) {
            closeInspectorToChannels()
            return true
        }
        return false
    }

    private fun closeInspectorToChannels() {
        val targetId = inspectedChannel?.id ?: selection.selectedId
        inspectedChannel = null
        targetId?.let { selection.focus(it) }
        showGroup(animate = true)
        post { focusSelectedCard() }
    }

    private fun openInspector(model: InspectorModel, animate: Boolean) {
        val switchingChannel = inspectedChannel?.id != model.channel.id
        if (switchingChannel) lastInspectorFocusKey = "fav"
        settingsMode = false
        inspectedChannel = model.channel
        channelGallery.visibility = GONE
        emptyStateLabel.visibility = GONE
        settingsScroll.visibility = GONE
        inspectorScroll.visibility = VISIBLE
        headerTitle.text = model.channel.name
        positionReadout.text = "${model.routes.size} 条线路"
        setFooterHints("OK" to "确认", "←" to "返回频道", "返回" to "关闭")
        renderInspectorPane(model)
        syncRailVisuals()
        if (animate) playBodySwap()
        inspectorScroll.post {
            inspectorScroll.scrollTo(0, 0)
            (lastInspectorFocusKey?.let { inspectorFocusViews[it] } ?: findFirstFocusable(inspectorContent))?.requestFocus()
        }
    }

    private fun renderInspectorPane(model: InspectorModel) {
        val hadFocus = inspectorScroll.hasFocus()
        inspectorFocusViews.clear()
        inspectorContent.removeAllViews()
        if (model.specSummary.isNotBlank()) {
            inspectorContent.addView(TextView(context).apply {
                text = model.specSummary
                textSize = sp(12f)
                setTextColor(TvStyle.muted)
                includeFontPadding = false
                setPadding(dp(4), dp(2), dp(4), dp(10))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        var wellHandle: TvIconWellView? = null
        var isFav = model.isFavorite
        val favRow = settingsActionRow(
            glyph = if (isFav) TvGlyph.STAR_SOLID else TvGlyph.STAR_OUTLINE,
            title = if (isFav) "取消收藏频道" else "收藏这个频道",
            subtitle = if (isFav) "在「收藏」分类中快速找到" else "加入左侧「收藏」分组",
            focusKey = "fav",
            focusMap = inspectorFocusViews,
            onFocusedKey = { lastInspectorFocusKey = it },
            onWellReady = { wellHandle = it }
        ) { titleTv, subTv ->
            isFav = inspectorActions.onToggleFavorite(model.channel)
            wellHandle?.setGlyph(if (isFav) TvGlyph.STAR_SOLID else TvGlyph.STAR_OUTLINE)
            titleTv.text = if (isFav) "取消收藏频道" else "收藏这个频道"
            subTv.text = if (isFav) "在「收藏」分类中快速找到" else "加入左侧「收藏」分组"
        }
        attachInspectorBackToChannelsOnLeft(favRow)
        inspectorContent.addView(favRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val reconnectRow = settingsActionRow(
            TvGlyph.UNDO,
            "重新连接此频道",
            "清理当前缓冲并重新拉流",
            focusKey = "reconnect",
            focusMap = inspectorFocusViews,
            onFocusedKey = { lastInspectorFocusKey = it }
        ) { _, _ -> inspectorActions.onReconnect(model.channel) }
        attachInspectorBackToChannelsOnLeft(reconnectRow)
        inspectorContent.addView(reconnectRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        model.previousChannel?.let { prev ->
            val prevRow = settingsActionRow(
                TvGlyph.PLAYBACK,
                "切回上一台：${prev.name}",
                "快速返回刚才观看的频道",
                focusKey = "prev",
                focusMap = inspectorFocusViews,
                onFocusedKey = { lastInspectorFocusKey = it }
            ) { _, _ -> inspectorActions.onTunePrevious(prev) }
            attachInspectorBackToChannelsOnLeft(prevRow)
            inspectorContent.addView(prevRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        inspectorContent.addView(TextView(context).apply {
            text = "选择信号线路 (${model.routes.size})"
            textSize = sp(11.5f)
            setTextColor(TvStyle.muted)
            includeFontPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }, LinearLayout.LayoutParams(-1, -2))

        model.routes.forEachIndexed { idx, route ->
            val dotView = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(route.dotColor)
                }
            }
            val hostTv = TextView(context).apply {
                text = route.host
                textSize = sp(11.5f)
                setTextColor(TvStyle.subtle)
                includeFontPadding = false
                maxLines = 1
                visibility = if (route.host.isEmpty()) GONE else VISIBLE
            }
            val trailing = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dotView, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    if (route.host.isNotEmpty()) rightMargin = dp(6)
                })
                addView(hostTv, LinearLayout.LayoutParams(-2, -2))
            }
            val routeRow = settingsBaseRow(
                glyph = TvGlyph.SIGNAL,
                title = route.title,
                subtitle = route.subtitle,
                trailing = trailing,
                focusKey = "route:$idx",
                focusMap = inspectorFocusViews,
                onFocusedKey = { lastInspectorFocusKey = it },
                onTrailingFocus = { focused ->
                    hostTv.setTextColor(if (focused) TvStyle.mutedOnPorcelain else TvStyle.subtle)
                }
            ) { _, _ ->
                inspectorActions.onSelectRoute(model.channel, route.source)
            }
            attachInspectorBackToChannelsOnLeft(routeRow)
            inspectorContent.addView(routeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        val probeRow = settingsActionRow(
            TvGlyph.SPEED,
            "检测此频道所有线路",
            "逐一测速并标记推荐路线",
            focusKey = "probe",
            focusMap = inspectorFocusViews,
            onFocusedKey = { lastInspectorFocusKey = it }
        ) { _, _ -> inspectorActions.onProbeChannel(model.channel) }
        attachInspectorBackToChannelsOnLeft(probeRow)
        inspectorContent.addView(probeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        if (hadFocus) {
            (lastInspectorFocusKey?.let { inspectorFocusViews[it] } ?: findFirstFocusable(inspectorContent))?.requestFocus()
        }
    }

    private fun attachInspectorBackToChannelsOnLeft(view: View) {
        view.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                closeInspectorToChannels()
                true
            } else false
        }
    }

    private fun addSettingsSectionLabel(text: String) {
        settingsContent.addView(TextView(context).apply {
            this.text = text
            textSize = sp(11.5f)
            setTextColor(TvStyle.muted)
            includeFontPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addSettingsRow(view: View, backOnLeft: Boolean = true) {
        if (backOnLeft) attachBackToRailOnLeft(view)
        settingsContent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun compactImportCard(glyph: TvGlyph, label: String, focusKey: String? = null, onClick: () -> Unit): LinearLayout {
        val well = TvIconWellView(context, glyph, boxDp = 34, iconDp = 17)
        val labelTv = TextView(context).apply {
            text = label
            textSize = sp(12f)
            setTextColor(TvStyle.ink)
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(7), 0, 0)
        }
        return LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(8), dp(12), dp(8), dp(11))
            background = TvStyle.porcelainRowSelector(context, TvStyle.ROW_RADIUS)
            addView(well, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(labelTv, LinearLayout.LayoutParams(-1, -2))
            if (focusKey != null) settingsFocusViews[focusKey] = this
            setOnFocusChangeListener { v, focused ->
                if (focused) {
                    lastFocus = v
                    if (focusKey != null) lastSettingsFocusKey = focusKey
                }
                well.setOnPorcelain(focused)
                labelTv.setTextColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
                labelTv.typeface = Typeface.create("sans-serif-medium", if (focused) Typeface.BOLD else Typeface.NORMAL)
                animateSettingsScale(v, focused)
            }
            setOnClickListener {
                if (focusKey != null) lastSettingsFocusKey = focusKey
                onClick()
            }
        }
    }

    private fun fontTierTile(label: String, mode: FontScaleMode, selected: Boolean, focusKey: String? = null, onClick: () -> Unit): LinearLayout {
        val titleTv = TextView(context).apply {
            text = "Aa"
            // Preview each tier at its own visual size rather than double-scaling by sp().
            textSize = 13f * mode.multiplier
            setTextColor(TvStyle.ink)
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val titleBox = FrameLayout(context).apply {
            addView(titleTv, LayoutParams(-2, -2, Gravity.CENTER))
        }
        val subTv = TextView(context).apply {
            text = label
            textSize = sp(11.5f)
            setTextColor(TvStyle.muted)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        val selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), TvStyle.porcelainSurface(context, 11f))
            addState(intArrayOf(android.R.attr.state_focused), TvStyle.porcelainSurface(context, 11f))
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = TvStyle.dpF(context, 11f)
                if (selected) {
                    setColor(Color.argb(30, 244, 248, 255))
                    setStroke(TvStyle.dpF(context, 1.5f).roundToInt().coerceAtLeast(1), Color.argb(200, 246, 248, 252))
                } else {
                    setColor(Color.argb(14, 240, 246, 255))
                    setStroke(dp(1).coerceAtLeast(1), Color.argb(24, 240, 246, 255))
                }
            })
        }
        return LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = selector
            addView(titleBox, LinearLayout.LayoutParams(-1, dp(30)))
            addView(subTv)
            if (focusKey != null) settingsFocusViews[focusKey] = this
            setOnFocusChangeListener { v, focused ->
                if (focused) {
                    lastFocus = v
                    if (focusKey != null) lastSettingsFocusKey = focusKey
                }
                titleTv.setTextColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
                subTv.setTextColor(if (focused) TvStyle.mutedOnPorcelain else TvStyle.muted)
                animateSettingsScale(v, focused)
            }
            setOnClickListener {
                if (focusKey != null) lastSettingsFocusKey = focusKey
                onClick()
            }
        }
    }

    private fun settingsBaseRow(
        glyph: TvGlyph,
        title: String,
        subtitle: String,
        trailing: View,
        focusKey: String? = null,
        focusMap: MutableMap<String, View> = settingsFocusViews,
        onFocusedKey: (String) -> Unit = { lastSettingsFocusKey = it },
        onWellReady: ((TvIconWellView) -> Unit)? = null,
        onTrailingFocus: (Boolean) -> Unit,
        onClick: (TextView, TextView) -> Unit
    ): LinearLayout {
        val well = TvIconWellView(context, glyph, boxDp = 30, iconDp = 15)
        onWellReady?.invoke(well)
        val titleTv = TextView(context).apply {
            text = title
            textSize = sp(14f)
            setTextColor(TvStyle.ink)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subTv = TextView(context).apply {
            text = subtitle
            textSize = sp(12f)
            setTextColor(TvStyle.muted)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
            visibility = if (subtitle.isEmpty()) GONE else VISIBLE
        }
        return LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(52)
            setPadding(dp(11), dp(8), dp(12), dp(8))
            background = TvStyle.porcelainRowSelector(context, TvStyle.ROW_RADIUS)
            addView(well, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleTv)
                addView(subTv)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            addView(trailing)
            if (focusKey != null) focusMap[focusKey] = this
            setOnFocusChangeListener { v, focused ->
                if (focused) {
                    lastFocus = v
                    if (focusKey != null) onFocusedKey(focusKey)
                }
                well.setOnPorcelain(focused)
                titleTv.setTextColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
                titleTv.typeface = Typeface.create("sans-serif-medium", if (focused) Typeface.BOLD else Typeface.NORMAL)
                subTv.setTextColor(if (focused) TvStyle.mutedOnPorcelain else TvStyle.muted)
                onTrailingFocus(focused)
                animateSettingsScale(v, focused)
            }
            setOnClickListener {
                if (focusKey != null) onFocusedKey(focusKey)
                onClick(titleTv, subTv)
            }
        }
    }

    private fun settingsSwitchRow(
        glyph: TvGlyph,
        title: String,
        subtitle: String,
        initial: Boolean,
        focusKey: String? = null,
        onToggle: () -> Boolean
    ): LinearLayout {
        val sw = CinemaSwitchView(context, initial)
        return settingsBaseRow(
            glyph = glyph,
            title = title,
            subtitle = subtitle,
            trailing = sw,
            focusKey = focusKey,
            onTrailingFocus = { focused -> sw.setRowFocused(focused) }
        ) { _, _ ->
            sw.setCheckedAnimated(onToggle())
        }
    }

    private fun settingsSegmentRow(
        glyph: TvGlyph,
        title: String,
        subtitle: String,
        initialValue: String,
        focusKey: String? = null,
        onCycle: () -> String
    ): LinearLayout {
        val pill = TextView(context).apply {
            text = "$initialValue ›"
            textSize = sp(11.5f)
            setTextColor(TvStyle.ink)
            includeFontPadding = false
            background = TvStyle.badge(context, onPorcelain = false)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val trigger = { pill.text = "${onCycle()} ›" }
        return settingsBaseRow(
            glyph = glyph,
            title = title,
            subtitle = subtitle,
            trailing = pill,
            focusKey = focusKey,
            onTrailingFocus = { focused ->
                pill.setTextColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
                pill.background = TvStyle.badge(context, onPorcelain = focused)
            }
        ) { _, _ -> trigger() }.apply {
            setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { trigger(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { focusActiveRailItem(); true }
                    else -> false
                }
            }
        }
    }

    private fun settingsActionRow(
        glyph: TvGlyph,
        title: String,
        subtitle: String,
        focusKey: String? = null,
        focusMap: MutableMap<String, View> = settingsFocusViews,
        onFocusedKey: (String) -> Unit = { lastSettingsFocusKey = it },
        onWellReady: ((TvIconWellView) -> Unit)? = null,
        onClick: (TextView, TextView) -> Unit
    ): LinearLayout {
        val accTv = TextView(context).apply {
            text = "›"
            textSize = sp(15f)
            setTextColor(TvStyle.subtle)
            includeFontPadding = false
        }
        return settingsBaseRow(
            glyph = glyph,
            title = title,
            subtitle = subtitle,
            trailing = accTv,
            focusKey = focusKey,
            focusMap = focusMap,
            onFocusedKey = onFocusedKey,
            onWellReady = onWellReady,
            onTrailingFocus = { focused ->
                accTv.setTextColor(if (focused) TvStyle.mutedOnPorcelain else TvStyle.subtle)
            },
            onClick = onClick
        )
    }

    private fun attachBackToRailOnLeft(view: View) {
        view.setOnKeyListener { _, key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                focusActiveRailItem()
                true
            } else false
        }
    }

    private fun animateSettingsScale(v: View, focused: Boolean) {
        if (TvStyle.lightweight(context)) return
        v.animate().cancel()
        val s = if (focused) FOCUS_SCALE else 1f
        if (Build.VERSION.SDK_INT >= 21) v.elevation = 0f
        v.animate().scaleX(s).scaleY(s).setDuration(140L).setInterpolator(TvStyle.springOut).start()
    }

    private fun updatePositionReadout() {
        if (settingsMode || inspectedChannel != null) return
        if (rows.isEmpty()) {
            positionReadout.text = "00 / 00"
            return
        }
        val idx = (rows.indexOfFirst { it.id == selection.selectedId }.takeIf { it >= 0 } ?: 0) + 1
        positionReadout.text = String.format(java.util.Locale.ROOT, "%02d / %02d", idx, rows.size)
    }

    fun open(
        currentId: String?,
        restore: Boolean,
        openSettings: Boolean = false,
        focusRightSettings: Boolean = false,
        focusSettingsKey: String? = null
    ) {
        cancelPendingSwitch()
        if (!openSettings && restore && inspectedChannel != null) {
            val current = inspectedChannel!!
            val model = inspectorActions.buildModel(current)
            if (model != null) {
                openInspector(model, animate = false)
                return
            }
        }
        inspectedChannel = null
        if (focusSettingsKey != null) lastSettingsFocusKey = focusSettingsKey
        if (openSettings) {
            if (railViews.isEmpty()) buildLeftRail()
            showSettingsPane()
            post {
                if (focusRightSettings || focusSettingsKey != null) focusSettingsKey(focusSettingsKey)
                else railViews[SETTINGS_KEY]?.requestFocus()
            }
            return
        }
        if (!restore) {
            settingsMode = false
            selection.open(currentId)
            actions.groupChanged(selection.group)
            showGroup()
            lastFocus = null
        } else if (settingsMode) {
            showSettingsPane()
            post {
                val remembered = lastSettingsFocusKey?.let { settingsFocusViews[it] }
                when {
                    remembered?.isShown == true && remembered.isFocusable -> remembered.requestFocus()
                    lastFocus?.isShown == true && lastFocus?.isFocusable == true -> lastFocus?.requestFocus()
                    else -> railViews[SETTINGS_KEY]?.requestFocus()
                }
            }
            return
        }
        post {
            if (restore && lastFocus?.isShown == true && lastFocus?.isFocusable == true) {
                lastFocus?.requestFocus()
            } else if (rows.isEmpty()) {
                focusActiveRailItem()
            } else {
                focusSelectedCard()
            }
        }
    }

    private fun focusActiveRailItem() {
        val key = if (settingsMode) SETTINGS_KEY else selection.group
        (railViews[key] ?: railViews.values.firstOrNull())?.requestFocus()
    }

    private fun focusRightPane() {
        when {
            inspectedChannel != null -> inspectorScroll.post { findFirstFocusable(inspectorContent)?.requestFocus() }
            settingsMode -> focusSettingsKey(lastSettingsFocusKey)
            rows.isNotEmpty() -> focusSelectedCard()
            else -> focusActiveRailItem()
        }
    }

    private fun focusSettingsKey(key: String?) {
        settingsScroll.post {
            val remembered = key?.let { settingsFocusViews[it] }
            val target = remembered ?: findFirstFocusable(settingsContent) ?: railViews[SETTINGS_KEY]
            target?.requestFocus()
        }
    }

    fun jumpToPlayingChannel(): Boolean {
        val id = playbackId ?: return false
        selection.channels.firstOrNull { it.id == id } ?: return false
        inspectedChannel = null
        settingsMode = false
        if (selection.shown.none { it.id == id } && selection.group != "全部") {
            selection.selectGroup("全部")
            actions.groupChanged("全部")
        }
        showGroup(animate = true)
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) {
            post { focusPosition(idx) }
        }
        return true
    }

    private fun focusPage(fromPos: Int, dir: Int) {
        if (rows.isEmpty()) return
        val visible = channelGallery.childCount.coerceAtLeast(1)
        val step = (visible - 1).coerceAtLeast(4)
        val target = (fromPos + dir * step).coerceIn(0, rows.lastIndex)
        if (target != fromPos) focusPosition(target)
    }

    private fun findFirstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            if (child.isFocusable) return child
            if (child is ViewGroup) findFirstFocusable(child)?.let { return it }
        }
        return null
    }

    private fun focusPosition(idx: Int) {
        if (rows.isEmpty()) { focusActiveRailItem(); return }
        val clamped = idx.coerceIn(0, rows.lastIndex)
        selection.focus(rows[clamped].id)
        updatePositionReadout()
        val holder = channelGallery.findViewHolderForAdapterPosition(clamped)
        if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            layoutMgr.scrollToPositionWithOffset(clamped, channelAnchorOffset())
            channelGallery.post {
                channelGallery.findViewHolderForAdapterPosition(clamped)?.itemView?.requestFocus()
                    ?: channelGallery.getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun focusSelectedCard() {
        if (rows.isEmpty()) { focusActiveRailItem(); return }
        val idx = rows.indexOfFirst { it.id == selection.selectedId }.coerceAtLeast(0)
        focusPosition(idx)
    }

    fun selectedChannel(): Channel? = if (settingsMode || inspectedChannel != null) null else selection.selected()
    fun selectedGroup(): String = selection.group
    fun hasCardFocus(): Boolean = !settingsMode && inspectedChannel == null && channelGallery.hasFocus()

    fun updatePlayback(channelId: String?, mark: ChannelPlaybackMark) {
        if (playbackId == channelId && playbackMark == mark) return
        val hadPlayback = playbackId != null
        playbackId = channelId
        playbackMark = mark
        refreshVisibleCards()
        if (hadPlayback != (channelId != null)) refreshGroupFooterHints()
    }

    fun refreshPrograms() {
        if (!settingsMode) refreshVisibleCards()
    }

    private fun markFor(id: String) = if (id == playbackId) playbackMark else ChannelPlaybackMark.NONE

    private fun refreshVisibleCards() {
        val now = System.currentTimeMillis()
        for (i in 0 until channelGallery.childCount) {
            val row = channelGallery.getChildAt(i) as? DualPaneChannelRow ?: continue
            row.updateStatus(markFor(row.channelId), row.channelId in selection.favorites, scheduleFor(row.channelId), now)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun fitToViewport(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val density = resources.displayMetrics.density
        val fontScale = TvStyle.fontScale(context).coerceIn(1f, 2f)
        val extra = (fontScale - 1f).coerceIn(0f, 1f)
        val vwDp = (widthPx / density).toInt()
        val vhDp = (heightPx / density).toInt()
        val edgeDp = (min(vwDp, vhDp) * 0.030f).roundToInt().coerceIn(10, 24)
        val availW = (vwDp - edgeDp * 2).coerceAtLeast(260)
        val availH = (vhDp - edgeDp * 2).coerceAtLeast(220)

        val desiredWDp = min((548 + 150 * extra).roundToInt(), (vwDp * (0.52f + 0.14f * extra)).roundToInt().coerceAtLeast(400))
        val desiredHDp = min((630 + 60 * extra).roundToInt(), (vhDp * 0.91f).roundToInt())
        val newW = dp(desiredWDp.coerceIn(260, availW))
        val newH = dp(desiredHDp.coerceIn(220, availH))
        val newCatW = dp((136 + 56 * extra).roundToInt().coerceIn(110, (desiredWDp * 0.34f).roundToInt()))
        val newRowH = dp((56 + 20 * extra).roundToInt().coerceIn(48, 86))
        val newNumW = dp((30f * fontScale).roundToInt().coerceAtLeast(28))

        if (newW != drawerWidthPx || newH != drawerHeightPx || newCatW != catRailWidthPx || newRowH != rowMinHeightPx || newNumW != numWidthPx) {
            drawerWidthPx = newW
            drawerHeightPx = newH
            catRailWidthPx = newCatW
            rowMinHeightPx = newRowH
            numWidthPx = newNumW
            drawerShell.layoutParams = LayoutParams(drawerWidthPx, drawerHeightPx, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(edgeDp); topMargin = dp(edgeDp); bottomMargin = dp(edgeDp); rightMargin = dp(edgeDp)
            }
            catScroll.layoutParams = LinearLayout.LayoutParams(catRailWidthPx, -1)
            adapter.notifyDataSetChanged()
        }
    }

    fun appear() {
        animate().cancel()
        drawerShell.animate().cancel()
        resetSwapViews()
        if (TvStyle.lightweight(context)) {
            alpha = 1f; drawerShell.translationX = 0f; drawerShell.alpha = 1f; return
        }
        alpha = 0f
        drawerShell.translationX = -dp(24).toFloat()
        drawerShell.alpha = 0f
        animate().alpha(1f).setDuration(TvStyle.ENTER_MS).setInterpolator(TvStyle.springOut).start()
        drawerShell.animate().translationX(0f).alpha(1f).setDuration(TvStyle.ENTER_MS).setInterpolator(TvStyle.springOut).start()
    }

    fun disappear(finished: () -> Unit) {
        cancelPendingSwitch()
        animate().cancel()
        drawerShell.animate().cancel()
        if (TvStyle.lightweight(context)) { finished(); return }
        drawerShell.animate().translationX(-dp(18).toFloat()).alpha(0f).setDuration(TvStyle.EXIT_MS).setInterpolator(TvStyle.easeIn).start()
        animate().alpha(0f).setDuration(TvStyle.EXIT_MS).setInterpolator(TvStyle.easeIn).withEndAction(finished).start()
    }

    fun cancelMotion() {
        cancelPendingSwitch()
        animate().withEndAction(null).cancel()
        drawerShell.animate().withEndAction(null).cancel()
        alpha = 1f; drawerShell.translationX = 0f; drawerShell.alpha = 1f
        resetSwapViews()
    }

    private fun resetSwapViews() {
        rightBodyHost.animate().cancel()
        rightBodyHost.alpha = 1f
        rightBodyHost.translationX = 0f
        headerTitle.animate().cancel()
        headerTitle.alpha = 1f
        headerTitle.translationX = 0f
    }

    private inner class RowHolder(val rowView: DualPaneChannelRow) : RecyclerView.ViewHolder(rowView)

    private inner class ChannelRowAdapter : RecyclerView.Adapter<RowHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = rows.size
        override fun getItemId(position: Int) = rows[position].id.fold(1125899906842597L) { h, ch -> h * 31 + ch.code }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val view = DualPaneChannelRow(context)
            view.isFocusable = true
            return RowHolder(view)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val channel = rows[position]
            holder.rowView.minimumHeight = rowMinHeightPx
            holder.rowView.layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                bottomMargin = dp(5)
            }
            holder.rowView.setNumWidth(numWidthPx)
            holder.rowView.bind(
                channel = channel,
                displayNumber = globalNumbers[channel.id] ?: String.format(java.util.Locale.ROOT, "%02d", position + 1),
                mark = markFor(channel.id),
                favorite = channel.id in selection.favorites,
                program = scheduleFor(channel.id),
                artwork = artwork,
                nowMs = System.currentTimeMillis()
            )
            holder.rowView.setOnFocusChangeListener { v, focused ->
                holder.rowView.applyFocusVisual(focused)
                if (focused) {
                    lastFocus = v
                    selection.focus(channel.id)
                    updatePositionReadout()
                }
            }
            holder.rowView.setOnClickListener {
                selection.focus(channel.id)
                actions.play(channel)
            }
            holder.rowView.setOnLongClickListener {
                selection.focus(channel.id)
                actions.more(channel)
                true
            }
            holder.rowView.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        focusActiveRailItem()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        selection.focus(channel.id)
                        actions.more(channel)
                        true
                    }
                    KeyEvent.KEYCODE_0 -> {
                        jumpToPlayingChannel()
                    }
                    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        focusPage(position, +1)
                        true
                    }
                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        focusPage(position, -1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (position == 0 && rows.size > 1) {
                            focusPosition(rows.lastIndex)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (position == rows.lastIndex && rows.size > 1) {
                            focusPosition(0)
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
    }
}

/**
 * High-contrast Obsidian/Porcelain channel row for the left dual-pane channel drawer.
 */
@SuppressLint("ViewConstructor")
private class DualPaneChannelRow(context: Context) : LinearLayout(context) {
    private fun dp(n: Int) = TvStyle.dp(context, n)
    private fun sp(base: Float) = TvStyle.sp(context, base)

    var channelId: String = ""
        private set
    private var missing = false
    private var unavailableReason = ""
    private var currentMark = ChannelPlaybackMark.NONE
    private var favoriteState = false
    private var rowFocused = false

    private val playingBarBg = GradientDrawable().apply {
        cornerRadius = TvStyle.dpF(context, 2f)
        setColor(TvStyle.ink)
    }
    private val playingBar = View(context).apply {
        background = playingBarBg
        visibility = INVISIBLE
    }
    private val focusedLogoBg = GradientDrawable().apply {
        setColor(Color.rgb(18, 22, 30))
        cornerRadius = TvStyle.dpF(context, 6f)
    }
    private val numView = TextView(context).apply {
        textSize = sp(11.5f)
        setTextColor(TvStyle.subtle)
        includeFontPadding = false
        if (Build.VERSION.SDK_INT >= 21) fontFeatureSettings = "tnum"
        maxLines = 1
        gravity = Gravity.CENTER
    }
    private val numLayoutParams = LayoutParams(dp(28), -2).apply { rightMargin = dp(6) }
    private val logoView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val liveDot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TvStyle.sage) }
        visibility = GONE
    }
    private val nameView = TextView(context).apply {
        textSize = sp(14f)
        setTextColor(TvStyle.ink)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subView = TextView(context).apply {
        textSize = sp(12f)
        setTextColor(TvStyle.muted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val rightHint = TextView(context).apply {
        textSize = sp(11.5f)
        setTextColor(TvStyle.subtle)
        includeFontPadding = false
        maxLines = 1
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(8), dp(12), dp(8))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), TvStyle.porcelainSurface(context, TvStyle.ROW_RADIUS.toFloat()))
            addState(intArrayOf(android.R.attr.state_focused), TvStyle.porcelainSurface(context, TvStyle.ROW_RADIUS.toFloat()))
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = TvStyle.dpF(context, TvStyle.ROW_RADIUS.toFloat())
            })
        }

        addView(playingBar, LayoutParams(dp(3), dp(22)).apply { rightMargin = dp(5) })
        addView(numView, numLayoutParams)
        addView(logoView, LayoutParams(dp(44), dp(28)).apply { rightMargin = dp(10) })

        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(liveDot, LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(6) })
            addView(nameView, LayoutParams(0, -2, 1f))
        }
        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(titleRow, LayoutParams(-1, -2))
            addView(subView, LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        addView(textCol, LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        addView(rightHint, LayoutParams(-2, -2))
    }

    fun setNumWidth(widthPx: Int) {
        if (numLayoutParams.width != widthPx) {
            numLayoutParams.width = widthPx
            numView.layoutParams = numLayoutParams
        }
    }

    fun bind(
        channel: Channel,
        displayNumber: String,
        mark: ChannelPlaybackMark,
        favorite: Boolean,
        program: EpgProgram?,
        artwork: ChannelLogos,
        nowMs: Long
    ) {
        channelId = channel.id
        missing = channel.sources.isEmpty()
        unavailableReason = channel.unavailableReason
        numView.text = displayNumber
        nameView.text = channel.name
        artwork.bind(logoView, channel)
        updateStatus(mark, favorite, program, nowMs)
        applyFocusVisual(isFocused, animate = false)
    }

    fun updateStatus(mark: ChannelPlaybackMark, favorite: Boolean, program: EpgProgram?, nowMs: Long) {
        currentMark = mark
        favoriteState = favorite
        liveDot.visibility = if (mark == ChannelPlaybackMark.PLAYING) VISIBLE else GONE
        val isActive = mark == ChannelPlaybackMark.PLAYING ||
            mark == ChannelPlaybackMark.BUFFERING ||
            mark == ChannelPlaybackMark.CONNECTING
        playingBar.visibility = if (isActive) VISIBLE else INVISIBLE

        val progTitle = program?.title?.takeIf { it.isNotBlank() }
        val detail = when {
            missing -> unavailableReason.ifEmpty { "暂无可用线路" }
            mark == ChannelPlaybackMark.FAILED -> "按右键切换备用线路或重试"
            mark == ChannelPlaybackMark.BUFFERING -> "正在缓冲…"
            mark == ChannelPlaybackMark.CONNECTING -> "正在连接…"
            progTitle != null -> progTitle
            mark == ChannelPlaybackMark.PLAYING -> "正在播出"
            else -> ""
        }
        subView.text = detail
        subView.visibility = if (detail.isEmpty()) GONE else VISIBLE

        refreshHint()
        syncColors()
    }

    private fun refreshHint() {
        val hint = when {
            rowFocused -> if (currentMark == ChannelPlaybackMark.FAILED) "异常 ›" else "线路 ›"
            currentMark == ChannelPlaybackMark.FAILED -> "异常"
            currentMark == ChannelPlaybackMark.PLAYING -> "直播中"
            favoriteState -> "已收藏"
            else -> ""
        }
        rightHint.text = hint
        rightHint.visibility = if (hint.isEmpty()) GONE else VISIBLE
    }

    fun applyFocusVisual(focused: Boolean, animate: Boolean = true) {
        rowFocused = focused
        refreshHint()
        syncColors()
        val d = logoView.drawable
        if (d is ChannelLogos.EditorialMonogram) {
            logoView.background = null
            logoView.setPadding(0, 0, 0, 0)
            d.setOnPorcelain(focused)
        } else if (focused) {
            logoView.background = focusedLogoBg
            logoView.setPadding(dp(3), dp(2), dp(3), dp(2))
        } else {
            logoView.background = null
            logoView.setPadding(0, 0, 0, 0)
        }
        if (!animate || TvStyle.lightweight(context)) {
            scaleX = 1f; scaleY = 1f
            if (Build.VERSION.SDK_INT >= 21) elevation = 0f
            return
        }
        animate().cancel()
        val s = if (focused) FOCUS_SCALE else 1f
        if (Build.VERSION.SDK_INT >= 21) elevation = 0f
        animate().scaleX(s).scaleY(s).setDuration(140L).setInterpolator(TvStyle.springOut).start()
    }

    private fun syncColors() {
        val focused = rowFocused
        playingBarBg.setColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
        nameView.setTextColor(when {
            focused -> TvStyle.inkOnPorcelain
            missing -> TvStyle.subtle
            else -> TvStyle.ink
        })
        nameView.typeface = Typeface.create("sans-serif-medium", if (focused) Typeface.BOLD else Typeface.NORMAL)
        subView.setTextColor(when {
            focused -> TvStyle.mutedOnPorcelain
            currentMark == ChannelPlaybackMark.FAILED -> TvStyle.danger
            else -> TvStyle.muted
        })
        numView.setTextColor(if (focused) TvStyle.mutedOnPorcelain else TvStyle.subtle)
        rightHint.setTextColor(when {
            currentMark == ChannelPlaybackMark.FAILED && !focused -> TvStyle.danger
            focused -> TvStyle.mutedOnPorcelain
            else -> TvStyle.subtle
        })
    }
}
