package app.swifttv.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Viewport-relative panel dimensions in dp; independent of Android display APIs. */
object SidebarGeometry {
    data class Layout(
        val widthDp: Int,
        val heightDp: Int,
        val edgeMarginDp: Int,
        val paddingDp: Int
    )

    /**
     * Prefer a narrow 30% panel on a TV, while letting small windows and large text use
     * more horizontal space. No content minimum is allowed to exceed the viewport.
     * The caller should use its available content bounds, after system insets.
     */
    fun calculate(viewportWidthDp: Int, viewportHeightDp: Int, fontScale: Float = 1f): Layout {
        if (viewportWidthDp <= 0 || viewportHeightDp <= 0) return Layout(0, 0, 0, 0)
        val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 3f) else 1f
        val shortSide = min(viewportWidthDp, viewportHeightDp)
        // Preserve an edge at TV sizes, then surrender it before content is clipped.
        val edge = min((shortSide * .035f).roundToInt().coerceIn(8, 28), (shortSide - 1) / 2)
        val availableWidth = viewportWidthDp - edge * 2
        val availableHeight = viewportHeightDp - edge * 2
        val readableWidth = (304f + 168f * (scale - 1f)).roundToInt()
        val width = max((viewportWidthDp * .30f).roundToInt(), readableWidth)
            .coerceIn(1, availableWidth)
        // Extra font size consumes vertical space: favor list rows over decorative margin.
        val heightFraction = if (viewportHeightDp < 320) 1f else .84f + .02f * (scale - 1f).coerceAtMost(1f)
        val height = min(640, (viewportHeightDp * heightFraction).roundToInt())
            .coerceIn(1, availableHeight)
        val padding = min((shortSide * .025f).roundToInt().coerceIn(12, 20), (min(width, height) - 1) / 2)
        return Layout(width, height, edge, padding)
    }
}
