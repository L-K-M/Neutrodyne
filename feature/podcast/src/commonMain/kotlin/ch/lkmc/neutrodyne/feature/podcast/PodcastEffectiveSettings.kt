// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import ch.lkmc.neutrodyne.core.ui.AttributionText
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceDialog
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.feedsIntervalText
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionNotice
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
    var intervalDialog by rememberSaveable { mutableStateOf(false) }
    var notificationsDialog by rememberSaveable { mutableStateOf(false) }
    val notifications = settings.effective.notifyNewEpisodes == true

    Column(modifier) {
        SectionHeader(stringResource(Res.string.section_notifications))
        ListItem(
            headlineContent = { Text(stringResource(Res.string.new_episode_notifications)) },
            supportingContent = {
                EffectiveSummary(
                    stringResource(if (notifications) Res.string.setting_on else Res.string.setting_off),
                    settings.sources.getValue(SettingField.NOTIFY_NEW_EPISODES),
                    SettingField.NOTIFY_NEW_EPISODES in state.invalidFields,
                )
            },
            trailingContent = {
                // The headline is a sibling node; name the switch itself for screen readers.
                val label = stringResource(Res.string.new_episode_notifications)
                Switch(
                    checked = notifications,
                    onCheckedChange = onNotifications,
                    modifier = Modifier.semantics { contentDescription = label },
                )
            },
            modifier = Modifier.clickable { notificationsDialog = true },
        )
        NotificationPermissionNotice(notifications, LocalPlatformActions.current.notifications)

        SectionHeader(stringResource(Res.string.section_refresh))
        ListItem(
            headlineContent = { Text(stringResource(Res.string.feeds_refresh_interval)) },
            supportingContent = {
                EffectiveSummary(
                    feedsIntervalText(
                        settings.effective.refreshIntervalMinutes ?: FeedsSettingKeys.MANUAL_ONLY_MINUTES,
                    ).asString(),
                    settings.sources.getValue(SettingField.REFRESH_INTERVAL_MINUTES),
                    SettingField.REFRESH_INTERVAL_MINUTES in state.invalidFields,
                )
            },
            modifier = Modifier.clickable { intervalDialog = true },
        )

        if (intervalDialog) {
            SettingsChoiceDialog(
                icon = NdIcons.Schedule,
                title = stringResource(Res.string.feeds_refresh_interval),
                options = listOf(null) + FeedsSettingKeys.REFRESH_INTERVAL_CHOICES,
                selected = settings.own.refreshIntervalMinutes,
                label = { minutes ->
                    when (minutes) {
                        null -> stringResource(Res.string.setting_inherit)
                        else -> feedsIntervalText(minutes).asString()
                    }
                },
                onSelect = {
                    intervalDialog = false
                    onRefreshInterval(it)
                },
                onDismissRequest = { intervalDialog = false },
            )
        }
        if (notificationsDialog) {
            SettingsChoiceDialog(
                icon = NdIcons.Info,
                title = stringResource(Res.string.new_episode_notifications),
                options = listOf(null, true, false),
                selected = settings.own.notifyNewEpisodes,
                label = {
                    stringResource(
                        when (it) {
                            null -> Res.string.setting_inherit
                            true -> Res.string.setting_on
                            false -> Res.string.setting_off
                        },
                    )
                },
                onSelect = {
                    notificationsDialog = false
                    onNotifications(it)
                },
                onDismissRequest = { notificationsDialog = false },
            )
        }
    }
}

@Composable
private fun EffectiveSummary(
    value: String,
    source: SettingSource,
    invalid: Boolean,
) {
    var showGroups by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(value)
        if (source is SettingSource.Groups && source.groups.size >= GROUP_LIST_THRESHOLD) {
            TextButton(onClick = { showGroups = true }) { Text(AttributionText.describe(source).asString()) }
        } else {
            Text(AttributionText.describe(source).asString())
        }
        if (invalid) {
            Text(stringResource(Res.string.setting_invalid_value), color = MaterialTheme.colorScheme.error)
        }
    }
    if (showGroups && source is SettingSource.Groups) {
        NdDialog(onDismissRequest = { showGroups = false }, title = stringResource(Res.string.setting_groups_title)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                source.groups.forEach { Text(it.name, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

private const val GROUP_LIST_THRESHOLD = 3
