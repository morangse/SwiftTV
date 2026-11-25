package app.swifttv.player

import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class HttpReply(val status: Int, val contentType: String = "application/json; charset=utf-8", val body: String)

/** A bounded, ephemeral HTTP receiver. It exposes only fixed import/manage or explicitly requested diagnostic endpoints. */
class ImportServer(
    private val host: String, private val page: String,
    private val importRequest: (JSONObject) -> JSONObject,
    private val commitRequest: (JSONObject) -> JSONObject,
    private val diagnostics: String? = null,
    private val stateRequest: (() -> JSONObject)? = null,
    private val manageRequest: ((JSONObject) -> JSONObject)? = null
) : AutoCloseable {
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 4, InetAddress.getByName("0.0.0.0"))
    val port: Int get() = server.localPort
    val address: String get() = "http://" + host + ":" + port
    val pairingUrl: String get() = address + "/#token=" + token
    private val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    @Volatile private var closed = false
    init {
        server.soTimeout = 1000
        Thread({
            while (!closed && System.nanoTime() < deadline) {
                try {
                    val socket = server.accept()
                    sockets.add(socket)
                    try { workers.execute { handle(socket) } } catch (_: Exception) { sockets.remove(socket); socket.close() }
                } catch (_: java.net.SocketTimeoutException) {} catch (_: SocketException) { break }
            }
            close()
        }, "SwiftTV-pairing").apply { isDaemon = true; start() }
    }

    private fun validToken(headers: Map<String, String>): Boolean {
        val provided = headers["x-swifttv-token"].orEmpty()
        return MessageDigest.isEqual(token.toByteArray(), provided.toByteArray())
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 20000
                val input = BufferedInputStream(socket.getInputStream())
                val header = readHeader(input)
                val lines = header.split("\r\n")
                val request = lines.first().split(' ')
                require(request.size == 3) { "请求格式错误" }
                val headers = lines.drop(1).filter { it.contains(':') }.associate {
                    it.substringBefore(':').lowercase(java.util.Locale.ROOT) to it.substringAfter(':').trim()
                }
                val route = request[1].substringBefore('?')
                val method = request[0]
                val expectedHost = host + ":" + port
                if (headers["host"] != expectedHost) { send(socket, HttpReply(403, body = """{"error":"Host rejected"}""")); return }
                if (closed || System.nanoTime() >= deadline) { send(socket, HttpReply(410, body = """{"error":"配对已结束，请重新扫码"}""")); return }
                if (method == "GET" && route == "/") { send(socket, HttpReply(200, "text/html; charset=utf-8", page)); return }
                if (diagnostics != null && method == "GET" && route == "/api/diagnostics") {
                    if (!validToken(headers)) { send(socket, HttpReply(403, body = """{"error":"Forbidden"}""")); return }
                    send(socket, HttpReply(200, body = diagnostics)); return
                }
                if (diagnostics == null && stateRequest != null && method == "GET" && route == "/api/state") {
                    if (!validToken(headers)) { send(socket, HttpReply(403, body = """{"error":"配对令牌无效，请重新扫码"}""")); return }
                    send(socket, HttpReply(200, body = stateRequest.invoke().toString())); return
                }
                val allowedPostRoutes = if (manageRequest != null) listOf("/api/import", "/api/commit", "/api/manage") else listOf("/api/import", "/api/commit")
                if (diagnostics != null || method != "POST" || route !in allowedPostRoutes) {
                    send(socket, HttpReply(404, body = """{"error":"Not found"}""")); return
                }
                if (!validToken(headers)) {
                    send(socket, HttpReply(403, body = """{"error":"配对令牌无效，请重新扫码"}""")); return
                }
                val origin = headers["origin"]
                if (origin != null && origin != address) { send(socket, HttpReply(403, body = """{"error":"Origin rejected"}""")); return }
                require(headers["transfer-encoding"] == null) { "不支持分块上传" }
                require(headers["content-type"]?.startsWith("application/json") == true) { "请使用配对页面提交" }
                val length = headers["content-length"]?.toIntOrNull() ?: error("缺少请求长度")
                require(length in 1..(M3uParser.MAX_BYTES * 2 + 8192)) { "请求内容过大" }
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) { val n = input.read(bytes, offset, length - offset); require(n > 0) { "上传中断" }; offset += n }
                val data = JSONObject(bytes.toString(Charsets.UTF_8))
                val answer = when (route) {
                    "/api/import" -> importRequest(data)
                    "/api/commit" -> commitRequest(data)
                    else -> manageRequest!!.invoke(data)
                }
                send(socket, HttpReply(200, body = answer.toString()))
            } catch (error: Exception) {
                val info = Network.describeImportError(error)
                val reply = JSONObject().put("error", info.message)
                if (info.relayEligible) reply.put("relayEligible", true)
                runCatching { send(socket, HttpReply(400, body = reply.toString())) }
            } finally { sockets.remove(socket) }
        }
    }

    private fun readHeader(input: BufferedInputStream): String {
        val output = java.io.ByteArrayOutputStream()
        var state = 0
        while (output.size() < 16384) {
            val c = input.read()
            require(c >= 0) { "请求中断" }
            output.write(c)
            state = when {
                state == 0 && c == 13 -> 1
                state == 1 && c == 10 -> 2
                state == 2 && c == 13 -> 3
                state == 3 && c == 10 -> 4
                c == 13 -> 1
                else -> 0
            }
            if (state == 4) return output.toString("US-ASCII").removeSuffix("\r\n\r\n")
        }
        error("请求头过大")
    }

    private fun send(socket: Socket, reply: HttpReply) {
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val status = when (reply.status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 410 -> "Gone"; else -> "Error" }
        val connectSrc = if (diagnostics == null) "connect-src 'self' https: http:" else "connect-src 'self'"
        val header = "HTTP/1.1 " + reply.status + " " + status + "\r\nContent-Type: " + reply.contentType +
            "\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " + connectSrc + "; img-src 'self' data:; frame-ancestors 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } }; sockets.clear() }
        workers.shutdownNow()
    }
}
