package app.swifttv.installer.net

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Temporary LAN HTTP server serving one local M3U/M3U8 file with a one-time random token. */
class OneShotFileServer(
    hostAddress: String,
    private val payloadBytes: ByteArray,
    extension: String = "m3u",
    timeoutMs: Int = DEFAULT_TIMEOUT_MS
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val deliveredLatch = CountDownLatch(1)
    private val safeExt = if (extension.equals("m3u8", ignoreCase = true)) "m3u8" else "m3u"
    private val expectedPath: String
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(0))
        soTimeout = timeoutMs.coerceIn(5_000, 60_000)
    }

    val port: Int = serverSocket.localPort
    val url: String

    private val thread: Thread

    init {
        require(payloadBytes.isNotEmpty() && payloadBytes.size <= MAX_BYTES) {
            "本地源文件为空或超过 2 MB 上限"
        }
        val token = randomToken()
        expectedPath = "/push/$token/playlist.$safeExt"
        url = "http://$hostAddress:$port$expectedPath"
        thread = Thread({ serveOnce() }, "swifttv-oneshot-push").apply {
            isDaemon = true
            start()
        }
    }

    fun awaitDelivered(timeoutMs: Long): Boolean =
        deliveredLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun serveOnce() {
        val deadlineMs = System.currentTimeMillis() + serverSocket.soTimeout
        try {
            while (!closed.get() && System.currentTimeMillis() < deadlineMs) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketTimeoutException) {
                    break
                }
                val served = socket.use { client ->
                    handleClient(client)
                }
                if (served) {
                    deliveredLatch.countDown()
                    break
                }
            }
        } catch (_: Throwable) {
            // Closed externally or expired.
        } finally {
            close()
        }
    }

    private fun handleClient(socket: java.net.Socket): Boolean {
        socket.soTimeout = 5_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine()?.trim() ?: return false
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return false
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        val pathMatch = MessageDigest.isEqual(
            path.toByteArray(Charsets.UTF_8),
            expectedPath.toByteArray(Charsets.UTF_8)
        )
        val out = socket.getOutputStream()
        if (!pathMatch || (method != "GET" && method != "HEAD")) {
            val body = "Not Found".toByteArray(Charsets.UTF_8)
            val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1)
            out.write(resp)
            if (method != "HEAD") out.write(body)
            out.flush()
            return false
        }
        val contentType = if (safeExt == "m3u8") {
            "application/vnd.apple.mpegurl; charset=utf-8"
        } else {
            "application/x-mpegurl; charset=utf-8"
        }
        val headers = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${payloadBytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        out.write(headers)
        if (method == "GET") {
            out.write(payloadBytes)
        }
        out.flush()
        return method == "GET"
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { serverSocket.close() }
        }
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val DEFAULT_TIMEOUT_MS = 30_000

        private fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
