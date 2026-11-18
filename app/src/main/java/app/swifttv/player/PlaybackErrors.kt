package app.swifttv.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Stable diagnostic categories. Never copy response bodies, headers, URLs or credentials. */
@UnstableApi
object PlaybackErrors {
    fun describe(error: Throwable): String {
        val causes = mutableListOf<Throwable>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cursor: Throwable? = error
        while (cursor != null && causes.size < 16 && seen.add(cursor)) {
            causes += cursor
            cursor = cursor.cause
        }
        causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
            return "HTTP " + it.responseCode + "：" + when (it.responseCode) {
                401, 403 -> "服务器拒绝访问或需要授权"
                404, 410 -> "线路不存在或已失效"
                429 -> "线路服务器请求过多"
                in 500..599 -> "线路服务器故障"
                else -> "线路服务器返回异常"
            }
        }
        if (causes.any { it is CertificateException || it is SSLPeerUnverifiedException })
            return "TLS 证书校验失败（请检查电视时间或线路证书）"
        if (causes.any { it is SSLHandshakeException }) return "TLS 握手失败（线路证书或加密协议异常）"
        if (causes.any { it is UnknownHostException }) return "线路域名解析失败"
        if (causes.any { it is SocketTimeoutException }) return "线路连接或读取超时"
        if (causes.any { it is SocketException && it.message.orEmpty().contains("reset", true) })
            return "连接被线路服务器重置"
        if (causes.any { it is ConnectException }) return "无法连接线路服务器"
        val playback = causes.filterIsInstance<PlaybackException>().firstOrNull()
        return when (playback?.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "无法连接线路服务器"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT -> "线路连接或读取超时"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器返回 HTTP 错误（未提供状态码）"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "线路服务器返回的内容不是视频"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "系统禁止此线路的明文 HTTP 连接"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "电视不支持此线路的解码格式"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "线路视频解码失败"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "直播数据格式错误或不受支持"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "播放位置已离开直播窗口"
            else -> if (playback != null) "播放失败（" + playback.errorCodeName + "）" else "无法读取线路数据"
        }
    }
}