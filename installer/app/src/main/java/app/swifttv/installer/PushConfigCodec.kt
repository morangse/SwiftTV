package app.swifttv.installer

import java.net.URI

/** Encodes push configurations into URL-safe Base64 payloads for TV intent extras. */
object PushConfigCodec {
    const val EXTRA_PUSH_B64 = "swifttv_push_b64"
    private const val MAX_B64_LEN = 16_384
    private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun httpUri(raw: String): URI {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty() && !trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            "请输入有效的 HTTP/HTTPS 地址"
        }
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        require((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
            "仅支持 http:// 或 https:// 地址"
        }
        return uri
    }

    fun encode(
        url: String,
        name: String = "",
        epgUrl: String = "",
        localSnapshot: Boolean = false
    ): String {
        val cleanUrl = url.trim()
        httpUri(cleanUrl)
        val cleanEpg = epgUrl.trim()
        if (cleanEpg.isNotEmpty()) httpUri(cleanEpg)
        val cleanName = name.trim().take(80)
        val json = buildString {
            append("{\"url\":").append(jsonQuote(cleanUrl))
            append(",\"name\":").append(jsonQuote(cleanName))
            append(",\"epgUrl\":").append(jsonQuote(cleanEpg))
            append(",\"localSnapshot\":").append(if (localSnapshot) "true" else "false")
            append('}')
        }
        val encoded = base64UrlEncode(json.toByteArray(Charsets.UTF_8))
        require(encoded.length <= MAX_B64_LEN) { "推送配置超出长度限制" }
        return encoded
    }

    internal fun jsonQuote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    internal fun base64UrlEncode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val hasB1 = i + 1 < bytes.size
            val hasB2 = i + 2 < bytes.size
            val b1 = if (hasB1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) bytes[i + 2].toInt() and 0xFF else 0
            out.append(URL_ALPHABET[b0 ushr 2])
            out.append(URL_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (hasB1) out.append(URL_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            if (hasB2) out.append(URL_ALPHABET[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }
}
