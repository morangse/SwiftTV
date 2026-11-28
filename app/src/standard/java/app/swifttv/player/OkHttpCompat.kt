package app.swifttv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

internal object OkHttpCompat {
    fun code(response: Response): Int = response.code
    fun body(response: Response): ResponseBody? = response.body
    fun dispatcher(client: OkHttpClient): Dispatcher = client.dispatcher
    fun connectionPool(client: OkHttpClient): ConnectionPool = client.connectionPool
    fun cache(client: OkHttpClient): Cache? = client.cache
    fun jsonBody(json: String): RequestBody = json.toRequestBody("application/json".toMediaType())
    fun shutdown(client: OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @UnstableApi
    fun mediaHttpDataSourceFactory(
        client: OkHttpClient,
        userAgent: String,
        headers: Map<String, String>
    ): HttpDataSource.Factory = OkHttpDataSource.Factory(client)
        .setUserAgent(userAgent)
        .setDefaultRequestProperties(headers)
}
