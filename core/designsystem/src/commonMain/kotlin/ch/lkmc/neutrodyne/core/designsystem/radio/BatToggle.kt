// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.designsystem.radio

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.resources.Res
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_switch_brass_off
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_switch_brass_on
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_switch_nickel_off
import ch.lkmc.neutrodyne.core.designsystem.resources.radio_switch_nickel_on
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The radio's panel toggle switch: a chrome bat lever through a hex nut on a plate printed
 * [onLabel] (red) and [offLabel] (black). Lever up means on. Tap it, or flick the lever toward
 * the other label. The caller sizes it (the radio home uses 54 x 64 dp and larger) and supplies
 * its accessible name and state; it is announced as a switch. The lever sounds as it is thrown
 * ([RadioSound.SWITCH_ON], [RadioSound.SWITCH_OFF]).
 */
@Composable
public fun BatToggle(
    on: Boolean,
    onToggle: () -> Unit,
    onLabel: String,
    offLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    plate: SwitchPlate = SwitchPlate.BRASS,
    plateSize: DpSize? = null,
) {
    // Read through states: the flick detector below outlives recompositions.
    val currentOn by rememberUpdatedState(on)
    val toggle by rememberUpdatedState(rememberSwitchFeedback { onToggle() })
    BatToggleFace(
        on,
        onLabel,
        offLabel,
        modifier
            .toggleable(on, enabled = enabled, role = Role.Switch) { toggle(!currentOn) }
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
                        if ((travel < 0f && !currentOn) || (travel > 0f && currentOn)) toggle(!currentOn)
                    },
                )
            },
        enabled,
        plate,
        plateSize,
    )
}

/**
 * [BatToggle]'s plate and lever as a picture of [on], for a row whose whole line is the toggle
 * and carries the switch semantics; that row sounds the lever with [rememberSwitchFeedback]. The
 * plate is a photographic render (owner-approved look, 2026-11-17) in two states, lever up and
 * lever down; the app prints [onLabel] and [offLabel] on its blank enamel so they follow the
 * user's language. The printed labels stay silent: the switch's state, not its plate, is what a
 * screen reader announces.
 *
 * [plateSize] separates the painted plate's box from the hit area: the caller sizes [modifier]
 * (the touch target, 48 dp or more) and the plate keeps its render's proportion inside
 * [plateSize], centred in the target.
 */
@Composable
public fun BatToggleFace(
    on: Boolean,
    onLabel: String,
    offLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    plate: SwitchPlate = SwitchPlate.BRASS,
    plateSize: DpSize? = null,
) {
    val reducedMotion = LocalReducedMotion.current
    BoxWithConstraints(modifier.alpha(if (enabled) 1f else DISABLED_ALPHA), contentAlignment = Alignment.Center) {
        // The plate keeps the render's proportion inside its own box: [plateSize] when the caller
        // reserved a larger hit area, else the whole box.
        val plateHeight =
            if (plateSize != null) {
                minOf(plateSize.height, plateSize.width * plate.aspect)
            } else {
                minOf(maxHeight, maxWidth * plate.aspect)
            }
        val plateWidth = plateHeight / plate.aspect
        Box(
            Modifier
                .size(plateWidth, plateHeight)
                .dropShadow(
                    PlateShape,
                    Shadow(
                        radius = plateWidth * 0.08f,
                        color = Color(0x99000000),
                        offset =
                            DpOffset(
                                plateWidth * 0.02f,
                                plateWidth * 0.05f,
                            ),
                    ),
                ),
        ) {
            Crossfade(on, animationSpec = if (reducedMotion) snap() else tween(LEVER_FLIP_MS), label = "lever") { up ->
                Image(
                    painterResource(if (up) plate.on else plate.off),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
            Column(
                Modifier.matchParentSize().clearAndSetSemantics {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(plateHeight * plate.onBandTop))
                SwitchLabel(onLabel, plateWidth * plate.labelWidth, plateHeight * plate.band, OnLettering)
                Spacer(Modifier.weight(1f))
                SwitchLabel(offLabel, plateWidth * plate.labelWidth, plateHeight * plate.band, OffLettering)
                Spacer(Modifier.height(plateHeight * plate.offBandBottom))
            }
        }
    }
}

@Composable
private fun SwitchLabel(
    text: String,
    width: Dp,
    height: Dp,
    color: Color,
) {
    Box(Modifier.size(width, height), contentAlignment = Alignment.Center) {
        // Printed on the plate, so it scales with the plate rather than with the text size; a long
        // translation is printed smaller instead of running under the lever.
        val fontScale = LocalDensity.current.fontScale
        Text(
            text,
            color = color,
            fontFamily = slab(),
            fontSize = (height.value * 0.8f / fontScale).sp,
            lineHeight = (height.value / fontScale).sp,
            autoSize =
                TextAutoSize.StepBased(
                    minFontSize = (height.value * 0.4f / fontScale).sp,
                    maxFontSize =
                        (
                            height.value *
                                0.8f /
                                fontScale
                        ).sp,
                ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val PlateShape = RoundedCornerShape(8)

/**
 * The two switch plates recreated from the owner's favourite renders: their pictures (lever up,
 * lever down), height over width, and where the enamel stays clear of the lever for the printed
 * labels: their bands as fractions of the plate's height and, because the lever swings out to the
 * right, the widest a centred label may print, as a fraction of the plate's width.
 */
public enum class SwitchPlate(
    internal val on: DrawableResource,
    internal val off: DrawableResource,
    internal val aspect: Float,
    internal val onBandTop: Float,
    internal val offBandBottom: Float,
    internal val band: Float,
    internal val labelWidth: Float,
) {
    /** Polished nickel rim (renders/podcast.png). */
    NICKEL(
        Res.drawable.radio_switch_nickel_on,
        Res.drawable.radio_switch_nickel_off,
        1170f / 824f,
        0.06f,
        0.06f,
        0.19f,
        0.4f,
    ),

    /** Brass rim with four Phillips screws (superseded/v2). */
    BRASS(
        Res.drawable.radio_switch_brass_on,
        Res.drawable.radio_switch_brass_off,
        1306f / 776f,
        0.1f,
        0.11f,
        0.16f,
        0.62f,
    ),
}

private const val LEVER_FLIP_MS = 90
private const val DISABLED_ALPHA = 0.56f
private val OnLettering = Color(0xFFF6EAD0)
private val OffLettering = Color(0xFF2A2117)
