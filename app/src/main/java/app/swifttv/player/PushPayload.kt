package app.swifttv.player

import org.json.JSONObject

data class PushConfig(
    val url: String,
    val name: String = "",
    val epgUrl: String = "",
    val localSnapshot: Boolean = false
)

/** Encodes and decodes URL-safe Base64 push payloads passed over local ADB with strict URI validation. */
object PushPayload {
    const val EXTRA_PUSH_B64 = "swifttv_push_b64"
    private const val MAX_B64_LEN = 16_384
    private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(config: PushConfig): String {
        httpUri(config.url)
        val epg = config.epgUrl.trim()
        if (epg.isNotEmpty()) httpUri(epg)
        val json = JSONObject()
            .put("url", config.url.trim())
            .put("name", config.name.trim().take(80))
            .put("epgUrl", epg)
            .put("localSnapshot", config.localSnapshot)
            .toString()
        return base64UrlEncode(json.toByteArray(Charsets.UTF_8))
    }

    fun decode(b64: String?): PushConfig? {
        val raw = b64?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_B64_LEN) return null
        return runCatching {
            val bytes = base64UrlDecode(raw)
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val url = root.getString("url").trim()
            httpUri(url)
            val epgUrl = root.optString("epgUrl").trim()
            if (epgUrl.isNotEmpty()) httpUri(epgUrl)
            PushConfig(
                url = url,
                name = root.optString("name").trim().take(80),
                epgUrl = epgUrl,
                localSnapshot = root.optBoolean("localSnapshot", false)
            )
        }.getOrNull()
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

    internal fun base64UrlDecode(input: String): ByteArray {
        val clean = input.trim().trimEnd('=')
        require(clean.isNotEmpty() && clean.length % 4 != 1) { "Invalid Base64URL length" }
        val table = IntArray(128) { -1 }
        for (i in URL_ALPHABET.indices) table[URL_ALPHABET[i].code] = i
        table['+'.code] = 62
        table['/'.code] = 63
        val out = ByteArray((clean.length * 3) / 4)
        var outIndex = 0
        var i = 0
        while (i < clean.length) {
            val c0 = valueOf(clean[i], table)
            val c1 = valueOf(clean[i + 1], table)
            out[outIndex++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            if (i + 2 < clean.length) {
                val c2 = valueOf(clean[i + 2], table)
                out[outIndex++] = (((c1 and 0x0F) shl 4) or (c2 ushr 2)).toByte()
                if (i + 3 < clean.length) {
                    val c3 = valueOf(clean[i + 3], table)
                    out[outIndex++] = (((c2 and 0x03) shl 6) or c3).toByte()
                }
            }
            i += 4
        }
        return if (outIndex == out.size) out else out.copyOf(outIndex)
    }

    private fun valueOf(c: Char, table: IntArray): Int {
        val code = c.code
        require(code in 0..127 && table[code] >= 0) { "Invalid Base64URL char" }
        return table[code]
    }
}
