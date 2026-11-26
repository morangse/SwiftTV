package app.swifttv.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : ComponentActivity(), PlaybackController.Callback {
    private val ink = TvStyle.ink
    private val muted = TvStyle.muted
    private val bg = TvStyle.canvas
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var store: CatalogStore
    private lateinit var network: Network
    private lateinit var artwork: ChannelLogos
    private lateinit var epg: EpgRepository
    private lateinit var blurEngine: CinemaBlurEngine
    private lateinit var codecProfile: DeviceCodecProfile
    private lateinit var root: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var landing: FrameLayout
    private lateinit var overlay: TvOverlayLayout
    private lateinit var modalHost: FrameLayout
    private lateinit var hud: LiveHudView
    private lateinit var numberOsd: NumberPadOsdView
    private lateinit var toastBanner: CinemaToastBanner

    private var controller: PlaybackController? = null
    private var server: ImportServer? = null
    private var overlayMode = ""
    private var group = "全部"
    private var ribbon: CinemaRibbonView? = null
    private var priorModalFocus: View? = null
    private var playbackChannelId: String? = null
    private var playbackMark = ChannelPlaybackMark.NONE
    private var lastStatusMessage = ""

    private data class MenuPage(val mode: String, val channelId: String?)
    private val menuHistory = mutableListOf<MenuPage>()
    private var restoringMenu = false
    private var optionsChannelId: String? = null
    private var lastSettingsTab = 0
    private var overlayGeneration = 0
    private var closingOverlay = false
    private var consumeOpeningConfirm = false
    private var channelCursor = -1
    private var lastZapAtMs = 0L
    private var refreshing = false
    private var isolatedTestMode = false
    private var startupRejected = false
    private var backgrounded = false
    private var userDeparted = false
    private var pendingPushB64: String? = null
    private var startupSourceDialog: View? = null

    private val numberPad = ChannelNumberPad()
    private val commitNumberRunnable = Runnable { commitNumberSelection() }
    private val hideHud = Runnable { hud.hideAnimated() }

    @Volatile private var session = 0
    @Volatile private var preview: ImportPreview? = null
    private var sessionExpiry: Runnable? = null
    private var probeLabel: TextView? = null

    data class ImportPreview(val id: String, val subscription: Subscription, val parsed: ParsedPlaylist)

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            io.execute {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytesBounded(M3uParser.MAX_BYTES) } ?: error("无法读取文件")
                    val text = TextDecoding.decodePlaylistBytes(bytes)
                    val value = createPreview(JSONObject().put("content", text).put("name", "本地频道表"))
                    postUi { confirmPreview(value) }
                } catch (e: Exception) { postUi { toast(e.message ?: "文件读取失败") } }
            }
        }
    }

    private fun capturePushExtra(source: Intent?) {
        val b64 = source?.getStringExtra(PushPayload.EXTRA_PUSH_B64)?.trim()
        if (!b64.isNullOrEmpty()) {
            pendingPushB64 = b64
            source.removeExtra(PushPayload.EXTRA_PUSH_B64)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startupRejected = !StartupRuntime.acceptsEntry(this, intent)
        if (startupRejected) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = CatalogStore(this)
        codecProfile = HardwareCodecProbe.profile(this)
        isolatedTestMode = BuildConfig.DEBUG && intent.getBooleanExtra("isolatedTestMode", false)
        capturePushExtra(intent)
        network = Network(cacheDir)
        artwork = ChannelLogos(this, network)
        epg = EpgRepository(this, network)
        blurEngine = CinemaBlurEngine(this)

        root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = interceptKeyEvent(event) || super.dispatchKeyEvent(event)
        }.apply { setBackgroundColor(bg); isFocusable = true; isFocusableInTouchMode = true }

        video = if (TvStyle.lightweight(this)) {
            PlayerView(this).apply {
                useController = false
                setKeepContentOnPlayerReset(false)
                isFocusable = false
            }
        } else {
            (LayoutInflater.from(this).inflate(R.layout.player_texture_view, root, false) as PlayerView).apply {
                setKeepContentOnPlayerReset(false)
            }
        }
        blurEngine.attach(video)
        root.addView(video, FrameLayout.LayoutParams(-1, -1))

        // Pointer click target above video and below every menu/modal.
        root.addView(View(this).apply {
            contentDescription = "点击播放画面打开频道画廊"
            isFocusable = false
            setOnClickListener { if (!this@MainActivity.overlay.isVisible && !modalHost.isNotEmpty()) showChannels() }
        }, FrameLayout.LayoutParams(-1, -1))

        landing = buildLandingView()
        root.addView(landing, FrameLayout.LayoutParams(-1, -1))

        hud = LiveHudView(this, blurEngine)
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(38); bottomMargin = dp(30); rightMargin = dp(38)
        })

        numberOsd = NumberPadOsdView(this, blurEngine)
        root.addView(numberOsd, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(30); rightMargin = dp(38)
        })

        overlay = TvOverlayLayout(this).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        modalHost = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(modalHost, FrameLayout.LayoutParams(-1, -1))

        toastBanner = CinemaToastBanner(this, blurEngine)
        root.addView(toastBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(24); leftMargin = dp(32); rightMargin = dp(32)
        })

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })
    }

    @SuppressLint("SetTextI18n")
    private fun buildLandingView(): FrameLayout = FrameLayout(this).apply {
        setBackgroundColor(bg)
        val card = FrostedContainer(this@MainActivity, null, cornerRadiusDp = 26f, veilAlpha = 88).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(42), dp(48), dp(40))
            addView(text("SwiftTV", 13, TvStyle.subtle, true))
            addView(text("静享此刻直播。", 32, ink, true).apply { setPadding(0, dp(10), 0, dp(10)) })
            addView(text("纯本地播放器 · 请扫码、通过手机安装助手或输入地址导入频道源。", 15, muted).apply {
                gravity = Gravity.CENTER
            })
            val btn = Button(this@MainActivity).apply {
                id = View.generateViewId()
                text = "手机扫码导入"
                textSize = TvStyle.sp(this@MainActivity, 15f)
                isAllCaps = false
                setTextColor(TvStyle.inkOnPorcelain)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = TvStyle.porcelainSurface(this@MainActivity, 999f)
                minHeight = dp(46); minimumHeight = dp(46)
                setPadding(dp(28), dp(12), dp(28), dp(12))
                stateListAnimator = null
                setOnClickListener { showPairing(onboarding = true) }
            }
            addView(btn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        }
        addView(card, FrameLayout.LayoutParams(dp(480), -2, Gravity.CENTER))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (StartupRuntime.acceptsEntry(this, intent)) {
            setIntent(intent)
            capturePushExtra(intent)
            if (controller != null && pendingPushB64 != null) consumePendingPush()
        } else if (backgrounded || userDeparted) {
            startupRejected = true
            finish()
        }
    }

    override fun onUserLeaveHint() {
        userDeparted = true
        StartupRuntime.userLeft(this)
        super.onUserLeaveHint()
    }

    override fun onStart() {
        super.onStart()
        if (startupRejected || !StartupRuntime.acceptsEntry(this, intent)) { finish(); return }
        backgrounded = false
        if (controller == null) {
            controller = PlaybackController(this, store, network, video, this)
            val channels = availableChannels()
            val start = CatalogSelection.chooseStart(channels, store.snapshot().lastChannel, store.snapshot().stats)
            landing.visibility = if (start == null) View.VISIBLE else View.GONE
            if (overlayMode == "pair" || overlayMode == "export") {
                showPairing(export = overlayMode == "export", onboarding = start == null && overlayMode == "pair")
            } else if (start == null) {
                showPairing(onboarding = true)
            } else {
                controller?.tune(start, diagnoseStartup = !isolatedTestMode)
                if (overlayMode == "channels") {
                    overlayMode = ""; restoringMenu = true
                    try { showChannels(restore = true) } finally { restoringMenu = false }
                }
            }
            if (!isolatedTestMode) {
                refreshSubscriptions(false)
                refreshEpg(false)
            }
        }
        if (pendingPushB64 != null) consumePendingPush()
        blurEngine.syncState()
    }

    override fun onResume() {
        super.onResume()
        if (startupRejected || !StartupRuntime.acceptsEntry(this, intent)) { finish(); return }
        backgrounded = false; userDeparted = false
        StartupRuntime.visible(this, intent)
        if (overlayMode == "startup") {
            overlayMode = ""; restoringMenu = true
            try { showStartupSettings() } finally { restoringMenu = false }
        }
    }

    override fun onPause() {
        StartupRuntime.hidden()
        cancelNumberInput()
        super.onPause()
    }

    override fun onStop() {
        backgrounded = true
        if (startupSourceDialog != null && modalHost.getChildAt(0) === startupSourceDialog) dismissModal()
        startupSourceDialog = null
        blurEngine.stop()
        controller?.stopProbe(false)
        closeSession()
        controller?.close(); controller = null
        super.onStop()
    }

    override fun onDestroy() {
        ribbon?.cancelMotion()
        blurEngine.stop()
        controller?.close(); controller = null
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        if (::epg.isInitialized) epg.close()
        if (::network.isInitialized) network.close()
        if (::artwork.isInitialized) artwork.close()
        super.onDestroy()
    }

    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = TvStyle.sp(this@MainActivity, size.toFloat()); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = true
    }

    private fun dp(value: Int) = TvStyle.dp(this, value)
    private fun postUi(block: () -> Unit) { handler.post { if (!isDestroyed) block() } }

    private fun showModal(
        title: String,
        message: String = "",
        inputHint: String? = null,
        initialInput: String = "",
        primaryLabel: String = "确认",
        secondaryLabel: String? = "取消",
        extraLabel: String? = null,
        onExtra: (() -> Unit)? = null,
        onPrimary: (String) -> Boolean
    ) {
        priorModalFocus = currentFocus
        modalHost.removeAllViews()
        modalHost.visibility = View.VISIBLE
        val dialog = CinemaModalDialog(
            context = this,
            blurEngine = blurEngine,
            title = title,
            message = message,
            inputHint = inputHint,
            initialInput = initialInput,
            primaryLabel = primaryLabel,
            secondaryLabel = secondaryLabel,
            extraLabel = extraLabel,
            onPrimary = { text ->
                if (onPrimary(text)) dismissModal()
                true
            },
            onSecondary = { dismissModal() },
            onExtra = if (onExtra != null) { { dismissModal(); onExtra() } } else null
        )
        modalHost.addView(dialog, FrameLayout.LayoutParams(-1, -1))
        blurEngine.syncState()
    }

    private fun dismissModal() {
        startupSourceDialog = null
        modalHost.removeAllViews()
        modalHost.visibility = View.GONE
        val restore = priorModalFocus
        priorModalFocus = null
        if (restore != null && restore.isShown && restore.isFocusable) {
            restore.requestFocus()
        } else if (!overlay.isVisible) {
            root.requestFocus()
        }
        blurEngine.syncState()
    }

    private fun sheet(eyebrow: String, title: String, subtitle: String = "", mode: String, anchoredRight: Boolean = false): CinemaSheetView {
        beginOverlay(mode)
        if (mode != "probe") {
            overlay.isClickable = true
            overlay.setOnClickListener { backThroughMenus() }
        }
        return CinemaSheetView(this, blurEngine, eyebrow, title, subtitle, anchoredRight = anchoredRight, onBackClick = { backThroughMenus() }).also { panel ->
            overlay.addView(panel)
            panel.appear()
            blurEngine.syncState()
        }
    }

    private fun beginOverlay(mode: String) {
        cancelNumberInput()
        if (!restoringMenu && overlay.isVisible && overlayMode.isNotBlank() && overlayMode != mode) {
            menuHistory.add(MenuPage(overlayMode, optionsChannelId))
        }
        overlayGeneration++; closingOverlay = false
        ribbon?.cancelMotion(); closeSession()
        overlay.setOnClickListener(null)
        overlay.isClickable = false
        overlay.removeAllViews(); overlay.visibility = View.VISIBLE; overlayMode = mode
        hud.visibility = View.GONE
        blurEngine.syncState()
    }

    private fun closeOverlay(animate: Boolean = true) {
        if (closingOverlay) return
        val failedBeforeClose = playbackMark == ChannelPlaybackMark.FAILED
        val generation = ++overlayGeneration
        val finished = {
            if (generation == overlayGeneration) {
                overlayGeneration++
                closeSession(); overlay.setOnClickListener(null); overlay.isClickable = false
                overlay.visibility = View.GONE; overlay.removeAllViews(); overlayMode = ""
                menuHistory.clear(); closingOverlay = false; probeLabel = null
                landing.visibility = if (availableChannels().isEmpty()) View.VISIBLE else View.GONE
                if (landing.isVisible) landing.findViewById<View>(View.NO_ID) ?: (landing.getChildAt(0) as? ViewGroup)?.let { g ->
                    for (i in 0 until g.childCount) if (g.getChildAt(i).isFocusable) g.getChildAt(i).requestFocus()
                } else root.requestFocus()
                if (controller?.exhausted == true) {
                    hud.showAnimated()
                    if (!failedBeforeClose) showChannels()
                }
                blurEngine.syncState()
            }
        }
        if (animate && overlayMode == "channels" && ribbon?.parent == overlay) {
            closingOverlay = true; ribbon?.disappear(finished)
            handler.postDelayed({ finished() }, TvStyle.EXIT_MS + 100)
        } else { ribbon?.cancelMotion(); finished() }
    }

    private fun buildSettingsModel(): CinemaRibbonView.SettingsModel {
        val snap = store.snapshot()
        return CinemaRibbonView.SettingsModel(
            versionName = BuildConfig.VERSION_NAME,
            subscriptionCount = snap.subscriptions.size,
            totalChannels = availableChannels().size,
            currentGroup = group,
            routingLabel = routingLabel(),
            switchDirectionLabel = switchDirectionLabel(),
            startupLabel = StartupRuntime.settingLabel(this),
            fontScaleMode = TvStyle.fontScaleMode(this),
            lightweight = TvStyle.lightweight(this),
            smartLogoLookup = TvStyle.smartLogoLookup(this),
            customEpgUrl = snap.customEpgUrl,
            supports4kHevc50 = codecProfile.supports4kHevc50,
            logoCacheBytes = artwork.cacheSizeBytes()
        )
    }

    private fun obtainRibbon(): CinemaRibbonView = ribbon ?: CinemaRibbonView(
        context = this,
        artwork = artwork,
        blurEngine = blurEngine,
        actions = object : CinemaRibbonView.Actions {
            override fun play(channel: Channel) {
                if (channel.sources.isEmpty()) {
                    val reason = channel.unavailableReason.ifEmpty { "暂缺适合本机的已验证线路" }
                    toast("${channel.name} · $reason")
                    return
                }
                closeOverlay(animate = false)
                syncChannelCursor(channel.id)
                controller?.tune(channel)
            }
            override fun more(channel: Channel?) { showOptions(channel ?: controller?.current) }
            override fun settings() { showSettings() }
            override fun groupChanged(group: String) {
                this@MainActivity.group = group
                channelCursor = -1
                lastZapAtMs = 0L
            }
        },
        initialGroup = group,
        scheduleFor = { id -> epg.scheduleFor(id)?.current },
        settingsModelProvider = { buildSettingsModel() },
        settingsActions = object : CinemaRibbonView.SettingsActions {
            override fun onOpenQrPairing() { showPairing() }
            override fun onOpenManualUrl() { manualImport() }
            override fun onOpenLocalFile() {
                StartupRuntime.beginInternalSystemFlow()
                try { openDocumentLauncher.launch(arrayOf("*/*")) }
                catch (_: Exception) { toast("这台电视未提供文件选择器，请使用手机扫码或安装助手导入") }
            }
            override fun onSelectFontScale(mode: FontScaleMode) {
                if (TvStyle.fontScaleMode(this@MainActivity) == mode) return
                val targetKey = "font:${mode.key}"
                TvStyle.setFontScaleMode(this@MainActivity, mode)
                rebuildChromeForFontScale()
                restoringMenu = true
                try { showSettings(focusRight = true, skipAppear = true, focusKey = targetKey) } finally { restoringMenu = false }
                toast("界面字号已切换为「${mode.title}」")
            }
            override fun onToggleLightweight(): Boolean {
                val nextLight = !TvStyle.lightweight(this@MainActivity)
                TvStyle.setLightweight(this@MainActivity, nextLight)
                hud.refreshSurface()
                numberOsd.refreshSurface()
                blurEngine.syncState()
                return nextLight
            }
            override fun onToggleSmartLogoLookup(): Boolean {
                val next = !TvStyle.smartLogoLookup(this@MainActivity)
                TvStyle.setSmartLogoLookup(this@MainActivity, next)
                ribbon?.refreshPrograms()
                toast(if (next) "已开启智能台标识别" else "已关闭在线台标兜底")
                return next
            }
            override fun onCycleRouting(): String {
                RoutingMode.save(this@MainActivity, RoutingMode.read(this@MainActivity).next())
                availableChannels().firstOrNull { it.id == controller?.current?.id && it.sources.isNotEmpty() }?.let { controller?.tune(it) }
                return routingLabel()
            }
            override fun onCycleSwitchDirection(): String {
                ChannelSwitchDirection.save(this@MainActivity, ChannelSwitchDirection.read(this@MainActivity).next())
                return switchDirectionLabel()
            }
            override fun onOpenStartupSettings() { showStartupSettings() }
            override fun onShowHardwareInfo() {
                val note = if (codecProfile.supports4kHevc50) "检测到 4K HEVC 硬件解码能力。实际播放由线路与设备决定。"
                else "优先选择 1080p H.264/AAC 线路。"
                showModal("本机解码能力", note, primaryLabel = "关闭", secondaryLabel = null) { true }
            }
            override fun onRefreshAll() {
                refreshSubscriptions(true)
                refreshEpg(true)
            }
            override fun onOpenSubscriptions() { showSubscriptions() }
            override fun onConfigureEpg() { configureEpgDialog() }
            override fun onProbeCurrentGroup() {
                val selected = availableChannels().filter {
                    it.sources.isNotEmpty() && (group == "全部" || (group == "收藏" && it.id in store.snapshot().favorites) || it.group == group)
                }
                if (selected.isNotEmpty()) startProbe(selected) else toast("当前分类没有可测线路")
            }
            override fun onExportDiagnostics() { showPairing(export = true) }
            override fun onClearLogoCache(): Long {
                val freed = artwork.clearCache()
                ribbon?.updateCatalog(availableChannels(), store.snapshot().favorites)
                toast("已清理台标缓存")
                return freed
            }
            override fun onShowAboutInfo() {
                val guide = "遥控器快捷指南：\n" +
                    "· 确认键 / 左键：打开左侧频道与设置抽屉\n" +
                    "· 频道行按右键：就地收藏、重连或切换线路\n" +
                    "· 频道列表中按 0：快速定位到当前在播频道\n" +
                    "· 菜单键：一键直达设置或关闭抽屉\n" +
                    "· 翻页键 / CH±：快速翻页浏览长频道列表"
                showModal(
                    title = "关于 SwiftTV ${BuildConfig.VERSION_NAME}",
                    message = guide,
                    primaryLabel = "关闭",
                    secondaryLabel = null,
                    extraLabel = "开源许可",
                    onExtra = { showLicenseModal() }
                ) { true }
            }
        },
        inspectorActions = object : CinemaRibbonView.InspectorActions {
            override fun buildModel(channel: Channel): CinemaRibbonView.InspectorModel {
                optionsChannelId = channel.id
                val spec = if (controller?.current?.id == channel.id) controller?.streamSpec?.badge().orEmpty() else ""
                val schedule = epg.scheduleFor(channel.id)?.current?.title?.let { "正在播出：$it" }
                val specSummary = listOfNotNull(spec.takeIf { it.isNotEmpty() }, schedule).joinToString(" · ")
                val prevId = controller?.previousChannelId
                val prevChannel = if (prevId != null && prevId != channel.id) {
                    availableChannels().firstOrNull { it.id == prevId && it.sources.isNotEmpty() }
                } else null
                val stats = store.snapshot().stats
                val routes = channel.sources.mapIndexed { index, source ->
                    val stat = stats[sourceId(source.url)]
                    val (health, dotColor) = when {
                        stat == null -> "尚未在本机实测" to TvStyle.subtle
                        stat.lastOutcome == "connecting" -> "正在连接…" to TvStyle.porcelain
                        stat.error.isNotBlank() -> ("上次异常 · " + stat.error.take(42)) to TvStyle.danger
                        stat.playedMs >= 60000 -> "稳定播放 · 首帧 ${stat.firstFrameMs}ms" to TvStyle.sage
                        stat.successes > 0 -> "已出画 · 首帧 ${stat.firstFrameMs}ms" to TvStyle.sage
                        else -> "待检测" to TvStyle.subtle
                    }
                    val host = runCatching { java.net.URI(source.url).host.orEmpty() }.getOrDefault("").take(18)
                    CinemaRibbonView.RouteOption(
                        source = source,
                        title = "线路 ${index + 1} · ${SourceRoutes.label(source)}",
                        subtitle = health,
                        host = host,
                        dotColor = dotColor
                    )
                }
                return CinemaRibbonView.InspectorModel(
                    channel = channel,
                    specSummary = specSummary,
                    isFavorite = channel.id in store.snapshot().favorites,
                    previousChannel = prevChannel,
                    routes = routes
                )
            }

            override fun onToggleFavorite(channel: Channel): Boolean {
                val added = store.favorite(channel.id)
                ribbon?.updateCatalog(availableChannels(), store.snapshot().favorites)
                toast(if (added) "已收藏「${channel.name}」" else "已取消收藏「${channel.name}」")
                return added
            }

            override fun onReconnect(channel: Channel) {
                closeOverlay(animate = false)
                controller?.tune(channel, explicitRetry = true)
            }

            override fun onTunePrevious(previous: Channel) {
                closeOverlay(animate = false)
                syncChannelCursor(previous.id)
                controller?.tune(previous)
            }

            override fun onSelectRoute(channel: Channel, source: Entry) {
                closeOverlay(animate = false)
                store.clearCooldown(source.url)
                controller?.tune(channel, preferred = source)
            }

            override fun onProbeChannel(channel: Channel) {
                if (channel.sources.isEmpty()) {
                    toast("${channel.name} · ${channel.unavailableReason.ifEmpty { "暂无可用线路" }}")
                    return
                }
                startProbe(listOf(channel))
            }
        },
        onDismissRequest = { closeOverlay() }
    ).also { ribbon = it }

    private fun showLicenseModal() {
        val license = runCatching {
            assets.open("licenses/Apache-2.0.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("Apache License, Version 2.0")
        showModal(
            title = "Apache License 2.0",
            message = license,
            primaryLabel = "关闭",
            secondaryLabel = null
        ) { true }
    }

    private fun showChannels(restore: Boolean = false) {
        val channels = availableChannels()
        if (channels.isEmpty()) { showSettings(focusRight = true); return }
        if (overlayMode == "channels" && overlay.isVisible && !closingOverlay) {
            ribbon?.updateCatalog(channels, store.snapshot().favorites); return
        }
        beginOverlay("channels")
        val view = obtainRibbon()
        (view.parent as? ViewGroup)?.removeView(view)
        overlay.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.updateCatalog(channels, store.snapshot().favorites)
        view.updatePlayback(playbackChannelId, playbackMark)
        view.open(controller?.current?.id, restore, openSettings = false)
        view.appear()
        blurEngine.syncState()
    }

    private fun backThroughMenus() {
        val previous = if (menuHistory.isEmpty()) null else menuHistory.removeAt(menuHistory.lastIndex)
        if (previous == null) { closeOverlay(); return }
        restoringMenu = true
        try {
            when (previous.mode) {
                "channels" -> showChannels(restore = true)
                "options" -> showOptions(availableChannels().firstOrNull { it.id == previous.channelId } ?: controller?.current)
                "settings" -> showSettings(tabIndex = lastSettingsTab)
                "startup" -> showStartupSettings()
                "subscriptions" -> showSubscriptions()
                else -> closeOverlay()
            }
        } finally { restoringMenu = false }
    }

    private fun showOptions(channel: Channel? = availableChannels().firstOrNull { it.id == controller?.current?.id } ?: controller?.current) {
        if (channel == null) { showSettings(); return }
        optionsChannelId = channel.id
        val channels = availableChannels()
        if (overlayMode == "channels" && overlay.isVisible && !closingOverlay && ribbon?.parent == overlay) {
            ribbon?.updateCatalog(channels, store.snapshot().favorites)
            if (ribbon?.openChannelInspector(channel) == true) return
        }
        beginOverlay("channels")
        val view = obtainRibbon()
        (view.parent as? ViewGroup)?.removeView(view)
        overlay.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.updateCatalog(channels, store.snapshot().favorites)
        view.updatePlayback(playbackChannelId, playbackMark)
        view.openForInspector(channel)
        view.appear()
        blurEngine.syncState()
    }

    private fun routingLabel(): String = RoutingMode.read(this).title

    private fun switchDirectionLabel(): String = ChannelSwitchDirection.read(this).title

    private fun fontScaleLabel(): String = TvStyle.fontScaleMode(this).title

    private fun showSettings(
        tabIndex: Int = 0,
        focusRight: Boolean = false,
        skipAppear: Boolean = false,
        focusKey: String? = null
    ) {
        lastSettingsTab = tabIndex
        val channels = availableChannels()
        if (overlayMode == "channels" && overlay.isVisible && !closingOverlay && ribbon?.parent == overlay) {
            ribbon?.updateCatalog(channels, store.snapshot().favorites)
            ribbon?.open(
                controller?.current?.id,
                restore = false,
                openSettings = true,
                focusRightSettings = focusRight,
                focusSettingsKey = focusKey
            )
            return
        }
        beginOverlay("channels")
        val view = obtainRibbon()
        (view.parent as? ViewGroup)?.removeView(view)
        overlay.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.updateCatalog(channels, store.snapshot().favorites)
        view.updatePlayback(playbackChannelId, playbackMark)
        view.open(
            controller?.current?.id,
            restore = false,
            openSettings = true,
            focusRightSettings = focusRight,
            focusSettingsKey = focusKey
        )
        if (skipAppear) view.cancelMotion() else view.appear()
        blurEngine.syncState()
    }

    private fun showStartupSettings() {
        val panel = sheet("", "开机自动看直播", "开机、待机唤醒及系统回收后恢复上次观看", "startup")
        val summaryView = panel.addInfoBlock(StartupRuntime.summary(this))

        panel.addSwitchRow("开启开机与唤醒自动播放", "按主页键或主动退出后本轮不强行拉回", StartupRuntime.enabled(this)) {
            val next = !StartupRuntime.enabled(this)
            StartupRuntime.setEnabled(this, next)
            summaryView.text = StartupRuntime.summary(this)
            next
        }

        fun openSettings(kind: String) {
            for (target in StartupRuntime.settingsIntents(this, kind)) {
                try {
                    StartupRuntime.beginInternalSystemFlow()
                    startActivity(target)
                    return
                } catch (_: android.content.ActivityNotFoundException) {}
                catch (_: SecurityException) {}
            }
            toast("当前电视系统未开放该设置页，可通过 ADB 授权适配")
        }

        panel.addActionRow(
            title = "授权开机助手（推荐）",
            subtitle = "仅识别前台包名与亮灭屏，不读取内容不过滤遥控器",
            accessory = if (StartupRuntime.guardEnabled(this)) "已授权" else "去开启 ›",
            glyph = TvGlyph.POWER
        ) { _, _ ->
            showModal(
                title = "启用 SwiftTV 开机助手",
                message = "用于开机、待机唤醒及系统回收后的直播恢复。\n\n仅识别前台应用包名，不读取文字节点，不拦截按键。请在下一页系统无障碍设置中找到「SwiftTV 开机助手」并开启。",
                primaryLabel = "前往系统设置"
            ) { openSettings("guard"); true }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            panel.addActionRow(
                title = "允许后台弹出界面",
                subtitle = "Android 10 及以上电视后台拉起直播所需权限",
                accessory = if (StartupRuntime.overlayAllowed(this)) "已允许" else "去允许 ›",
                glyph = TvGlyph.SETTINGS
            ) { _, _ ->
                openSettings("overlay")
            }
        }
        panel.addActionRow(
            title = "系统回收恢复辅助（可选）",
            subtitle = "借助通知服务重绑机制恢复直播，不读取任何通知",
            accessory = if (StartupRuntime.listenerEnabled(this)) "已开启" else "去设置 ›",
            glyph = TvGlyph.REFRESH
        ) { _, _ ->
            openSettings("listener")
        }
        panel.addActionRow(
            title = "电视应用自启动与后台权限",
            subtitle = "打开本应用系统详情页配置厂商后台策略",
            accessory = "打开 ›",
            glyph = TvGlyph.CHIP
        ) { _, _ -> openSettings("app") }
        panel.focusFirstRow()
    }

    private fun showSubscriptions() {
        val panel = sheet("", "订阅管理", "删除单个订阅不会影响其他订阅或收藏记录", "subscriptions")
        val subs = store.snapshot().subscriptions
        if (subs.isEmpty()) {
            panel.addInfoBlock("当前没有已保存的订阅。")
        } else {
            subs.forEach { sub ->
                val rawOrigin = if (sub.url.isEmpty()) "本机内置 / 本地文件" else (runCatching { streamUri(sub.url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: sub.url)
                val baseOrigin = if (rawOrigin == sub.name) "网络订阅源" else rawOrigin
                val epgTag = if (sub.epgUrl.isNotEmpty()) " · 含节目单" else ""
                panel.addActionRow(
                    title = sub.name,
                    subtitle = baseOrigin + epgTag,
                    accessory = "删除",
                    glyph = TvGlyph.SOURCES,
                    accessoryColor = TvStyle.danger
                ) { _, _ ->
                    showModal(
                        title = "移除订阅「${sub.name}」？",
                        message = "将移除该订阅提供的频道线路，其他订阅与收藏列表保持不变。",
                        primaryLabel = "确认移除"
                    ) {
                        deleteSubscriptionById(sub.id)
                        if (availableChannels().isEmpty()) {
                            menuHistory.removeAll { it.mode == "subscriptions" }
                            showSettings()
                        } else {
                            restoringMenu = true
                            try { showSubscriptions() } finally { restoringMenu = false }
                        }
                        true
                    }
                }
            }
        }
        panel.focusFirstRow()
    }

    private fun deleteSubscriptionById(id: String) {
        store.removeSubscription(id)
        val channels = availableChannels()
        ribbon?.updateCatalog(channels, store.snapshot().favorites)
        val currentId = controller?.current?.id
        val remaining = channels.firstOrNull { it.id == currentId && it.sources.isNotEmpty() }
            ?: CatalogSelection.chooseStart(channels, "", store.snapshot().stats)
        controller?.stop()
        remaining?.let { controller?.tune(it) }
        refreshEpg(false)
    }

    private fun configureEpgDialog() {
        showModal(
            title = "设置节目单（XMLTV）地址",
            message = "支持标准 XMLTV（.xml 或 .xml.gz），留空保存即可清除。未匹配到真实节目时保持静默。",
            inputHint = "https://example.com/epg.xml",
            initialInput = store.snapshot().customEpgUrl,
            primaryLabel = "保存设置"
        ) { raw ->
            try {
                val v = raw.trim()
                store.setCustomEpgUrl(v)
                refreshEpg(true)
                toast(if (v.isEmpty()) "已清除自定义节目单地址" else "节目单地址已保存，后台加载中…")
                true
            } catch (e: Exception) {
                toast(e.message ?: "请输入合法的 HTTP/HTTPS 地址")
                false
            }
        }
    }

    private fun manualImport() {
        showModal(
            title = "添加订阅或单频道直播流",
            message = "支持 M3U / TXT / JSON 订阅链接，或直接输入单频道 HLS (.m3u8) / TS / FLV / UDP / RTP / RTSP / RTMP 地址。",
            inputHint = "https://example.com/channels.m3u",
            primaryLabel = "读取预览"
        ) { raw ->
            val url = raw.trim()
            if (url.isEmpty()) {
                toast("请填写订阅或直播流地址")
                false
            } else {
                toast("正在读取频道信息…")
                io.execute {
                    try {
                        val result = createPreview(JSONObject().put("url", url))
                        postUi { confirmPreview(result, tryOnSave = true) }
                    } catch (e: Exception) { postUi { toast(Network.describeImportError(e).message) } }
                }
                true
            }
        }
    }

    private fun consumePendingPush() {
        val raw = pendingPushB64 ?: return
        pendingPushB64 = null
        val cfg = PushPayload.decode(raw)
        if (cfg == null) {
            toast("手机推送的地址格式无效")
            return
        }
        toast("正在读取手机推送的直播源…")
        io.execute {
            try {
                val previewData = createPreview(JSONObject().put("url", cfg.url).put("name", cfg.name))
                val resolvedSub = if (cfg.localSnapshot) {
                    previewData.subscription.copy(
                        id = sourceId("push:" + System.nanoTime()),
                        name = cfg.name.ifBlank { "手机推送频道表" },
                        url = ""
                    )
                } else {
                    previewData.subscription
                }
                val finalPreview = previewData.copy(subscription = resolvedSub)
                postUi { confirmPreview(finalPreview, tryOnSave = true, pendingEpgUrl = cfg.epgUrl) }
            } catch (e: Exception) {
                postUi { toast("读取手机推送失败：" + Network.describeImportError(e).message) }
            }
        }
    }

    private fun confirmPreview(value: ImportPreview, tryOnSave: Boolean = false, pendingEpgUrl: String = "") {
        val singleStream = value.parsed.entries.size == 1 && value.parsed.entries.first().group == M3uParser.SINGLE_STREAM_GROUP
        val chCount = value.parsed.entries.map { it.channelId }.distinct().size
        val epgHint = if (pendingEpgUrl.isNotBlank() || value.parsed.epgUrls.isNotEmpty()) "\n· 含节目单（EPG）配置" else ""
        val sampleNames = value.parsed.entries.map { it.name }.distinct().take(10).joinToString("  ·  ")
        val summary = if (singleStream) {
            "单频道直播流 · ${value.parsed.entries.first().name}$epgHint"
        } else {
            "共解析出 $chCount 个频道、${value.parsed.entries.size} 条线路$epgHint\n\n示例频道：$sampleNames"
        }
        showModal(
            title = "确认导入「${value.subscription.name}」",
            message = summary,
            primaryLabel = "保存到电视"
        ) {
            io.execute {
                try {
                    store.replaceSubscription(value.subscription, value.parsed)
                    if (pendingEpgUrl.isNotBlank()) store.setCustomEpgUrl(pendingEpgUrl)
                    postUi { imported(value.takeIf { tryOnSave }) }
                }
                catch (e: Exception) { postUi { toast("保存失败：" + e.message) } }
            }
            true
        }
    }

    private fun createPreview(data: JSONObject): ImportPreview {
        val rawUrl = data.optString("url").trim().replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        val inlineContent = data.optString("content")
        val explicitName = data.optString("name").trim().take(80)
        val parsedUri = if (rawUrl.isNotEmpty()) streamUri(rawUrl) else null
        val url = parsedUri?.toString().orEmpty()
        val isHttp = parsedUri?.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http", "https")
        val download = if (inlineContent.isEmpty() && isHttp) network.downloadForImport(url) else null
        val parsed = when {
            download?.directStreamMime != null -> M3uParser.singleStreamPlaylist(url, explicitName)
            inlineContent.isEmpty() && !isHttp && url.isNotEmpty() -> M3uParser.singleStreamPlaylist(url, explicitName)
            else -> M3uParser.parseImport(download?.text ?: inlineContent, url, explicitName)
        }
        val name = when {
            explicitName.isNotEmpty() -> explicitName
            parsed.entries.size == 1 && parsed.entries.first().group == M3uParser.SINGLE_STREAM_GROUP -> parsed.entries.first().name
            url.isNotEmpty() -> parsedUri?.host?.takeIf { it.isNotBlank() } ?: url
            else -> "本地频道表"
        }
        val subUrl = if (inlineContent.isEmpty() && isHttp && download?.directStreamMime == null) url else if (isHttp && inlineContent.isNotEmpty()) url else ""
        val sub = Subscription(
            sourceId(if (url.isNotEmpty()) "url:$url" else "file:$name"),
            name,
            subUrl,
            System.currentTimeMillis(),
            download?.etag.orEmpty(),
            download?.lastModified.orEmpty(),
            parsed.epgUrls.firstOrNull().orEmpty()
        )
        return ImportPreview(UUID.randomUUID().toString(), sub, parsed)
    }

    @SuppressLint("SetTextI18n")
    private fun showPairing(export: Boolean = false, onboarding: Boolean = false) {
        beginOverlay(if (export) "export" else "pair")
        val thisSession = session
        val host = localAddress()
        overlay.isClickable = true
        overlay.setOnClickListener { backThroughMenus() }
        val content = FrostedContainer(this, blurEngine, cornerRadiusDp = 24f, veilAlpha = 76).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(42), dp(32), dp(42), dp(32))
            elevation = dp(14).toFloat()
        }
        val cardW = minOf(dp(800), (resources.displayMetrics.widthPixels * 0.90f).toInt())
        overlay.addView(content, FrameLayout.LayoutParams(cardW, -2, Gravity.CENTER).apply {
            setMargins(dp(24), dp(24), dp(24), dp(24))
        })

        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(36), 0) }
        content.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        copy.addView(text(if (onboarding) "纯本地播放器 · 欢迎使用" else "局域网无线连接", 12, TvStyle.subtle, true))
        copy.addView(text(
            when {
                export -> "导出脱敏诊断到手机"
                onboarding -> "手机扫码导入直播源"
                else -> "手机扫码管理电视"
            },
            25, ink, true
        ).apply { setPadding(0, dp(8), 0, dp(10)) })
        copy.addView(text(
            when {
                export -> "手机连接同一局域网扫码即可下载 JSON 报告。\n完整线路路径、令牌与请求头均已自动脱敏。"
                onboarding -> "SwiftTV 不内置任何直播源，请导入您合法拥有的 M3U 或单频道 .m3u8 链接：\n\n① 手机连接同一 Wi-Fi，扫码即可添加或上传频道表\n② 也可使用「SwiftTV 安装助手」在手机端粘贴链接一键直推"
                else -> "① 手机连接同一局域网\n② 扫码添加 M3U / HLS 直链、上传文件或设置节目单\n③ 预览频道后保存，数据存于电视本机"
            },
            14, muted
        ).apply { setLineSpacing(dp(6).toFloat(), 1f) })

        fun makeActionPill(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            id = View.generateViewId()
            this.text = label
            textSize = TvStyle.sp(this@MainActivity, 14f)
            isAllCaps = false
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = if (primary) TvStyle.primaryPillButton(this@MainActivity) else TvStyle.pillButton(this@MainActivity)
            minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            stateListAnimator = null
            setOnFocusChangeListener { _, focused ->
                setTextColor(if (focused) TvStyle.inkOnPorcelain else ink)
                typeface = Typeface.create("sans-serif-medium", if (focused) Typeface.BOLD else Typeface.NORMAL)
            }
            setOnClickListener { onClick() }
        }

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val primaryFocusView: View
        if (onboarding && !export) {
            val manualBtn = makeActionPill("手动输入地址", primary = true) { manualImport() }
            val fileBtn = makeActionPill("本地文件") {
                StartupRuntime.beginInternalSystemFlow()
                try { openDocumentLauncher.launch(arrayOf("*/*")) }
                catch (_: Exception) { toast("这台电视未提供文件选择器，请使用手机扫码或安装助手导入") }
            }
            val settingsBtn = makeActionPill("更多设置") { showSettings() }
            actionRow.addView(manualBtn, LinearLayout.LayoutParams(-2, -2))
            actionRow.addView(fileBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            actionRow.addView(settingsBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            primaryFocusView = manualBtn
        } else {
            val close = makeActionPill("返回") { backThroughMenus() }
            actionRow.addView(close, LinearLayout.LayoutParams(-2, -2))
            primaryFocusView = close
        }
        copy.addView(actionRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        content.addView(right, LinearLayout.LayoutParams(dp(240), -2))
        if (host == null) {
            right.addView(text("未检测到局域网连接\n请先连接 Wi-Fi 或网线", 15, muted).apply { gravity = Gravity.CENTER })
            primaryFocusView.requestFocus()
            return
        }
        try {
            val page = assets.open(if (export) "export.html" else "import.html").bufferedReader().use { it.readText() }
            val report = if (export) {
                JSONObject(store.diagnostics(Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE, RoutingMode.read(this).key))
                    .put("startup", StartupRuntime.diagnostics(this))
                    .put("codec4kHevc50", codecProfile.supports4kHevc50)
                    .toString(2)
            } else null

            val stateCallback: (() -> JSONObject)? = if (export) null else {
                {
                    val snap = store.snapshot()
                    val subsJson = JSONArray(snap.subscriptions.map { s ->
                        JSONObject()
                            .put("id", s.id)
                            .put("name", DiagnosticRedaction.message(s.name))
                            .put("origin", if (s.url.isEmpty()) "电视本机" else DiagnosticRedaction.origin(s.url))
                            .put("hasEpg", s.epgUrl.isNotEmpty())
                    })
                    JSONObject()
                        .put("subscriptions", subsJson)
                        .put("customEpgOrigin", if (snap.customEpgUrl.isEmpty()) "" else DiagnosticRedaction.origin(snap.customEpgUrl))
                }
            }

            val manageCallback: ((JSONObject) -> JSONObject)? = if (export) null else {
                { data ->
                    check(session == thisSession) { "配对已结束" }
                    when (data.optString("action")) {
                        "deleteSubscription" -> {
                            val id = data.getString("id")
                            postUi { deleteSubscriptionById(id) }
                            JSONObject().put("ok", true)
                        }
                        "setEpgUrl" -> {
                            val epgUrl = data.optString("epgUrl").trim()
                            store.setCustomEpgUrl(epgUrl)
                            postUi { refreshEpg(true) }
                            JSONObject().put("ok", true).put("message", if (epgUrl.isEmpty()) "已清除自定义节目单" else "节目单已保存并正在后台同步")
                        }
                        "refresh" -> {
                            postUi { refreshSubscriptions(true); refreshEpg(true) }
                            JSONObject().put("ok", true).put("message", "已在电视后台触发刷新")
                        }
                        else -> error("未知管理操作")
                    }
                }
            }

            val created = ImportServer(host, page, { data ->
                check(session == thisSession) { "配对已结束" }
                val next = createPreview(data)
                check(session == thisSession) { "配对已结束" }
                preview = next
                JSONObject().put("previewId", next.id).put("channelCount", next.parsed.entries.map { it.channelId }.distinct().size)
                    .put("sourceCount", next.parsed.entries.size).put("skipped", next.parsed.skipped)
                    .put("channels", JSONArray(next.parsed.entries.map { it.name }.distinct().take(10)))
            }, { data ->
                check(session == thisSession) { "配对已结束" }
                val next = preview ?: error("请先预览频道")
                require(data.getString("previewId") == next.id) { "预览已过期，请重新读取" }
                store.replaceSubscription(next.subscription, next.parsed)
                preview = null
                postUi {
                    val wasEmpty = overlayMode == "pair" && (controller?.current == null || landing.isVisible)
                    landing.visibility = View.GONE
                    val channels = availableChannels()
                    ribbon?.updateCatalog(channels, store.snapshot().favorites)
                    if (wasEmpty || controller?.current == null) {
                        if (wasEmpty && overlay.isVisible) closeOverlay(animate = false)
                        CatalogSelection.chooseStart(channels, store.snapshot().lastChannel, store.snapshot().stats)?.let { controller?.tune(it) }
                    }
                    refreshEpg(true)
                    toast("已通过手机导入：" + next.subscription.name)
                }
                JSONObject().put("channelCount", next.parsed.entries.map { it.channelId }.distinct().size)
            }, report, stateCallback, manageCallback)

            server = created
            val matrix = QRCodeWriter().encode(created.pairingUrl, BarcodeFormat.QR_CODE, 512, 512)
            val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) Color.rgb(10, 13, 19) else Color.rgb(246, 248, 252) }
            val bitmap = Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
            right.addView(ImageView(this).apply {
                setImageBitmap(bitmap); contentDescription = "手机配对二维码"
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(246, 248, 252))
                    cornerRadius = dp(14).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(206), dp(206)))
            right.addView(text(created.address, 12, muted).apply { setPadding(0, dp(10), 0, dp(2)) })
            right.addView(text("5 分钟内有效", 11, TvStyle.subtle))

            sessionExpiry = Runnable {
                if (session == thisSession) {
                    closeSession()
                    right.removeAllViews()
                    right.addView(text("二维码已过期", 16, ink))
                    right.addView(makeActionPill("刷新二维码") {
                        restoringMenu = true
                        try { showPairing(export = export, onboarding = onboarding) } finally { restoringMenu = false }
                    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
                }
            }.also { handler.postDelayed(it, 5 * 60 * 1000) }
        } catch (e: Exception) { right.addView(text("无法启动配对：" + e.message, 14, muted)) }
        if (!TvStyle.lightweight(this)) {
            content.alpha = 0f; content.scaleX = 0.96f; content.scaleY = 0.96f
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).setInterpolator(TvStyle.springOut).start()
        }
        primaryFocusView.requestFocus()
        blurEngine.syncState()
    }

    private fun closeSession() {
        session++
        sessionExpiry?.let { handler.removeCallbacks(it) }; sessionExpiry = null
        server?.close(); server = null; preview = null
    }

    private fun imported(preferred: ImportPreview? = null) {
        closeOverlay(); landing.visibility = View.GONE
        val channels = availableChannels()
        val state = store.snapshot()
        val importUrls = preferred?.parsed?.entries?.groupBy({ it.channelId }, { it.url }).orEmpty()
        val newChoices = channels.filter { channel ->
            channel.sources.any { it.url in importUrls[channel.id].orEmpty() }
        }
        val playable = newChoices.ifEmpty { channels.filter { it.sources.isNotEmpty() } }
        val selected = playable.firstOrNull { it.id == state.lastChannel } ?: playable.firstOrNull()
        selected?.let { channel ->
            val alternatives = channel.sources.filter { it.url in importUrls[channel.id].orEmpty() }
            val source = SourceRoutes.rank(
                alternatives,
                state.stats,
                System.currentTimeMillis(),
                controller?.effectiveRoutingMode() ?: RoutingMode.AUTO,
                ipv6Available = controller?.hasGlobalIpv6() ?: true
            ).firstOrNull()
            controller?.tune(channel, preferred = source)
        }
        refreshEpg(true)
        toast("频道导入成功，共 ${channels.count { it.sources.isNotEmpty() }} 个频道有线路")
        showChannels()
    }

    private fun localAddress(): String? {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val address = active?.let { manager.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        if (address != null) return address
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()
    }

    private fun refreshEpg(force: Boolean) {
        if (isolatedTestMode) return
        val urls = store.snapshot().allEpgUrls()
        epg.refresh(urls, availableChannels(), force = force) {
            postUi {
                ribbon?.refreshPrograms()
                val cur = controller?.current
                if (cur != null && hud.isVisible) renderHud(cur, lastStatusMessage, controller?.streamSpec ?: StreamSpec())
            }
        }
    }

    private fun refreshSubscriptions(explicit: Boolean) {
        if (refreshing) { if (explicit) toast("正在后台刷新订阅…"); return }
        val now = System.currentTimeMillis()
        val subs = store.snapshot().subscriptions.filter {
            SourceRoutes.isHttpFamily(it.url) && (explicit || now - it.updatedAt >= 86400000L)
        }
        if (subs.isEmpty()) { if (explicit) toast("当前无需要刷新的远程订阅"); return }
        refreshing = true
        if (explicit) toast("正在同步远程频道表…")
        io.execute {
            var success = 0; var failed = 0
            for (sub in subs) {
                try {
                    val result = network.download(sub.url, sub.etag, sub.lastModified)
                    if (result.notModified) store.touchSubscription(sub.id, System.currentTimeMillis())
                    else {
                        val parsed = M3uParser.parseImport(result.text, sub.url, sub.name)
                        val updated = sub.copy(
                            updatedAt = System.currentTimeMillis(),
                            etag = result.etag,
                            lastModified = result.lastModified,
                            epgUrl = sub.epgUrl.ifBlank { parsed.epgUrls.firstOrNull().orEmpty() }
                        )
                        store.replaceSubscription(updated, parsed)
                    }
                    success++
                } catch (_: Exception) { failed++ }
            }
            postUi {
                refreshing = false
                if (explicit || failed > 0) toast("订阅同步完成：$success 成功" + if (failed > 0) "，$failed 失败（已保留旧列表）" else "")
                if (overlayMode == "channels") ribbon?.updateCatalog(availableChannels(), store.snapshot().favorites)
                if (success > 0) refreshEpg(false)
                if (success > 0 && controller?.exhausted == true && controller?.probing != true) {
                    availableChannels().firstOrNull { it.id == controller?.current?.id && it.sources.isNotEmpty() }?.let { controller?.tune(it, explicitRetry = true) }
                }
            }
        }
    }

    private fun startProbe(channels: List<Channel>) {
        val totalSources = channels.sumOf { it.sources.size }
        showModal(
            title = "开始自动检测线路？",
            message = "将暂时静音逐条拉流，实测视频首帧耗时与持续缓冲表现。\n预计检测 $totalSources 条线路（每条约 12–15 秒），期间随时可按返回键停止。",
            primaryLabel = "开始检测"
        ) {
            val panel = sheet("", "线路实测进行中", "按返回键可随时中止并恢复观看", "probe")
            probeLabel = panel.addInfoBlock("准备开始检测…", ink)
            panel.addActionRow("停止检测并恢复播放", "", "停止") { _, _ ->
                controller?.stopProbe(); closeOverlay()
            }.requestFocus()
            controller?.startProbe(channels)
            true
        }
    }

    override fun onProbeProgress(index: Int, total: Int, message: String) { probeLabel?.text = getString(R.string.probe_progress, index, total, message) }
    override fun onProbeFinished() { if (overlayMode == "probe") { closeOverlay(); toast("线路检测已完成，结果已更新至线路详情") } }

    override fun onStreamSpecChanged(spec: StreamSpec) {
        val cur = controller?.current
        if (cur != null && hud.isVisible) renderHud(cur, lastStatusMessage, spec)
    }

    override fun onStatus(channel: Channel?, message: String, failed: Boolean) {
        playbackChannelId = channel?.id
        lastStatusMessage = message
        playbackMark = when {
            failed -> ChannelPlaybackMark.FAILED
            message == "正在播放" -> ChannelPlaybackMark.PLAYING
            message.contains("缓冲") -> ChannelPlaybackMark.BUFFERING
            else -> ChannelPlaybackMark.CONNECTING
        }
        ribbon?.updatePlayback(playbackChannelId, playbackMark)
        renderHud(channel, message, controller?.streamSpec ?: StreamSpec())
        if (overlay.isVisible) {
            hud.visibility = View.GONE
        } else {
            hud.showAnimated()
        }
        handler.removeCallbacks(hideHud)
        if (!failed && message == "正在播放") handler.postDelayed(hideHud, 3800)
        if (failed && !overlay.isVisible && !modalHost.isNotEmpty() && controller?.probing != true) {
            toast("${channel?.name ?: "当前频道"} · $message")
            showChannels()
        }
    }

    override fun onStartupSourceIssue(channel: Channel, issue: StartupSourceIssue) {
        if (backgrounded || isFinishing) return
        if (issue == StartupSourceIssue.OFFLINE) {
            toast("电视尚未连接网络，请检查 Wi-Fi 或网线")
            return
        }
        if (overlay.isVisible || modalHost.isNotEmpty()) {
            toast("启动后暂无画面，可在设置中调整信号源")
            return
        }
        showModal(
            title = "当前频道暂无可用画面",
            message = "所有候选线路均连接超时或无画面。可切换其他线路，或通过手机扫码 / 安装助手更新订阅源。",
            primaryLabel = "查看线路",
            secondaryLabel = "稍后",
            extraLabel = "手机扫码管理",
            onExtra = { showPairing() }
        ) {
            showOptions(channel)
            true
        }
        startupSourceDialog = modalHost.getChildAt(0)
    }

    override fun onFirstFrame(channel: Channel, ms: Long) {
        if (startupSourceDialog != null && modalHost.getChildAt(0) === startupSourceDialog) dismissModal()
        landing.visibility = View.GONE
        syncChannelCursor(channel.id)
    }

    private fun renderHud(channel: Channel?, message: String, spec: StreamSpec) {
        val schedule = channel?.let { epg.scheduleFor(it.id) }
        val numStr = if (channel != null) {
            val idx = availableChannels().filter { it.sources.isNotEmpty() }.indexOfFirst { it.id == channel.id }
            if (idx >= 0) String.format(java.util.Locale.ROOT, "%02d", idx + 1) else ""
        } else ""
        hud.bind(channel, numStr, message, spec, schedule, artwork)
    }

    private fun rebuildChromeForFontScale() {
        ribbon?.cancelMotion()
        ribbon = null

        val landingWasVisible = landing.isVisible
        val landingIdx = root.indexOfChild(landing)
        if (landingIdx >= 0) {
            root.removeViewAt(landingIdx)
            landing = buildLandingView().apply { visibility = if (landingWasVisible) View.VISIBLE else View.GONE }
            root.addView(landing, landingIdx, FrameLayout.LayoutParams(-1, -1))
        }

        val hudWasVisible = hud.isVisible
        val hudIdx = root.indexOfChild(hud)
        if (hudIdx >= 0) {
            root.removeViewAt(hudIdx)
            hud = LiveHudView(this, blurEngine)
            root.addView(hud, hudIdx, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(38); bottomMargin = dp(30); rightMargin = dp(38)
            })
            val cur = controller?.current
            if (cur != null) renderHud(cur, lastStatusMessage, controller?.streamSpec ?: StreamSpec())
            if (hudWasVisible) hud.visibility = View.VISIBLE
        }

        val osdIdx = root.indexOfChild(numberOsd)
        if (osdIdx >= 0) {
            root.removeViewAt(osdIdx)
            numberOsd = NumberPadOsdView(this, blurEngine)
            root.addView(numberOsd, osdIdx, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(30); rightMargin = dp(38)
            })
        }

        val bannerIdx = root.indexOfChild(toastBanner)
        if (bannerIdx >= 0) {
            root.removeViewAt(bannerIdx)
            toastBanner = CinemaToastBanner(this, blurEngine)
            root.addView(toastBanner, bannerIdx, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(24); leftMargin = dp(32); rightMargin = dp(32)
            })
        }
        blurEngine.syncState()
    }

    private fun syncChannelCursor(channelId: String) {
        val zappable = zappableChannels()
        val idx = zappable.indexOfFirst { it.id == channelId }
        if (idx >= 0) channelCursor = idx
    }

    private fun zappableChannels(): List<Channel> {
        val allPlayable = availableChannels().filter { it.sources.isNotEmpty() }
        if (group == "全部") return allPlayable
        val scoped = allPlayable.filter {
            (group == "收藏" && it.id in store.snapshot().favorites) || (group != "收藏" && it.group == group)
        }
        return if (scoped.size >= 2) scoped else allPlayable
    }

    private fun cancelNumberInput() {
        handler.removeCallbacks(commitNumberRunnable)
        numberPad.clear()
        if (::numberOsd.isInitialized) numberOsd.visibility = View.GONE
    }

    private fun commitNumberSelection() {
        val preview = numberPad.currentPreview(availableChannels())
        cancelNumberInput()
        val target = preview?.matchedChannel ?: return
        if (!preview.playable) {
            toast(target.name + " · " + target.unavailableReason.ifEmpty { "暂无可用线路" })
            return
        }
        syncChannelCursor(target.id)
        controller?.tune(target)
    }

    private fun handleBack() {
        if (modalHost.isNotEmpty()) { dismissModal(); return }
        if (numberPad.active) { cancelNumberInput(); return }
        if (controller?.probing == true) { controller?.stopProbe(); return }
        if (overlayMode == "channels" && overlay.isVisible && ribbon?.handleBack() == true) return
        if (overlay.isVisible) { backThroughMenus(); return }
        showModal(
            title = "退出 SwiftTV 直播？",
            message = "退出后本次不会自动拉回界面；下次电视开机或待机唤醒时恢复上次观看频道。",
            primaryLabel = "退出到桌面",
            secondaryLabel = "继续观看"
        ) {
            StartupRuntime.userLeft(this)
            finish()
            true
        }
    }

    private fun interceptKeyEvent(event: KeyEvent): Boolean {
        if (closingOverlay) return true
        val confirm = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        if (confirm && consumeOpeningConfirm) {
            if (event.action == KeyEvent.ACTION_UP) consumeOpeningConfirm = false
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleBack()
            return true
        }
        if (modalHost.isNotEmpty()) return false
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (overlayMode == "channels" && overlay.isVisible && ribbon?.isInSettingsMode() == true) closeOverlay()
                else showSettings(focusRight = true)
            }
            return true
        }
        if (confirm && !overlay.isVisible && event.action == KeyEvent.ACTION_DOWN) {
            if (numberPad.active) {
                consumeOpeningConfirm = true
                commitNumberSelection()
                return true
            }
            consumeOpeningConfirm = true
            showChannels()
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (modalHost.isNotEmpty() || overlay.isVisible) return super.onKeyDown(keyCode, event)
        ChannelNumberPad.digitFromKeyCode(keyCode)?.let { digit ->
            if (event.repeatCount == 0) {
                val preview = numberPad.appendDigit(digit, availableChannels())
                if (preview != null) {
                    numberOsd.showPreview(preview)
                    handler.removeCallbacks(commitNumberRunnable)
                    handler.postDelayed(commitNumberRunnable, 1200)
                }
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> { if (event.repeatCount == 0) handleBack(); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                val prevId = controller?.previousChannelId
                val prev = prevId?.let { id -> availableChannels().firstOrNull { it.id == id && it.sources.isNotEmpty() } }
                if (prev != null) {
                    syncChannelCursor(prev.id)
                    controller?.tune(prev)
                } else {
                    toast("暂无可回看的上一频道")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                cancelNumberInput()
                val delta = ChannelSwitchDirection.read(this).deltaForKey(keyCode) ?: return true
                val channels = zappableChannels()
                if (channels.isEmpty()) { showPairing(onboarding = true); return true }
                if (channels.size == 1) {
                    channelCursor = 0
                    renderHud(channels[0], "当前仅 1 个频道", StreamSpec())
                    hud.showAnimated()
                    handler.removeCallbacks(hideHud)
                    handler.postDelayed(hideHud, 2500)
                    return true
                }
                val now = SystemClock.elapsedRealtime()
                val playingIdx = channels.indexOfFirst { it.id == controller?.current?.id }.coerceAtLeast(0)
                channelCursor = ChannelSwitchDirection.stepCursor(
                    currentCursor = channelCursor,
                    playingIndex = playingIdx,
                    size = channels.size,
                    delta = delta,
                    rapidZapping = (now - lastZapAtMs) <= 1_200L
                )
                lastZapAtMs = now
                val target = channels[channelCursor]
                renderHud(target, "即将切换…", StreamSpec())
                hud.showAnimated()
                handler.removeCallbacks(hideHud)
                controller?.tuneDebounced(target, now)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showChannels(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { showOptions(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { showChannels(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun availableChannels(): List<Channel> {
        val raw = store.snapshot().channels()
        val projected = if (isolatedTestMode) raw else CatalogSelection.gateHardware(raw, codecProfile.supports4kHevc50)
        return projected.filter { !isolatedTestMode || it.id.startsWith("id:controlled-") || it.id == "id:failover" }
    }

    private fun toast(message: String) {
        if (::toastBanner.isInitialized) toastBanner.show(message)
    }
}
