package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

/** Navigation regressions: focus, playing ID and favorites must remain independent. */
class SidebarSelectionTest {
    private fun channel(id: String, group: String = "央视", name: String = id, suffix: String = "original") =
        Channel(id, name, group, listOf(Entry(id, name, group, "https://example.test/$id/$suffix.m3u8")))

    private val five = channel("cctv-5")
    private val six = channel("cctv-6")
    private val hunan = channel("hunan", "卫视")
    private val zhejiang = channel("zhejiang", "卫视")
    private val catalog = listOf(five, six, hunan, zhejiang)

    @Test fun categoriesAreDistinctAndKeepImportedGroupOrder() {
        val state = SidebarSelection()
        state.update(catalog + channel("custom", "地方") + channel("blank", "") + channel("reserved", "收藏"), emptySet())
        assertEquals(listOf("全部", "收藏", "央视", "卫视", "地方"), state.groups)
    }

    @Test fun switchingCategoriesFiltersWithoutChangingTheCatalogOrFavorites() {
        val favorites = setOf(six.id, hunan.id)
        val state = SidebarSelection()
        state.update(catalog, favorites)
        assertEquals(catalog, state.shown)
        state.selectGroup("央视")
        assertEquals(listOf(five, six), state.shown)
        state.selectGroup("卫视")
        assertEquals(listOf(hunan, zhejiang), state.shown)
        state.selectGroup("收藏")
        assertEquals(listOf(six, hunan), state.shown)
        assertSame(catalog, state.channels)
        assertEquals(favorites, state.favorites)
    }

    @Test fun emptyFavoritesHasNoInvisibleSelectedChannel() {
        val state = SidebarSelection()
        state.update(catalog, emptySet())
        state.selectGroup("收藏")
        assertTrue(state.shown.isEmpty())
        assertNull(state.selectedId)
        assertNull(state.selected())
    }

    @Test fun aPlaylistGroupNamedFavoritesDoesNotLeakUnfavoritedChannelsIntoFavorites() {
        val imported = channel("imported", "收藏")
        val state = SidebarSelection()
        state.update(catalog + imported, setOf(five.id))
        state.selectGroup("收藏")
        assertEquals(listOf(five), state.shown)
    }

    @Test fun movingFocusDoesNotReplaceThePlayingChannelOrChangeFavorites() {
        val state = SidebarSelection()
        val playing = five
        val favorites = setOf(five.id)
        state.update(catalog, favorites)
        state.open(playing.id)
        state.focus(six.id)
        assertEquals(six.id, state.selectedId)
        assertEquals(five.id, playing.id)
        assertSame(five, state.channels.first())
        assertEquals(favorites, state.favorites)
        state.selectGroup("收藏")
        assertEquals(listOf(five), state.shown)
    }

    @Test fun focusIsRememberedIndependentlyForEachCategory() {
        val state = SidebarSelection()
        state.update(catalog, setOf(five.id, hunan.id))
        state.focus(six.id)
        state.selectGroup("央视")
        state.focus(five.id)
        state.selectGroup("卫视")
        state.focus(zhejiang.id)
        state.selectGroup("收藏")
        state.focus(hunan.id)
        state.selectGroup("全部")
        assertEquals(six.id, state.selectedId)
        state.selectGroup("央视")
        assertEquals(five.id, state.selectedId)
        state.selectGroup("卫视")
        assertEquals(zhejiang.id, state.selectedId)
        state.selectGroup("收藏")
        assertEquals(hunan.id, state.selectedId)
    }

    @Test fun openingWithPlayingChannelOutsideCurrentCategoryReturnsToAll() {
        val state = SidebarSelection()
        state.update(catalog, emptySet())
        state.selectGroup("卫视")
        state.focus(zhejiang.id)
        state.open(five.id)
        assertEquals("全部", state.group)
        assertEquals(five.id, state.selectedId)
        state.selectGroup("卫视")
        assertEquals(zhejiang.id, state.selectedId)
    }

    @Test fun openingWithPlayingChannelInsideCurrentCategoryKeepsThatCategory() {
        val state = SidebarSelection()
        state.update(catalog, emptySet())
        state.selectGroup("央视")
        state.focus(six.id)
        state.open(five.id)
        assertEquals("央视", state.group)
        assertEquals(five.id, state.selectedId)
    }

    @Test fun reopeningWithoutAPlayingChannelKeepsTheNavigationLocation() {
        val state = SidebarSelection()
        state.update(catalog, emptySet())
        state.selectGroup("卫视")
        state.focus(zhejiang.id)
        state.open(null)
        assertEquals("卫视", state.group)
        assertEquals(zhejiang.id, state.selectedId)
    }

    @Test fun unknownCategoriesAndOffscreenChannelIdsCannotCreateInvisibleFocus() {
        val state = SidebarSelection()
        state.update(catalog, emptySet())
        state.selectGroup("央视")
        state.focus(six.id)
        state.selectGroup("不存在")
        state.focus(hunan.id)
        state.focus("deleted")
        assertEquals("央视", state.group)
        assertEquals(six.id, state.selectedId)
    }

    @Test fun deletingFocusedChannelFallsBackToAnExistingRowInTheSameCategory() {
        val state = SidebarSelection()
        state.update(catalog, emptySet())
        state.selectGroup("央视")
        state.focus(six.id)
        state.update(listOf(five, hunan), emptySet())
        assertEquals("央视", state.group)
        assertEquals(five.id, state.selectedId)
        assertSame(five, state.selected())
    }

    @Test fun deletingTheWholeCurrentCategoryFallsBackToAll() {
        val state = SidebarSelection()
        state.update(catalog, emptySet())
        state.selectGroup("卫视")
        state.focus(zhejiang.id)
        state.update(listOf(five, six), emptySet())
        assertEquals("全部", state.group)
        assertEquals(five.id, state.selectedId)
        assertFalse("卫视" in state.groups)
    }

    @Test fun directoryRefreshResolvesSelectedIdToTheNewChannelAndSources() {
        val state = SidebarSelection()
        state.update(catalog, emptySet())
        state.focus(six.id)
        val refreshed = channel(six.id, name = "CCTV-6 电影", suffix = "updated")
        state.update(listOf(five, refreshed, hunan), emptySet())
        assertEquals(six.id, state.selectedId)
        assertSame(refreshed, state.selected())
        assertEquals("CCTV-6 电影", state.selected()?.name)
        assertEquals(refreshed.sources, state.selected()?.sources)
        assertNotEquals(six.sources, state.selected()?.sources)
    }

    @Test fun removingLastFavoriteClearsSelectionAndNewFavoriteCanBeSelected() {
        val state = SidebarSelection()
        state.update(catalog, setOf(six.id))
        state.selectGroup("收藏")
        state.focus(six.id)
        state.update(catalog, emptySet())
        assertEquals("收藏", state.group)
        assertTrue(state.shown.isEmpty())
        assertNull(state.selected())
        state.update(catalog, setOf(hunan.id))
        assertEquals(hunan.id, state.selectedId)
        assertSame(hunan, state.selected())
    }

    @Test fun emptyRefreshedCatalogLeavesOnlyBaseCategoriesAndNoStaleSelection() {
        val state = SidebarSelection()
        state.update(catalog, setOf(six.id))
        state.selectGroup("央视")
        state.focus(six.id)
        state.update(emptyList(), emptySet())
        assertEquals("全部", state.group)
        assertEquals(listOf("全部", "收藏"), state.groups)
        assertTrue(state.shown.isEmpty())
        assertNull(state.selectedId)
        assertNull(state.selected())
    }
}
