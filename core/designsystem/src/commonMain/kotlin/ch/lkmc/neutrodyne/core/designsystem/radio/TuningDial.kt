// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_dial_window
import org.jetbrains.compose.resources.imageResource

/**
 * A radio's station scale: [stations] printed along a strip of dial paper behind glass, with a
 * red needle on the [tuned] station. The scale scrolls sideways and keeps the tuned station in
 * view; tapping a station tunes to it and the needle travels there. Each station is a tab in one
 * selectable group, read as written (the printed capitals are decoration). [tag] names a
 * station for tests.
 */
@Composable
public fun TuningDial(
    stations: List<String>,
    tuned: Int,
    onTune: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: (Int) -> String? = { null },
) {
    val scroll = rememberScrollState()
    val centres = remember { mutableStateMapOf<Int, Float>() }
    var viewport by remember { mutableIntStateOf(0) }
    val needle = remember { Animatable(Float.NaN) }
    val target = centres[tuned]

    LaunchedEffect(target, viewport) {
        if (target == null || viewport == 0) return@LaunchedEffect
        val visible = (target - viewport / 2f).toInt().coerceIn(0, scroll.maxValue)
        if (needle.value.isNaN()) {
            // The first tuning places the needle; later ones sweep it along the scale.
            needle.snapTo(target)
            scroll.scrollTo(visible)
        } else {
            scroll.animateScrollTo(visible)
            needle.animateTo(target)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(DIAL_HEIGHT)
            .dialWindow(imageResource(Res.drawable.radio_dial_window))
            .onSizeChanged { viewport = it.width },
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .horizontalScroll(scroll)
                .drawBehind { drawScale(centres.filterKeys { it < stations.size }.values) }
                .drawWithContent {
                    drawContent()
                    if (!needle.value.isNaN()) drawNeedle(needle.value)
                }.selectableGroup(),
            verticalAlignment = Alignment.Top,
        ) {
            stations.forEachIndexed { index, station ->
                Box(
                    Modifier
                        .let { m -> tag(index)?.let { m.testTag(it) } ?: m }
                        .fillMaxHeight()
                        .widthIn(min = STATION_MIN_WIDTH)
                        .onPlaced { centres[index] = it.positionInParent().x + it.size.width / 2f }
                        .selectable(index == tuned, role = Role.Tab, onClick = { onTune(index) })
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        station.uppercase(),
                        Modifier.padding(top = 10.dp).clearAndSetSemantics { text = AnnotatedString(station) },
                        color = Ink,
                        fontFamily = barlow(),
                        fontWeight = if (index == tuned) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 12.sp,
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Minor ticks every few millimetres along a printed line, a long tick under each station. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScale(stationCentres: Collection<Float>) {
    val baseline = size.height * BASELINE_FRACTION
    val step = MINOR_STEP.toPx()
    val minor = 4.dp.toPx()
    var x = SCALE_INSET.toPx()
    while (x <= size.width - SCALE_INSET.toPx()) {
        drawLine(Ink, Offset(x, baseline - minor), Offset(x, baseline), 0.8.dp.toPx())
        x += step
    }
    drawLine(
        Ink,
        Offset(SCALE_INSET.toPx(), baseline),
        Offset(size.width - SCALE_INSET.toPx(), baseline),
        0.8.dp.toPx(),
    )
    val major = 9.dp.toPx()
    stationCentres.forEach { centre ->
        drawLine(Ink, Offset(centre, baseline - major), Offset(centre, baseline), 1.4.dp.toPx())
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNeedle(x: Float) {
    val top = NEEDLE_TOP.toPx()
    val bottom = size.height - 6.dp.toPx()
    // A soft shadow on the paper behind the needle sells the gap under the glass.
    drawLine(NeedleShadow, Offset(x + 1.5.dp.toPx(), top), Offset(x + 1.5.dp.toPx(), bottom), 2.dp.toPx())
    drawLine(NeedleRed, Offset(x, top), Offset(x, bottom), 2.dp.toPx(), StrokeCap.Round)
}

private val NeedleRed = Color(0xFFB3261E)
private val NeedleShadow = Color(0x33201208)
private val DIAL_HEIGHT = 64.dp
private val STATION_MIN_WIDTH = 72.dp
private val SCALE_INSET = 8.dp
private val MINOR_STEP = 6.dp
private val NEEDLE_TOP = 26.dp
private const val BASELINE_FRACTION = 0.78f
