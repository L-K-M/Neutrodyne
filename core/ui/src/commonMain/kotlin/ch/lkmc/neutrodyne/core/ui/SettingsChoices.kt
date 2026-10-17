// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_hour
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_hours
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_manual

/** "Manual only" / "Every hour" / "Every N hours" — row summary, dialog label and home row. */
public fun feedsIntervalText(minutes: Int): UiText =
    when (minutes) {
        FeedsSettingKeys.MANUAL_ONLY_MINUTES -> UiText.Res(Res.string.feeds_interval_manual)
        MINUTES_PER_HOUR -> UiText.Res(Res.string.feeds_interval_hour)
        else -> UiText.Res(Res.string.feeds_interval_hours, listOf(minutes / MINUTES_PER_HOUR))
    }

/** The page's radio-row chooser, shared by the interval and show-notes images rows. */
@Composable
public fun <T> SettingsChoiceDialog(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NdDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        icon = icon,
        title = title,
    ) {
        // The chooser's rows must stay reachable in a landscape-height window (08's dialog rule).
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (option in options) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton,
                            ).padding(vertical = RADIO_ROW_GAP),
                ) {
                    RadioButton(selected = option == selected, onClick = null)
                    Text(
                        label(option),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = RADIO_LABEL_GAP),
                    )
                }
            }
        }
    }
}

private const val MINUTES_PER_HOUR = 60
private val RADIO_ROW_GAP = 6.dp
private val RADIO_LABEL_GAP = 16.dp
