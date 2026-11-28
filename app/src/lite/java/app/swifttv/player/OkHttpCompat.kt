package app.swifttv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

internal object OkHttpCompat {
    fun code(response: Response): Int = response.code()
    fun body(response: Response): ResponseBody? = response.body()
    fun dispatcher(client: OkHttpClient): Dispatcher = client.dispatcher()
    fun connectionPool(client: OkHttpClient): ConnectionPool = client.connectionPool()
    fun cache(client: OkHttpClient): Cache? = client.cache()
    fun jsonBody(json: String): RequestBody = RequestBody.create(MediaType.parse("application/json"), json)
    fun shutdown(client: OkHttpClient) {
        client.dispatcher().executorService().shutdown()
        client.connectionPool().evictAll()
    }

    @UnstableApi
    fun mediaHttpDataSourceFactory(
        client: OkHttpClient,
        userAgent: String,
        headers: Map<String, String>
    ): HttpDataSource.Factory = OkHttp3DataSourceFactory(client, userAgent).setDefaultRequestProperties(headers)
}

@UnstableApi
private class OkHttp3DataSourceFactory(
    private val client: OkHttpClient,
    private val userAgent: String
) : HttpDataSource.BaseFactory() {
    override fun createDataSourceInternal(
        defaultRequestProperties: HttpDataSource.RequestProperties
    ): HttpDataSource = OkHttp3DataSource(client, userAgent, defaultRequestProperties)
}

/**
 * Minimal Media3 1.4.1 HttpDataSource backed by OkHttp 3.12.13 so the Lite edition
 * shares SwiftTV's API-19 TLS 1.2 + bundled root CA client cleanly on Android 4.4+.
 */
@UnstableApi
private class OkHttp3DataSource(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val defaultRequestProperties: HttpDataSource.RequestProperties?
) : BaseDataSource(/* isNetwork = */ true), HttpDataSource {

    private val requestProperties = HttpDataSource.RequestProperties()
    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseStream: InputStream? = null
    private var opened = false
    private var bytesToSkip = 0L
    private var bytesToRead = 0L
    private var bytesRead = 0L

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun getResponseCode(): Int = response?.code() ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers()?.toMultimap() ?: emptyMap()

    override fun getUri(): Uri? = response?.request()?.url()?.toString()?.let(Uri::parse) ?: dataSpec?.uri

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0L
        bytesToSkip = 0L
        transferInitializing(dataSpec)

        val request = buildRequest(dataSpec)
        val executed = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        response = executed
        val body = executed.body()
        responseStream = body?.byteStream()
        val code = executed.code()

        if (!executed.isSuccessful) {
            if (code == 416) {
                val docSize = HttpUtil.getDocumentSize(executed.header("Content-Range"))
                if (dataSpec.position == docSize) {
                    opened = true
                    transferStarted(dataSpec)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
                }
            }
            val errorBytes = readBoundedErrorBody(responseStream)
            val headersMap = executed.headers().toMultimap()
            val message = executed.message()
            closeConnectionQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                message,
                null,
                headersMap,
                dataSpec,
                errorBytes
            )
        }

        bytesToSkip = if (code == 200 && dataSpec.position != 0L) dataSpec.position else 0L
        val contentLength = body?.contentLength() ?: C.LENGTH_UNSET.toLong()
        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (contentLength != C.LENGTH_UNSET.toLong()) {
            (contentLength - bytesToSkip).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return try {
            if (bytesToSkip > 0L) skipInternal()
            if (length == 0) return 0
            if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                val remaining = bytesToRead - bytesRead
                if (remaining <= 0L) return C.RESULT_END_OF_INPUT
                val capped = minOf(length.toLong(), remaining).toInt()
                val n = responseStream?.read(buffer, offset, capped) ?: -1
                if (n < 0) return C.RESULT_END_OF_INPUT
                bytesRead += n
                bytesTransferred(n)
                n
            } else {
                val n = responseStream?.read(buffer, offset, length) ?: -1
                if (n < 0) return C.RESULT_END_OF_INPUT
                bytesRead += n
                bytesTransferred(n)
                n
            }
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec ?: DataSpec(Uri.EMPTY),
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        closeConnectionQuietly()
        dataSpec = null
    }

    private fun buildRequest(dataSpec: DataSpec): Request {
        val url = HttpUrl.parse(dataSpec.uri.toString())
            ?: throw HttpDataSource.HttpDataSourceException(
                "Malformed URL",
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        val mergedHeaders = LinkedHashMap<String, String>()
        defaultRequestProperties?.snapshot?.let { mergedHeaders.putAll(it) }
        mergedHeaders.putAll(requestProperties.snapshot)
        mergedHeaders.putAll(dataSpec.httpRequestHeaders)

        val builder = Request.Builder().url(url)
        mergedHeaders.forEach { (k, v) -> builder.header(k, v) }
        if (mergedHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) } && userAgent.isNotEmpty()) {
            builder.header("User-Agent", userAgent)
        }
        HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length)?.let { range ->
            builder.header("Range", range)
        }
        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            builder.header("Accept-Encoding", "identity")
        }
        val body = when (dataSpec.httpMethod) {
            DataSpec.HTTP_METHOD_POST -> RequestBody.create(null, dataSpec.httpBody ?: ByteArray(0))
            else -> null
        }
        builder.method(dataSpec.httpMethodString, body)
        return builder.build()
    }

    private fun skipInternal() {
        val skipBuf = ByteArray(4096)
        val stream = responseStream ?: return
        while (bytesToSkip > 0L) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val n = stream.read(skipBuf, 0, minOf(skipBuf.size.toLong(), bytesToSkip).toInt())
            if (n < 0) {
                throw HttpDataSource.HttpDataSourceException(
                    dataSpec ?: DataSpec(Uri.EMPTY),
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            bytesToSkip -= n
            bytesTransferred(n)
        }
    }

    private fun readBoundedErrorBody(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        return runCatching {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            var total = 0
            while (total < 4096) {
                val n = stream.read(buf, 0, minOf(buf.size, 4096 - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toByteArray()
        }.getOrElse { ByteArray(0) }
    }

    private fun closeConnectionQuietly() {
        runCatching { responseStream?.close() }
        runCatching { response?.close() }
        responseStream = null
        response = null
    }
}
