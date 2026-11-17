// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons

/**
 * The cap of a paper slip's round key: a concave ivory cap in a turned brass ring, the owner's
 * favourite from the earlier prototypes (2026-11-17), with [icon] engraved in it and read as
 * [contentDescription], like an icon. It fills its node; callers supply the touch target and role.
 * [NdIcons.Pause] is cut as two solid bars, since the outlined symbol reads as "00" at a slip
 * key's size.
 */
@Composable
public fun SlipKeyCap(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val described =
        if (contentDescription == null) {
            modifier
        } else {
            modifier.semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            }
        }
    BoxWithConstraints(described, contentAlignment = Alignment.Center) {
        val pause = icon == NdIcons.Pause
        Canvas(Modifier.matchParentSize()) {
            drawIvoryKey(center, size.minDimension / 2)
            if (pause) drawPauseBars()
        }
        if (!pause) {
            // Engraved: the cut's lower wall catches the light just below the ink.
            val glyph = Modifier.size(minOf(maxWidth, maxHeight) * GLYPH_FRACTION)
            Icon(icon, null, glyph.offset(y = 0.75.dp), tint = EngravingLight)
            Icon(icon, null, glyph, tint = Ink)
        }
    }
}

private fun DrawScope.drawPauseBars() {
    val bar = Size(size.width * 0.1f, size.height * 0.3f)
    val gap = size.width * 0.07f
    for ((drop, color) in listOf(0.75.dp.toPx() to EngravingLight, 0f to Ink)) {
        for (side in listOf(-1f, 1f)) {
            val left = center.x + side * (gap + bar.width / 2f) - bar.width / 2f
            drawRoundRect(color, Offset(left, center.y - bar.height / 2f + drop), bar, CornerRadius(bar.width / 3f))
        }
    }
}

/**
 * A round ivory key in its socket: a turned brass ring lit from the top left, the dark gap of the
 * socket and a concave ivory cap whose dish is shaded at the top (its rim shades it) and lit
 * toward the bottom.
 */
private fun DrawScope.drawIvoryKey(
    center: Offset,
    radius: Float,
) {
    drawCircle(Color(0x80000000), radius, center + Offset(radius * 0.03f, radius * 0.08f))
    drawCircle(
        Brush.linearGradient(
            listOf(Color(0xFFF8E7B8), Color(0xFFC09A5A), Color(0xFF6E5128), Color(0xFF3E2C14)),
            center - Offset(radius, radius),
            center + Offset(radius, radius),
        ),
        radius * 0.98f,
        center,
    )
    drawCircle(Color(0xFF1A130A), radius * 0.86f, center)
    val cap = radius * 0.8f
    drawCircle(
        Brush.linearGradient(
            listOf(Color(0xFFFFF9EA), Color(0xFFEADBB6), Color(0xFFB59B6C)),
            center - Offset(cap, cap),
            center + Offset(cap * 0.8f, cap),
        ),
        cap,
        center,
    )
    val dish = cap * 0.8f
    drawCircle(
        Brush.linearGradient(
            listOf(Color(0xFFCDB68A), Color(0xFFEFE2C2), Color(0xFFFFF8E6)),
            center - Offset(dish * 0.6f, dish),
            center + Offset(dish * 0.6f, dish),
        ),
        dish,
        center,
    )
    drawCircle(Color(0x33604A26), dish, center, style = Stroke(radius * 0.025f))
}

private val EngravingLight = Color(0xB3FFFBEF)
private const val GLYPH_FRACTION = 0.5f
