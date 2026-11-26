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

    @Test fun resolvesAptvStyleAliasesCctvChineseAndTraditionalNames() {
        val index = mapOf(
            "cctv1" to "cctv-1.webp",
            "cctv5+" to "cctv-5plus.webp",
            "cgtn西班牙语" to "cgtn-es.webp",
            "cgtn阿拉伯语" to "cgtn-ar.webp",
            "湖南卫视" to "hunan.webp",
            "凤凰中文" to "phoenix.webp"
        )
        assertEquals("cctv-1.webp", ChannelLogoMatcher.resolveAssetFile(Channel("name:c1", "CCTV-01 综合高清", "央视", emptyList()), index))
        assertEquals("cctv-1.webp", ChannelLogoMatcher.resolveAssetFile(Channel("name:c1cn", "中央一套", "央视", emptyList()), index))
        assertEquals("cctv-5plus.webp", ChannelLogoMatcher.resolveAssetFile(Channel("id:CCTV5PLUS", "CCTV5+体育赛事", "央视", emptyList()), index))
        assertEquals("cgtn-es.webp", ChannelLogoMatcher.resolveAssetFile(Channel("id:CGTN西语", "CGTN西语", "央视", emptyList()), index))
        assertEquals("cgtn-ar.webp", ChannelLogoMatcher.resolveAssetFile(Channel("id:CGTN阿语", "CGTN阿语", "央视", emptyList()), index))
        assertEquals("hunan.webp", ChannelLogoMatcher.resolveAssetFile(Channel("name:hn", "湖南高清[IPV4]", "卫视", emptyList()), index))
        assertEquals("phoenix.webp", ChannelLogoMatcher.resolveAssetFile(Channel("name:ph", "鳳凰衛視中文台", "资讯", emptyList()), index))
    }

    @Test fun parsesUnencodedChineseTvgLogoAndUnwrapsGithubProxyToJsDelivr() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="slam" tvg-logo="https://ghproxy.net/raw.githubusercontent.com/suxuang/myIPTV/main/img/灌篮高手.png",灌篮高手
            http://example.invalid/live.m3u8
            #EXTINF:-1 tvg-id="anime" tvg-logo="https://live.fanmingming.cn/tv/动漫秀场.png",动漫秀场
            http://example.invalid/live2.m3u8
        """.trimIndent()
        val channels = CatalogState(entries = M3uParser.parse(m3u).entries).channels()
        val slam = channels.first { it.name == "灌篮高手" }
        assertTrue(slam.logoUrl.contains("%E7%81%8C%E7%AF%AE%E9%AB%98%E6%89%8B.png"))
        val slamCandidates = Network.logoCandidates(slam.logoUrl)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/suxuang/myIPTV@main/img/%E7%81%8C%E7%AF%AE%E9%AB%98%E6%89%8B.png",
            slamCandidates.first()
        )

        val anime = channels.first { it.name == "动漫秀场" }
        val animeCandidates = Network.logoCandidates(anime.logoUrl)
        assertTrue(animeCandidates.any { it == "https://cdn.jsdelivr.net/gh/fanmingming/live@main/tv/%E5%8A%A8%E6%BC%AB%E7%A7%80%E5%9C%BA.png" })
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

    @Test fun publicFallbackGeneratesEncodedCdnUrlsForUnbundledChannels() {
        val channel = Channel("id:jade", "翡翠台 HD", "影视", emptyList())
        val names = ChannelLogoMatcher.publicLookupNames(channel)
        assertTrue(names.contains("翡翠台"))
        val urls = Network.publicLogoFallbackCandidates(names)
        assertTrue(urls.any { it == "https://cdn.jsdelivr.net/gh/fanmingming/live@main/tv/%E7%BF%A1%E7%BF%A0%E5%8F%B0.png" })
    }
}
