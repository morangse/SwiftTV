package app.swifttv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.graphics.withClip
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Scheme C — "Obsidian Crystal Float" Bottom Channel Gallery.
 * Leaves the upper ~60% of the live broadcast completely unobstructed while presenting
 * compact jewel-like frosted capsules and a 60fps horizontal RecyclerView of optical crystal cards.
 */
@UnstableApi
@SuppressLint("ViewConstructor")
class CinemaRibbonView(
    context: Context,
    private val artwork: ChannelLogos,
    private val blurEngine: CinemaBlurEngine?,
    private val actions: Actions,
    initialGroup: String = "全部",
    private val scheduleFor: (String) -> EpgProgram? = { null }
) : FrameLayout(context), ViewportSizedPanel {

    interface Actions {
        fun play(channel: Channel)
        fun more(channel: Channel?)
        fun settings()
        fun groupChanged(group: String)
    }

    private fun dp(n: Int) = TvStyle.dp(context, n)
    private fun sp(base: Float) = TvStyle.sp(context, base)
    private val selection = SidebarSelection(initialGroup)
    private var rows: List<Channel> = emptyList()
    private var playbackId: String? = null
    private var playbackMark = ChannelPlaybackMark.NONE
    private var lastFocus: View? = null
    private var cardWidthPx = dp(176)
    private var cardHeightPx = dp(112)
    private var maxTabScrollWidthPx = dp(680)

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tabs = linkedMapOf<String, Button>()

    // Animated sliding translucent crystal indicator behind active category tab
    private val activeTabIndicator = View(context).apply {
        background = TvStyle.activeCrystalPill(context)
        visibility = INVISIBLE
    }
    private val tabStrip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val tabTrack = FrameLayout(context).apply {
        addView(activeTabIndicator, LayoutParams(0, dp(32), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(tabStrip, LayoutParams(-2, -2))
    }
    private val tabScroll = object : HorizontalScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cappedSpec = MeasureSpec.makeMeasureSpec(
                minOf(MeasureSpec.getSize(widthMeasureSpec), maxTabScrollWidthPx),
                MeasureSpec.AT_MOST
            )
            super.onMeasure(cappedSpec, heightMeasureSpec)
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        clipToPadding = false
        addView(tabTrack)
    }
    private val categoryCapsule = FrostedContainer(context, blurEngine, cornerRadiusDp = 999f, veilAlpha = 64).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(4), dp(5), dp(4))
        elevation = dp(6).toFloat()
        addView(tabScroll, LinearLayout.LayoutParams(-2, -2))
    }

    private val positionReadout = TextView(context).apply {
        textSize = sp(12.5f)
        setTextColor(TvStyle.muted)
        includeFontPadding = false
        fontFeatureSettings = "tnum"
        setPadding(dp(12), dp(6), dp(10), dp(6))
    }
    private val capsuleDivider = View(context).apply {
        setBackgroundColor(Color.argb(30, 255, 255, 255))
    }
    private val morePill = actionPill("线路与收藏") { actions.more(selectedChannel()) }
    private val settingsPill = actionPill("设置") { actions.settings() }

    private val rightCapsule = FrostedContainer(context, blurEngine, cornerRadiusDp = 999f, veilAlpha = 64).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
        elevation = dp(6).toFloat()
        addView(positionReadout, LinearLayout.LayoutParams(-2, -2))
        addView(capsuleDivider, LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), dp(14)).apply { rightMargin = dp(4) })
        addView(morePill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(2) })
        addView(settingsPill, LinearLayout.LayoutParams(-2, -2))
    }

    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(categoryCapsule, LinearLayout.LayoutParams(-2, -2))
        addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(rightCapsule, LinearLayout.LayoutParams(-2, -2))
    }

    private val emptyStateCard = FrostedContainer(context, blurEngine, cornerRadiusDp = 20f, veilAlpha = 76).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(30), dp(22), dp(30), dp(22))
        visibility = GONE
        addView(TextView(context).apply {
            id = android.R.id.text1
            text = "还没有收藏频道 · 在任意频道按菜单键即可加入收藏"
            textSize = sp(14.5f)
            setTextColor(TvStyle.muted)
            gravity = Gravity.CENTER
        })
    }

    private val layoutMgr = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    val channelGallery = RecyclerView(context).apply {
        id = View.generateViewId()
        layoutManager = layoutMgr
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null
        setPadding(dp(4), dp(10), dp(4), dp(10))
        contentDescription = "底部频道画廊"
    }

    private val galleryFrame = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        addView(channelGallery, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        addView(emptyStateCard, LayoutParams(-2, -2, Gravity.CENTER))
    }

    private val bottomDock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        clipChildren = false
        clipToPadding = false
        addView(topBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        addView(galleryFrame, LinearLayout.LayoutParams(-1, -2))
    }

    private val adapter = CardAdapter()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        layoutDirection = LAYOUT_DIRECTION_LTR
        addView(bottomDock, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(36); rightMargin = dp(36); bottomMargin = dp(24)
        })
        channelGallery.adapter = adapter

        listOf(morePill, settingsPill).forEach { pill ->
            pill.setOnFocusChangeListener { v, focused ->
                if (focused) lastFocus = v
                pill.setTextColor(if (focused) TvStyle.inkOnPorcelain else TvStyle.ink)
                pill.typeface = Typeface.create("sans-serif-medium", if (focused) Typeface.BOLD else Typeface.NORMAL)
                animatePillScale(v, focused)
            }
            pill.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { focusSelectedCard(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            }
        }
        morePill.nextFocusRightId = settingsPill.id
        settingsPill.nextFocusLeftId = morePill.id
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrimPaint.shader = LinearGradient(
            0f, h * 0.46f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.argb(120, 5, 7, 11), Color.argb(226, 5, 7, 11)),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!TvStyle.lightweight(context)) {
            canvas.drawRect(0f, height * 0.46f, width.toFloat(), height.toFloat(), scrimPaint)
        }
    }

    private fun actionPill(text: String, onClick: () -> Unit) = Button(context).apply {
        id = View.generateViewId()
        this.text = text
        textSize = sp(13.5f)
        isAllCaps = false
        setTextColor(TvStyle.ink)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = 0; minimumWidth = 0
        minHeight = dp(32); minimumHeight = dp(32)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = TvStyle.capsuleActionSelector(context)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun animatePillScale(v: View, focused: Boolean) {
        if (TvStyle.lightweight(context)) return
        v.animate().cancel()
        val s = if (focused) 1.03f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(140L).setInterpolator(TvStyle.springOut).start()
    }

    fun updateCatalog(channels: List<Channel>, favorites: Set<String>) {
        val prevGroups = selection.groups
        val changed = selection.channels != channels || selection.favorites != favorites
        val focusedTabKey = tabs.entries.firstOrNull { it.value.hasFocus() }?.key
        val galleryHadFocus = channelGallery.hasFocus()
        selection.update(channels, favorites)
        if (tabs.isEmpty() || prevGroups != selection.groups) buildTabs()
        if (changed || rows != selection.shown) {
            showGroup(animateIndicator = false)
        } else {
            refreshVisibleCards()
        }
        updatePositionReadout()
        if (changed && (galleryHadFocus || focusedTabKey != null)) {
            post {
                if (focusedTabKey != null) (tabs[focusedTabKey] ?: tabs[selection.group])?.requestFocus()
                else if (rows.isEmpty()) focusActiveTab()
                else focusSelectedCard()
            }
        }
    }

    private fun buildTabs() {
        tabs.clear()
        tabStrip.removeAllViews()
        selection.groups.forEach { name ->
            val btn = Button(context).apply {
                id = View.generateViewId()
                text = name
                textSize = sp(13.5f)
                isAllCaps = false
                gravity = Gravity.CENTER
                includeFontPadding = false
                minWidth = 0; minimumWidth = 0
                minHeight = dp(32); minimumHeight = dp(32)
                setPadding(dp(16), dp(6), dp(16), dp(6))
                background = tabSelector(context)
                stateListAnimator = null
                setOnClickListener {
                    if (selection.group != name) {
                        selection.selectGroup(name)
                        actions.groupChanged(name)
                        showGroup(animateIndicator = true)
                    }
                    requestFocus()
                }
                setOnFocusChangeListener { v, focused ->
                    if (focused) {
                        lastFocus = v
                        tabScroll.smoothScrollTo((v.left - dp(48)).coerceAtLeast(0), 0)
                    }
                    syncTabTypography()
                }
                setOnKeyListener { _, key, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (rows.isEmpty()) morePill.requestFocus() else focusSelectedCard()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> true
                        else -> false
                    }
                }
            }
            tabs[name] = btn
            tabStrip.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(2) })
        }
        val list = tabs.values.toList()
        list.forEachIndexed { idx, tab ->
            tab.nextFocusLeftId = if (idx > 0) list[idx - 1].id else tab.id
            tab.nextFocusRightId = if (idx < list.lastIndex) list[idx + 1].id else morePill.id
        }
        if (list.isNotEmpty()) {
            morePill.nextFocusLeftId = list.last().id
        }
        post { syncTabIndicator(animate = false) }
    }

    private fun tabSelector(c: Context) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), TvStyle.porcelainSurface(c, 999f))
        addState(intArrayOf(android.R.attr.state_focused), TvStyle.porcelainSurface(c, 999f))
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = TvStyle.dpF(c, 999f) })
    }

    private fun syncTabTypography() {
        tabs.forEach { (name, tab) ->
            val selected = name == selection.group
            val focused = tab.hasFocus()
            tab.setTextColor(when {
                focused -> TvStyle.inkOnPorcelain
                selected -> TvStyle.ink
                else -> TvStyle.muted
            })
            tab.typeface = Typeface.create("sans-serif-medium", if (selected || focused) Typeface.BOLD else Typeface.NORMAL)
            tab.contentDescription = name + if (selected) "，当前分类" else ""
        }
    }

    private fun syncTabIndicator(animate: Boolean) {
        val target = tabs[selection.group] ?: return
        syncTabTypography()
        if (target.width <= 0) {
            target.post { syncTabIndicator(false) }
            return
        }
        activeTabIndicator.visibility = VISIBLE
        val lp = activeTabIndicator.layoutParams as LayoutParams
        if (lp.width != target.width || (target.height > 0 && lp.height != target.height)) {
            lp.width = target.width
            if (target.height > 0) lp.height = target.height
            activeTabIndicator.layoutParams = lp
        }
        if (!animate || TvStyle.lightweight(context)) {
            activeTabIndicator.animate().cancel()
            activeTabIndicator.translationX = target.left.toFloat()
        } else {
            activeTabIndicator.animate().translationX(target.left.toFloat())
                .setDuration(195L).setInterpolator(TvStyle.springOut).start()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showGroup(animateIndicator: Boolean) {
        rows = selection.shown
        adapter.notifyDataSetChanged()
        syncTabIndicator(animate = animateIndicator)

        val emptyLabel = emptyStateCard.findViewById<TextView>(android.R.id.text1)
        emptyLabel?.text = if (selection.group == "收藏") "还没有收藏频道 · 在任意频道按菜单键或右键即可收藏" else "当前分类暂无频道"
        emptyStateCard.visibility = if (rows.isEmpty()) VISIBLE else GONE
        channelGallery.visibility = if (rows.isEmpty()) INVISIBLE else VISIBLE
        updatePositionReadout()

        if (rows.isNotEmpty()) {
            val targetIdx = rows.indexOfFirst { it.id == selection.selectedId }.coerceAtLeast(0)
            layoutMgr.scrollToPositionWithOffset(targetIdx, dp(48))
        }
    }

    private fun updatePositionReadout() {
        if (rows.isEmpty()) {
            positionReadout.text = context.getString(R.string.channel_count, selection.channels.count { it.sources.isNotEmpty() })
            return
        }
        val idx = (rows.indexOfFirst { it.id == selection.selectedId }.takeIf { it >= 0 } ?: 0) + 1
        positionReadout.text = String.format(java.util.Locale.ROOT, "%02d / %02d", idx, rows.size)
    }

    fun open(currentId: String?, restore: Boolean) {
        if (!restore) {
            selection.open(currentId)
            actions.groupChanged(selection.group)
            showGroup(animateIndicator = false)
            lastFocus = null
        }
        post {
            if (restore && lastFocus?.isShown == true && lastFocus?.isFocusable == true) {
                lastFocus?.requestFocus()
            } else if (rows.isEmpty()) {
                focusActiveTab()
            } else {
                focusSelectedCard()
            }
        }
    }

    private fun focusActiveTab() {
        (tabs[selection.group] ?: tabs.values.firstOrNull() ?: morePill).requestFocus()
    }

    private fun focusSelectedCard() {
        if (rows.isEmpty()) { morePill.requestFocus(); return }
        val idx = rows.indexOfFirst { it.id == selection.selectedId }.coerceAtLeast(0)
        val existingHolder = channelGallery.findViewHolderForAdapterPosition(idx)
        if (existingHolder != null) {
            existingHolder.itemView.requestFocus()
        } else {
            layoutMgr.scrollToPositionWithOffset(idx, dp(48))
            channelGallery.post {
                channelGallery.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                    ?: channelGallery.getChildAt(0)?.requestFocus()
            }
        }
    }

    fun selectedChannel(): Channel? = selection.selected()
    fun selectedGroup(): String = selection.group
    fun hasCardFocus(): Boolean = channelGallery.hasFocus()

    fun updatePlayback(channelId: String?, mark: ChannelPlaybackMark) {
        if (playbackId == channelId && playbackMark == mark) return
        playbackId = channelId
        playbackMark = mark
        refreshVisibleCards()
    }

    fun refreshPrograms() = refreshVisibleCards()

    private fun markFor(id: String) = if (id == playbackId) playbackMark else ChannelPlaybackMark.NONE

    private fun refreshVisibleCards() {
        val now = System.currentTimeMillis()
        for (i in 0 until channelGallery.childCount) {
            val card = channelGallery.getChildAt(i) as? CinemaChannelCard ?: continue
            card.updateStatus(markFor(card.channelId), card.channelId in selection.favorites, scheduleFor(card.channelId), now)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun fitToViewport(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val density = resources.displayMetrics.density
        val fontScale = TvStyle.fontScale(context).coerceIn(1f, 2f)
        val vwDp = (widthPx / density).toInt()
        val vhDp = (heightPx / density).toInt()
        val edgeDp = (minOf(vwDp, vhDp) * 0.036f).roundToInt().coerceIn(10, 36)
        val dockLp = bottomDock.layoutParams as LayoutParams
        dockLp.leftMargin = dp(edgeDp)
        dockLp.rightMargin = dp(edgeDp)
        dockLp.bottomMargin = dp((vhDp * 0.032f).roundToInt().coerceIn(8, 24))
        bottomDock.layoutParams = dockLp

        maxTabScrollWidthPx = ((widthPx - dp(edgeDp * 2)) * 0.62f).toInt().coerceAtLeast(dp(180))

        val targetCardWDp = (174f + 56f * (fontScale - 1f)).roundToInt().coerceIn(128, maxOf(128, vwDp - edgeDp * 2 - 16))
        val targetCardHDp = (112f + 42f * (fontScale - 1f)).roundToInt().coerceIn(78, maxOf(78, (vhDp * 0.44f).roundToInt()))
        val newW = dp(targetCardWDp)
        val newH = dp(targetCardHDp)
        if (newW != cardWidthPx || newH != cardHeightPx) {
            cardWidthPx = newW
            cardHeightPx = newH
            galleryFrame.minimumHeight = cardHeightPx + dp(20)
            adapter.notifyDataSetChanged()
        }
    }

    fun appear() {
        animate().cancel()
        bottomDock.animate().cancel()
        if (TvStyle.lightweight(context)) {
            alpha = 1f; bottomDock.translationY = 0f; bottomDock.alpha = 1f; return
        }
        alpha = 0f
        bottomDock.translationY = dp(24).toFloat()
        bottomDock.alpha = 0f
        animate().alpha(1f).setDuration(TvStyle.ENTER_MS).setInterpolator(TvStyle.springOut).start()
        bottomDock.animate().translationY(0f).alpha(1f).setDuration(TvStyle.ENTER_MS).setInterpolator(TvStyle.springOut).start()
    }

    fun disappear(finished: () -> Unit) {
        animate().cancel()
        bottomDock.animate().cancel()
        if (TvStyle.lightweight(context)) { finished(); return }
        bottomDock.animate().translationY(dp(16).toFloat()).alpha(0f).setDuration(TvStyle.EXIT_MS).setInterpolator(TvStyle.easeIn).start()
        animate().alpha(0f).setDuration(TvStyle.EXIT_MS).setInterpolator(TvStyle.easeIn).withEndAction(finished).start()
    }

    fun cancelMotion() {
        animate().withEndAction(null).cancel()
        bottomDock.animate().withEndAction(null).cancel()
        alpha = 1f; bottomDock.translationY = 0f; bottomDock.alpha = 1f
    }

    private inner class CardHolder(val card: CinemaChannelCard) : RecyclerView.ViewHolder(card)

    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = rows.size
        override fun getItemId(position: Int) = rows[position].id.fold(1125899906842597L) { h, ch -> h * 31 + ch.code }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = CinemaChannelCard(context, blurEngine)
            card.isFocusable = true
            card.isFocusableInTouchMode = true
            return CardHolder(card)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val channel = rows[position]
            val lp = RecyclerView.LayoutParams(cardWidthPx, cardHeightPx).apply {
                rightMargin = dp(12)
            }
            holder.card.layoutParams = lp
            holder.card.bind(
                channel = channel,
                displayNumber = String.format(java.util.Locale.ROOT, "%02d", position + 1),
                mark = markFor(channel.id),
                favorite = channel.id in selection.favorites,
                program = scheduleFor(channel.id),
                artwork = artwork,
                nowMs = System.currentTimeMillis()
            )
            holder.card.setOnFocusChangeListener { v, focused ->
                holder.card.applyFocusVisual(focused)
                if (focused) {
                    lastFocus = v
                    selection.focus(channel.id)
                    updatePositionReadout()
                }
            }
            holder.card.setOnClickListener {
                selection.focus(channel.id)
                actions.play(channel)
            }
            holder.card.setOnLongClickListener {
                selection.focus(channel.id)
                actions.more(channel)
                true
            }
            holder.card.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> { focusActiveTab(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> true
                    KeyEvent.KEYCODE_DPAD_LEFT -> position == 0
                    KeyEvent.KEYCODE_DPAD_RIGHT -> position == rows.lastIndex
                    else -> false
                }
            }
        }
    }
}

/**
 * Sculptural Obsidian Optical Crystal Channel Card:
 * Renders live backdrop video blur with generous logo breathing room, transforming on D-pad focus
 * with a 135-degree liquid crystal sheen and crisp pure ice-white rim (matching the SwiftTV icon).
 */
@UnstableApi
@SuppressLint("ViewConstructor")
class CinemaChannelCard(
    context: Context,
    private val blurEngine: CinemaBlurEngine?
) : LinearLayout(context) {
    private fun dp(n: Int) = TvStyle.dp(context, n)
    private fun sp(base: Float) = TvStyle.sp(context, base)
    var channelId: String = ""
        private set
    private var missing = false
    private var unavailableReason = ""
    private var channelName = ""
    private var cardFocused = false

    private val clipPath = Path()
    private val boundsRect = RectF()
    private val strokeRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val loc = IntArray(2)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val idleFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val focusFacetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val idleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvStyle.dpF(context, 1f).coerceAtLeast(1f)
    }
    private val focusRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvStyle.dpF(context, 1.6f).coerceAtLeast(1.5f)
    }

    private val numView = TextView(context).apply {
        textSize = sp(11.5f)
        setTextColor(TvStyle.subtle)
        includeFontPadding = false
        fontFeatureSettings = "tnum"
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val liveDot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TvStyle.sage) }
        visibility = GONE
    }
    private val badgeView = TextView(context).apply {
        textSize = sp(10.5f)
        setTextColor(TvStyle.muted)
        includeFontPadding = false
        maxLines = 1
    }
    private val logoView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val logoStage = FrameLayout(context).apply {
        addView(logoView, FrameLayout.LayoutParams(dp(94), dp(34), Gravity.CENTER))
    }
    private val nameView = TextView(context).apply {
        textSize = sp(13.5f)
        setTextColor(TvStyle.ink)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subView = TextView(context).apply {
        textSize = sp(11f)
        setTextColor(TvStyle.muted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val progressTrack = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(36, 255, 255, 255))
            cornerRadius = dp(1).toFloat()
        }
        visibility = GONE
    }
    private val progressFill = View(context).apply {
        background = GradientDrawable().apply {
            setColor(TvStyle.ink)
            cornerRadius = dp(1).toFloat()
        }
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(dp(13), dp(10), dp(13), dp(10))
        elevation = dp(3).toFloat()
        blurEngine?.registerConsumer(this)

        val statusGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(liveDot, LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(5) })
            addView(badgeView, LayoutParams(-2, -2))
        }
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(numView, LayoutParams(0, -2, 1f))
            addView(statusGroup, LayoutParams(-2, -2))
        }
        addView(topRow, LayoutParams(-1, -2))
        addView(logoStage, LayoutParams(-1, 0, 1f).apply { topMargin = dp(2); bottomMargin = dp(2) })
        addView(nameView, LayoutParams(-1, -2))
        addView(subView, LayoutParams(-1, -2).apply { topMargin = dp(1) })
        progressTrack.addView(progressFill, FrameLayout.LayoutParams(0, -1))
        addView(progressTrack, LayoutParams(-1, dp(2).coerceAtLeast(2)).apply { topMargin = dp(4) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blurEngine?.registerConsumer(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = TvStyle.dpF(context, TvStyle.CARD_RADIUS.toFloat())
        boundsRect.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, r, r, Path.Direction.CW)

        idleFallbackPaint.shader = LinearGradient(
            0f, 0f, w * 0.75f, h.toFloat(),
            Color.argb(232, 20, 25, 34), Color.argb(242, 10, 13, 19), Shader.TileMode.CLAMP
        )
        focusFacetPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.argb(68, 242, 248, 255),
                Color.argb(18, 210, 224, 246),
                Color.argb(38, 232, 242, 255)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        idleRimPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.argb(46, 240, 246, 255), Color.argb(12, 255, 255, 255), Shader.TileMode.CLAMP
        )
        focusRimPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.argb(246, 255, 255, 255), Color.argb(115, 216, 228, 248), Shader.TileMode.CLAMP
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.withClip(clipPath) {
            val drewBlur = blurEngine?.drawBlurredSlice(this, this@CinemaChannelCard, loc, srcRect, dstRect, bitmapPaint) == true
            if (drewBlur) {
                veilPaint.color = if (cardFocused) Color.argb(46, 14, 18, 26) else Color.argb(92, 8, 10, 16)
                drawRect(boundsRect, veilPaint)
            } else {
                drawRect(boundsRect, idleFallbackPaint)
            }
            if (cardFocused) {
                drawRect(boundsRect, focusFacetPaint)
            }
            super.dispatchDraw(this)
            val rim = if (cardFocused) focusRimPaint else idleRimPaint
            val inset = rim.strokeWidth / 2f
            strokeRect.set(inset, inset, width - inset, height - inset)
            val r = minOf(TvStyle.dpF(context, TvStyle.CARD_RADIUS.toFloat()), strokeRect.width() / 2f, strokeRect.height() / 2f)
            drawRoundRect(strokeRect, r, r, rim)
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
        channelName = channel.name
        missing = channel.sources.isEmpty()
        unavailableReason = channel.unavailableReason
        numView.text = displayNumber
        nameView.text = channel.name
        artwork.bind(logoView, channel)
        updateStatus(mark, favorite, program, nowMs)
        applyFocusVisual(isFocused, animate = false)
    }

    fun updateStatus(mark: ChannelPlaybackMark, favorite: Boolean, program: EpgProgram?, nowMs: Long) {
        liveDot.visibility = if (mark == ChannelPlaybackMark.PLAYING) VISIBLE else GONE
        badgeView.text = when {
            mark == ChannelPlaybackMark.PLAYING -> if (favorite) "直播 · ★" else "直播"
            mark == ChannelPlaybackMark.BUFFERING -> "缓冲"
            mark == ChannelPlaybackMark.CONNECTING -> "连接"
            mark == ChannelPlaybackMark.FAILED -> "异常"
            favorite -> "★"
            else -> ""
        }
        badgeView.setTextColor(when (mark) {
            ChannelPlaybackMark.FAILED -> TvStyle.danger
            ChannelPlaybackMark.PLAYING -> TvStyle.ink
            else -> TvStyle.muted
        })

        val progTitle = program?.title?.takeIf { it.isNotBlank() }
        val detail = when {
            missing -> unavailableReason.ifEmpty { "暂无可用线路" }
            mark == ChannelPlaybackMark.FAILED -> "按菜单键切换或重试"
            progTitle != null -> progTitle
            mark == ChannelPlaybackMark.PLAYING -> "正在播出"
            else -> ""
        }
        subView.text = detail
        subView.visibility = if (detail.isEmpty()) GONE else VISIBLE

        if (program != null && !missing) {
            progressTrack.visibility = VISIBLE
            progressTrack.post {
                val lp = progressFill.layoutParams
                lp.width = (progressTrack.width * program.progress(nowMs)).toInt().coerceAtLeast(dp(3))
                progressFill.layoutParams = lp
            }
        } else {
            progressTrack.visibility = GONE
        }
        contentDescription = "$channelName，$detail" + if (favorite) "，已收藏" else ""
    }

    fun applyFocusVisual(focused: Boolean, animate: Boolean = true) {
        cardFocused = focused
        numView.setTextColor(if (focused) TvStyle.ink else TvStyle.subtle)
        invalidate()
        animate().cancel()
        val targetScale = if (focused) 1.055f else 1f
        val targetY = if (focused) -dp(6).toFloat() else 0f
        val targetZ = if (focused) dp(14).toFloat() else dp(3).toFloat()
        if (!animate || TvStyle.lightweight(context)) {
            scaleX = targetScale; scaleY = targetScale; translationY = targetY; elevation = targetZ
        } else {
            elevation = targetZ
            animate().scaleX(targetScale).scaleY(targetScale).translationY(targetY)
                .setDuration(TvStyle.FOCUS_MS).setInterpolator(TvStyle.springOut).start()
        }
    }
}
