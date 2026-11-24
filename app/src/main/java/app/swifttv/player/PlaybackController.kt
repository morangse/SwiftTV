package app.swifttv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import okhttp3.Call
import java.util.UUID

data class StreamSpec(
    val width: Int = 0,
    val height: Int = 0,
    val videoMime: String = "",
    val frameRate: Float = 0f,
    val audioMime: String = "",
    val routeLabel: String = "",
    val firstFrameMs: Long = 0
) {
    fun badge(): String {
        val parts = mutableListOf<String>()
        if (height > 0) {
            parts += when {
                width >= 3800 || height >= 2100 -> "4K"
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                else -> "${height}p"
            }
        }
        when (videoMime.lowercase(java.util.Locale.ROOT)) {
            MimeTypes.VIDEO_H265 -> parts += "HEVC"
            MimeTypes.VIDEO_H264 -> parts += "H.264"
            MimeTypes.VIDEO_AV1 -> parts += "AV1"
        }
        if (routeLabel.isNotBlank()) parts += routeLabel
        if (firstFrameMs in 1..9999) parts += "${firstFrameMs}ms"
        return parts.joinToString(" · ")
    }
}

enum class RecoveryAction { RESYNC_LIVE_EDGE, RECONNECT_SAME_SOURCE, FAIL_AND_COOL }

/** Pure playback recovery policy so live-edge resync and transient drop budgets stay testable. */
object PlaybackRecoveryPolicy {
    const val MAX_LIVE_EDGE_RESYNCS = 2
    const val MAX_TRANSIENT_RECONNECTS = 1
    const val MIN_SUSTAINED_MS_FOR_RECONNECT = 10_000L
    const val RECONNECT_DELAY_MS = 800L

    fun decide(
        errorCode: Int?,
        httpStatusCode: Int?,
        firstFrameRendered: Boolean,
        sustainedPlayedMs: Long,
        liveEdgeResyncs: Int,
        transientReconnects: Int,
        probing: Boolean
    ): RecoveryAction {
        if (probing) return RecoveryAction.FAIL_AND_COOL
        if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && liveEdgeResyncs < MAX_LIVE_EDGE_RESYNCS) {
            return RecoveryAction.RESYNC_LIVE_EDGE
        }
        val fatalDecoder = errorCode in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
        )
        val permanentHttp = httpStatusCode in listOf(401, 403, 404, 410)
        if (!fatalDecoder && !permanentHttp && firstFrameRendered &&
            sustainedPlayedMs >= MIN_SUSTAINED_MS_FOR_RECONNECT &&
            transientReconnects < MAX_TRANSIENT_RECONNECTS
        ) {
            return RecoveryAction.RECONNECT_SAME_SOURCE
        }
        return RecoveryAction.FAIL_AND_COOL
    }
}

@UnstableApi
class PlaybackController(
    private val context: Context, private val store: CatalogStore, private val network: Network,
    private val view: PlayerView, private val callback: Callback
) {
    interface Callback {
        fun onStatus(channel: Channel?, message: String, failed: Boolean = false)
        fun onFirstFrame(channel: Channel, ms: Long)
        fun onStreamSpecChanged(spec: StreamSpec) {}
        fun onStartupSourceIssue(channel: Channel, issue: StartupSourceIssue) {}
        fun onProbeProgress(index: Int, total: Int, message: String)
        fun onProbeFinished()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val automaticRouting = AutomaticSourceRouting()
    private var attemptNetwork: Long? = null
    private var startupSourceCheck: StartupSourceCheck? = null
    private val codecProfile = HardwareCodecProbe.profile(context)
    private val selector = DefaultTrackSelector(context).apply {
        val preferredVideo = if (codecProfile.supports4kHevc50 || codecProfile.supports1080pHevc) {
            arrayOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
        } else {
            arrayOf(MimeTypes.VIDEO_H264)
        }
        parameters = buildUponParameters()
            .setMaxVideoSize(codecProfile.maxWidth, codecProfile.maxHeight)
            .setPreferredVideoMimeTypes(*preferredVideo)
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC)
            .setExceedVideoConstraintsIfNecessary(true).build()
    }
    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setTrackSelector(selector)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(3000, 15000, 700, 1800)
            .setTargetBufferBytes(16 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
        .build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }

    var current: Channel? = null
        private set
    var previousChannelId: String? = null
        private set
    var streamSpec: StreamSpec = StreamSpec()
        private set
    var exhausted: Boolean = false
        private set
    private var entry: Entry? = null
    private var queue: List<Entry> = emptyList()
    private var queueIndex = 0
    private var switchId = ""
    private var requestedAt = 0L
    private var sourceStartedAt = 0L
    private var firstFrame = false
    private var attemptPlayedMs = 0L
    private var liveEdgeResyncs = 0
    private var transientReconnects = 0
    private var lastTick = 0L
    private var lastPosition = 0L
    private var noProgressAt = 0L
    private var bufferingAt = 0L
    private var playedUnflushed = 0L
    private var rebufferUnflushed = 0L
    private var lastFlush = 0L
    private var lastFormat: Format? = null
    private var lastAudioMime: String = ""
    private var lastFirstFrameMs = 0L
    private var closed = false
    private var warmCall: Call? = null
    private var sniffCall: Call? = null
    private var pendingTune: Runnable? = null
    private var pendingReconnect: Runnable? = null
    private var probeItems: List<Pair<Channel, Entry>> = emptyList()
    private var probeIndex = -1
    private var probeFirstFrameAt = 0L
    private var beforeProbeId: String? = null
    val probing: Boolean get() = probeIndex >= 0
    var onInternetRestored: (() -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            handler.post {
                if (!closed && exhausted && current != null && !probing && activeNetworkId() == network.networkHandle) {
                    onInternetRestored?.invoke()
                }
            }
        }
    }

    private fun activeNetworkId(): Long? {
        val active = connectivity?.activeNetwork ?: return null
        return active.takeIf { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
            ?.networkHandle
    }

    fun hasGlobalIpv6(): Boolean {
        val active = connectivity?.activeNetwork ?: return false
        val link = connectivity.getLinkProperties(active) ?: return false
        return link.linkAddresses.any { la ->
            val addr = la.address
            addr is java.net.Inet6Address &&
                !addr.isLoopbackAddress &&
                !addr.isLinkLocalAddress &&
                !addr.isSiteLocalAddress &&
                !addr.isMulticastAddress &&
                (addr.address[0].toInt() and 0xfe) != 0xfc
        }
    }

    fun effectiveRoutingMode(): RoutingMode = automaticRouting.effective(activeNetworkId(), RoutingMode.read(context))

    fun tune(
        channel: Channel,
        requestedTime: Long = SystemClock.elapsedRealtime(),
        explicitRetry: Boolean = false,
        preferred: Entry? = null,
        diagnoseStartup: Boolean = false
    ) {
        startupSourceCheck = if (diagnoseStartup) StartupSourceCheck(requestedTime) else null
        cancelPending()
        if (probing) stopProbe(false)
        val outgoing = current
        if (outgoing != null && outgoing.id != channel.id && firstFrame) {
            previousChannelId = outgoing.id
        }
        finishSource()
        warmCall?.cancel()
        current = channel
        exhausted = false
        streamSpec = StreamSpec()
        callback.onStreamSpecChanged(streamSpec)
        requestedAt = requestedTime
        store.remember(channel.id)
        if (explicitRetry) channel.sources.forEach { store.clearCooldown(it.url) }
        queue = SourceRoutes.rank(
            channel.sources,
            store.snapshot().stats,
            System.currentTimeMillis(),
            effectiveRoutingMode(),
            preferred,
            ipv6Available = hasGlobalIpv6()
        )
        queueIndex = 0
        playQueued()
    }

    fun tuneDebounced(channel: Channel, requestedTime: Long) {
        pendingTune?.let { handler.removeCallbacks(it) }
        pendingTune = Runnable { pendingTune = null; tune(channel, requestedTime) }.also { handler.postDelayed(it, 150) }
    }
    private fun cancelPending() {
        pendingTune?.let { handler.removeCallbacks(it) }; pendingTune = null
        pendingReconnect?.let { handler.removeCallbacks(it) }; pendingReconnect = null
    }

    private fun playQueued() {
        if (queueIndex >= queue.size) {
            exhausted = true
            finishSource()
            player.stop()
            reportStartupSourceIssue(SystemClock.elapsedRealtime(), exhausted = true)
            callback.onStatus(current, if (current?.sources.isNullOrEmpty()) "此频道暂无已验证线路。可在设置添加自定义源。" else "所有线路均失败。按右键重试或刷新订阅。", true)
            return
        }
        startSource(queue[queueIndex++], resetRecoveryBudget = true)
    }

    private fun startSource(next: Entry, resetRecoveryBudget: Boolean = true) {
        pendingReconnect?.let { handler.removeCallbacks(it) }; pendingReconnect = null
        if (resetRecoveryBudget) {
            finishSource()
            liveEdgeResyncs = 0
            transientReconnects = 0
            attemptPlayedMs = 0L
        } else {
            sniffCall?.cancel(); sniffCall = null
            flush()
        }
        player.stop()
        player.playWhenReady = true
        entry = next
        attemptNetwork = activeNetworkId()
        if (!probing) startupSourceCheck?.attempting(SourceRoutes.isHenan(next.url))
        firstFrame = false
        lastFormat = null
        lastAudioMime = ""
        lastFirstFrameMs = 0L
        switchId = UUID.randomUUID().toString()
        sourceStartedAt = SystemClock.elapsedRealtime()
        if (requestedAt == 0L) requestedAt = sourceStartedAt
        lastTick = sourceStartedAt
        lastFlush = sourceStartedAt
        noProgressAt = sourceStartedAt
        bufferingAt = sourceStartedAt
        lastPosition = 0
        probeFirstFrameAt = 0
        val routeLabel = SourceRoutes.label(next)
        streamSpec = StreamSpec(routeLabel = routeLabel)
        callback.onStreamSpecChanged(streamSpec)
        if (resetRecoveryBudget) {
            store.record(next.url) { it.copy(attempts = it.attempts + 1, checkedAt = System.currentTimeMillis(), audioStarted = false, error = "", lastOutcome = "connecting") }
        }
        val path = httpUri(next.url).path.orEmpty()
        val mime = when {
            path.endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".ts", true) -> MimeTypes.VIDEO_MP2T
            path.endsWith(".flv", true) -> MimeTypes.VIDEO_FLV
            else -> null
        }
        callback.onStatus(current, when {
            probing -> "正在检测线路…"
            !resetRecoveryBudget -> "网络波动，正在自动重连…"
            else -> "正在连接 · $routeLabel · $queueIndex/${queue.size}"
        })
        if (mime != null) prepare(next, mime)
        else {
            val expected = switchId
            sniffCall = network.sniff(next) { result ->
                handler.post {
                    if (!closed && switchId == expected && entry?.url == next.url) {
                        result.fold({ prepare(next, it) }, {
                            val reachability = PlaybackErrors.isReachabilityFailure(it)
                            val message = if (reachability) PlaybackErrors.describe(it) else it.message ?: "无法读取线路清单"
                            fail(message, reachability)
                        })
                    }
                }
            }
        }
    }

    private fun prepare(next: Entry, mime: String) {
        val factory = OkHttpDataSource.Factory(network.client).setUserAgent(Network.USER_AGENT).setDefaultRequestProperties(next.headers)
        val mediaFactory = DefaultMediaSourceFactory(factory)
        val item = MediaItem.Builder().setUri(next.url).setMediaId(switchId).setMimeType(mime).build()
        player.setMediaSource(mediaFactory.createMediaSource(item))
        player.prepare()
        player.playWhenReady = true
    }

    private fun recoverOrFail(error: PlaybackException?, message: String) {
        val active = entry ?: return
        val httpCode = generateSequence<Throwable>(error) { it.cause }.take(12)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val decision = PlaybackRecoveryPolicy.decide(
            errorCode = error?.errorCode,
            httpStatusCode = httpCode,
            firstFrameRendered = firstFrame,
            sustainedPlayedMs = attemptPlayedMs,
            liveEdgeResyncs = liveEdgeResyncs,
            transientReconnects = transientReconnects,
            probing = probing
        )
        when (decision) {
            RecoveryAction.RESYNC_LIVE_EDGE -> {
                liveEdgeResyncs++
                val now = SystemClock.elapsedRealtime()
                bufferingAt = now; noProgressAt = now
                Log.i("SwiftTVMetric", "live_edge_resync source=" + sourceId(active.url) + " attempt=$liveEdgeResyncs")
                callback.onStatus(current, "直播窗口偏移，正在对齐实时画面…")
                player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            }
            RecoveryAction.RECONNECT_SAME_SOURCE -> {
                transientReconnects++
                val expectedChannelId = current?.id
                Log.i("SwiftTVMetric", "transient_reconnect source=" + sourceId(active.url) + " playedMs=$attemptPlayedMs")
                player.stop()
                callback.onStatus(current, "连接短暂中断，正在重连当前线路…")
                pendingReconnect = Runnable {
                    pendingReconnect = null
                    if (!closed && current?.id == expectedChannelId && entry?.url == active.url) {
                        requestedAt = SystemClock.elapsedRealtime()
                        startSource(active, resetRecoveryBudget = false)
                    }
                }.also { handler.postDelayed(it, PlaybackRecoveryPolicy.RECONNECT_DELAY_MS) }
            }
            RecoveryAction.FAIL_AND_COOL -> fail(message, error?.let(PlaybackErrors::isReachabilityFailure) == true)
        }
    }

    private fun fail(message: String, reachabilityFailure: Boolean = false) {
        val failedEntry = entry ?: return
        if (!probing && !firstFrame && reachabilityFailure && SourceRoutes.isHenan(failedEntry.url)) {
            val networkId = activeNetworkId()
            if (networkId != null && networkId == attemptNetwork) {
                val mode = RoutingMode.read(context)
                val before = automaticRouting.effective(networkId, mode)
                automaticRouting.regionalFailure(networkId, failedEntry.url)
                val after = automaticRouting.effective(networkId, mode)
                if (before != after) queue = queue.take(queueIndex) + SourceRoutes.prioritize(queue.drop(queueIndex), after)
            }
        }
        Log.w("SwiftTVMetric", "source_failed source=" + sourceId(failedEntry.url) + " error=" + DiagnosticRedaction.message(message))
        finishSource(failed = true)
        store.record(failedEntry.url) { it.copy(failures = it.failures + 1, lastOutcome = "failed", error = DiagnosticRedaction.message(message), checkedAt = System.currentTimeMillis(),
            cooldownUntil = System.currentTimeMillis() + 2 * 60 * 1000) }
        player.stop()
        callback.onStatus(current, message + "，正在尝试备用线路…")
        if (probing) nextProbe() else playQueued()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastTick).coerceIn(0, 2000)
            lastTick = now
            if (entry != null && pendingReconnect == null && player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                if (firstFrame && player.isPlaying) {
                    playedUnflushed += delta
                    attemptPlayedMs += delta
                }
                if (firstFrame && player.playbackState == Player.STATE_BUFFERING) rebufferUnflushed += delta
                val position = player.currentPosition
                if (position != lastPosition) { noProgressAt = now; lastPosition = position }
                val firstFrameLimitMs = if (lastFormat != null) 12_000L else 8_000L
                if (!firstFrame && now - sourceStartedAt >= firstFrameLimitMs) {
                    fail(if (lastFormat != null) "十二秒内未渲染视频首帧" else "八秒内没有视频画面", reachabilityFailure = lastFormat == null)
                }
                else if (bufferingAt > 0 && now - bufferingAt >= 8000) recoverOrFail(null, "持续缓冲超过八秒")
                else if (firstFrame && player.isPlaying && now - noProgressAt >= 8000) recoverOrFail(null, "播放进度停止")
                else if (probing && firstFrame && now - probeFirstFrameAt >= 12000) {
                    flush()
                    callback.onProbeProgress(probeIndex + 1, probeItems.size, current?.name.orEmpty() + " · 短时播放完成")
                    nextProbe()
                }
                if (now - lastFlush >= 10000) flush()
            }
            if (!probing && entry != null && player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                reportStartupSourceIssue(now)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun reportStartupSourceIssue(now: Long, exhausted: Boolean = false) {
        val check = startupSourceCheck ?: return
        val channel = current ?: return
        val issue = check.check(now, exhausted, activeNetworkId() != null) ?: return
        if (issue != StartupSourceIssue.OFFLINE) startupSourceCheck = null
        callback.onStartupSourceIssue(channel, issue)
    }

    private fun flush() {
        val e = entry ?: return
        val played = playedUnflushed; val buffered = rebufferUnflushed
        playedUnflushed = 0; rebufferUnflushed = 0; lastFlush = SystemClock.elapsedRealtime()
        if (played > 0 || buffered > 0) store.record(e.url) { it.copy(playedMs = it.playedMs + played, rebufferMs = it.rebufferMs + buffered) }
    }

    private fun finishSource(failed: Boolean = false) {
        sniffCall?.cancel(); sniffCall = null
        pendingReconnect?.let { handler.removeCallbacks(it) }; pendingReconnect = null
        flush()
        entry?.let { e ->
            if (!failed) store.record(e.url) { s ->
                if (!firstFrame && s.lastOutcome == "connecting") s.copy(cancelled = s.cancelled + 1, lastOutcome = "cancelled")
                else s.copy(lastOutcome = "stopped")
            }
        }
        entry = null; playedUnflushed = 0; rebufferUnflushed = 0
    }

    private fun publishStreamSpec() {
        val active = entry ?: return
        val fmt = lastFormat
        streamSpec = StreamSpec(
            width = fmt?.width?.coerceAtLeast(0) ?: 0,
            height = fmt?.height?.coerceAtLeast(0) ?: 0,
            videoMime = fmt?.sampleMimeType.orEmpty(),
            frameRate = fmt?.frameRate?.takeIf { it > 0f } ?: 0f,
            audioMime = lastAudioMime,
            routeLabel = SourceRoutes.label(active),
            firstFrameMs = lastFirstFrameMs
        )
        callback.onStreamSpecChanged(streamSpec)
    }

    private fun warmNext() {
        val channels = BuiltinPolicy.withMissingChannels(store.snapshot().channels(), codecProfile.supports4kHevc50).filter { it.sources.isNotEmpty() }
        val index = channels.indexOfFirst { it.id == current?.id }
        if (channels.size < 2 || index < 0) return
        val next = channels[(index + 1) % channels.size]
        val source = SourceRoutes.rank(
            next.sources,
            store.snapshot().stats,
            System.currentTimeMillis(),
            effectiveRoutingMode(),
            ipv6Available = hasGlobalIpv6()
        ).firstOrNull() ?: return
        warmCall?.cancel()
        warmCall = network.warm(source)
    }

    fun startProbe(channels: List<Channel>) {
        val items = channels.flatMap { c -> c.sources.distinctBy { it.url }.map { c to it } }
        if (items.isEmpty()) { callback.onProbeFinished(); return }
        cancelPending()
        startupSourceCheck = null
        beforeProbeId = current?.id
        finishSource()
        warmCall?.cancel()
        probeItems = items
        probeIndex = 0
        player.volume = 0f
        playProbe()
    }
    private fun playProbe() {
        if (probeIndex !in probeItems.indices) { stopProbe(true); return }
        val (channel, source) = probeItems[probeIndex]
        current = channel
        exhausted = false
        requestedAt = SystemClock.elapsedRealtime()
        callback.onProbeProgress(probeIndex + 1, probeItems.size, channel.name + " · 检测中")
        startSource(source)
    }
    private fun nextProbe() { finishSource(); probeIndex++; playProbe() }
    fun stopProbe(restore: Boolean = true) {
        if (!probing) return
        finishSource()
        player.stop()
        probeIndex = -1
        player.volume = 1f
        callback.onProbeFinished()
        if (restore) {
            BuiltinPolicy.withMissingChannels(store.snapshot().channels(), codecProfile.supports4kHevc50)
                .firstOrNull { it.id == beforeProbeId && it.sources.isNotEmpty() }?.let { tune(it) }
        }
    }

    fun stop() {
        cancelPending()
        startupSourceCheck = null
        if (probing) stopProbe(false)
        finishSource()
        player.stop()
        current = null
        exhausted = false
        streamSpec = StreamSpec()
        callback.onStreamSpecChanged(streamSpec)
    }
    fun close() {
        if (closed) return
        closed = true
        cancelPending()
        handler.removeCallbacksAndMessages(null)
        warmCall?.cancel()
        finishSource()
        runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback) }
        view.player = null
        player.release()
    }
    private fun classify(error: PlaybackException): String = PlaybackErrors.describe(error)
    private fun supportsHardware(format: Format): Boolean =
        HardwareCodecProbe.supportsHardwareFormat(format.sampleMimeType, format.width, format.height, format.frameRate)

    init {
        view.player = player
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                val audioGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                if (audioGroup != null) {
                    for (i in 0 until audioGroup.length) if (audioGroup.isTrackSelected(i)) {
                        lastAudioMime = audioGroup.getTrackFormat(i).sampleMimeType.orEmpty()
                    }
                }
                if (video != null) {
                    for (i in 0 until video.length) if (video.isTrackSelected(i)) {
                        val format = video.getTrackFormat(i)
                        lastFormat = format
                        if ((format.width > 1920 || format.height > 1080) && !supportsHardware(format)) {
                            fail("此电视没有适配这条 4K 线路的硬件解码器")
                            return
                        }
                    }
                }
                publishStreamSpec()
                if (audioGroup == null && video != null) callback.onStatus(current, "此线路没有可用音轨")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (closed || entry == null || pendingReconnect != null) return
                val now = SystemClock.elapsedRealtime()
                if (state == Player.STATE_BUFFERING) {
                    if (bufferingAt == 0L) bufferingAt = now
                    callback.onStatus(current, if (firstFrame) "正在缓冲…" else "正在连接线路…")
                } else if (state == Player.STATE_READY) {
                    bufferingAt = 0
                    if (firstFrame) callback.onStatus(current, "正在播放")
                } else if (state == Player.STATE_ENDED) {
                    recoverOrFail(null, "直播已停止")
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                if (closed || entry == null) return
                val timeline = eventTime.timeline
                val eventId = if (!timeline.isEmpty && eventTime.windowIndex in 0 until timeline.windowCount)
                    timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId else player.currentMediaItem?.mediaId
                if (eventId == switchId) recoverOrFail(error, classify(error))
            }
            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                if (closed || entry == null || eventTime.timeline.isEmpty) return
                if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return
                if (eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId != switchId) return
                val e = entry ?: return
                store.record(e.url) { it.copy(audioStarted = true) }
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (closed || firstFrame || entry == null || eventTime.timeline.isEmpty) return
                val window = Timeline.Window()
                if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return
                if (eventTime.timeline.getWindow(eventTime.windowIndex, window).mediaItem.mediaId != switchId) return
                firstFrame = true
                probeFirstFrameAt = SystemClock.elapsedRealtime()
                val ms = probeFirstFrameAt - requestedAt
                val sourceMs = probeFirstFrameAt - sourceStartedAt
                lastFirstFrameMs = ms
                val e = entry ?: return
                if (!probing) {
                    startupSourceCheck = null
                    val networkId = activeNetworkId()
                    if (networkId != null && networkId == attemptNetwork) automaticRouting.rendered(networkId, SourceRoutes.isHenan(e.url))
                }
                store.record(e.url) { s -> s.copy(successes = s.successes + 1,
                    firstFrameMs = if (s.firstFrameMs == 0L) sourceMs else (s.firstFrameMs * 2 + sourceMs) / 3,
                    checkedAt = System.currentTimeMillis(), error = "", cooldownUntil = 0, lastOutcome = "playing",
                    width = lastFormat?.width ?: s.width, height = lastFormat?.height ?: s.height,
                    codec = lastFormat?.sampleMimeType ?: s.codec) }
                publishStreamSpec()
                current?.let { callback.onFirstFrame(it, ms) }
                callback.onStatus(current, "正在播放")
                Log.i("SwiftTVMetric", "first_frame channel=" + current?.id + " ms=" + ms +
                    " source=" + sourceId(e.url) + " switch=" + switchId + " probe=" + probing)
                if (!probing) warmNext()
            }
        })
        onInternetRestored = { current?.let { tune(it, explicitRetry = true, diagnoseStartup = startupSourceCheck != null) } }
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.registerNetworkCallback(android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
        }
        handler.post(tick)
    }
}
