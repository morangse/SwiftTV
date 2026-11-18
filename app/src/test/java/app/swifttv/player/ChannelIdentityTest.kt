package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class ChannelIdentityTest {
    @Test fun expandedCctvStationsHaveExplicitStableIdentities() {
        val titles = mapOf(2 to "财经", 3 to "综艺", 4 to "中文国际", 7 to "国防军事", 8 to "电视剧", 10 to "科教",
            11 to "戏曲", 12 to "社会与法", 14 to "少儿", 15 to "音乐", 16 to "奥林匹克", 17 to "农业农村")
        titles.forEach { (number, title) ->
            val expected = "cctv-$number" to "CCTV-$number"
            assertEquals(expected, ChannelIdentity.resolve("CCTV$number.CN@HD", "CCTV-$number $title HD"))
            assertEquals(expected, ChannelIdentity.resolve("", "CCTV-$number$title（高清）"))
        }
    }

    @Test fun fivePlusIsSeparateAndAcceptsExplicitSportsEventTitle() {
        for (name in listOf("CCTV-5+", "CCTV5Plus", "CCTV5＋体育赛事 HD")) {
            assertEquals("cctv-5+" to "CCTV-5+", ChannelIdentity.resolve("", name))
        }
        assertEquals("cctv-5", ChannelIdentity.resolve("CCTV5.cn", "CCTV-5体育").first)
        assertNotEquals("cctv-5", ChannelIdentity.resolve("", "CCTV-5体育赛事").first)
    }

    @Test fun fourRegionalEditionsNeverMergeWithGenericFourOrEachOther() {
        val generic = ChannelIdentity.resolve("CCTV4.cn", "CCTV-4中文国际").first
        val editions = listOf("CCTV-4中文国际欧洲", "CCTV-4中文国际美洲", "CCTV-4(欧洲)", "CCTV-4[美洲]")
            .map { ChannelIdentity.resolve("CCTV4.cn", it).first }
        assertTrue(editions.none { it == generic })
        assertEquals(editions.size, editions.toSet().size)
        assertNotEquals(generic, ChannelIdentity.resolve("CCTV4Europe.cn", "CCTV-4").first)
        assertNotEquals(generic, ChannelIdentity.resolve("CCTV4America.cn", "CCTV-4").first)
    }

    @Test fun ultraHdAndCgtnDeclarationsCannotFallBackToNormalNumberedChannels() {
        assertEquals("cctv-16", ChannelIdentity.resolve("CCTV16.cn", "CCTV-16奥林匹克").first)
        for ((id, name) in listOf("CCTV16.cn" to "CCTV-16(4K)", "CCTV16.cn" to "CCTV-16奥林匹克8K",
            "CCTV4K.cn" to "CCTV-4", "CGTNDocumentary.cn" to "CCTV-9", "CCTV13.cn" to "CGTN News")) {
            assertFalse(ChannelIdentity.resolve(id, name).first.startsWith("cctv-"))
        }
        assertNotEquals(ChannelIdentity.resolve("CCTV16.cn", "CCTV-16(4K)").first,
            ChannelIdentity.resolve("CCTV16.cn", "CCTV-16(8K)").first)
    }

    @Test fun knownResolutionDecorationsMergeButCustomBracketIdentitySurvives() {
        assertEquals("cctv-2", ChannelIdentity.resolve("", "CCTV-2 财经 [1080p]").first)
        assertEquals("cctv-14", ChannelIdentity.resolve("", "CCTV-14少儿(1920x1080)").first)
        val morning = ChannelIdentity.resolve("", "家庭新闻(早间)")
        val evening = ChannelIdentity.resolve("", "家庭新闻(晚间)")
        assertEquals("家庭新闻(早间)", morning.second)
        assertNotEquals(morning.first, evening.first)
        assertEquals("id:private.local", ChannelIdentity.resolve("Private.Local", "我的自定义频道").first)
    }

    @Test fun unsupportedNumbersAndInventedPlusStationsRemainCustom() {
        for (name in listOf("CCTV-0", "CCTV-18", "CCTV-99", "CCTV-1+", "CCTV-6Plus", "CCTV-17农业农村国际")) {
            assertFalse(ChannelIdentity.resolve("", name).first.startsWith("cctv-"))
        }
    }
}
