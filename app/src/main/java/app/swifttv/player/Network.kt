package app.swifttv.player

import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

data class Download(val text: String, val etag: String, val lastModified: String, val notModified: Boolean)
data class ImportDownload(val text: String?, val directStreamMime: String?, val etag: String, val lastModified: String)
data class ImportErrorInfo(val message: String, val relayEligible: Boolean)

class Network(cacheDir: File?, tlsConfig: TlsConfig? = runCatching { TlsTrust.create() }.getOrNull()) {
    // A live MPEG-TS/FLV body can remain open for hours. Its total duration is not a timeout;
    // readTimeout still bounds a stalled socket, and the player bounds first-frame waits.
    val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
        .apply { if (tlsConfig != null) sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager) }
        .build()

    // Bounded catalogue/sniff/prefetch/EPG calls share the connection pool and keep their own cache.
    private val requestClient = client.newBuilder().callTimeout(20, TimeUnit.SECONDS)
        .apply { if (cacheDir != null) cache(Cache(File(cacheDir, "http"), 8L * 1024 * 1024)) }
        .build()

    // Fast parallel-race client for Happy Eyeballs candidate selection.
    private val probeClient = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    fun download(url: String, etag: String = "", lastModified: String = ""): Download {
        httpUri(url)
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply {
                if (etag.isNotEmpty()) header("If-None-Match", etag)
                if (lastModified.isNotEmpty()) header("If-Modified-Since", lastModified)
            }.build()
        requestClient.newCall(request).execute().use { response ->
            if (response.code == 304) return Download("", etag, lastModified, true)
            require(response.isSuccessful) { "订阅服务器返回 HTTP " + response.code }
            val body = response.body ?: error("订阅没有返回内容")
            require(body.contentLength() <= M3uParser.MAX_BYTES) { "频道表不能超过 2 MB" }
            val bytes = body.byteStream().use { it.readBytesBounded(M3uParser.MAX_BYTES) }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            return Download(text, response.header("ETag").orEmpty(), response.header("Last-Modified").orEmpty(), false)
        }
    }

    /**
     * Downloads an import target while sniffing initial bytes so direct continuous streams (.ts/.flv)
     * are recognized without attempting to download an unbounded live video body.
     */
    fun downloadForImport(url: String): ImportDownload {
        httpUri(url)
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        requestClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "地址返回 HTTP " + response.code }
            val body = response.body ?: error("服务器没有返回内容")
            val etag = response.header("ETag").orEmpty()
            val lastModified = response.header("Last-Modified").orEmpty()
            body.byteStream().use { input ->
                val head = ByteArray(2048)
                var headLen = 0
                while (headLen < head.size) {
                    val n = input.read(head, headLen, head.size - headLen)
                    if (n < 0) break
                    headLen += n
                    val detected = streamMime(head, headLen)
                    if (detected != null && detected != "application/x-mpegURL") {
                        return ImportDownload(null, detected, etag, lastModified)
                    }
                    if (detected == "application/x-mpegURL") break
                }
                require(body.contentLength() <= M3uParser.MAX_BYTES) { "频道表不能超过 2 MB" }
                val out = ByteArrayOutputStream(maxOf(headLen, 4096))
                out.write(head, 0, headLen)
                val buf = ByteArray(8192)
                var total = headLen
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= M3uParser.MAX_BYTES) { "频道表不能超过 2 MB" }
                    out.write(buf, 0, n)
                }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()
                return ImportDownload(text, null, etag, lastModified)
            }
        }
    }

    /** Downloads a bounded channel logo image (<= 512 KiB), with automatic jsDelivr mirror fallback for GitHub URLs. */
    fun downloadLogoBytes(url: String, maxBytes: Int = 512 * 1024): ByteArray {
        val candidates = logoCandidates(url)
        var lastError: Exception? = null
        for (target in candidates) {
            try {
                httpUri(target)
                val req = Request.Builder().url(target).header("User-Agent", USER_AGENT).build()
                requestClient.newCall(req).execute().use { resp ->
                    require(resp.isSuccessful) { "Logo HTTP " + resp.code }
                    val body = resp.body ?: error("Empty logo")
                    require(body.contentLength() <= maxBytes) { "Logo too large" }
                    return body.byteStream().use { it.readBytesBounded(maxBytes) }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Unable to fetch logo")
    }

    /** Stream-processes an HTTP(S) XML or gzipped XML payload under strict compressed/uncompressed bounds. */
    fun <T> streamXml(url: String, maxCompressedBytes: Int = 8 * 1024 * 1024, maxUncompressedBytes: Int = 24 * 1024 * 1024, consumer: (InputStream) -> T): T {
        val uri = httpUri(url)
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "gzip").build()
        requestClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "节目单服务器返回 HTTP " + response.code }
            val body = response.body ?: error("节目单没有返回内容")
            require(body.contentLength() <= maxCompressedBytes) { "节目单文件过大" }
            val boundedRaw = BoundedInputStream(body.byteStream(), maxCompressedBytes, "节目单下载体积超限")
            val buffered = java.io.BufferedInputStream(boundedRaw, 8192)
            buffered.mark(4)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()
            val isGzipMagic = (b0 == 0x1f && b1 == 0x8b) || uri.path.orEmpty().endsWith(".gz", true)
            val uncompressed: InputStream = if (isGzipMagic && b0 == 0x1f && b1 == 0x8b) {
                BoundedInputStream(GZIPInputStream(buffered, 8192), maxUncompressedBytes, "节目单解压体积超限")
            } else {
                BoundedInputStream(buffered, maxUncompressedBytes, "节目单内容超限")
            }
            return uncompressed.use(consumer)
        }
    }

    fun sniff(entry: Entry, callback: (Result<String>) -> Unit): Call {
        val call = requestClient.newCall(Request.Builder().url(entry.url).header("User-Agent", USER_AGENT).apply {
            entry.headers.forEach { (k, v) -> header(k, v) }
        }.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        require(it.isSuccessful) { "线路服务器返回 HTTP " + it.code }
                        val body = it.body ?: error("线路没有返回数据")
                        val buffer = ByteArray(1024)
                        var offset = 0
                        var mime: String? = null
                        body.byteStream().use { input ->
                            while (offset < buffer.size && mime == null) {
                                val n = input.read(buffer, offset, buffer.size - offset)
                                if (n < 0) break
                                offset += n
                                mime = streamMime(buffer, offset)
                            }
                        }
                        mime ?: throw IOException("线路返回的内容不是 HLS 清单、MPEG-TS 或 FLV 视频")
                    }
                }
                callback(result)
            }
        })
        return call
    }

    /**
     * Concurrently probes up to [candidates] using bounded 2.5s calls and returns the first candidate
     * delivering HTTP 2xx + valid media stream header (#EXTM3U / TS / FLV / fMP4), cancelling all others.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun raceProbe(
        candidates: List<Entry>,
        onComplete: (winner: Entry?, failedErrors: Map<String, String>) -> Unit
    ): () -> Unit {
        if (candidates.isEmpty()) {
            onComplete(null, emptyMap())
            return {}
        }
        val settled = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val remaining = AtomicInteger(candidates.size)
        val failed = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())

        fun cancelAll() {
            if (cancelled.compareAndSet(false, true)) {
                synchronized(calls) { calls.forEach { runCatching { it.cancel() } } }
            }
        }

        fun finishFailure(url: String, error: String) {
            failed[url] = error
            if (remaining.decrementAndGet() == 0 && !cancelled.get() && settled.compareAndSet(false, true)) {
                onComplete(null, synchronized(failed) { failed.toMap() })
            }
        }

        for (candidate in candidates) {
            val call = probeClient.newCall(Request.Builder().url(candidate.url).header("User-Agent", USER_AGENT).apply {
                candidate.headers.forEach { (k, v) -> header(k, v) }
            }.build())
            calls += call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (settled.get() || cancelled.get()) return
                    finishFailure(candidate.url, PlaybackErrors.describe(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    var valid = false
                    var errMessage = ""
                    try {
                        response.use { resp ->
                            if (!resp.isSuccessful) {
                                errMessage = "HTTP " + resp.code
                            } else {
                                val body = resp.body
                                if (body == null) {
                                    errMessage = "线路没有返回数据"
                                } else {
                                    val buffer = ByteArray(4096)
                                    var offset = 0
                                    var mime: String? = null
                                    body.byteStream().use { input ->
                                        while (offset < buffer.size && mime == null) {
                                            if (settled.get() || cancelled.get()) break
                                            val n = input.read(buffer, offset, buffer.size - offset)
                                            if (n < 0) break
                                            offset += n
                                            mime = streamMime(buffer, offset)
                                        }
                                    }
                                    if (mime != null) valid = true else errMessage = "非有效音视频流"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errMessage = PlaybackErrors.describe(e)
                    }
                    if (cancelled.get()) return
                    if (valid) {
                        if (settled.compareAndSet(false, true)) {
                            cancelAll()
                            onComplete(candidate, synchronized(failed) { failed.toMap() })
                        }
                    } else if (!settled.get()) {
                        finishFailure(candidate.url, errMessage.ifBlank { "线路探测失败" })
                    }
                }
            })
        }
        return { cancelAll() }
    }

    /**
     * Warms the TCP/TLS connection for an HLS manifest by reading a bounded playlist body to EOF
     * so OkHttp returns the keep-alive socket cleanly to the shared ConnectionPool.
     * Continuous .ts/.flv streams are never warmed because aborting mid-body discards the socket.
     */
    fun warm(entry: Entry): Call? {
        val path = runCatching { httpUri(entry.url).path.orEmpty() }.getOrDefault("")
        if (path.endsWith(".ts", true) || path.endsWith(".flv", true)) return null
        val call = requestClient.newCall(Request.Builder().url(entry.url).header("User-Agent", USER_AGENT)
            .apply { entry.headers.forEach { (k, v) -> header(k, v) } }.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                runCatching {
                    response.use { resp ->
                        val body = resp.body ?: return@use
                        if (body.contentLength() > 131072) return@use
                        body.byteStream().use { stream ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (total < 131072) {
                                val n = stream.read(buffer, 0, minOf(buffer.size, 131072 - total))
                                if (n < 0) break
                                total += n
                                if (total >= 16 && !String(buffer, 0, minOf(total, 32), Charsets.UTF_8).removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U")) {
                                    break
                                }
                            }
                        }
                    }
                }
            }
        })
        return call
    }

    fun close() {
        probeClient.dispatcher.cancelAll()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        requestClient.cache?.close()
    }

    companion object {
        const val USER_AGENT = "SwiftTV/0.4"

        fun describeImportError(error: Throwable): ImportErrorInfo {
            val causes = mutableListOf<Throwable>()
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cursor: Throwable? = error
            while (cursor != null && causes.size < 16 && seen.add(cursor)) {
                causes += cursor
                cursor = cursor.cause
            }
            if (causes.any { it is CertificateException || it is SSLPeerUnverifiedException || it is SSLHandshakeException }) {
                return ImportErrorInfo(
                    "电视 HTTPS 证书校验失败（请检查电视系统日期时间是否正确，或改用手机代拉取 / 本地文件上传）",
                    relayEligible = true
                )
            }
            if (causes.any { it is UnknownHostException }) {
                return ImportErrorInfo(
                    "电视无法解析该域名（请检查电视 DNS 或网络连接）",
                    relayEligible = true
                )
            }
            if (causes.any { it is SocketTimeoutException }) {
                return ImportErrorInfo(
                    "电视连接订阅地址超时（服务器响应较慢或网络受限）",
                    relayEligible = true
                )
            }
            if (causes.any { it is ConnectException || it is SocketException }) {
                return ImportErrorInfo(
                    "电视无法连接到订阅服务器（连接被拒绝或重置）",
                    relayEligible = true
                )
            }
            if (causes.any { it is IOException }) {
                return ImportErrorInfo(
                    "电视读取订阅失败：" + DiagnosticRedaction.message(error.message ?: "网络异常"),
                    relayEligible = true
                )
            }
            val raw = DiagnosticRedaction.message(error.message ?: "导入失败")
            val relay = raw.startsWith("地址返回 HTTP ") || raw.startsWith("订阅服务器返回 HTTP ")
            return ImportErrorInfo(raw, relayEligible = relay)
        }

        internal fun logoCandidates(url: String): List<String> {
            val trimmed = url.trim()
            val list = mutableListOf(trimmed)
            val ghPages = Regex("^https?://([\\w-]+)\\.github\\.io/([\\w.-]+)/(.*)$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
            if (ghPages != null) {
                val (owner, repo, rest) = ghPages.destructured
                list += "https://cdn.jsdelivr.net/gh/$owner/$repo@main/public/$rest"
                list += "https://cdn.jsdelivr.net/gh/$owner/$repo@main/$rest"
            }
            val ghRaw = Regex("^https?://raw\\.githubusercontent\\.com/([\\w-]+)/([\\w.-]+)/([\\w.-]+)/(.*)$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
            if (ghRaw != null) {
                val (owner, repo, branch, rest) = ghRaw.destructured
                list += "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$rest"
            }
            return list.distinct()
        }

        internal fun streamMime(bytes: ByteArray, size: Int): String? {
            if (size <= 0) return null
            val prefix = String(bytes, 0, minOf(size, 64), Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
            if (prefix.startsWith("#EXTM3U") && (prefix.length == 7 || prefix[7].isWhitespace())) return "application/x-mpegURL"
            // HTTP-FLV magic header: 'F' 'L' 'V' 0x01
            if (size >= 4 && bytes[0] == 0x46.toByte() && bytes[1] == 0x4C.toByte() && bytes[2] == 0x56.toByte() && bytes[3] == 0x01.toByte()) {
                return "video/x-flv"
            }
            // ISO BMFF / fMP4 box header: [4-byte size] 'f' 't' 'y' 'p' or 's' 't' 'y' 'p'
            if (size >= 8 && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte() &&
                (bytes[4] == 0x66.toByte() || bytes[4] == 0x73.toByte())) {
                return "video/mp4"
            }
            // Verify three MPEG-TS sync bytes, allowing initial padding before the first packet.
            for (start in 0 until minOf(188, size)) {
                if (start + 376 >= size) break
                if (bytes[start] == 0x47.toByte() && bytes[start + 188] == 0x47.toByte() && bytes[start + 376] == 0x47.toByte())
                    return "video/mp2t"
            }
            return null
        }
    }
}

private class BoundedInputStream(
    private val delegate: InputStream,
    private val limit: Int,
    private val errorMessage: String
) : InputStream() {
    private var total = 0
    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) {
            total++
            if (total > limit) throw IOException(errorMessage)
        }
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            total += n
            if (total > limit) throw IOException(errorMessage)
        }
        return n
    }
    override fun close() = delegate.close()
}

fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        require(total <= limit) { "请求内容过大" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
