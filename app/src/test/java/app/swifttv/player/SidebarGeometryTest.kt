package app.swifttv.player

import org.junit.Assert.*
import org.junit.Test

class SidebarGeometryTest {
    @Test fun normalTelevisionPanelLeavesVideoVisible() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 3840 to 2160)) {
            val panel = SidebarGeometry.calculate(width, height)
            val widthRatio = panel.widthDp.toFloat() / width
            assertTrue("Panel width at $width", widthRatio in .29f..31f)
            assertTrue("Panel must retain a safe edge", panel.edgeMarginDp >= 16)
            assertTrue("Large panels should not become full-height", panel.heightDp <= 640)
            assertContained(width, height, panel)
        }
    }

    @Test fun sevenTwentyHeightPanelUsesCompactVerticalSpace() {
        val panel = SidebarGeometry.calculate(1280, 720)
        assertTrue(panel.heightDp.toFloat() / 720 in .80f..86f)
        assertTrue("Header and list need usable inner width", panel.widthDp - panel.paddingDp * 2 >= 340)
        assertTrue("The panel must leave visible video below", 720 - panel.heightDp >= 80)
    }

    @Test fun smallEmulatorWindowPrioritizesReadableContentOverThirtyPercentRule() {
        val panel = SidebarGeometry.calculate(640, 360)
        assertTrue("Thirty percent is too narrow for logo plus title", panel.widthDp >= 300)
        assertTrue("A small window must still leave video visible", panel.widthDp <= 400)
        assertTrue("List area needs useful height", panel.heightDp - panel.paddingDp * 2 >= 260)
        assertContained(640, 360, panel)
    }

    @Test fun fontScalingExpandsPanelWithoutTakingSpaceAwayFromRows() {
        for ((width, height) in listOf(1280 to 720, 640 to 360, 360 to 640, 240 to 180)) {
            val normal = SidebarGeometry.calculate(width, height, 1f)
            val larger = SidebarGeometry.calculate(width, height, 1.3f)
            val largest = SidebarGeometry.calculate(width, height, 2f)
            assertTrue(larger.widthDp >= normal.widthDp)
            assertTrue(largest.widthDp >= larger.widthDp)
            assertTrue(larger.heightDp >= normal.heightDp)
            assertTrue(largest.heightDp >= larger.heightDp)
            assertTrue(largest.paddingDp <= normal.paddingDp)
            assertContained(width, height, larger)
            assertContained(width, height, largest)
        }
        assertTrue(SidebarGeometry.calculate(640, 360, 2f).widthDp > SidebarGeometry.calculate(640, 360).widthDp)
    }

    @Test fun narrowAndShortWindowsNeverOverflowOrLoseAllInnerSpace() {
        val dimensions = listOf(1, 2, 3, 7, 16, 48, 100, 180, 240, 320, 360, 640, 720, 1080, 1920, 3840)
        for (width in dimensions) for (height in dimensions) for (scale in listOf(1f, 1.3f, 2f, 3f)) {
            val panel = SidebarGeometry.calculate(width, height, scale)
            assertContained(width, height, panel)
            assertTrue("A positive viewport retains positive width", panel.widthDp > 0)
            assertTrue("A positive viewport retains positive height", panel.heightDp > 0)
            assertTrue("Padding leaves inner width", panel.widthDp - panel.paddingDp * 2 > 0)
            assertTrue("Padding leaves inner height", panel.heightDp - panel.paddingDp * 2 > 0)
        }
    }

    @Test fun unmeasuredViewportsReturnEmptyLayoutRatherThanInventingBounds() {
        val empty = SidebarGeometry.Layout(0, 0, 0, 0)
        assertEquals(empty, SidebarGeometry.calculate(0, 720))
        assertEquals(empty, SidebarGeometry.calculate(1280, 0))
        assertEquals(empty, SidebarGeometry.calculate(-1, 720))
        assertEquals(empty, SidebarGeometry.calculate(1280, -1))
    }

    @Test fun invalidFontScalesAreSafeAndSmallerTextDoesNotShrinkNavigation() {
        val baseline = SidebarGeometry.calculate(640, 360)
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f, .85f)) {
            assertEquals(baseline, SidebarGeometry.calculate(640, 360, scale))
        }
        assertContained(640, 360, SidebarGeometry.calculate(640, 360, Float.MAX_VALUE))
    }

    private fun assertContained(width: Int, height: Int, panel: SidebarGeometry.Layout) {
        assertTrue("Horizontal overflow at ${width}x$height: $panel", panel.widthDp.toLong() + panel.edgeMarginDp * 2L <= width)
        assertTrue("Vertical overflow at ${width}x$height: $panel", panel.heightDp.toLong() + panel.edgeMarginDp * 2L <= height)
        assertTrue(panel.edgeMarginDp >= 0)
        assertTrue(panel.paddingDp >= 0)
    }
}
