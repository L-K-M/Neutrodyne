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
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_dial_window
import org.jetbrains.compose.resources.imageResource

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
            .dialWindow(imageResource(Res.drawable.radio_dial_window))
            .clearAndSetSemantics {},
    ) {
        val inset = 6.dp.toPx()
        val baseline = size.height * BASELINE_FRACTION
        val span = size.width - inset * 2
        // The marks shrink with a short face, such as the mini player's or a download row's
        // window, so the needle keeps most of the face's height. A face of about 12 dp or more
        // draws at the full sizes.
        val margin = minOf(NEEDLE_MARGIN.toPx(), size.height * MARGIN_FRACTION)
        val majorLength = minOf(MAJOR_TICK.toPx(), baseline - margin)
        val fullSize = majorLength >= MAJOR_TICK.toPx()
        val minorLength = if (fullSize) MINOR_TICK.toPx() else majorLength * MINOR_TICK.value / MAJOR_TICK.value
        for (tick in 0..TICKS) {
            val x = inset + span * tick / TICKS
            val major = tick % MAJOR_EVERY == 0
            val length = if (major) majorLength else minorLength
            drawLine(Ink, Offset(x, baseline - length), Offset(x, baseline), (if (major) 1.2.dp else 0.8.dp).toPx())
        }
        drawLine(Ink, Offset(inset, baseline), Offset(size.width - inset, baseline), 0.8.dp.toPx())
        val needleX = inset + span * (progress ?: 0f).coerceIn(0f, 1f)
        drawLine(NeedleRed, Offset(needleX, margin), Offset(needleX, size.height - margin), 1.6.dp.toPx())
    }
}

private val NeedleRed = Color(0xFFB3261E)
private const val TICKS = 20
private const val MAJOR_EVERY = 5
private const val BASELINE_FRACTION = 0.72f
private val MAJOR_TICK = 7.dp
private val MINOR_TICK = 4.dp

/** The needle's gap to the face's top and bottom edge, at most [MARGIN_FRACTION] of the face. */
private val NEEDLE_MARGIN = 2.dp
private const val MARGIN_FRACTION = 0.12f
