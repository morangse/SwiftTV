package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class PushPayloadTest {
    @Test fun encodeDecodeRoundTripsValidConfig() {
        val original = PushConfig(
            url = "https://example.com/list.m3u?token=abc_123",
            name = "自定义源",
            epgUrl = "https://example.com/epg.xml.gz",
            localSnapshot = true
        )
        val b64 = PushPayload.encode(original)
        assertFalse(b64.contains('+'))
        assertFalse(b64.contains('/'))
        assertFalse(b64.contains('='))
        assertEquals(original, PushPayload.decode(b64))
    }

    @Test fun decodeRejectsNonHttpSchemesAndGarbage() {
        assertNull(PushPayload.decode(null))
        assertNull(PushPayload.decode(""))
        assertNull(PushPayload.decode("not-valid-base64!!!"))
        val filePayload = PushPayload.base64UrlEncode("""{"url":"file:///sdcard/a.m3u"}""".toByteArray())
        assertNull(PushPayload.decode(filePayload))
        val badEpg = PushPayload.base64UrlEncode("""{"url":"https://a.example/1.m3u","epgUrl":"javascript:alert(1)"}""".toByteArray())
        assertNull(PushPayload.decode(badEpg))
    }
}
