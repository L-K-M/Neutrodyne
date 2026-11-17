// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdTooltipIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.BatToggleFace
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_off
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_on
import org.jetbrains.compose.resources.stringResource

/** The back affordance of every pushed Settings page: pops the tab's stack. */
@Composable
internal fun SettingsBackButton() {
    val navigator = LocalAppNavigator.current
    NdTooltipIconButton(
        onClick = { navigator.pop() },
        icon = NdIcons.ArrowBack,
        tooltip = stringResource(Res.string.back),
    )
}

/**
 * One Settings row as a paper slip (PO-55): a printed symbol, the title in Courier, an optional
 * [summary] line under it and an optional [value] at the end, such as the current setting.
 * There is no chevron. The click target deliberately precedes the slip's shape: the whole list
 * line takes the click, while the slip draws the press, hover and focus.
 */
@Suppress("ktlint:compose:modifier-clickable-order")
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: Boolean = false,
    value: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    SettingsSlip(
        interaction,
        enabled,
        modifier.clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        BadgedBox(badge = { if (badge) Badge() }) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(ICON_SIZE))
        }
        SlipText(title, summary, Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                Modifier.widthIn(max = VALUE_MAX_WIDTH),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A switch row (08 Settings) as a paper slip with the radio's bat-handle switch at its end. The
 * whole line is the toggle target and carries the switch semantics, so touch targets stay
 * accessible; the switch is its picture.
 */
@Suppress("ktlint:compose:modifier-clickable-order")
@Composable
internal fun SettingsSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    SettingsSlip(
        interaction,
        enabled,
        modifier.toggleable(
            value = checked,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    ) {
        SlipText(title, summary, Modifier.weight(1f))
        BatToggleFace(
            checked,
            stringResource(Res.string.radio_switch_on),
            stringResource(Res.string.radio_switch_off),
            Modifier.size(SWITCH_WIDTH, SWITCH_HEIGHT),
            enabled,
        )
    }
}

@Composable
private fun SettingsSlip(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .shadow(1.5.dp, SlipShape)
            .clip(SlipShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .indication(interaction, LocalIndication.current)
            .border(0.5.dp, Brass.copy(alpha = 0.7f), SlipShape)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = SLIP_MIN_HEIGHT)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun SlipText(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
    }
}

private val SlipShape = RoundedCornerShape(4.dp)
private val SLIP_MIN_HEIGHT = 56.dp
private val ICON_SIZE = 22.dp
private val VALUE_MAX_WIDTH = 180.dp
private val SWITCH_WIDTH = 30.dp
private val SWITCH_HEIGHT = 40.dp
private const val DISABLED_ALPHA = 0.38f
