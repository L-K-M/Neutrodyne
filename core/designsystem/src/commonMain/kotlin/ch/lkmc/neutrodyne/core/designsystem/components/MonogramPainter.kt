// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalSystemUiState
import ch.lkmc.neutrodyne.core.model.art.MonogramMode
import ch.lkmc.neutrodyne.core.model.art.MonogramSpec
import kotlin.math.min

/**
 * Paints a [MonogramSpec] live (08): background and centred initials (`FontWeight.Medium`, size
 * 38 % of the shorter side, at least 12 sp equivalent) in the LIGHT/DARK tone pair. At
 * [MIN_TITLE_SIDE] dp or larger the [title] draws in two `labelMedium` lines below the initials
 * (CoverTile), so tiles are never ambiguous when titles are hidden. In-app monograms are painted
 * this way — never loaded from the raster files.
 */
public class MonogramPainter(
    public val spec: MonogramSpec,
    private val title: String?,
    private val dark: Boolean,
    private val textMeasurer: TextMeasurer,
    private val fontFamilyResolver: FontFamily.Resolver,
    private val titleStyle: TextStyle,
) : Painter() {
    /** The background tone the caller may reuse as an `avgArgb` placeholder (never grey, R5.4). */
    public val backgroundArgb: Int = spec.colors(mode()).first

    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val (bg, fg) = spec.colors(mode())
        drawRect(Color(bg))

        val side = min(size.width, size.height)
        val initialsLayout = measureInitials(fg, side)
        val titleLayout =
            if (title != null && side >= MIN_TITLE_SIDE.toPx()) {
                measureTitle(fg)
            } else {
                null
            }

        // Initials centre in the space left above the title block (or in the whole tile).
        val freeHeight =
            size.height - (titleLayout?.size?.height ?: 0) -
                if (titleLayout != null) TITLE_EDGE_PADDING.toPx() else 0f
        val initialsTop = ((freeHeight - initialsLayout.size.height) / 2f).coerceAtLeast(0f)
        drawText(
            initialsLayout,
            topLeft = Offset((size.width - initialsLayout.size.width) / 2f, initialsTop),
        )

        if (titleLayout != null) {
            drawText(
                titleLayout,
                topLeft =
                    Offset(
                        (size.width - titleLayout.size.width) / 2f,
                        size.height - titleLayout.size.height - TITLE_EDGE_PADDING.toPx(),
                    ),
            )
        }
    }

    private fun mode(): MonogramMode = if (dark) MonogramMode.DARK else MonogramMode.LIGHT

    private fun DrawScope.measureInitials(
        fgArgb: Int,
        side: Float,
    ): TextLayoutResult {
        val px = (side * INITIALS_FRACTION).coerceAtLeast(MIN_INITIALS_SIZE.toPx())
        val fontSize: TextUnit = (px / (density * fontScale)).sp
        return textMeasurer.measure(
            text = spec.initials,
            style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Medium, color = Color(fgArgb)),
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(0)),
            layoutDirection = layoutDirection,
            density = this,
            fontFamilyResolver = fontFamilyResolver,
        )
    }

    private fun DrawScope.measureTitle(fgArgb: Int): TextLayoutResult =
        textMeasurer.measure(
            text = title.orEmpty(),
            style = titleStyle.copy(color = Color(fgArgb)),
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            maxLines = 2,
            constraints =
                Constraints(
                    maxWidth =
                        (size.width - TITLE_EDGE_PADDING.toPx() * 2).toInt().coerceAtLeast(0),
                ),
            layoutDirection = layoutDirection,
            density = this,
            fontFamilyResolver = fontFamilyResolver,
        )

    private companion object {
        /** Initials at 38 % of the shorter side (08). */
        const val INITIALS_FRACTION = 0.38f

        /** …but never smaller than a 12 sp equivalent (08). */
        val MIN_INITIALS_SIZE = 12.sp

        /** Tiles ≥ 96 dp also draw the title below the initials (08 CoverTile). */
        val MIN_TITLE_SIDE = 96.dp

        val TITLE_EDGE_PADDING = 8.dp
    }
}

/**
 * The [MonogramPainter] for [spec], wired to the ambient typography/theme (LIGHT/DARK follows
 * [LocalSystemUiState]); [title] enables the two-line label at ≥ 96 dp tiles.
 */
@Composable
public fun rememberMonogramPainter(
    spec: MonogramSpec,
    title: String? = null,
): MonogramPainter {
    val measurer = rememberTextMeasurer()
    val resolver = LocalFontFamilyResolver.current
    val dark = LocalSystemUiState.current.dark
    val labelStyle = MaterialTheme.typography.labelMedium
    return remember(spec, title, dark, measurer, resolver, labelStyle) {
        MonogramPainter(spec, title, dark, measurer, resolver, labelStyle)
    }
}
