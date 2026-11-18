package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class ChannelLogoMatcherTest {
    @Test fun missingIndexFallsBackWithoutMatchingAnUnrelatedChannel() {
        val channel = Channel("cctv-1", "CCTV-1", "央视", emptyList())
        assertNull(ChannelLogoMatcher.resolveAssetFile(channel, emptyMap()))
        assertNull(ChannelLogoMatcher.resolveAssetFile(channel, mapOf("cctv-2" to "other.webp")))
    }

    @Test fun resolvesExactIdsAndRecognizesQualitySuffixes() {
        val index = mapOf("cctv-5+" to "sports-plus.webp", "广东卫视" to "guangdong.webp")
        val sports = Channel("cctv-5+", "赛事", "央视", emptyList())
        val guangdong = Channel("name:gd", "广东卫视高清", "卫视", emptyList())
        assertEquals("sports-plus.webp", ChannelLogoMatcher.resolveAssetFile(sports, index))
        assertEquals("guangdong.webp", ChannelLogoMatcher.resolveAssetFile(guangdong, index))
    }

    @Test fun parsesTvgLogoAndMatchesEncodedLogoStemFallback() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="example" tvg-logo="https://example.invalid/logos/%E5%AE%89%E5%BE%BD%E5%8D%AB%E8%A7%86.png",Example station
            https://example.invalid/stream.m3u8
        """.trimIndent()
        val channel = CatalogState(entries = M3uParser.parse(m3u).entries).channels().single()
        assertEquals("anhui.webp", ChannelLogoMatcher.resolveAssetFile(channel, mapOf("安徽卫视" to "anhui.webp")))
    }

    @Test fun githubLogoUrlsGenerateJsDelivrMirrorFallbacks() {
        val pages = Network.logoCandidates("https://example.github.io/test-playlist/logos/station.png")
        assertTrue(pages.any { it.contains("cdn.jsdelivr.net/gh/example/test-playlist@main/public/logos/station.png") })
    }
}
