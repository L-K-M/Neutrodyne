// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.radio.BatToggleFace
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKeys
import ch.lkmc.neutrodyne.core.designsystem.radio.StepCounter
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_off
import ch.lkmc.neutrodyne.core.ui.resources.radio_switch_on
import ch.lkmc.neutrodyne.core.ui.resources.settings_step_down
import ch.lkmc.neutrodyne.core.ui.resources.settings_step_up
import org.jetbrains.compose.resources.stringResource

/**
 * One settings row as a paper slip (PO-55): a printed symbol, the title in Courier, an optional
 * [summary] line under it and an optional [value] at the end, such as the current setting.
 * There is no chevron. The click target deliberately precedes the slip's shape: the whole list
 * line takes the click, while the slip draws the press, hover and focus. A null [onClick] makes
 * a slip that only informs. [trailing] puts a key of its own at the slip's end.
 */
@Suppress("ktlint:compose:modifier-clickable-order")
@Composable
public fun SettingsRow(
    icon: ImageVector?,
    title: String,
    summary: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: Boolean = false,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    SettingsSlip(
        interaction,
        enabled,
        if (onClick == null) {
            modifier
        } else {
            modifier.clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SLIP_GAP), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                BadgedBox(badge = { if (badge) Badge() }) {
                    Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(ICON_SIZE))
                }
            }
            if (value == null) {
                SlipText(title, summary, Modifier.weight(1f))
            } else {
                SlipTextAndValue(title, summary, value, Modifier.weight(1f))
            }
            trailing?.invoke()
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
public fun SettingsSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
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
        Row(horizontalArrangement = Arrangement.spacedBy(SLIP_GAP), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(ICON_SIZE))
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
}

/**
 * A choice made on the slip itself, with no dialog (PO-55). Up to [MAX_PIANO_KEYS] options are
 * piano keys under the title, the chosen one pressed in; longer lists, which are ordered (sizes,
 * counts, intervals), are a counter under the title whose keys step to the neighbouring option.
 * [details] adds lines under the summary, such as where an inherited value comes from.
 * Disabling the row dims only its keys: the title, summary and details stay readable, since
 * they say why it is disabled. A [selected] value missing from [options], such as an invalid
 * stored value, presses no key; on the counter, stepping up then picks the first option.
 */
@Composable
public fun <T> SettingsChoiceRow(
    icon: ImageVector?,
    title: String,
    summary: String?,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: (T) -> String? = { null },
    details: @Composable ColumnScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    SettingsSlip(interaction, enabled = true, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(SLIP_GAP), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(ICON_SIZE))
            Column(Modifier.weight(1f)) {
                SlipText(title, summary)
                details()
            }
        }
        if (options.size <= MAX_PIANO_KEYS) {
            PianoKeys(
                options,
                selected,
                onSelect,
                label,
                Modifier.fillMaxWidth().padding(top = CHOOSER_GAP),
                enabled = enabled,
                tag = tag,
            )
        } else {
            // Indices, not values: an "Inherit" option may be null.
            val index = options.indexOf(selected)
            val previous = index - 1
            val next = index + 1
            StepCounter(
                label = title,
                value = label(selected),
                onDecrease = if (enabled && previous >= 0) ({ onSelect(options[previous]) }) else null,
                onIncrease = if (enabled && next < options.size) ({ onSelect(options[next]) }) else null,
                decreaseLabel = stringResource(Res.string.settings_step_down, title),
                increaseLabel = stringResource(Res.string.settings_step_up, title),
                modifier = Modifier.padding(top = CHOOSER_GAP),
                engraved = false,
            )
        }
    }
}

@Composable
private fun SettingsSlip(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
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
        verticalArrangement = Arrangement.Center,
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

/**
 * The title and summary with the current value at the slip's end. The value yields to the
 * title's longest word, so a narrow pane never breaks the title inside a word; where even the
 * value's longest word then has no room beside it, the value moves to its own line below.
 */
@Composable
private fun SlipTextAndValue(
    title: String,
    summary: String?,
    value: String,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            SlipText(title, summary)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
    ) { (text, current), constraints ->
        val width = constraints.maxWidth
        val gap = SLIP_GAP.roundToPx()
        val room = minOf(VALUE_MAX_WIDTH.roundToPx(), width - gap - text.minIntrinsicWidth(Constraints.Infinity))
        if (room > 0 && current.minIntrinsicWidth(Constraints.Infinity) <= room) {
            val v = current.measure(Constraints(maxWidth = room))
            val t = text.measure(Constraints.fixedWidth((width - gap - v.width).coerceAtLeast(0)))
            val height = constraints.constrainHeight(maxOf(t.height, v.height))
            layout(width, height) {
                t.placeRelative(0, (height - t.height) / 2)
                v.placeRelative(width - v.width, (height - v.height) / 2)
            }
        } else {
            val t = text.measure(Constraints.fixedWidth(width))
            val v = current.measure(Constraints(maxWidth = width))
            layout(width, constraints.constrainHeight(t.height + v.height)) {
                t.placeRelative(0, 0)
                v.placeRelative(width - v.width, t.height)
            }
        }
    }
}

/** Piano keys hold two to four exclusive choices; longer, ordered lists step on a counter. */
private const val MAX_PIANO_KEYS = 4
private val SlipShape = RoundedCornerShape(4.dp)
private val SLIP_MIN_HEIGHT = 56.dp
private val SLIP_GAP = 14.dp
private val CHOOSER_GAP = 10.dp
private val ICON_SIZE = 22.dp
private val VALUE_MAX_WIDTH = 180.dp
private val SWITCH_WIDTH = 30.dp
private val SWITCH_HEIGHT = 40.dp
private const val DISABLED_ALPHA = 0.38f
