// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * A small linear dial behind glass, like the home radio's progress window: a printed tick scale
 * on dial paper and a red needle at [progress] (0 to 1). Without a known [progress] the needle
 * rests at the start. Decorative only; the owner of the playback state announces progress.
 */
@Composable
public fun NeedleWindow(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .recess(WindowShape)
            .dialPaper()
            .radioGlass()
            .clearAndSetSemantics {},
    ) {
        val inset = 6.dp.toPx()
        val baseline = size.height * BASELINE_FRACTION
        val span = size.width - inset * 2
        for (tick in 0..TICKS) {
            val x = inset + span * tick / TICKS
            val major = tick % MAJOR_EVERY == 0
            val length = (if (major) 7.dp else 4.dp).toPx()
            drawLine(Ink, Offset(x, baseline - length), Offset(x, baseline), (if (major) 1.2.dp else 0.8.dp).toPx())
        }
        drawLine(Ink, Offset(inset, baseline), Offset(size.width - inset, baseline), 0.8.dp.toPx())
        val needleX = inset + span * (progress ?: 0f).coerceIn(0f, 1f)
        drawLine(NeedleRed, Offset(needleX, 2.dp.toPx()), Offset(needleX, size.height - 2.dp.toPx()), 1.6.dp.toPx())
    }
}

private val WindowShape = RoundedCornerShape(3.dp)
private val NeedleRed = Color(0xFFB3261E)
private const val TICKS = 20
private const val MAJOR_EVERY = 5
private const val BASELINE_FRACTION = 0.72f
