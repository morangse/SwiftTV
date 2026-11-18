package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class BuiltinPolicyTest {
    @Test fun lowEndProfileGates4kCctv16While4kCapableProfileUnlocksAll24Channels() {
        val fixture = buildString {
            appendLine("#EXTM3U")
            BuiltinPolicy.channelOrder.forEach { id ->
                appendLine("#EXTINF:-1 tvg-id=\"$id\"," + BuiltinPolicy.names.getValue(id))
                if (id == "cctv-16") appendLine("#EXTVLCOPT:swifttv-profile=4k-hevc50")
                appendLine("https://test.example/$id.m3u8")
            }
        }
        val entries = BuiltinPolicy.curate(M3uParser.parse(fixture)).entries
        assertEquals(24, entries.size)
        // Profile flag must never pollute outgoing HTTP headers.
        assertTrue(entries.all { "swifttv-profile" !in it.headers.keys.map(String::lowercase) })

        val lowEnd = BuiltinPolicy.withMissingChannels(CatalogState(entries = entries).channels(), supports4kHevc50 = false)
        assertEquals(BuiltinPolicy.channelOrder, lowEnd.map { it.id })
        assertEquals(24, lowEnd.size)
        assertEquals(23, lowEnd.count { it.sources.isNotEmpty() })
        assertEquals(listOf("cctv-16"), lowEnd.filter { it.sources.isEmpty() }.map { it.id })
        assertTrue(lowEnd.first { it.id == "cctv-16" }.unavailableReason.contains("4K"))

        val highEnd = BuiltinPolicy.withMissingChannels(CatalogState(entries = entries).channels(), supports4kHevc50 = true)
        assertEquals(24, highEnd.size)
        assertEquals(24, highEnd.count { it.sources.isNotEmpty() })
        assertTrue(highEnd.all { it.sources.size <= 2 })
        assertTrue(highEnd.all { it.sources.map { s -> s.url }.distinct().size == it.sources.size })
    }

    private fun channel(id: String, url: String) = Channel(id, id, "央视", listOf(Entry(id, id, "央视", url)))

    @Test fun respectsLastPlayableChannelOverDefaultAndSkipsMissing() {
        val channels = listOf(channel("hunan", "https://a.example/hunan"), channel("cctv-1", "https://a.example/one"), Channel("cctv-5", "CCTV-5", "央视", emptyList()))
        assertEquals("cctv-1", BuiltinPolicy.chooseStart(channels, "cctv-1", emptyMap())!!.id)
        assertEquals("hunan", BuiltinPolicy.chooseStart(channels, "cctv-5", emptyMap())!!.id)
    }

    @Test fun upgradingReplacesLegacyProvidersAndKeepsUserData() {
        val custom = Entry("cctv-1", "CCTV-1", "央视", "https://custom.example/one", subscriptionId = "custom")
        val old = custom.copy(url = "https://dead.example/one", subscriptionId = "builtin-community")
        val health = mapOf(sourceId(custom.url) to SourceStat(successes = 1))
        val state = CatalogState(listOf(old, custom), listOf(Subscription("builtin-community", "old", "https://old.example/list", 0), Subscription("custom", "Mine", "https://custom.example/list", 0)), setOf("cctv-1"), "cctv-1", health)
        val fresh = ParsedPlaylist(listOf(custom.copy(url = "https://new.example/one", subscriptionId = "")), 0)
        val sub = Subscription(BuiltinCatalog.ID, "Reviewed", "", 1)
        val migrated = BuiltinPolicy.migrate(state, sub, fresh, BuiltinCatalog.REVISION)
        assertEquals(listOf(custom, fresh.entries.single().copy(subscriptionId = sub.id)), migrated.entries)
        assertFalse(migrated.subscriptions.any { it.id == "builtin-community" })
        assertEquals(state.favorites, migrated.favorites); assertEquals(state.lastChannel, migrated.lastChannel); assertEquals(health, migrated.stats)
        assertEquals(BuiltinCatalog.REVISION, migrated.builtinRevision)
        assertEquals(migrated, BuiltinPolicy.migrate(migrated, sub, fresh, BuiltinCatalog.REVISION))
        assertEquals(migrated, CatalogStore.decode(CatalogStore.encode(migrated)))
    }

    @Test fun numericTvgIdUsesExplicitNameAndDoesNotConfuseCctvTenWithOne() {
        val parsed = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"572136\" tvg-name=\"CCTV-10\",CCTV-1 综合\nhttps://example.com/cctv10\n")
        assertEquals("cctv-10", parsed.entries.single().channelId)
        assertEquals("cctv-10", BuiltinPolicy.curate(parsed).entries.single().channelId)
        assertFalse(BuiltinPolicy.curate(parsed).entries.any { it.channelId == "cctv-1" })
    }

    @Test fun hlsMediaPlaylistCannotReplaceBuiltinCatalog() {
        assertTrue(runCatching { BuiltinPolicy.curate(M3uParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:5\nhttps://example.com/segment.ts")) }.isFailure)
    }
}
