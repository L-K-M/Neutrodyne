// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.radio.BatToggle
import ch.lkmc.neutrodyne.core.designsystem.radio.BatToggleFace
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_off
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_on
import org.jetbrains.compose.resources.stringResource

/**
 * The radio's bat-handle switch in place of Material's `Switch`, with the same contract: with
 * [onCheckedChange] it is a switch of its own (announced as one; the caller names it), and with
 * null it is the picture of [checked] inside a row that is the switch. The interactive target is
 * 48 × 48 dp; the painted plate stays 30 × 40 dp, centred inside it.
 */
@Composable
public fun RadioSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val on = stringResource(Res.string.radio_switch_on)
    val off = stringResource(Res.string.radio_switch_off)
    // The painted plate keeps its size, centred in a 48 dp hit target: a smaller target is
    // below the platform minimum in dense panels, and a larger plate would not fit the rows.
    val hit = modifier.size(SWITCH_HIT_TARGET, SWITCH_HIT_TARGET)
    val plate = DpSize(SWITCH_WIDTH, SWITCH_HEIGHT)
    if (onCheckedChange == null) {
        BatToggleFace(checked, on, off, hit, enabled, plateSize = plate)
    } else {
        BatToggle(checked, { onCheckedChange(!checked) }, on, off, hit, enabled, plateSize = plate)
    }
}

private val SWITCH_WIDTH = 30.dp
private val SWITCH_HEIGHT = 40.dp
private val SWITCH_HIT_TARGET = 48.dp
