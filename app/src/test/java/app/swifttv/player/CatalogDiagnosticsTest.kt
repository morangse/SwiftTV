package app.swifttv.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogDiagnosticsTest {
    private val regional = Entry("henan", "河南卫视", "卫视",
        "http://iptv.cdn.ha.chinamobile.com/PLTV/fixture-test.m3u8",
        subscriptionId = "builtin-reviewed")

    @Test fun sameUrlIsOneSourceWithAllProviderLabelsAndMeasuredStatistics() {
        val measured = SourceStat(attempts = 7, successes = 4, firstFrameMs = 973, rebufferMs = 321,
            playedMs = 90000, checkedAt = 12345, width = 1920, height = 1080, codec = "video/avc",
            audioStarted = true, failures = 1, cancelled = 2, lastOutcome = "playing")
        val state = CatalogState(entries = listOf(regional, regional.copy(subscriptionId = "custom"), regional),
            subscriptions = listOf(Subscription("builtin-reviewed", "内置精选", "", 0),
                Subscription("custom", "用户订阅", "https://provider.example/list", 0)),
            stats = mapOf(sourceId(regional.url) to measured), builtinRevision = 3)
        val root = JSONObject(CatalogDiagnostics.create(state, "Sony Android 10", "0.1.2", "henan_first", 45678))
        assertEquals(3, root.getInt("schemaVersion"))
        assertEquals("0.1.2", root.getString("appVersion"))
        assertEquals("Sony Android 10", root.getString("device"))
        assertEquals(45678L, root.getLong("exportedAt"))
        assertEquals(3, root.getInt("builtinRevision"))
        assertEquals("henan_first", root.getString("routingPreference"))
        val sources = root.getJSONArray("sources")
        assertEquals(1, sources.length())
        val source = sources.getJSONObject(0)
        assertEquals("henan_cmcc", source.getString("routeGroup"))
        assertEquals("河南卫视", source.getString("channel"))
        assertEquals(DiagnosticRedaction.origin(regional.url), source.getString("origin"))
        assertTrue(source.getString("origin").endsWith("#" + sourceId(regional.url)))
        val providers = source.getJSONArray("providers")
        assertEquals(2, providers.length())
        assertEquals("内置精选", providers.getString(0))
        assertEquals("用户订阅", providers.getString(1))
        assertEquals(7, source.getInt("attempts"))
        assertEquals(4, source.getInt("successes"))
        assertEquals(973L, source.getLong("firstFrameMs"))
        assertEquals(321L, source.getLong("rebufferMs"))
        assertEquals(90000L, source.getLong("playedMs"))
        assertEquals(12345L, source.getLong("checkedAt"))
        assertEquals(1920, source.getInt("width"))
        assertEquals(1080, source.getInt("height"))
        assertEquals("video/avc", source.getString("codec"))
        assertTrue(source.getBoolean("audioStarted"))
        assertEquals(1, source.getInt("failures"))
        assertEquals(2, source.getInt("cancelled"))
        assertEquals("playing", source.getString("lastOutcome"))
        assertEquals("", source.getString("error"))
        assertEquals("sustained_playback", source.getString("health"))
        assertEquals(measured, state.stats.getValue(sourceId(regional.url)))
    }

    @Test fun providerNamesChannelNamesAndErrorsCannotExposeUrlPathsOrTokens() {
        val sourceUrl = "https://source.example/source-secret/live.m3u8?key=source-token"
        val name = "频道 https://channel.example/channel-secret/live?token=channel-token"
        val provider = "订阅 https://provider.example/provider-secret/list?key=provider-token"
        val error = "Failed https://error.example/error-secret/live?key=error-token"
        val state = CatalogState(entries = listOf(regional.copy(name = name, url = sourceUrl, subscriptionId = "private")),
            subscriptions = listOf(Subscription("private", provider, "https://provider.example/unexported-subscription", 0)),
            stats = mapOf(sourceId(sourceUrl) to SourceStat(attempts = 1, failures = 1, error = error, lastOutcome = "failed")))
        val report = CatalogDiagnostics.create(state, "TV", "0.1.2", "quality_first", 0)
        for (secret in listOf("source-secret", "source-token", "channel-secret", "channel-token",
            "provider-secret", "provider-token", "error-secret", "error-token", "unexported-subscription")) {
            assertFalse("Leaked $secret", report.contains(secret))
        }
        val source = JSONObject(report).getJSONArray("sources").getJSONObject(0)
        assertTrue(source.getString("channel").startsWith("频道 https://channel.example/"))
        assertTrue(source.getJSONArray("providers").getString(0).startsWith("订阅 https://provider.example/"))
        assertEquals(DiagnosticRedaction.origin(sourceUrl), source.getString("origin"))
        assertTrue(source.getString("error").startsWith("Failed https://error.example/"))
        assertEquals("failed", source.getString("health"))
    }

    @Test fun missingSubscriptionAndUntestedSourcesHaveExplicitDefaultsAndRouteGroups() {
        val state = CatalogState(entries = listOf(
            regional.copy(url = "https://public.example/live", subscriptionId = "builtin-reviewed"),
            regional.copy(url = "https://custom.example/live", subscriptionId = "missing")))
        val root = JSONObject(CatalogDiagnostics.create(state, "TV", "0.1.2", "quality_first", 0))
        val sources = root.getJSONArray("sources")
        assertEquals(2, sources.length())
        assertEquals("public_fallback", sources.getJSONObject(0).getString("routeGroup"))
        assertEquals("custom", sources.getJSONObject(1).getString("routeGroup"))
        assertEquals("quality_first", root.getString("routingPreference"))
        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            assertEquals("本地频道表", source.getJSONArray("providers").getString(0))
            assertEquals("untested", source.getString("health"))
            assertEquals(0, source.getInt("attempts"))
            assertEquals(0, source.getInt("successes"))
            assertFalse(source.getBoolean("audioStarted"))
        }
        assertTrue(state.stats.isEmpty())
    }
}