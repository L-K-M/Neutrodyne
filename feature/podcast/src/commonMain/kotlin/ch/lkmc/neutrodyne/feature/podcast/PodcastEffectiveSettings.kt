// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.ui.SettingAttribution
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceRow
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.feedsIntervalText
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionNotice
import ch.lkmc.neutrodyne.core.ui.radio.RadioSwitch
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_refresh_interval
import ch.lkmc.neutrodyne.core.ui.resources.new_episode_notifications
import ch.lkmc.neutrodyne.core.ui.resources.section_notifications
import ch.lkmc.neutrodyne.core.ui.resources.section_refresh
import ch.lkmc.neutrodyne.core.ui.resources.setting_groups_title
import ch.lkmc.neutrodyne.core.ui.resources.setting_inherit
import ch.lkmc.neutrodyne.core.ui.resources.setting_invalid_value
import ch.lkmc.neutrodyne.core.ui.resources.setting_off
import ch.lkmc.neutrodyne.core.ui.resources.setting_on
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PodcastEffectiveSettings(
    state: PodcastSettingsUiState,
    onRefreshInterval: (Int?) -> Unit,
    onNotifications: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.scoped ?: return
    val notifications = settings.effective.notifyNewEpisodes == true
    val inherit = stringResource(Res.string.setting_inherit)

    Column(modifier) {
        SectionHeader(stringResource(Res.string.section_notifications))
        SettingsChoiceRow(
            icon = NdIcons.Info,
            title = stringResource(Res.string.new_episode_notifications),
            summary = onOffText(notifications),
            options = listOf(null, true, false),
            selected = settings.own.notifyNewEpisodes,
            label = { if (it == null) inherit else onOffText(it) },
            onSelect = onNotifications,
            tag = { "notifications:${it ?: "inherit"}" },
        ) {
            Attribution(
                settings.sources.getValue(SettingField.NOTIFY_NEW_EPISODES),
                SettingField.NOTIFY_NEW_EPISODES in state.invalidFields,
            )
        }
        NotificationPermissionNotice(notifications, LocalPlatformActions.current.notifications)

        SectionHeader(stringResource(Res.string.section_refresh))
        SettingsChoiceRow(
            icon = NdIcons.Schedule,
            title = stringResource(Res.string.feeds_refresh_interval),
            summary =
                feedsIntervalText(
                    settings.effective.refreshIntervalMinutes ?: FeedsSettingKeys.MANUAL_ONLY_MINUTES,
                ).asString(),
            options = listOf(null) + FeedsSettingKeys.REFRESH_INTERVAL_CHOICES,
            selected = settings.own.refreshIntervalMinutes,
            label = { minutes -> if (minutes == null) inherit else feedsIntervalText(minutes).asString() },
            onSelect = onRefreshInterval,
        ) {
            Attribution(
                settings.sources.getValue(SettingField.REFRESH_INTERVAL_MINUTES),
                SettingField.REFRESH_INTERVAL_MINUTES in state.invalidFields,
            )
        }
    }
}

@Composable
private fun onOffText(on: Boolean): String = stringResource(if (on) Res.string.setting_on else Res.string.setting_off)

/** Where the effective value comes from, and a warning when the stored override is invalid. */
@Composable
private fun Attribution(
    source: SettingSource,
    invalid: Boolean,
) {
    Column {
        SettingAttribution(source)
        if (invalid) {
            Text(
                stringResource(Res.string.setting_invalid_value),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
