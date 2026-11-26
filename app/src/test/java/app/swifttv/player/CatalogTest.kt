package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    @Test fun mergesExplicitAliasesAndKeepsCctvFivePlusSeparate() {
        val parsed = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="CCTV1.cn@HD",CCTV-1 HD (1080p)
            https://one.example/live.m3u8
            #EXTINF:-1 tvg-id="CCTV-1",CCTV-1
            https://two.example/live.m3u8
            #EXTINF:-1 tvg-id="CCTV5Plus.cn@SD",CCTV-5+
            https://three.example/live.m3u8
            #EXTINF:-1 tvg-id="CCTV5.cn@HD",CCTV-5
            https://four.example/live.m3u8
        """.trimIndent())
        val channels = CatalogState(entries = parsed.entries).channels()
        assertEquals(3, channels.size)
        assertEquals(2, channels.first { it.id == "cctv-1" }.sources.size)
        assertNotEquals(channels[1].id, channels[2].id)
    }
    @Test fun parsesQuotedCommasHeadersBomAndCrLf() {
        val parsed = M3uParser.parse("\uFEFF#EXTM3U\r\n#EXTINF:-1 tvg-id='a' group-title=\"News, local\",Station, local\r\n#EXTVLCOPT:http-user-agent=Custom TV\r\n#EXTVLCOPT:http-referrer=https://official.example/\r\nhttps://live.example/a.m3u8\r\n")
        assertEquals("News, local", parsed.entries.single().group)
        assertEquals("Station, local", parsed.entries.single().name)
        assertEquals("Custom TV", parsed.entries.single().headers["User-Agent"])
    }
    @Test fun parsesHeaderEpgUrlsAndInternalProfilesWithoutLeakingIntoHttpHeaders() {
        val parsed = M3uParser.parse("""
            #EXTM3U x-tvg-url="https://epg.example/guide.xml.gz, https://backup.example/epg.xml"
            #EXTINF:-1 tvg-id="cctv16",CCTV-16 奥林匹克
            #EXTVLCOPT:swifttv-profile=4k-hevc50
            #EXTVLCOPT:http-user-agent=PlayerUA
            https://live.example/cctv16.m3u8
        """.trimIndent())
        assertEquals(listOf("https://epg.example/guide.xml.gz", "https://backup.example/epg.xml"), parsed.epgUrls)
        val single = parsed.entries.single()
        assertEquals("4k-hevc50", single.profile)
        assertEquals(mapOf("User-Agent" to "PlayerUA"), single.headers)
    }
    @Test fun duplicateUrlDoesNotBecomeBackup() {
        val text = "#EXTM3U\n#EXTINF:-1,CCTV-1\nhttps://one.example/live\n#EXTINF:-1,CCTV-1\nhttps://one.example/live\n"
        assertEquals(1, M3uParser.parse(text).entries.size)
    }
    @Test fun henanSatelliteAliasesMergeWithoutMergingHunanOrHenanLocalChannels() {
        for ((id, name) in listOf("HenanTV.cn@HD" to "河南卫视", "HenanSatelliteTV.cn" to "Henan TV", "" to "河南卫视 HD")) {
            assertEquals("henan" to "河南卫视", ChannelIdentity.resolve(id, name))
        }
        assertEquals("hunan", ChannelIdentity.resolve("HunanTV.cn@HD", "湖南卫视").first)
        for (name in listOf("河南都市频道", "河南新闻频道", "河南卫视国际", "河南4K试验")) {
            assertNotEquals("henan", ChannelIdentity.resolve("", name).first)
        }
    }
    @Test fun standardChannelVariantsMergeAndKeepCctvFivePlusSeparate() {
        val parsed = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="河南卫视" tvg-name="河南卫视" group-title="卫视频道",河南卫视
            http://one.example/live/1.m3u8
            #EXTINF:-1 tvg-id="HenanSatelliteTV.cn" tvg-name="河南卫视 HD",河南卫视高清
            https://backup.example/henan.m3u8
            #EXTINF:-1 tvg-id="湖南卫视" tvg-name="湖南卫视",湖南卫视
            http://one.example/live/2.m3u8
            #EXTINF:-1 tvg-id="CCTV5" tvg-name="CCTV5",CCTV5
            http://one.example/cctv5.m3u8
            #EXTINF:-1 tvg-id="CCTV5+" tvg-name="CCTV5+",CCTV5+
            http://one.example/cctv5plus.m3u8
        """.trimIndent())
        val channels = CatalogState(entries = parsed.entries).channels()
        assertEquals(listOf("henan", "hunan", "cctv-5", "cctv-5+"), channels.map { it.id })
        assertEquals(2, channels.first { it.id == "henan" }.sources.size)
        assertEquals("河南卫视", channels.first().name)
    }
    @Test fun internationalDragonIsNotDomesticDragon() {
        assertNotEquals(ChannelIdentity.resolve("DragonTVInternational.cn@SD", "Dragon TV International").first,
            ChannelIdentity.resolve("DragonTV.cn@HD", "东方卫视").first)
    }
    @Test fun parsesSingleChannelHlsManifestsAndRejectsRelativeLocalMasters() {
        val mediaHls = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nhttps://example.com/a.ts"
        assertTrue(M3uParser.isHlsManifest(mediaHls))
        assertTrue(runCatching { M3uParser.parse(mediaHls) }.isFailure)

        val singleHls = M3uParser.parseImport(mediaHls, sourceUrl = "https://stream.example/live/cctv1.m3u8", explicitName = "CCTV-1 单频道")
        assertEquals(M3uParser.SINGLE_STREAM_GROUP, singleHls.entries.single().group)
        assertEquals("CCTV-1 单频道", singleHls.entries.single().name)
        assertEquals("https://stream.example/live/cctv1.m3u8", singleHls.entries.single().url)

        val localMaster = M3uParser.parseImport(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttps://cdn.example/1080.m3u8\n",
            sourceUrl = "",
            explicitName = "本地单流"
        )
        assertEquals("https://cdn.example/1080.m3u8", localMaster.entries.single().url)
        assertTrue(runCatching {
            M3uParser.parseImport("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nchunklist.m3u8\n")
        }.isFailure)

        val binarySingle = M3uParser.singleStreamPlaylist("https://stream.example/live.ts")
        assertEquals(M3uParser.SINGLE_STREAM_GROUP, binarySingle.entries.single().group)
        assertEquals("https://stream.example/live.ts", binarySingle.entries.single().url)
    }
    @Test fun hardwareGatingAndStartChannelSelectionRespectStatsAndLastPlayed() {
        val hd = Channel("cctv-1", "CCTV-1", "央视", listOf(Entry("cctv-1", "CCTV-1", "央视", "https://a.example/1.m3u8")))
        val uhd = Channel("cctv-16", "CCTV-16 4K", "央视", listOf(Entry("cctv-16", "CCTV-16 4K", "央视", "https://a.example/4k.m3u8", profile = CatalogSelection.PROFILE_4K_HEVC50)))
        val gatedOff = CatalogSelection.gateHardware(listOf(hd, uhd), supports4kHevc50 = false)
        assertEquals(2, gatedOff.size)
        assertEquals(hd.sources, gatedOff[0].sources)
        assertTrue(gatedOff[1].sources.isEmpty())
        assertTrue(gatedOff[1].unavailableReason.contains("4K"))
        assertEquals(listOf(hd, uhd), CatalogSelection.gateHardware(listOf(hd, uhd), supports4kHevc50 = true))
        val healthyStats = mapOf(sourceId("https://a.example/4k.m3u8") to SourceStat(attempts = 1, successes = 1))
        assertEquals(uhd, CatalogSelection.chooseStart(listOf(hd, uhd), "", healthyStats))
        assertEquals(hd, CatalogSelection.chooseStart(listOf(hd, uhd), "cctv-1", healthyStats))
    }
    @Test fun invalidSchemesAndHtmlAreRejected() {
        for (text in listOf("<html>Forbidden</html>", "#EXTM3U\n#EXTINF:-1,Channel\nfile:///sdcard/private.ts",
            "#EXTM3U\n#EXTINF:-1,Channel\njavascript:alert(1)")) {
            assertTrue(runCatching { M3uParser.parse(text) }.isFailure)
        }
    }
    @Test fun skipsInvalidRowsWithoutLosingGoodChannels() {
        val parsed = M3uParser.parse("#EXTM3U\n#EXTINF:-1,Bad\nnot a url\n#EXTINF:-1,Good\nhttps://example.com/good\n")
        assertEquals(1, parsed.skipped)
        assertEquals("Good", parsed.entries.single().name)
    }
    @Test fun retainsUnknownChannelsWithoutFuzzyMerge() {
        assertNotEquals(ChannelIdentity.resolve("", "News").first, ChannelIdentity.resolve("", "News International").first)
    }
    @Test fun oversizedPayloadIsRejected() {
        assertTrue(runCatching { M3uParser.parse("#EXTM3U\n" + "x".repeat(M3uParser.MAX_BYTES)) }.isFailure)
    }
    @Test fun rankingRespectsCooldownAndIndependentHosts() {
        val entries = listOf("https://a.example/one", "https://a.example/two", "https://b.example/one", "https://dead.example/one")
            .map { Entry("x", "X", "G", it) }
        val stats = mapOf(sourceId(entries[3].url) to SourceStat(cooldownUntil = 10000))
        val ranked = SourceRanking.rank(entries, stats, 9000)
        assertEquals(3, ranked.size)
        assertEquals("https://b.example/one", ranked[1].url)
    }
    @Test fun allCoolingSourcesFallBackToEarliestExpiringInsteadOfDeadlocking() {
        val single = Entry("cctv-2", "CCTV-2", "央视", "https://only.example/live.m3u8")
        val statsSingle = mapOf(sourceId(single.url) to SourceStat(failures = 1, cooldownUntil = 50_000L))
        assertEquals(listOf(single), SourceRanking.rank(listOf(single), statsSingle, 10_000L))

        val later = Entry("x", "X", "G", "https://later.example/live.m3u8")
        val sooner = Entry("x", "X", "G", "https://sooner.example/live.m3u8")
        val bothCooling = mapOf(
            sourceId(later.url) to SourceStat(failures = 1, cooldownUntil = 30_000L),
            sourceId(sooner.url) to SourceStat(failures = 1, cooldownUntil = 15_000L)
        )
        assertEquals(listOf(sooner, later), SourceRanking.rank(listOf(later, sooner), bothCooling, 10_000L))
    }
    @Test fun diagnosticsRemoveTokensPathAndUserInfo() {
        val text = DiagnosticRedaction.message("Failed https://example.com/private/tokenABC/live.m3u8?key=secret")
        assertFalse(text.contains("secret")); assertFalse(text.contains("tokenABC")); assertTrue(text.contains("example.com"))
        assertEquals("[invalid URL]", DiagnosticRedaction.origin("https://user:secret@example.com/live"))
    }
    @Test fun diskSchemaRoundTripRetainsFavoriteHeadersEpgAndHealth() {
        val state = CatalogState(
            entries = listOf(Entry("one", "One", "News", "https://example.com/live", mapOf("User-Agent" to "TV"), "sub", "4k-hevc50")),
            subscriptions = listOf(Subscription("sub", "Source", "https://example.com/list", 123, epgUrl = "https://example.com/epg.xml.gz")),
            favorites = setOf("one"), lastChannel = "one",
            stats = mapOf("stat" to SourceStat(attempts = 2, successes = 1, firstFrameMs = 900)),
            customEpgUrl = "https://custom.example/epg.xml"
        )
        assertEquals(state, CatalogStore.decode(CatalogStore.encode(state)))
    }
    @Test fun cancelledTuneDoesNotReduceReliability() {
        val sources = listOf(Entry("x", "X", "G", "https://a.example/one"), Entry("x", "X", "G", "https://b.example/one"))
        val stats = mapOf(sourceId(sources[0].url) to SourceStat(attempts = 8, cancelled = 8, lastOutcome = "cancelled"))
        assertEquals(sources, SourceRanking.rank(sources, stats, 0))
        assertEquals("cancelled", SourceHealth.describe(stats.values.single()))
        assertEquals("incomplete", SourceHealth.describe(SourceStat(attempts = 1)))
        assertEquals("in_progress", SourceHealth.describe(SourceStat(attempts = 1, lastOutcome = "connecting")))
    }
    @Test fun upgradedStatisticsKeepFailuresAndCancellationsDistinct() {
        val state = CatalogState(stats = mapOf("x" to SourceStat(attempts = 5, successes = 1, failures = 2, cancelled = 2, lastOutcome = "failed", error = "HTTP 403")))
        assertEquals(state, CatalogStore.decode(CatalogStore.encode(state)))
        val legacy = CatalogStore.encode(state).replace("\"failures\":2,", "").replace("\"cancelled\":2,", "").replace(",\"failures\":2", "").replace(",\"cancelled\":2", "")
        assertTrue(CatalogStore.decode(legacy).stats.getValue("x").failures > 0)
    }

    @Test fun parsesAptvExtendedM3uWithoutFalseHlsClassificationAndExtractsInlineHeaders() {
        val aptv = """
            #EXTM3U x-tvg-url="https://epg.example/t.xml"
            #EXT-X-APP APTV
            #EXT-X-APTV-TYPE remote
            #EXT-X-SUB-URL https://example.com/sub.m3u
            #EXT-X-APTV-LATENCY: FALSE
            #EXTINF:-1 tvg-id="CCTV1" tvg-name="CCTV1" http-user-agent="AptvPlayer-UA" group-title="央视IPV4",CCTV1
            http://101.35.240.114:88/live.php?id=CCTV1
            #EXTINF:-1 tvg-id="五星体育" http-user-agent="iPhone" http-header="Referer=https://live.kankanews.com/" group-title="卫视IPV4",五星体育
            http://101.35.240.114:88/live.php?id=sports
            #EXTINF:-1 group-title="广西",广西台
            rtmp://live.gxrb.com.cn/hls/a23
        """.trimIndent()
        assertFalse(M3uParser.isHlsManifest(aptv))
        val parsed = M3uParser.parseImport(aptv, sourceUrl = "https://cumtwjq.github.io/iptv-subscriptions/aptv-iptv.m3u")
        assertEquals(3, parsed.entries.size)
        assertEquals("AptvPlayer-UA", parsed.entries[0].headers["User-Agent"])
        assertEquals("iPhone", parsed.entries[1].headers["User-Agent"])
        assertEquals("https://live.kankanews.com/", parsed.entries[1].headers["Referer"])
        assertEquals("rtmp://live.gxrb.com.cn/hls/a23", parsed.entries[2].url)
    }

    @Test fun parsesTxtGenreMultiSourcePipeHeadersJsonAndLocalUdpRtpStreams() {
        val txt = """
            ; local multicast + web playlist
            央视频道,#genre#
            CCTV1,udp://@239.1.1.1:5000${'$'}组播#rtp://239.1.1.2:5002#http://192.168.1.1:4022/rtp/239.1.1.1:5000|User-Agent=CustomUA&Referer=https://a.example/${'$'}线路3
            卫视频道，#genre#
            湖南卫视，rtsp://10.0.0.1:554/live/hunan.sdp
        """.trimIndent()
        val parsedTxt = M3uParser.parseImport(txt)
        assertEquals(4, parsedTxt.entries.size)
        assertEquals("udp://239.1.1.1:5000", parsedTxt.entries[0].url)
        assertEquals("rtp://239.1.1.2:5002", parsedTxt.entries[1].url)
        assertEquals("http://192.168.1.1:4022/rtp/239.1.1.1:5000", parsedTxt.entries[2].url)
        assertEquals("CustomUA", parsedTxt.entries[2].headers["User-Agent"])
        assertEquals("https://a.example/", parsedTxt.entries[2].headers["Referer"])
        assertEquals("卫视频道", parsedTxt.entries[3].group)
        assertEquals("rtsp://10.0.0.1:554/live/hunan.sdp", parsedTxt.entries[3].url)

        val json = """[{"name":"测试频道","group":"测试分组","urls":["https://cdn.example/live.m3u8${'$'}超清"],"headers":{"User-Agent":"TVBox"}}]"""
        val parsedJson = M3uParser.parseImport(json)
        assertEquals(1, parsedJson.entries.size)
        assertEquals("TVBox", parsedJson.entries.single().headers["User-Agent"])
        assertEquals("https://cdn.example/live.m3u8", parsedJson.entries.single().url)
    }

    @Test fun decodesGb18030PlaylistsAndSupportsExtHttpWithoutExtm3uHeader() {
        val m3u = "#EXTINF:-1,河南卫视\n#EXTGRP:卫视频道\n#EXTHTTP:{\"User-Agent\":\"KodiUA\",\"Referer\":\"https://henan.example/\"}\nhttps://cdn.example/henan.m3u8\n"
        val gbkBytes = m3u.toByteArray(java.nio.charset.Charset.forName("GB18030"))
        val decoded = TextDecoding.decodePlaylistBytes(gbkBytes)
        val parsed = M3uParser.parseImport(decoded)
        assertEquals("河南卫视", parsed.entries.single().name)
        assertEquals("卫视频道", parsed.entries.single().group)
        assertEquals("KodiUA", parsed.entries.single().headers["User-Agent"])
        assertEquals("https://henan.example/", parsed.entries.single().headers["Referer"])
    }
}
