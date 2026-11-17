// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_dial_window
import org.jetbrains.compose.resources.imageResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * An analog meter behind glass, like a radio's signal meter: an arc scale printed on dial paper,
 * [startLabel] and [endLabel] at its ends, [caption] under the arc and a red needle at [fraction]
 * (0 to 1; null rests it at the start). The needle pivots below the window, as on a real meter.
 * Decorative: the owner states the value in text.
 */
@Composable
public fun NeedleGauge(
    fraction: Float?,
    caption: String,
    modifier: Modifier = Modifier,
    startLabel: String = "0",
    endLabel: String = "",
) {
    Box(
        modifier
            .aspectRatio(GAUGE_ASPECT)
            .dialWindow(imageResource(Res.drawable.radio_dial_window))
            .clearAndSetSemantics {},
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width / 2, size.height * PIVOT_Y)
            val radius = size.height * SCALE_RADIUS

            fun point(
                degrees: Float,
                r: Float,
            ): Offset {
                val rad = degrees * PI.toFloat() / 180f
                return Offset(pivot.x + r * cos(rad), pivot.y + r * sin(rad))
            }
            for (tick in 0..TICKS) {
                val angle = START_ANGLE + SWEEP * tick / TICKS
                val major = tick % MAJOR_EVERY == 0
                val length = (if (major) 9.dp else 5.dp).toPx()
                drawLine(
                    Ink,
                    point(angle, radius - length),
                    point(angle, radius),
                    (if (major) 1.4.dp else 0.9.dp).toPx(),
                )
            }
            val needle = START_ANGLE + SWEEP * (fraction ?: 0f).coerceIn(0f, 1f)
            drawLine(NeedleRed, pivot, point(needle, radius + 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            // The brass pivot cap rises into the window from below.
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFF2DDA0), Brass, Color(0xFF6E5530)),
                    pivot,
                    size.height * CAP_RADIUS,
                ),
                size.height * CAP_RADIUS,
                pivot,
            )
        }
        GaugeText(startLabel, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 22.dp))
        GaugeText(endLabel, Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 22.dp))
        GaugeText(caption.uppercase(), Modifier.align(Alignment.Center).padding(top = 18.dp), spaced = true)
    }
}

@Composable
private fun GaugeText(
    text: String,
    modifier: Modifier = Modifier,
    spaced: Boolean = false,
) {
    if (text.isEmpty()) return
    Text(
        text,
        modifier,
        color = Ink,
        fontFamily = barlow(),
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = if (spaced) 2.sp else 0.sp,
        maxLines = 1,
    )
}

private val NeedleRed = Color(0xFFB3261E)
private const val GAUGE_ASPECT = 2.8f
private const val PIVOT_Y = 1.18f
private const val SCALE_RADIUS = 0.98f
private const val CAP_RADIUS = 0.3f
private const val START_ANGLE = 215f
private const val SWEEP = 110f
private const val TICKS = 20
private const val MAJOR_EVERY = 5
