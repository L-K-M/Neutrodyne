// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal fun Modifier.cabinetEdges(): Modifier =
    drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(listOf(Color(0x88311507), Color.Transparent, Color(0x88230E06))))
        drawLine(Color(0xBFE8B56E), Offset.Zero, Offset(size.width, 0f), 2.dp.toPx())
        drawLine(Color(0x992D1609), Offset(0f, size.height), Offset(size.width, size.height), 5.dp.toPx())
        drawLine(Color(0x88D39C58), Offset(5.dp.toPx(), 20.dp.toPx()), Offset(5.dp.toPx(), size.height), 3.dp.toPx())
        drawLine(
            Color(0xBB32170B),
            Offset(size.width - 5.dp.toPx(), 20.dp.toPx()),
            Offset(size.width - 5.dp.toPx(), size.height),
            5.dp.toPx(),
        )
    }

@Composable
internal fun Speaker(
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Box(modifier.background(Color(0xFF4E4030)).recess()) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF201F1B), Color(0xFF6D5A3D), Color(0xFF8A7150)),
                    center = Offset(size.width * 0.5f, size.height * 0.55f),
                    radius = maxOf(size.width, size.height) * 0.5f,
                ),
            )
            val pitch = 4.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(Color(0x776E6043), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                x += pitch
            }
            var y = 0f
            while (y < size.height) {
                drawLine(Color(0xAA28251B), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                y += pitch
            }
            for (rib in 1..7) {
                val ribX = size.width * rib / 8
                drawLine(
                    Color(0xFF241B11),
                    Offset(ribX, 0f),
                    Offset(ribX, size.height),
                    if (large) 5.dp.toPx() else 3.dp.toPx(),
                )
                drawLine(
                    Color(0x887C6744),
                    Offset(ribX - 1.dp.toPx(), 0f),
                    Offset(ribX - 1.dp.toPx(), size.height),
                    1.dp.toPx(),
                )
            }
        }
    }
}
