package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class SourceRoutesTest {
    private val regional = Entry("one", "One", "Test", "http://iptv.cdn.ha.chinamobile.com/fixture-test.m3u8", subscriptionId = "builtin-reviewed")
    private val public = regional.copy(url = "https://public.example/live.m3u8")
    @Test fun preferenceIsExplicitAndDoesNotFabricateSuccessfulPlayback() {
        val health = mapOf(sourceId(public.url) to SourceStat(attempts = 4, successes = 4, firstFrameMs = 500))
        assertEquals(listOf(regional, public), SourceRoutes.rank(listOf(public, regional), health, 0, true))
        assertEquals(listOf(public, regional), SourceRoutes.rank(listOf(regional, public), health, 0, false))
        assertFalse(health.containsKey(sourceId(regional.url)))
        assertEquals("henan_cmcc", SourceRoutes.group(regional))
    }
    @Test fun failedRegionalCooldownGoesStraightToPublicBackup() {
        val health = mapOf(sourceId(regional.url) to SourceStat(failures = 1, cooldownUntil = 1000))
        assertEquals(listOf(public), SourceRoutes.rank(listOf(regional, public), health, 999, true))
        assertEquals(listOf(regional, public), SourceRoutes.rank(listOf(regional, public), health, 1000, true))
    }
    @Test fun manualChoiceWinsButOtherCoolingSourcesStayExcluded() {
        val cooling = regional.copy(url = "https://cooling.example/live.m3u8")
        val health = mapOf(sourceId(cooling.url) to SourceStat(cooldownUntil = 1000))
        assertEquals(listOf(public, regional), SourceRoutes.rank(listOf(regional, public, cooling), health, 0, true, public))
    }
    @Test fun duplicatedCatalogUrlsNeverCreateIndependentBackups() {
        assertEquals(listOf(regional, public), SourceRoutes.rank(listOf(regional, regional.copy(subscriptionId="other"), public), emptyMap(), 0, true))
    }
    @Test fun regionClassificationDoesNotTrustSimilarHostnames() {
        assertTrue(SourceRoutes.isHenan(regional.url))
        assertFalse(SourceRoutes.isHenan("https://iptv.cdn.ha.chinamobile.com.example/live"))
        assertFalse(SourceRoutes.isHenan("https://user:secret@iptv.cdn.ha.chinamobile.com/live"))
        assertFalse(SourceRoutes.isHenan("http://example.com/iptv.cdn.ha.chinamobile.com/live"))
    }
}