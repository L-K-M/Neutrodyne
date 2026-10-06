// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Scrollbar style provided through [LocalScrollbars] (08 Desktop windows). A `null` local disables
 * thumb drawing; the desktop shell provides a non-null style so `Modifier.ndScrollbar` paints an
 * overlay thumb on desktop scrollables.
 *
 * Deviation (08 "Nd wrappers"): the stock `VerticalScrollbar`/`HorizontalScrollbar` are sibling
 * composables and cannot hang off a `Modifier`, so the documented 8 dp / 25 % onSurface style is
 * drawn in `drawWithContent` instead. Drag-to-scroll is not implemented at M0a.
 */
public class NdScrollbarStyle(
    public val enabled: Boolean,
    public val thickness: Dp = THICKNESS,
    public val cornerRadius: Dp = CORNER_RADIUS,
    public val minThumb: Dp = MIN_THUMB,
    public val alpha: Float = ALPHA,
) {
    public companion object {
        public val Disabled: NdScrollbarStyle = NdScrollbarStyle(enabled = false)
    }
}

private val THICKNESS = 8.dp
private val CORNER_RADIUS = 4.dp
private val MIN_THUMB = 24.dp
private const val ALPHA = 0.25f

public val LocalScrollbars: androidx.compose.runtime.ProvidableCompositionLocal<NdScrollbarStyle?> =
    staticCompositionLocalOf { null }

/** Vertical overlay scrollbar for a `LazyColumn`, drawn only when [LocalScrollbars] is enabled. */
public fun Modifier.ndScrollbar(state: LazyListState): Modifier = composed {
    val style = LocalScrollbars.current
    val thumbColor = scrollbarColor(style)
    drawWithContent {
        drawContent()
        if (style == null || !style.enabled) return@drawWithContent
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo.size
        if (total <= visible || total == 0) return@drawWithContent
        val viewport = info.viewportSize.height.toFloat()
        val first = info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
        val itemProgress = first.index + if (first.size > 0) -first.offset / first.size.toFloat() else 0f
        val track = size.height
        val thumb = (track * visible / total).coerceAtLeast(style.minThumb.toPx())
        val travel = track - thumb
        val offset = (itemProgress / (total - visible).coerceAtLeast(1)) * travel
        drawThumb(thumbColor, style, viewport, thumb, offset.coerceIn(0f, travel))
    }
}

/** Vertical overlay scrollbar for a `LazyVerticalGrid`. */
public fun Modifier.ndScrollbar(state: LazyGridState): Modifier = composed {
    val style = LocalScrollbars.current
    val thumbColor = scrollbarColor(style)
    drawWithContent {
        drawContent()
        if (style == null || !style.enabled) return@drawWithContent
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo.size
        if (total <= visible || total == 0) return@drawWithContent
        val track = size.height
        val thumb = (track * visible / total).coerceAtLeast(style.minThumb.toPx())
        val travel = track - thumb
        val first = info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
        val itemProgress = first.index + if (first.size.height > 0) -first.offset.y / first.size.height.toFloat() else 0f
        val offset = (itemProgress / (total - visible).coerceAtLeast(1)) * travel
        drawThumb(thumbColor, style, size.height, thumb, offset.coerceIn(0f, travel))
    }
}

/** Vertical overlay scrollbar for `Modifier.verticalScroll`. */
public fun Modifier.ndScrollbar(state: ScrollState): Modifier = composed {
    val style = LocalScrollbars.current
    val thumbColor = scrollbarColor(style)
    drawWithContent {
        drawContent()
        if (style == null || !style.enabled) return@drawWithContent
        val contentSize = (state.maxValue + size.height).coerceAtLeast(1f)
        if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return@drawWithContent
        val track = size.height
        val thumb = (track * size.height / contentSize).coerceAtLeast(style.minThumb.toPx())
        val travel = track - thumb
        val offset = (state.value / state.maxValue.toFloat()) * travel
        drawThumb(thumbColor, style, track, thumb, offset.coerceIn(0f, travel))
    }
}

@Composable
private fun scrollbarColor(style: NdScrollbarStyle?): Color {
    if (style == null || !style.enabled) return Color.Transparent
    val base = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    return base.copy(alpha = style.alpha)
}

private fun DrawScope.drawThumb(
    color: Color,
    style: NdScrollbarStyle,
    track: Float,
    thumb: Float,
    offset: Float,
) {
    val width = style.thickness.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width, offset),
        size = Size(width, thumb),
        cornerRadius = CornerRadius(style.cornerRadius.toPx()),
    )
}
