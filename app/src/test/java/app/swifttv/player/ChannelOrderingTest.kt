package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class ChannelOrderingTest {
    private fun channel(id: String, group: String = "自定义分组") = Channel(id, id, group, emptyList())

    @Test fun numberedCctvStationsFollowNumericOrderWithFivePlusAfterFive() {
        val expected = (1..17).flatMap { if (it == 5) listOf("cctv-5", "cctv-5+") else listOf("cctv-$it") }
        val actual = ChannelOrdering.order(expected.reversed().map { channel(it) })
        assertEquals(expected, actual.map { it.id })
        assertEquals(expected, ChannelOrdering.numberedCctvIds)
    }

    @Test fun satelliteAndCustomChannelsRetainTheirOriginalPositionsAndOrder() {
        val input = listOf("hunan", "cctv-13", "id:family-news", "cctv-2", "henan", "cctv-5+", "cctv-5", "id:cgtn", "cctv-1")
        val expected = listOf("hunan", "cctv-1", "id:family-news", "cctv-2", "henan", "cctv-5", "cctv-5+", "id:cgtn", "cctv-13")
        assertEquals(expected, ChannelOrdering.order(input.map { channel(it) }).map { it.id })
    }

    @Test fun customAndUltraHdIdentifiersAreNotTreatedAsNumberedCctv() {
        val input = listOf("id:cctv4europe", "cctv-16", "name:cctv-16(4k)", "cctv-4", "cctv-18", "cctv-1+", "id:cgtn")
        val expected = listOf("id:cctv4europe", "cctv-4", "name:cctv-16(4k)", "cctv-16", "cctv-18", "cctv-1+", "id:cgtn")
        assertEquals(expected, ChannelOrdering.order(input.map { channel(it) }).map { it.id })
    }

    @Test fun catalogProjectionSortsOnceWithoutChangingSourcesOrUserState() {
        val thirteen = Entry("cctv-13", "CCTV-13", "用户央视", "https://one.example/news", mapOf("User-Agent" to "TV"), "custom-sub")
        val two = Entry("cctv-2", "CCTV-2", "用户财经", "https://two.example/business", subscriptionId = "custom-sub")
        val state = CatalogState(entries = listOf(thirteen, two, thirteen), favorites = setOf("cctv-13"), lastChannel = "cctv-13")
        val channels = state.channels()
        assertEquals(listOf("cctv-2", "cctv-13"), channels.map { it.id })
        assertEquals(listOf(thirteen), channels.last().sources)
        assertEquals("用户央视", channels.last().group)
        assertEquals(setOf("cctv-13"), state.favorites)
        assertEquals("cctv-13", state.lastChannel)
        assertEquals(channels, ChannelOrdering.order(channels))
    }
}
