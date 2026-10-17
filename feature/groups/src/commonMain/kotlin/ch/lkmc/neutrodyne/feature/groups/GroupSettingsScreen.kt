// SPDX-License-Identifier: Unlicense

@file:Suppress("FunctionNaming") // Compose functions use PascalCase.

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
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
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.feeds_refresh_interval
import ch.lkmc.neutrodyne.core.ui.resources.group_settings_named_title
import ch.lkmc.neutrodyne.core.ui.resources.group_settings_title
import ch.lkmc.neutrodyne.core.ui.resources.new_episode_notifications
import ch.lkmc.neutrodyne.core.ui.resources.section_notifications
import ch.lkmc.neutrodyne.core.ui.resources.section_refresh
import ch.lkmc.neutrodyne.core.ui.resources.setting_invalid_value
import ch.lkmc.neutrodyne.core.ui.resources.setting_off
import ch.lkmc.neutrodyne.core.ui.resources.setting_on
import ch.lkmc.neutrodyne.core.ui.resources.setting_use_default
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GroupSettingsScreen(
    state: GroupSettingsUiState,
    onRefreshInterval: (Int?) -> Unit,
    onNotifications: (Boolean?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var intervalDialog by rememberSaveable { mutableStateOf(false) }
    var notificationsDialog by rememberSaveable { mutableStateOf(false) }
    val settings = state.scoped
    Column(modifier.fillMaxSize()) {
        NdTopAppBar(
            title =
                state.group?.let { stringResource(Res.string.group_settings_named_title, it.name) }
                    ?: stringResource(Res.string.group_settings_title),
            navigation = {
                NdIconButton(onClose, NdIcons.ArrowBack, stringResource(Res.string.back))
            },
        )
        if (!state.loaded) {
            CircularProgressIndicator(Modifier.padding(SECTION_PADDING))
            return@Column
        }
        if (state.gone || settings == null) return@Column

        Column(Modifier.verticalScroll(rememberScrollState())) {
            val notifications = settings.effective.notifyNewEpisodes == true
            val notificationLabel = stringResource(Res.string.new_episode_notifications)
            SectionHeader(stringResource(Res.string.section_notifications))
            ListItem(
                headlineContent = { Text(notificationLabel) },
                supportingContent = {
                    EffectiveSummary(
                        notificationText(notifications),
                        settings.sources.getValue(SettingField.NOTIFY_NEW_EPISODES),
                        SettingField.NOTIFY_NEW_EPISODES in state.invalidFields,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = notifications,
                        onCheckedChange = onNotifications,
                        modifier = Modifier.semantics { contentDescription = notificationLabel },
                    )
                },
                modifier = Modifier.clickable { notificationsDialog = true }.semantics { role = Role.Button },
            )
            NotificationPermissionNotice(notifications, LocalPlatformActions.current.notifications)
            SectionHeader(stringResource(Res.string.section_refresh))
            ListItem(
                headlineContent = { Text(stringResource(Res.string.feeds_refresh_interval)) },
                supportingContent = {
                    EffectiveSummary(
                        intervalText(settings.effective.refreshIntervalMinutes),
                        settings.sources.getValue(SettingField.REFRESH_INTERVAL_MINUTES),
                        SettingField.REFRESH_INTERVAL_MINUTES in state.invalidFields,
                    )
                },
                modifier = Modifier.clickable { intervalDialog = true }.semantics { role = Role.Button },
            )
        }
        if (intervalDialog) {
            SettingsChoiceDialog(
                icon = NdIcons.Schedule,
                title = stringResource(Res.string.feeds_refresh_interval),
                options = listOf(null) + FeedsSettingKeys.REFRESH_INTERVAL_CHOICES,
                selected = settings.own.refreshIntervalMinutes,
                label = { minutes ->
                    if (minutes == null) {
                        useDefaultText(intervalText(state.inherited.refreshIntervalMinutes))
                    } else {
                        intervalText(minutes)
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
                label = { enabled ->
                    if (enabled == null) {
                        useDefaultText(notificationText(state.inherited.notifyNewEpisodes == true))
                    } else {
                        notificationText(enabled)
                    }
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
private fun intervalText(minutes: Int?): String =
    feedsIntervalText(minutes ?: FeedsSettingKeys.MANUAL_ONLY_MINUTES).asString()

@Composable
private fun notificationText(enabled: Boolean): String =
    stringResource(if (enabled) Res.string.setting_on else Res.string.setting_off)

@Composable
private fun useDefaultText(value: String): String =
    stringResource(Res.string.setting_use_default, value, AttributionText.describe(SettingSource.AppDefault).asString())

@Composable
private fun EffectiveSummary(
    value: String,
    source: SettingSource,
    invalid: Boolean,
) {
    Column {
        Text(value)
        Text(AttributionText.describe(source).asString())
        if (invalid) Text(stringResource(Res.string.setting_invalid_value), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(SECTION_PADDING),
    )
}

private val SECTION_PADDING = 16.dp
