package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class SourceRoutesTest {
    private val v6 = Entry("one", "One", "Test", "http://[2409:8087:8:21::18]:6610/live/1.m3u8")
    private val v4 = Entry("one", "One", "Test", "https://cdn.example/live.m3u8")

    @Test fun preferenceIsExplicitAndDoesNotFabricateSuccessfulPlayback() {
        val health = mapOf(sourceId(v4.url) to SourceStat(attempts = 4, successes = 4, firstFrameMs = 500))
        assertEquals(listOf(v6, v4), SourceRoutes.rank(listOf(v4, v6), health, 0, RoutingMode.IPV6_FIRST, ipv6Available = true))
        assertEquals(listOf(v4, v6), SourceRoutes.rank(listOf(v6, v4), health, 0, RoutingMode.PLAYBACK_HISTORY, ipv6Available = true))
        assertEquals(listOf(v4, v6), SourceRoutes.rank(listOf(v6, v4), health, 0, RoutingMode.IPV4_FIRST, ipv6Available = true))
        assertFalse(health.containsKey(sourceId(v6.url)))
        assertEquals("ipv6", SourceRoutes.group(v6))
        assertEquals("IPv6 线路", SourceRoutes.label(v6))
        assertEquals("ipv4", SourceRoutes.group(v4))
        assertEquals("IPv4 线路", SourceRoutes.label(v4))
    }

    @Test fun failedSourceCooldownSkipsStraightToHealthyBackup() {
        val health = mapOf(sourceId(v6.url) to SourceStat(failures = 1, cooldownUntil = 1000))
        assertEquals(listOf(v4), SourceRoutes.rank(listOf(v6, v4), health, 999, RoutingMode.IPV6_FIRST, ipv6Available = true))
        assertEquals(listOf(v6, v4), SourceRoutes.rank(listOf(v6, v4), health, 1000, RoutingMode.IPV6_FIRST, ipv6Available = true))
    }

    @Test fun manualChoiceWinsButOtherCoolingSourcesStayExcluded() {
        val cooling = v4.copy(url = "https://cooling.example/live.m3u8")
        val health = mapOf(sourceId(cooling.url) to SourceStat(cooldownUntil = 1000))
        assertEquals(listOf(v4, v6), SourceRoutes.rank(listOf(v6, v4, cooling), health, 0, RoutingMode.IPV6_FIRST, v4, ipv6Available = true))
    }

    @Test fun ipv4OnlyNetworkDemotesIpv6LiteralsEvenUnderAuto() {
        val rankedV4Only = SourceRoutes.rank(
            listOf(v6, v4),
            emptyMap(),
            0,
            RoutingMode.AUTO,
            ipv6Available = false
        )
        assertEquals(listOf(v4, v6), rankedV4Only)

        val rankedAutoV6 = SourceRoutes.rank(
            listOf(v4, v6),
            emptyMap(),
            0,
            RoutingMode.AUTO,
            ipv6Available = true
        )
        assertEquals(listOf(v4, v6), rankedAutoV6)

        val rankedPreferV6 = SourceRoutes.rank(
            listOf(v4, v6),
            emptyMap(),
            0,
            RoutingMode.IPV6_FIRST,
            ipv6Available = true
        )
        assertEquals(listOf(v6, v4), rankedPreferV6)
    }

    @Test fun measuredPlaybackHealthWinsUnderAutoAndHistoryModes() {
        val stats = mapOf(sourceId(v4.url) to SourceStat(attempts = 3, successes = 3, firstFrameMs = 400))
        val ranked = SourceRoutes.rank(listOf(v6, v4), stats, 0, RoutingMode.AUTO, ipv6Available = true)
        assertEquals(listOf(v4, v6), ranked)
    }

    @Test fun pickRaceBatchDiversifiesAcrossDistinctHostsBeforeRepeatingHost() {
        val a1 = Entry("x", "X", "G", "https://alpha.example/1.m3u8")
        val a2 = Entry("x", "X", "G", "https://alpha.example/2.m3u8")
        val b1 = Entry("x", "X", "G", "https://beta.example/1.m3u8")
        val c1 = Entry("x", "X", "G", "https://gamma.example/1.m3u8")
        val batch = SourceRoutes.pickRaceBatch(listOf(a1, a2, b1, c1), maxCount = 3, ipv6Available = true)
        assertEquals(listOf(a1, b1, c1), batch)
    }

    @Test fun ipv6ClassifierIgnoresSpoofedPathsAndResolvesSavedModes() {
        assertTrue(SourceRoutes.isIpv6Literal("http://[2409:8087::1]/live.m3u8"))
        assertFalse(SourceRoutes.isIpv6Literal("http://example.com/[2409:8087::1]/live.m3u8"))
        assertEquals(RoutingMode.AUTO, RoutingMode.resolve(null))
        assertEquals(RoutingMode.IPV6_FIRST, RoutingMode.resolve("ipv6_first"))
        assertEquals(RoutingMode.IPV4_FIRST, RoutingMode.resolve("ipv4_first"))
        assertEquals(RoutingMode.PLAYBACK_HISTORY, RoutingMode.resolve("playback_history"))
        assertEquals(RoutingMode.AUTO, RoutingMode.resolve("unknown_mode"))
    }

    @Test fun excludesMulticastAndNonHttpStreamsFromHttpRaceBatch() {
        val udp = Entry("u", "U", "组播", "udp://239.1.1.1:5000")
        val rtp = Entry("r", "R", "组播", "rtp://239.1.1.2:5000")
        val http = Entry("h", "H", "网络", "https://cdn.example/live.m3u8")
        assertEquals("UDP 组播", SourceRoutes.label(udp))
        assertEquals("RTP 组播", SourceRoutes.label(rtp))
        assertEquals(listOf(http), SourceRoutes.pickRaceBatch(listOf(udp, rtp, http), maxCount = 3))
        assertEquals(emptyList<Entry>(), SourceRoutes.pickRaceBatch(listOf(udp, rtp), maxCount = 3))
    }
}
