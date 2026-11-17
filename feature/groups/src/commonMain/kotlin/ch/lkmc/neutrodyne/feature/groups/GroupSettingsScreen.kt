// SPDX-License-Identifier: Unlicense

@file:Suppress("FunctionNaming") // Compose functions use PascalCase.

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.EngravedHeading
import ch.lkmc.neutrodyne.core.model.SettingField
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.ui.AttributionText
import ch.lkmc.neutrodyne.core.ui.DownloadSettingEdit
import ch.lkmc.neutrodyne.core.ui.DownloadSettingsRows
import ch.lkmc.neutrodyne.core.ui.DownloadSettingsScope
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceRow
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.downloadExternalReasonText
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
import ch.lkmc.neutrodyne.core.ui.resources.setting_inherit
import ch.lkmc.neutrodyne.core.ui.resources.setting_invalid_value
import ch.lkmc.neutrodyne.core.ui.resources.setting_off
import ch.lkmc.neutrodyne.core.ui.resources.setting_on
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GroupSettingsScreen(
    state: GroupSettingsUiState,
    onRefreshInterval: (Int?) -> Unit,
    onNotifications: (Boolean?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDownloadEdit: (DownloadSettingEdit) -> Unit = {},
) {
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

        val scroll = rememberScrollState()
        val bars = LocalScrollbars.current
        val barColor = ndScrollbarColor(bars)
        Column(
            Modifier
                .verticalScroll(scroll)
                .ndScrollbar(scroll, bars, barColor),
        ) {
            DownloadSettingsRows(
                settings,
                DownloadSettingsScope.GROUP,
                LocalPlatformKind.current,
                onDownloadEdit,
                invalidFields = state.invalidFields,
                externalGroupReason =
                    state.externalGroupReason?.let {
                        downloadExternalReasonText(it, LocalPlatformKind.current).asString()
                    },
            )
            val notifications = settings.effective.notifyNewEpisodes == true
            val inherit = stringResource(Res.string.setting_inherit)
            SectionHeader(stringResource(Res.string.section_notifications))
            SettingsChoiceRow(
                icon = NdIcons.Info,
                title = stringResource(Res.string.new_episode_notifications),
                summary = notificationText(notifications),
                options = listOf(null, true, false),
                selected = settings.own.notifyNewEpisodes,
                label = { enabled -> if (enabled == null) inherit else notificationText(enabled) },
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
                summary = intervalText(settings.effective.refreshIntervalMinutes),
                options = listOf(null) + FeedsSettingKeys.REFRESH_INTERVAL_CHOICES,
                selected = settings.own.refreshIntervalMinutes,
                label = { minutes -> if (minutes == null) inherit else intervalText(minutes) },
                onSelect = onRefreshInterval,
            ) {
                Attribution(
                    settings.sources.getValue(SettingField.REFRESH_INTERVAL_MINUTES),
                    SettingField.REFRESH_INTERVAL_MINUTES in state.invalidFields,
                )
            }
        }
    }
}

@Composable
private fun intervalText(minutes: Int?): String =
    feedsIntervalText(minutes ?: FeedsSettingKeys.MANUAL_ONLY_MINUTES).asString()

@Composable
private fun notificationText(enabled: Boolean): String =
    stringResource(if (enabled) Res.string.setting_on else Res.string.setting_off)

/** Where the effective value comes from, and a warning when the stored override is invalid. */
@Composable
private fun Attribution(
    source: SettingSource,
    invalid: Boolean,
) {
    Column {
        Text(AttributionText.describe(source).asString(), style = MaterialTheme.typography.bodySmall)
        if (invalid) {
            Text(
                stringResource(Res.string.setting_invalid_value),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    EngravedHeading(title, Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
}

private val SECTION_PADDING = 16.dp
