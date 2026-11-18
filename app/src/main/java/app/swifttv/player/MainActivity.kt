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
    private var overlayGeneration = 0
    private var closingOverlay = false
    private var consumeOpeningConfirm = false
    private var channelCursor = -1
    private var refreshing = false
    private var skipBuiltinsForTest = false
    private var startupRejected = false
    private var backgrounded = false
    private var userDeparted = false

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
                    val value = createPreview(JSONObject().put("content", bytes.toString(Charsets.UTF_8)).put("name", "本地频道表"))
                    postUi { confirmPreview(value) }
                } catch (e: Exception) { postUi { toast(e.message ?: "文件读取失败") } }
            }
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
        skipBuiltinsForTest = BuildConfig.DEBUG && intent.getBooleanExtra("skipBuiltinsForTest", false)
        if (!skipBuiltinsForTest) BuiltinCatalog.installBundled(this, store)
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
        root.addView(toastBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(24); leftMargin = dp(40); rightMargin = dp(40)
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
            addView(text("在控制中心输入 M3U 地址、导入文件或使用手机扫码添加频道。", 15, muted).apply {
                gravity = Gravity.CENTER
            })
            val btn = Button(this@MainActivity).apply {
                id = View.generateViewId()
                text = "打开控制中心"
                textSize = 15f
                isAllCaps = false
                setTextColor(TvStyle.inkOnPorcelain)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = TvStyle.porcelainSurface(this@MainActivity, 999f)
                setPadding(dp(28), dp(12), dp(28), dp(12))
                stateListAnimator = null
                setOnClickListener { showSettings() }
            }
            addView(btn, LinearLayout.LayoutParams(-2, dp(46)).apply { topMargin = dp(28) })
        }
        addView(card, FrameLayout.LayoutParams(dp(460), -2, Gravity.CENTER))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (StartupRuntime.acceptsEntry(this, intent)) setIntent(intent)
        else if (backgrounded || userDeparted) {
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
            landing.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
            if (overlayMode == "pair" || overlayMode == "export") {
                showPairing(overlayMode == "export")
            } else if (channels.isEmpty()) {
                showSettings()
            } else {
                BuiltinPolicy.chooseStart(channels, store.snapshot().lastChannel, store.snapshot().stats)?.let { controller?.tune(it) }
                if (overlayMode == "channels") {
                    overlayMode = ""; restoringMenu = true
                    try { showChannels(restore = true) } finally { restoringMenu = false }
                }
            }
            if (!skipBuiltinsForTest) {
                refreshSubscriptions(false)
                refreshEpg(false)
            }
        }
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
        text = value; textSize = size.toFloat(); setTextColor(color)
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
                closeSession(); overlay.visibility = View.GONE; overlay.removeAllViews(); overlayMode = ""
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

    private fun showChannels(restore: Boolean = false) {
        val channels = availableChannels()
        if (channels.isEmpty()) { showSettings(); return }
        if (overlayMode == "channels" && overlay.isVisible && !closingOverlay) {
            ribbon?.updateCatalog(channels, store.snapshot().favorites); return
        }
        beginOverlay("channels")
        val view = ribbon ?: CinemaRibbonView(this, artwork, blurEngine, object : CinemaRibbonView.Actions {
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
            override fun groupChanged(group: String) { this@MainActivity.group = group }
        }, group, scheduleFor = { id -> epg.scheduleFor(id)?.current }).also { ribbon = it }
        (view.parent as? ViewGroup)?.removeView(view)
        overlay.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.updateCatalog(channels, store.snapshot().favorites)
        view.updatePlayback(playbackChannelId, playbackMark)
        view.open(controller?.current?.id, restore); view.appear()
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
                "settings" -> showSettings()
                "startup" -> showStartupSettings()
                "subscriptions" -> showSubscriptions()
                else -> closeOverlay()
            }
        } finally { restoringMenu = false }
    }

    private fun showOptions(channel: Channel? = availableChannels().firstOrNull { it.id == controller?.current?.id } ?: controller?.current) {
        if (channel == null) { showSettings(); return }
        optionsChannelId = channel.id
        val spec = if (controller?.current?.id == channel.id) controller?.streamSpec?.badge().orEmpty() else ""
        val schedule = epg.scheduleFor(channel.id)
        val subParts = listOfNotNull(
            spec.takeIf { it.isNotEmpty() },
            schedule?.current?.title?.let { "正在播出：$it" }
        )
        val panel = sheet("", channel.name, subParts.joinToString(" · ").ifEmpty { "频道操作与多线路热切" }, "options", anchoredRight = true)

        fun favTitle(fav: Boolean) = if (fav) "★  已在收藏夹" else "☆  加入收藏夹"
        panel.addActionRow(favTitle(channel.id in store.snapshot().favorites), "收藏频道可在顶部「收藏」胶囊一键筛选", "OK 切换") { titleTv, _ ->
            val added = store.favorite(channel.id)
            titleTv.text = favTitle(added)
            ribbon?.updateCatalog(availableChannels(), store.snapshot().favorites)
        }
        panel.addActionRow("重新连接此频道", "清除线路冷却并从最优源重新拉流", "立即重试") { _, _ ->
            closeOverlay(animate = false)
            controller?.tune(channel, explicitRetry = true)
        }
        val prevId = controller?.previousChannelId
        val prevChannel = if (prevId != null && prevId != channel.id) availableChannels().firstOrNull { it.id == prevId && it.sources.isNotEmpty() } else null
        if (prevChannel != null) {
            panel.addActionRow("↺  回看上一频道", prevChannel.name, "切回") { _, _ ->
                closeOverlay(animate = false)
                syncChannelCursor(prevChannel.id)
                controller?.tune(prevChannel)
            }
        }
        panel.addSectionHeader("可用线路（${channel.sources.size}）")
        if (channel.sources.isEmpty()) {
            panel.addInfoBlock(channel.unavailableReason.ifEmpty { "此频道暂无可用内置线路，可在控制中心导入自定义订阅。" })
        } else {
            channel.sources.forEachIndexed { index, source ->
                val stat = store.snapshot().stats[sourceId(source.url)]
                val (health, dotColor) = when {
                    stat == null -> "尚未在本机实测" to TvStyle.subtle
                    stat.lastOutcome == "connecting" -> "正在连接…" to TvStyle.accent
                    stat.error.isNotBlank() -> ("上次异常 · " + stat.error.take(64)) to TvStyle.danger
                    stat.playedMs >= 60000 -> "稳定播放 · 首帧 ${stat.firstFrameMs}ms" to TvStyle.sage
                    stat.successes > 0 -> "已出画 · 首帧 ${stat.firstFrameMs}ms" to TvStyle.sage
                    else -> "待检测" to TvStyle.subtle
                }
                val note = BuiltinPolicy.note(source)
                val sub = health + if (note.isNotEmpty()) "\n$note" else ""
                panel.addActionRow(
                    title = "线路 ${index + 1} · ${SourceRoutes.label(source)}",
                    subtitle = sub,
                    accessory = java.net.URI(source.url).host.take(18),
                    statusColor = dotColor
                ) { _, _ ->
                    closeOverlay(animate = false)
                    store.clearCooldown(source.url)
                    controller?.tune(channel, preferred = source)
                }
            }
            panel.addActionRow("自动测速此频道全部线路", "静音逐条验证首帧与缓冲状态", "开始") { _, _ ->
                startProbe(listOf(channel))
            }
        }
        panel.addActionRow("打开控制中心", "订阅源、节目单与自启动设置", "→") { _, _ -> showSettings() }
        panel.focusFirstRow()
    }

    private fun showSettings() {
        val panel = sheet("", "控制中心", "SwiftTV ${BuildConfig.VERSION_NAME}", "settings")

        panel.addSectionHeader("播放与画面体验")
        if (BuildConfig.HAS_BUNDLED_CATALOG || store.snapshot().subscriptions.any { it.id.startsWith("builtin-") }) {
            panel.addSwitchRow("内置精选频道", "随包精选频道表（按硬件能力自动开放 4K 频道）", BuiltinCatalog.enabled(this)) {
                val next = !BuiltinCatalog.enabled(this)
                BuiltinCatalog.setEnabled(this, store, next)
                val channels = availableChannels()
                ribbon?.updateCatalog(channels, store.snapshot().favorites)
                if (channels.isEmpty()) controller?.stop()
                else BuiltinPolicy.chooseStart(channels, store.snapshot().lastChannel, store.snapshot().stats)?.let { controller?.tune(it) }
                next
            }
        }
        panel.addSegmentRow("优先路由策略", "方向键左右或确认键切换默认选路策略", if (BuiltinCatalog.preferHenan(this)) "河南移动优先" else "按实测表现") {
            val next = !BuiltinCatalog.preferHenan(this)
            BuiltinCatalog.setPreferHenan(this, next)
            availableChannels().firstOrNull { it.id == controller?.current?.id && it.sources.isNotEmpty() }?.let { controller?.tune(it) }
            if (next) "河南移动优先" else "按实测表现"
        }
        panel.addSegmentRow("视觉与毛玻璃效果", "实时光学视频动态模糊与丝滑弹簧动效", if (TvStyle.lightweight(this)) "极速纯色" else "光学毛玻璃") {
            val nextLight = !TvStyle.lightweight(this)
            TvStyle.setLightweight(this, nextLight)
            hud.refreshSurface()
            numberOsd.refreshSurface()
            panel.refreshSurface()
            blurEngine.syncState()
            if (nextLight) "极速纯色" else "光学毛玻璃"
        }
        panel.addActionRow("开机自动看直播", StartupRuntime.settingLabel(this), "配置 →") { _, _ -> showStartupSettings() }

        panel.addSectionHeader("订阅源、台标与节目单（EPG）")
        panel.addActionRow("手机扫码管理台", "同一 Wi-Fi 扫码导入 M3U、配置节目单与管理订阅", "打开二维码 →") { _, _ -> showPairing() }
        panel.addActionRow("导入河南移动全量源（HA-CMCC-IPTV）", "预览 Elykia 外部订阅 · 需河南移动 IPTV 网络", "拉取预览 →") { _, _ ->
            fetchPresetHenanFullCatalog()
        }
        panel.addActionRow("手动输入订阅地址", "输入任意 HTTP/HTTPS M3U 频道表链接", "输入 →") { _, _ -> manualImport() }
        panel.addActionRow("从电视本地文件导入", "选择本地存储中的 .m3u / .m3u8 文件", "浏览 →") { _, _ ->
            StartupRuntime.beginInternalSystemFlow()
            try { openDocumentLauncher.launch(arrayOf("*/*")) }
            catch (_: Exception) { toast("这台电视未提供文件选择器，请使用手机扫码导入") }
        }
        panel.addActionRow("设置 XMLTV 节目单地址", if (store.snapshot().customEpgUrl.isEmpty()) "支持 .xml / .xml.gz（未配置时保持纯净静默）" else "已配置自定义节目单", "配置 →") { _, _ ->
            configureEpgDialog()
        }
        panel.addActionRow("刷新全部订阅与节目单", "立即后台同步远程 M3U 与最新节目预告", "立即刷新") { _, _ ->
            refreshSubscriptions(true)
            refreshEpg(true)
        }
        panel.addActionRow("管理已保存订阅", "共 ${store.snapshot().subscriptions.size} 个订阅源", "查看 →") { _, _ -> showSubscriptions() }

        panel.addSectionHeader("诊断与说明")
        panel.addActionRow("批量测速当前分类线路", "当前分类：${group}", "开始") { _, _ ->
            val selected = availableChannels().filter {
                it.sources.isNotEmpty() && (group == "全部" || (group == "收藏" && it.id in store.snapshot().favorites) || it.group == group)
            }
            if (selected.isNotEmpty()) startProbe(selected) else toast("当前分类没有可测线路")
        }
        panel.addActionRow("扫码导出脱敏诊断", "在手机下载隐藏密钥的本机播放质量 JSON", "导出 →") { _, _ -> showPairing(true) }
        panel.addActionRow("本机硬件解码能力", if (codecProfile.supports4kHevc50) "检测到 4K HEVC 硬件解码能力" else "1080p 优先", "详情") { _, _ ->
            val note = if (codecProfile.supports4kHevc50) "检测到 4K HEVC 硬件解码能力。实际播放由线路与设备决定。"
                else "优先选择 1080p H.264/AAC 线路。"
            showModal("本机解码能力", note, primaryLabel = "关闭", secondaryLabel = null) { true }
        }
        panel.addActionRow("开源许可", "Apache-2.0", "查看") { _, _ ->
            val license = assets.open("licenses/Apache-2.0.txt").bufferedReader().use { it.readText() }
            showModal("Apache-2.0", license, primaryLabel = "关闭", secondaryLabel = null) { true }
        }
        panel.focusFirstRow()
    }

    private fun fetchPresetHenanFullCatalog() {
        toast("正在拉取 HA-CMCC-IPTV 全量频道表…")
        io.execute {
            val urls = listOf(
                "https://elykia093.github.io/HA-CMCC-IPTV/index.m3u",
                "https://cdn.jsdelivr.net/gh/Elykia093/HA-CMCC-IPTV@main/public/index.m3u",
                "https://fastly.jsdelivr.net/gh/Elykia093/HA-CMCC-IPTV@main/public/index.m3u"
            )
            var lastErr: Exception? = null
            for (u in urls) {
                try {
                    val result = createPreview(JSONObject().put("url", u).put("name", "河南移动全量扩展 (Elykia)"))
                    postUi { confirmPreview(result) }
                    return@execute
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            postUi { toast("拉取失败：" + (lastErr?.message ?: "请检查网络连接或使用手机扫码上传本地 M3U")) }
        }
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

        panel.addActionRow("授权开机助手（推荐）", "仅识别前台包名与亮灭屏，不读取内容不过滤遥控器", if (StartupRuntime.guardEnabled(this)) "已授权" else "去开启 →") { _, _ ->
            showModal(
                title = "启用 SwiftTV 开机助手",
                message = "用于开机、待机唤醒及系统回收后的直播恢复。\n\n仅识别前台应用包名，不读取文字节点，不拦截按键。请在下一页系统无障碍设置中找到「SwiftTV 开机助手」并开启。",
                primaryLabel = "前往系统设置"
            ) { openSettings("guard"); true }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            panel.addActionRow("允许后台弹出界面", "Android 10 及以上电视后台拉起直播所需权限", if (StartupRuntime.overlayAllowed(this)) "已允许" else "去允许 →") { _, _ ->
                openSettings("overlay")
            }
        }
        panel.addActionRow("系统回收恢复辅助（可选）", "借助通知服务重绑机制恢复直播，不读取任何通知", if (StartupRuntime.listenerEnabled(this)) "已开启" else "去设置 →") { _, _ ->
            openSettings("listener")
        }
        panel.addActionRow("电视应用自启动与后台权限", "打开本应用系统详情页配置厂商后台策略", "打开 →") { _, _ -> openSettings("app") }
        panel.focusFirstRow()
    }

    private fun showSubscriptions() {
        val panel = sheet("", "订阅管理", "删除单个订阅不会影响其他订阅或收藏记录", "subscriptions")
        val subs = store.snapshot().subscriptions
        if (subs.isEmpty()) {
            panel.addInfoBlock("当前没有已保存的订阅。")
        } else {
            subs.forEach { sub ->
                val origin = if (sub.url.isEmpty()) "本机内置/本地文件" else java.net.URI(sub.url).host
                val epgTag = if (sub.epgUrl.isNotEmpty()) " · 含节目单" else ""
                panel.addActionRow(sub.name, origin + epgTag, "删除", statusColor = TvStyle.sage) { _, _ ->
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
        if (id.startsWith("builtin-")) BuiltinCatalog.setEnabled(this, store, false)
        else store.removeSubscription(id)
        val channels = availableChannels()
        ribbon?.updateCatalog(channels, store.snapshot().favorites)
        val currentId = controller?.current?.id
        val remaining = channels.firstOrNull { it.id == currentId && it.sources.isNotEmpty() }
            ?: BuiltinPolicy.chooseStart(channels, "", store.snapshot().stats)
        controller?.stop()
        remaining?.let { controller?.tune(it) }
        refreshEpg(false)
    }

    private fun configureEpgDialog() {
        showModal(
            title = "设置节目单（XMLTV）地址",
            message = "支持标准 XMLTV（.xml 或 .xml.gz），留空保存即可清除。未匹配到真实节目时保持静默。",
            inputHint = "https://epg.zsdc.eu.org/t.xml",
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
            title = "添加自定义 M3U 订阅",
            message = "输入你有权使用的 HTTP/HTTPS M3U 频道表链接。",
            inputHint = "https://elykia093.github.io/HA-CMCC-IPTV/index.m3u",
            primaryLabel = "读取预览"
        ) { raw ->
            val url = raw.trim()
            if (url.isEmpty()) {
                toast("请填写订阅地址")
                false
            } else {
                toast("正在读取频道表…")
                io.execute {
                    try {
                        val result = createPreview(JSONObject().put("url", url))
                        postUi { confirmPreview(result) }
                    } catch (e: Exception) { postUi { toast(e.message ?: "读取失败") } }
                }
                true
            }
        }
    }

    private fun confirmPreview(value: ImportPreview) {
        val chCount = value.parsed.entries.map { it.channelId }.distinct().size
        val epgHint = if (value.parsed.epgUrls.isNotEmpty()) "\n· 已自动识别随表节目单（EPG）" else ""
        val sampleNames = value.parsed.entries.map { it.name }.distinct().take(10).joinToString("  ·  ")
        showModal(
            title = "确认导入「${value.subscription.name}」",
            message = "共解析出 $chCount 个频道、${value.parsed.entries.size} 条线路$epgHint\n\n示例频道：$sampleNames",
            primaryLabel = "保存到电视"
        ) {
            io.execute {
                try { store.replaceSubscription(value.subscription, value.parsed); postUi { imported() } }
                catch (e: Exception) { postUi { toast("保存失败：" + e.message) } }
            }
            true
        }
    }

    private fun createPreview(data: JSONObject): ImportPreview {
        val url = data.optString("url").trim()
        val download = if (url.isNotEmpty()) network.download(url) else null
        val body = download?.text ?: data.optString("content")
        val parsed = M3uParser.parse(body)
        val explicitName = data.optString("name").trim().take(80)
        val name = when {
            explicitName.isNotEmpty() -> explicitName
            url.isNotEmpty() -> httpUri(url).host
            else -> "本地频道表"
        }
        val sub = Subscription(
            sourceId(if (url.isNotEmpty()) "url:$url" else "file:$name"),
            name,
            url,
            System.currentTimeMillis(),
            download?.etag.orEmpty(),
            download?.lastModified.orEmpty(),
            parsed.epgUrls.firstOrNull().orEmpty()
        )
        return ImportPreview(UUID.randomUUID().toString(), sub, parsed)
    }

    @SuppressLint("SetTextI18n")
    private fun showPairing(export: Boolean = false) {
        beginOverlay(if (export) "export" else "pair")
        val thisSession = session
        val host = localAddress()
        val content = FrostedContainer(this, blurEngine, cornerRadiusDp = 24f, veilAlpha = 76).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(42), dp(32), dp(42), dp(32))
            elevation = dp(14).toFloat()
        }
        val cardW = minOf(dp(760), (resources.displayMetrics.widthPixels * 0.88f).toInt())
        overlay.addView(content, FrameLayout.LayoutParams(cardW, -2, Gravity.CENTER).apply {
            setMargins(dp(24), dp(24), dp(24), dp(24))
        })

        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(36), 0) }
        content.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        copy.addView(text("局域网无线连接", 12, TvStyle.subtle, true))
        copy.addView(text(if (export) "导出脱敏诊断到手机" else "手机扫码管理电视", 25, ink, true).apply { setPadding(0, dp(8), 0, dp(10)) })
        copy.addView(text(
            if (export) "手机连接同一局域网扫码即可下载 JSON 报告。\n完整线路路径、令牌与请求头均已自动脱敏。"
            else "① 手机连接同一局域网\n② 扫码添加 M3U 订阅、上传文件或设置节目单\n③ 预览频道后保存，数据存于电视本机",
            14, muted
        ).apply { setLineSpacing(dp(6).toFloat(), 1f) })

        val close = Button(this).apply {
            id = View.generateViewId()
            text = "返回"
            textSize = 14f
            isAllCaps = false
            setTextColor(ink)
            background = TvStyle.pillButton(this@MainActivity)
            setPadding(dp(22), dp(8), dp(22), dp(8))
            stateListAnimator = null
            setOnFocusChangeListener { _, focused ->
                setTextColor(if (focused) TvStyle.inkOnPorcelain else ink)
                typeface = Typeface.create("sans-serif-medium", if (focused) Typeface.BOLD else Typeface.NORMAL)
            }
            setOnClickListener { backThroughMenus() }
        }
        copy.addView(close, LinearLayout.LayoutParams(-2, dp(40)).apply { topMargin = dp(24) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        content.addView(right, LinearLayout.LayoutParams(dp(240), -2))
        if (host == null) {
            right.addView(text("未检测到局域网连接\n请先连接 Wi-Fi 或网线", 15, muted).apply { gravity = Gravity.CENTER })
            close.requestFocus()
            return
        }
        try {
            val page = assets.open(if (export) "export.html" else "import.html").bufferedReader().use { it.readText() }
            val report = if (export) {
                JSONObject(store.diagnostics(Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE, if (BuiltinCatalog.preferHenan(this)) "henan_first" else "playback_history"))
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
                            .put("builtin", s.id.startsWith("builtin-"))
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
                            require(!id.startsWith("builtin-")) { "内置频道请在电视控制中心开关" }
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
                    landing.visibility = View.GONE
                    val channels = availableChannels()
                    ribbon?.updateCatalog(channels, store.snapshot().favorites)
                    if (controller?.current == null) {
                        (channels.firstOrNull { it.id == store.snapshot().lastChannel } ?: channels.firstOrNull())?.let { controller?.tune(it) }
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
                }
            }.also { handler.postDelayed(it, 5 * 60 * 1000) }
        } catch (e: Exception) { right.addView(text("无法启动配对：" + e.message, 14, muted)) }
        if (!TvStyle.lightweight(this)) {
            content.alpha = 0f; content.scaleX = 0.96f; content.scaleY = 0.96f
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).setInterpolator(TvStyle.springOut).start()
        }
        close.requestFocus()
        blurEngine.syncState()
    }

    private fun closeSession() {
        session++
        sessionExpiry?.let { handler.removeCallbacks(it) }; sessionExpiry = null
        server?.close(); server = null; preview = null
    }

    private fun imported() {
        closeOverlay(); landing.visibility = View.GONE
        val channels = availableChannels()
        val selected = channels.firstOrNull { it.id == store.snapshot().lastChannel && it.sources.isNotEmpty() }
            ?: channels.firstOrNull { it.sources.isNotEmpty() }
        selected?.let { controller?.tune(it) }
        refreshEpg(true)
        toast("频道导入成功，共 ${channels.count { it.sources.isNotEmpty() }} 个可播频道")
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
        if (skipBuiltinsForTest) return
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
        val subs = store.snapshot().subscriptions.filter { !it.id.startsWith("builtin-") && it.url.isNotEmpty() && (explicit || now - it.updatedAt >= 86400000L) }
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
                        val parsed = M3uParser.parse(result.text)
                        val updated = sub.copy(
                            updatedAt = System.currentTimeMillis(),
                            etag = result.etag,
                            lastModified = result.lastModified,
                            epgUrl = sub.epgUrl.ifBlank { parsed.epgUrls.firstOrNull().orEmpty() }
                        )
                        if (!sub.id.startsWith("builtin-")) store.replaceSubscription(updated, parsed)
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
        if (failed && !overlay.isVisible && controller?.probing != true) showChannels()
    }

    override fun onFirstFrame(channel: Channel, ms: Long) {
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
                if (overlayMode == "channels") showOptions(ribbon?.selectedChannel() ?: controller?.current)
                else if (!overlay.isVisible) showOptions()
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
                val channels = zappableChannels()
                if (channels.isEmpty()) { showSettings(); return true }
                if (channelCursor !in channels.indices) channelCursor = channels.indexOfFirst { it.id == controller?.current?.id }.coerceAtLeast(0)
                val increment = if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1
                channelCursor = (channelCursor + increment + channels.size) % channels.size
                val target = channels[channelCursor]
                renderHud(target, "即将切换…", StreamSpec())
                hud.showAnimated()
                handler.removeCallbacks(hideHud)
                controller?.tuneDebounced(target, SystemClock.elapsedRealtime())
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showChannels(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> { showOptions(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { showChannels(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun availableChannels(): List<Channel> {
        val snapshot = store.snapshot()
        val raw = snapshot.channels()
        val projected = if (!skipBuiltinsForTest && BuiltinCatalog.enabled(this) &&
            snapshot.subscriptions.any { it.id.startsWith("builtin-") }) {
            BuiltinPolicy.withMissingChannels(raw, codecProfile.supports4kHevc50)
        } else {
            raw
        }
        return projected.filter { !skipBuiltinsForTest || it.id.startsWith("id:controlled-") || it.id == "id:failover" }
    }

    private fun toast(message: String) {
        if (::toastBanner.isInitialized) toastBanner.show(message)
    }
}
