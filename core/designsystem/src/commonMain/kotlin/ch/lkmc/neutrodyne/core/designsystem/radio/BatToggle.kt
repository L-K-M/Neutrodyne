// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The radio's panel toggle switch: a chrome bat lever through a hex nut on a plate printed
 * [onLabel] (red) and [offLabel] (black). Lever up means on. Tap it, or flick the lever toward
 * the other label. The caller sizes it (the radio home uses 54 x 64 dp and larger) and supplies
 * its accessible name and state; it is announced as a switch.
 */
@Composable
public fun BatToggle(
    on: Boolean,
    onToggle: () -> Unit,
    onLabel: String,
    offLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val reducedMotion = LocalReducedMotion.current
    // -1 is lever up (ON), +1 is lever down (OFF).
    val lever by animateFloatAsState(
        if (on) -1f else 1f,
        if (reducedMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 900f),
    )
    val currentOn by rememberUpdatedState(on)
    val toggle by rememberUpdatedState(onToggle)
    BoxWithConstraints(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleable(on, enabled = enabled, role = Role.Switch) { toggle() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        travel += amount
                    },
                    // Crossing touch slop already cancelled the tap, so any flick toward the other
                    // label toggles; a further threshold would swallow short flicks. On a scrolling
                    // (very short) radio, a drag that starts on the switch moves the lever, not the page.
                    onDragEnd = {
                        if ((travel < 0f && !currentOn) || (travel > 0f && currentOn)) toggle()
                    },
                )
            },
    ) {
        val tab = maxHeight * SWITCH_TAB_FRACTION
        Canvas(Modifier.matchParentSize()) { drawSwitchPlate(tab.toPx()) }
        Column(Modifier.matchParentSize()) {
            SwitchLabel(onLabel, tab)
            Spacer(Modifier.weight(1f))
            SwitchLabel(offLabel, tab)
        }
        Canvas(Modifier.matchParentSize()) { drawSwitchLever(lever) }
    }
}

@Composable
private fun SwitchLabel(
    text: String,
    height: Dp,
) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        // Printed on the plate, so it scales with the plate rather than with the text size.
        Text(
            text,
            color = Color(0xFFF7F1E3),
            fontFamily = barlow(),
            fontWeight = FontWeight.Bold,
            fontSize = (height.value * 0.52f / LocalDensity.current.fontScale).sp,
            lineHeight = (height.value * 0.6f / LocalDensity.current.fontScale).sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun DrawScope.drawSwitchPlate(tab: Float) {
    val corner = CornerRadius(5.dp.toPx())
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFF1F0EA), Color(0xFFB4B2A8), Color(0xFFDCDAD2), Color(0xFF9C9A90))),
        cornerRadius = corner,
    )
    drawRoundRect(Color(0xFF6E6C64), cornerRadius = corner, style = Stroke(1.dp.toPx()))
    val inset = 3.dp.toPx()
    drawRoundRect(
        Color(0xFFCB2A22),
        Offset(inset, inset),
        Size(size.width - inset * 2, tab - inset),
        CornerRadius(3.dp.toPx()),
    )
    drawRoundRect(
        Color(0xFF1C1B19),
        Offset(inset, size.height - tab),
        Size(size.width - inset * 2, tab - inset),
        CornerRadius(3.dp.toPx()),
    )
    val center = Offset(size.width / 2, size.height / 2)
    val nut = minOf(size.width, size.height - tab * 2) * 0.46f
    val hexagon =
        Path().apply {
            for (corner in 0..5) {
                val angle = (corner * 60.0) * PI / 180.0
                val point = Offset(center.x + nut * cos(angle).toFloat(), center.y + nut * sin(angle).toFloat())
                if (corner == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
    drawPath(hexagon, Color(0x55000000), style = Stroke(2.dp.toPx()))
    drawPath(
        hexagon,
        Brush.linearGradient(
            listOf(Color(0xFFFAFAF6), Color(0xFFA7A59C), Color(0xFFE6E4DC), Color(0xFF7F7D74)),
            center - Offset(nut, nut),
            center + Offset(nut, nut),
        ),
    )
    drawPath(hexagon, Color(0xFF6A685F), style = Stroke(0.75.dp.toPx()))
    // Threaded bushing: concentric rings around the lever's pivot hole.
    val bushing = nut * 0.68f
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFE9E7E0), Color(0xFF8D8B82)), center, bushing),
        bushing,
        center,
    )
    for (ring in 1..3) {
        drawCircle(Color(0x66504E46), bushing * (1f - ring * 0.18f), center, style = Stroke(0.75.dp.toPx()))
    }
    drawCircle(Color(0xFF2A2925), bushing * 0.34f, center)
}

private fun DrawScope.drawSwitchLever(position: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    // The tip stops just short of the printed tabs so ON and OFF stay legible.
    val reach = size.height * 0.17f
    val base = size.width * 0.07f
    val tip = size.width * 0.11f
    val end = Offset(center.x, center.y + position * reach)
    val shadow = Offset(1.5.dp.toPx(), 2.dp.toPx())

    fun bat(offset: Offset): Path =
        Path().apply {
            moveTo(center.x - base + offset.x, center.y + offset.y)
            lineTo(end.x - tip + offset.x, end.y + offset.y)
            lineTo(end.x + tip + offset.x, end.y + offset.y)
            lineTo(center.x + base + offset.x, center.y + offset.y)
            close()
        }
    drawPath(bat(shadow), Color(0x40000000))
    drawCircle(Color(0x40000000), tip, end + shadow)
    val chrome =
        Brush.horizontalGradient(
            listOf(Color(0xFF6F6D66), Color(0xFFFBFBF8), Color(0xFFBDBBB3), Color(0xFF5E5C55)),
            end.x - tip,
            end.x + tip,
        )
    drawCircle(chrome, base * 1.3f, center)
    drawPath(bat(Offset.Zero), chrome)
    drawCircle(chrome, tip, end)
    drawCircle(Color(0xCCFFFFFF), tip * 0.35f, end - Offset(tip * 0.3f, tip * 0.3f))
}

private const val SWITCH_TAB_FRACTION = 0.22f
private const val DISABLED_ALPHA = 0.56f
