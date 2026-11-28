package app.swifttv.player

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class NetworkTest {
    @Test fun handlesConditionalUpdatesWithoutEmptyReplacement() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            server.enqueue(MockResponse().setResponseCode(304))
            val result = network.download(server.url("/list").toString(), "\"v1\"", "Yesterday")
            assertTrue(result.notModified)
            val request = server.takeRequest()
            assertEquals("\"v1\"", request.getHeader("If-None-Match"))
            assertEquals("Yesterday", request.getHeader("If-Modified-Since"))
        } finally { network.close(); server.shutdown() }
    }
    @Test fun rejectsHttpErrorsAndMalformedUtf8() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("<html>Forbidden</html>"))
            assertTrue(runCatching { network.download(server.url("/403").toString()) }.isFailure)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))))
            assertTrue(runCatching { network.download(server.url("/invalid-utf8").toString()) }.isFailure)
        } finally { network.close(); server.shutdown() }
    }
    @Test fun rejectsOversizedResponseBeforeParsing() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            server.enqueue(MockResponse().setHeader("Content-Length", M3uParser.MAX_BYTES + 1).setBody("too large"))
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", M3uParser.MAX_BYTES + 1))
            network.download(server.url("/small").toString())
            assertTrue(runCatching { network.download(server.url("/large").toString()) }.isFailure)
        } finally { network.close(); server.shutdown() }
    }
    @Test fun recognizesHlsAtAnExtensionlessAddress() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        var mime: String? = null
        try {
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:5\n"))
            network.sniff(Entry("one", "One", "Test", server.url("/live/id").toString())) {
                mime = it.getOrNull(); latch.countDown()
            }
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("application/x-mpegURL", mime)
        } finally { network.close(); server.shutdown() }
    }
    @Test fun recognizesHttpFlvAndFragmentedMp4WithoutTrustingContentType() {
        val flv = byteArrayOf(0x46, 0x4C, 0x56, 0x01, 0x05, 0x00, 0x00, 0x00, 0x09)
        assertEquals("video/x-flv", Network.streamMime(flv, flv.size))
        val fmp4 = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)
        assertEquals("video/mp4", Network.streamMime(fmp4, fmp4.size))
    }
    @Test fun warmSkipsContinuousTransportStreamsAndReadsBoundedHlsManifests() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            assertNull(network.warm(Entry("ts", "TS", "Test", server.url("/live/stream.ts").toString())))
            assertNull(network.warm(Entry("flv", "FLV", "Test", server.url("/live/stream.flv").toString())))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\nsegment0.ts\n"))
            val call = network.warm(Entry("hls", "HLS", "Test", server.url("/live/index.m3u8").toString()))
            assertNotNull(call)
            val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(recorded)
        } finally { network.close(); server.shutdown() }
    }
    @Test fun streamXmlDecompressesGzipAndEnforcesUncompressedBounds() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            val xml = "<tv><channel id=\"1\"><display-name>CCTV-1</display-name></channel></tv>"
            val gzBytes = ByteArrayOutputStream().also { bos ->
                GZIPOutputStream(bos).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            }.toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(gzBytes)))
            val decoded = network.streamXml(server.url("/epg.xml.gz").toString()) { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals(xml, decoded)

            server.enqueue(MockResponse().setBody(okio.Buffer().write(gzBytes)))
            assertTrue(runCatching {
                network.streamXml(server.url("/bomb.xml.gz").toString(), maxUncompressedBytes = 16) { it.readBytes() }
            }.isFailure)
        } finally { network.close(); server.shutdown() }
    }
    @Test fun followsPlaylistRedirects() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,One\nhttps://example.com/a\n").setHeader("ETag", "fresh"))
            val result = network.download(server.url("/redirect").toString())
            assertEquals("fresh", result.etag); assertEquals(1, M3uParser.parse(result.text).entries.size)
        } finally { network.close(); server.shutdown() }
    }
    @Test fun continuousPlaybackRequestSurvivesPastTwentySeconds() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        val expected = 22 * 1024
        try {
            server.enqueue(MockResponse().setBody("x".repeat(expected))
                .throttleBody(1024, 1, java.util.concurrent.TimeUnit.SECONDS))
            network.client.newCall(okhttp3.Request.Builder().url(server.url("/live.ts")).build()).execute().use {
                assertEquals(expected, OkHttpCompat.body(it)!!.bytes().size)
            }
        } finally { network.close(); server.shutdown() }
    }
    @Test fun rejectsHtmlMasqueradingAsAnExtensionlessStream() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Result<String>? = null
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<html><body>Access denied</body></html>"))
            network.sniff(Entry("one", "One", "Test", server.url("/channel").toString())) {
                result = it; latch.countDown()
            }
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(result!!.isFailure)
            assertTrue(result!!.exceptionOrNull()!!.message!!.contains("不是 HLS"))
        } finally { network.close(); server.shutdown() }
    }
    @Test fun recognizesTransportStreamPacketSyncWithoutTrustingContentType() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        var mime: String? = null
        try {
            val packets = ByteArray(5 + 188 * 3)
            for (packet in 0..2) packets[5 + 188 * packet] = 0x47
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setBody(okio.Buffer().write(packets)))
            network.sniff(Entry("one", "One", "Test", server.url("/channel").toString())) {
                mime = it.getOrNull(); latch.countDown()
            }
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("video/mp2t", mime)
        } finally { network.close(); server.shutdown() }
    }
    @Test fun sniffPreservesHttpStatusAndHasABoundedCallDeadline() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
            val call = network.sniff(Entry("one", "One", "Test", server.url("/denied").toString())) {
                failure = it.exceptionOrNull(); latch.countDown()
            }
            assertEquals(java.util.concurrent.TimeUnit.SECONDS.toNanos(20), call.timeout().timeoutNanos())
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failure!!.message!!.contains("HTTP 403"))
        } finally { network.close(); server.shutdown() }
    }
    @Test fun playbackNeverReusesCachedBodiesAtRecycledLiveUrls() {
        val server = MockWebServer(); server.start()
        val cacheDir = java.nio.file.Files.createTempDirectory("swifttv-network-test").toFile()
        val network = Network(cacheDir)
        val url = server.url("/recycled.m3u8").toString()
        val cachedPlaylist = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n"
        try {
            server.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=600").setBody(cachedPlaylist))
            assertEquals(cachedPlaylist, network.download(url).text)
            server.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=600").setBody("live sequence 2"))
            server.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=600").setBody("live sequence 3"))
            fun playbackRead(): String = network.client.newCall(okhttp3.Request.Builder().url(url).build())
                .execute().use { OkHttpCompat.body(it)!!.string() }
            assertNull(OkHttpCompat.cache(network.client))
            assertEquals("live sequence 2", playbackRead())
            assertEquals("live sequence 3", playbackRead())
            assertEquals(cachedPlaylist, network.download(url).text)
            assertEquals(3, server.requestCount)
        } finally { network.close(); server.shutdown(); cacheDir.deleteRecursively() }
    }

    @Test fun downloadForImportDetectsPlaylistHlsManifestAndBinaryStream() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,CCTV-1\nhttps://a.example/1.m3u8\n"))
            val playlist = network.downloadForImport(server.url("/list.m3u").toString())
            assertNull(playlist.directStreamMime)
            assertFalse(M3uParser.isHlsManifest(playlist.text!!))

            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n"))
            val hls = network.downloadForImport(server.url("/live.m3u8").toString())
            assertNull(hls.directStreamMime)
            assertTrue(M3uParser.isHlsManifest(hls.text!!))

            val tsPackets = ByteArray(188 * 3).also { buf ->
                for (i in 0..2) buf[i * 188] = 0x47
            }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(tsPackets)))
            val binary = network.downloadForImport(server.url("/stream.ts").toString())
            assertNull(binary.text)
            assertEquals("video/mp2t", binary.directStreamMime)
        } finally { network.close(); server.shutdown() }
    }

    @Test fun raceProbeSelectsFastestHealthyCandidateDespiteErrors() {
        val badServer = MockWebServer(); badServer.start()
        val goodServer = MockWebServer(); goodServer.start()
        val network = Network(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        var picked: Entry? = null
        try {
            badServer.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            goodServer.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\nseg.ts\n"))
            val bad = Entry("c1", "CCTV-1", "央视", badServer.url("/bad.m3u8").toString())
            val good = Entry("c1", "CCTV-1", "央视", goodServer.url("/good.m3u8").toString())
            network.raceProbe(listOf(bad, good)) { entry, _ ->
                picked = entry
                latch.countDown()
            }
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(good, picked)
        } finally { network.close(); badServer.shutdown(); goodServer.shutdown() }
    }

    @Test fun bundledRootCasLoadModernPublicAuthorities() {
        val bundled = TlsTrust.loadBundledCertificates()
        assertTrue(bundled.size >= 18)
        val subjects = bundled.joinToString("\n") { it.subjectX500Principal.name }
        assertTrue(subjects.contains("ISRG Root X1"))
        assertTrue(subjects.contains("DigiCert Global Root G2"))
        assertTrue(subjects.contains("GTS Root R1"))
        assertTrue(subjects.contains("USERTrust RSA Certification Authority"))

        val config = TlsTrust.create(includeSystemDefaults = true)
        val combinedSubjects = config.trustManager.acceptedIssuers.joinToString("\n") { it.subjectX500Principal.name }
        assertTrue(combinedSubjects.contains("ISRG Root X1"))
    }

    @Test fun describeImportErrorClassifiesTlsNetworkAndValidationErrors() {
        val tls = javax.net.ssl.SSLHandshakeException("Chain validation failed").apply {
            initCause(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found."))
        }
        val tlsInfo = Network.describeImportError(tls)
        assertTrue(tlsInfo.relayEligible)
        assertTrue(tlsInfo.message.contains("证书校验失败"))
        assertFalse(tlsInfo.message.contains("CertPathValidatorException"))

        val dnsInfo = Network.describeImportError(java.net.UnknownHostException("private.example"))
        assertTrue(dnsInfo.relayEligible)
        assertTrue(dnsInfo.message.contains("域名"))
        assertFalse(dnsInfo.message.contains("private.example"))

        val timeoutInfo = Network.describeImportError(java.net.SocketTimeoutException("timeout"))
        assertTrue(timeoutInfo.relayEligible)
        assertTrue(timeoutInfo.message.contains("超时"))

        val validationInfo = Network.describeImportError(IllegalArgumentException("不是有效的 M3U 频道表"))
        assertFalse(validationInfo.relayEligible)
        assertEquals("不是有效的 M3U 频道表", validationInfo.message)
    }

    @Test fun warmSkipsNonHttpSchemesAndDownloadDecodesGb18030Playlists() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            assertNull(network.warm(Entry("udp", "UDP", "组播", "udp://239.1.1.1:5000")))
            assertNull(network.warm(Entry("rtp", "RTP", "组播", "rtp://239.1.1.1:5000")))
            assertNull(network.warm(Entry("rtsp", "RTSP", "直播", "rtsp://10.0.0.1/live")))
            assertNull(network.warm(Entry("rtmp", "RTMP", "直播", "rtmp://10.0.0.1/live")))

            val gbkTxt = "央视,#genre#\nCCTV1,http://example.com/1.m3u8\n"
            val bytes = gbkTxt.toByteArray(java.nio.charset.Charset.forName("GB18030"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val downloaded = network.downloadForImport(server.url("/list.txt").toString())
            assertTrue(downloaded.text!!.contains("央视"))
            assertEquals(1, M3uParser.parseImport(downloaded.text!!).entries.size)
        } finally { network.close(); server.shutdown() }
    }

    @Test fun enableModernTlsProtocolsAddsTls12WithoutDroppingSupportedProtocols() {
        val patched = TlsTrust.enableModernTlsProtocols(
            supported = arrayOf("TLSv1", "TLSv1.1", "TLSv1.2"),
            enabled = arrayOf("TLSv1")
        )
        assertTrue("TLSv1.2" in patched)
        assertTrue("TLSv1" in patched)
        val unsupported = TlsTrust.enableModernTlsProtocols(
            supported = arrayOf("TLSv1"),
            enabled = arrayOf("TLSv1")
        )
        assertArrayEquals(arrayOf("TLSv1"), unsupported)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun mediaHttpDataSourceStreamsRangeAndSurfacesHttpStatusErrors() {
        val server = MockWebServer(); server.start()
        val network = Network(null)
        try {
            server.enqueue(MockResponse().setBody("0123456789"))
            val factory = OkHttpCompat.mediaHttpDataSourceFactory(
                network.client,
                Network.USER_AGENT,
                mapOf("X-Test" to "lite")
            )
            val ds = factory.createDataSource()
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.StubUri.of(server.url("/live/seg.ts").toString()))
                .setPosition(4)
                .setLength(6)
                .build()
            val length = ds.open(spec)
            assertEquals(6L, length)
            val buf = ByteArray(16)
            val read = ds.read(buf, 0, buf.size)
            assertEquals("456789", String(buf, 0, read, Charsets.UTF_8))
            ds.close()
            val req = server.takeRequest()
            assertEquals("bytes=4-9", req.getHeader("Range"))
            assertEquals("lite", req.getHeader("X-Test"))

            server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
            val dsErr = factory.createDataSource()
            try {
                dsErr.open(
                    androidx.media3.datasource.DataSpec.Builder()
                        .setUri(android.net.StubUri.of(server.url("/denied.ts").toString()))
                        .build()
                )
                fail("Expected InvalidResponseCodeException")
            } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                assertEquals(403, e.responseCode)
            } finally {
                dsErr.close()
            }
        } finally {
            network.close()
            server.shutdown()
        }
    }
}
