package app.swifttv.installer

import app.swifttv.installer.net.OneShotFileServer
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class PushConfigCodecTest {
    @Test fun encodeProducesUrlSafeBase64AndValidatesSchemes() {
        val b64 = PushConfigCodec.encode(
            url = "https://example.com/playlist.m3u8",
            name = "测试清单",
            epgUrl = "http://192.168.1.10:8080/epg.xml",
            localSnapshot = false
        )
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(b64))
        assertTrue(runCatching { PushConfigCodec.encode("ftp://example.com/a.m3u") }.isFailure)
        assertTrue(runCatching { PushConfigCodec.encode("https://example.com/a.m3u", epgUrl = "javascript:alert(1)") }.isFailure)
    }

    @Test fun oneShotFileServerServesPayloadOnceAndCloses() {
        val payload = "#EXTM3U\n#EXTINF:-1,CCTV-1\nhttps://example.com/1.m3u8\n".toByteArray()
        val server = OneShotFileServer("127.0.0.1", payload, extension = "m3u8")
        try {
            val badConn = URL("http://127.0.0.1:${server.port}/wrong/token").openConnection() as HttpURLConnection
            assertEquals(404, badConn.responseCode)
            badConn.disconnect()

            val okConn = URL(server.url).openConnection() as HttpURLConnection
            assertEquals(200, okConn.responseCode)
            assertArrayEquals(payload, okConn.inputStream.readBytes())
            okConn.disconnect()
            assertTrue(server.awaitDelivered(2_000L))
        } finally {
            server.close()
        }
    }
}
