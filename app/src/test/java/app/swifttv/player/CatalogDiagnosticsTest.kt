package app.swifttv.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogDiagnosticsTest {
    private val v6Entry = Entry("cctv-1", "CCTV-1", "央视",
        "http://[2409:8087:8:21::18]:6610/live/fixture.m3u8",
        subscriptionId = "sub-v6")

    @Test fun sameUrlIsOneSourceWithAllProviderLabelsAndMeasuredStatistics() {
        val measured = SourceStat(attempts = 7, successes = 4, firstFrameMs = 973, rebufferMs = 321,
            playedMs = 90000, checkedAt = 12345, width = 1920, height = 1080, codec = "video/avc",
            audioStarted = true, failures = 1, cancelled = 2, lastOutcome = "playing")
        val state = CatalogState(
            entries = listOf(v6Entry, v6Entry.copy(subscriptionId = "custom"), v6Entry),
            subscriptions = listOf(
                Subscription("sub-v6", "主订阅", "https://one.example/list.m3u", 0),
                Subscription("custom", "用户订阅", "https://provider.example/list.m3u", 0)
            ),
            stats = mapOf(sourceId(v6Entry.url) to measured)
        )
        val root = JSONObject(CatalogDiagnostics.create(state, "Sony Android 10", "0.4.0", "auto", 45678))
        assertEquals(4, root.getInt("schemaVersion"))
        assertFalse(root.has("builtinRevision"))
        assertEquals("0.4.0", root.getString("appVersion"))
        assertEquals("Sony Android 10", root.getString("device"))
        assertEquals(45678L, root.getLong("exportedAt"))
        assertEquals("auto", root.getString("routingPreference"))
        val sources = root.getJSONArray("sources")
        assertEquals(1, sources.length())
        val source = sources.getJSONObject(0)
        assertEquals("ipv6", source.getString("routeGroup"))
        assertEquals("CCTV-1", source.getString("channel"))
        assertEquals(DiagnosticRedaction.origin(v6Entry.url), source.getString("origin"))
        assertTrue(source.getString("origin").endsWith("#" + sourceId(v6Entry.url)))
        val providers = source.getJSONArray("providers")
        assertEquals(2, providers.length())
        assertEquals("主订阅", providers.getString(0))
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
        assertEquals(measured, state.stats.getValue(sourceId(v6Entry.url)))
    }

    @Test fun providerNamesChannelNamesAndErrorsCannotExposeUrlPathsOrTokens() {
        val sourceUrl = "https://source.example/source-secret/live.m3u8?key=source-token"
        val name = "频道 https://channel.example/channel-secret/live?token=channel-token"
        val provider = "订阅 https://provider.example/provider-secret/list?key=provider-token"
        val error = "Failed https://error.example/error-secret/live?key=error-token"
        val state = CatalogState(
            entries = listOf(v6Entry.copy(name = name, url = sourceUrl, subscriptionId = "private")),
            subscriptions = listOf(Subscription("private", provider, "https://provider.example/unexported-subscription", 0)),
            stats = mapOf(sourceId(sourceUrl) to SourceStat(attempts = 1, failures = 1, error = error, lastOutcome = "failed"))
        )
        val report = CatalogDiagnostics.create(state, "TV", "0.4.0", "ipv4_first", 0)
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

    @Test fun missingSubscriptionAndUntestedSourcesHaveExplicitDefaultsAndProtocolRouteGroups() {
        val state = CatalogState(entries = listOf(
            v6Entry.copy(subscriptionId = "missing-a"),
            v6Entry.copy(url = "https://public.example/live", subscriptionId = "missing-b")
        ))
        val root = JSONObject(CatalogDiagnostics.create(state, "TV", "0.4.0", "ipv4_first", 0))
        val sources = root.getJSONArray("sources")
        assertEquals(2, sources.length())
        assertEquals("ipv6", sources.getJSONObject(0).getString("routeGroup"))
        assertEquals("ipv4", sources.getJSONObject(1).getString("routeGroup"))
        assertEquals("ipv4_first", root.getString("routingPreference"))
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
