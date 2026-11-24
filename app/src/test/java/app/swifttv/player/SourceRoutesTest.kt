package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class SourceRoutesTest {
    private val regional = Entry("one", "One", "Test", "http://iptv.cdn.ha.chinamobile.com/PLTV/88888888/224/3221226304/index.m3u8", subscriptionId = "builtin-reviewed")
    private val public = regional.copy(url = "https://public.example/live.m3u8")
    @Test fun preferenceIsExplicitAndDoesNotFabricateSuccessfulPlayback() {
        val health = mapOf(sourceId(public.url) to SourceStat(attempts = 4, successes = 4, firstFrameMs = 500))
        assertEquals(listOf(regional, public), SourceRoutes.rank(listOf(public, regional), health, 0, RoutingMode.HENAN_FIRST))
        assertEquals(listOf(public, regional), SourceRoutes.rank(listOf(regional, public), health, 0, RoutingMode.PLAYBACK_HISTORY))
        assertFalse(health.containsKey(sourceId(regional.url)))
        assertEquals("henan_cmcc", SourceRoutes.group(regional))
    }
    @Test fun failedRegionalCooldownGoesStraightToPublicBackup() {
        val health = mapOf(sourceId(regional.url) to SourceStat(failures = 1, cooldownUntil = 1000))
        assertEquals(listOf(public), SourceRoutes.rank(listOf(regional, public), health, 999, RoutingMode.HENAN_FIRST))
        assertEquals(listOf(regional, public), SourceRoutes.rank(listOf(regional, public), health, 1000, RoutingMode.HENAN_FIRST))
    }
    @Test fun manualChoiceWinsButOtherCoolingSourcesStayExcluded() {
        val cooling = regional.copy(url = "https://cooling.example/live.m3u8")
        val health = mapOf(sourceId(cooling.url) to SourceStat(cooldownUntil = 1000))
        assertEquals(listOf(public, regional), SourceRoutes.rank(listOf(regional, public, cooling), health, 0, RoutingMode.HENAN_FIRST, public))
    }
    @Test fun duplicatedCatalogUrlsNeverCreateIndependentBackups() {
        assertEquals(listOf(regional, public), SourceRoutes.rank(listOf(regional, regional.copy(subscriptionId="other"), public), emptyMap(), 0, RoutingMode.HENAN_FIRST))
    }
    @Test fun regionClassificationDoesNotTrustSimilarHostnames() {
        assertTrue(SourceRoutes.isHenan(regional.url))
        assertFalse(SourceRoutes.isHenan("https://iptv.cdn.ha.chinamobile.com.example/live"))
        assertFalse(SourceRoutes.isHenan("https://user:secret@iptv.cdn.ha.chinamobile.com/live"))
        assertFalse(SourceRoutes.isHenan("http://example.com/iptv.cdn.ha.chinamobile.com/live"))
    }

    @Test fun autoNeedsDistinctFailuresOrPositiveFallbackEvidence() {
        val auto = AutomaticSourceRouting()
        auto.regionalFailure(11, regional.url)
        auto.regionalFailure(11, regional.url)
        assertEquals(RoutingMode.PLAYBACK_HISTORY, auto.effective(11, RoutingMode.AUTO))
        auto.rendered(11, regional = false)
        assertEquals(RoutingMode.PUBLIC_FIRST, auto.effective(11, RoutingMode.AUTO))
        assertEquals(RoutingMode.HENAN_FIRST, auto.effective(11, RoutingMode.HENAN_FIRST))
        assertEquals(RoutingMode.PLAYBACK_HISTORY, auto.effective(12, RoutingMode.AUTO))
    }

    @Test fun validatedRegionalPlaybackRecoversPriorityWithoutFlappingOnOneFailure() {
        val auto = AutomaticSourceRouting()
        auto.regionalFailure(11, regional.url)
        auto.regionalFailure(11, regional.url + "?b=2")
        assertEquals(RoutingMode.PUBLIC_FIRST, auto.effective(11, RoutingMode.AUTO))
        auto.rendered(11, regional = true)
        auto.regionalFailure(11, regional.url)
        auto.rendered(11, regional = false)
        assertEquals(RoutingMode.HENAN_FIRST, auto.effective(11, RoutingMode.AUTO))
        auto.regionalFailure(11, regional.url + "?b=2")
        assertEquals(RoutingMode.PUBLIC_FIRST, auto.effective(11, RoutingMode.AUTO))
    }

    @Test fun guovinPresetUsesTheOfficialIpv4ResultAndSharesManualSubscriptionIdentity() {
        val url = PlaylistPreset.GUOVIN.urls.first()
        assertEquals("https://guovin.github.io/iptv-api/ipv4.m3u", url)
        assertTrue(PlaylistPreset.GUOVIN.urls.size >= 2)
        val guovin = public.copy(subscriptionId = sourceId("url:$url"))
        assertEquals("guovin_ipv4", SourceRoutes.group(guovin))
        assertEquals("henan_cmcc", SourceRoutes.group(regional.copy(subscriptionId = guovin.subscriptionId)))
        assertEquals(listOf(guovin, regional), SourceRoutes.rank(listOf(regional, guovin), emptyMap(), 0, RoutingMode.PUBLIC_FIRST))
    }

    @Test fun domesticIpv6AndDualStackPresetsIncludeFastlyMirrorsAndClassification() {
        assertEquals("https://guovin.github.io/iptv-api/ipv6.m3u", PlaylistPreset.GUOVIN_IPV6.urls.first())
        assertTrue(PlaylistPreset.GUOVIN_IPV6.urls.any { it.contains("jsdelivr.net") })
        assertEquals("https://guovin.github.io/iptv-api/result.m3u", PlaylistPreset.GUOVIN_ALL.urls.first())
        assertTrue(PlaylistPreset.FANMINGMING_IPV6.urls.first().contains("fanmingming/live"))

        val cmccV6 = Entry("cctv-3", "CCTV-3", "央视", "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/1.m3u8", subscriptionId = sourceId("url:${PlaylistPreset.GUOVIN_IPV6.urls.first()}"))
        assertTrue(SourceRoutes.isIpv6Literal(cmccV6.url))
        assertTrue(SourceRoutes.isChinaCarrierIpv6(cmccV6.url))
        assertEquals("guovin_ipv6", SourceRoutes.group(cmccV6))
        assertEquals("国内 IPv6", SourceRoutes.label(cmccV6))
    }

    @Test fun dualStackNetworkPrioritizesDomesticCarrierIpv6OverOverseasRelays() {
        val us1 = Entry("cctv-3", "CCTV-3", "央视", "http://74.91.26.218:82/live/cctv3hd.m3u8")
        val us2 = Entry("cctv-3", "CCTV-3", "央视", "http://107.150.60.122/live/cctv3hd.m3u8")
        val us3 = Entry("cctv-3", "CCTV-3", "央视", "http://63.141.230.178:82/gslb/zbdq5.m3u8?id=cctv3hd")
        val cmccV6 = Entry("cctv-3", "CCTV-3", "央视", "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/1.m3u8")
        val domesticV4 = Entry("cctv-3", "CCTV-3", "央视", "http://ali-m-l.cztv.com/live/cctv3.m3u8")

        val rankedV6 = SourceRoutes.rank(
            listOf(us1, us2, us3, cmccV6, domesticV4),
            emptyMap(),
            0,
            RoutingMode.AUTO,
            ipv6Available = true
        )
        assertEquals(listOf(cmccV6, domesticV4, us1, us2, us3), rankedV6)
    }

    @Test fun ipv4OnlyNetworkDemotesIpv6LiteralsBelowOverseasAndPrefersDomesticIpv4() {
        val us = Entry("hunan", "湖南卫视", "卫视", "http://74.91.26.218:82/live/hnwshd.m3u8")
        val cmccV6 = Entry("hunan", "湖南卫视", "卫视", "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/hunan.m3u8")
        val domesticV4 = Entry("hunan", "湖南卫视", "卫视", "http://hlsal-ldvt.qing.mgtv.com/live/hunan.m3u8")

        val rankedV4Only = SourceRoutes.rank(
            listOf(cmccV6, us, domesticV4),
            emptyMap(),
            0,
            RoutingMode.AUTO,
            ipv6Available = false
        )
        assertEquals(listOf(domesticV4, us, cmccV6), rankedV4Only)
    }

    @Test fun measuredPlaybackHealthStillWinsOverInitialNetworkHints() {
        val us = Entry("cctv-3", "CCTV-3", "央视", "http://74.91.26.218:82/live/cctv3hd.m3u8")
        val cmccV6 = Entry("cctv-3", "CCTV-3", "央视", "http://[2409:8087:8:21::18]:6610/otttv.bj.chinamobile.com/1.m3u8")
        val stats = mapOf(sourceId(us.url) to SourceStat(attempts = 3, successes = 3, firstFrameMs = 400))

        val ranked = SourceRoutes.rank(listOf(cmccV6, us), stats, 0, RoutingMode.AUTO, ipv6Available = true)
        assertEquals(listOf(us, cmccV6), ranked)
    }

    @Test fun preserveSettingsOnlyWhenThereWasAnExplicitChoice() {
        assertEquals(RoutingMode.AUTO, RoutingMode.resolve(null, null))
        assertEquals(RoutingMode.HENAN_FIRST, RoutingMode.resolve(null, true))
        assertEquals(RoutingMode.PLAYBACK_HISTORY, RoutingMode.resolve(null, false))
        assertEquals(RoutingMode.AUTO, RoutingMode.resolve("auto", true))
        assertEquals(RoutingMode.PUBLIC_FIRST, RoutingMode.resolve("public_first", null))
    }
}
