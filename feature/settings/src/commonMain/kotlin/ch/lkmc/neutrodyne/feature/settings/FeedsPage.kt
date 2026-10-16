// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import ch.lkmc.neutrodyne.core.ui.SettingsChoiceDialog
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.feedsIntervalText
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionNotice
import ch.lkmc.neutrodyne.core.ui.platform.NotificationPermissionRequester
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.feeds_backfill
import ch.lkmc.neutrodyne.core.ui.resources.feeds_backfill_summary
import ch.lkmc.neutrodyne.core.ui.resources.feeds_images_always
import ch.lkmc.neutrodyne.core.ui.resources.feeds_images_tap
import ch.lkmc.neutrodyne.core.ui.resources.feeds_images_wifi
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_hour
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_hours
import ch.lkmc.neutrodyne.core.ui.resources.feeds_interval_manual
import ch.lkmc.neutrodyne.core.ui.resources.feeds_not_on_desktop
import ch.lkmc.neutrodyne.core.ui.resources.feeds_notes_images
import ch.lkmc.neutrodyne.core.ui.resources.feeds_on_open
import ch.lkmc.neutrodyne.core.ui.resources.feeds_refresh_interval
import ch.lkmc.neutrodyne.core.ui.resources.feeds_wifi_only
import ch.lkmc.neutrodyne.core.ui.resources.new_episode_notifications
import ch.lkmc.neutrodyne.core.ui.resources.settings_feeds
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Feeds (08 Settings screen structure; M1a rows): refresh interval, the two Android
 * triggers, backfill and show-notes images. M2 adds the contextual new-episode notification
 * switch (the page's later sections are listed in 08's `SettingsPage` table).
 *
 * On the desktop the Wi-Fi-only row stays visible with 11's "Not used on computers" explanation —
 * users may look for it (08 Shared pages) — while refresh-on-open, an Android foreground trigger
 * with no desktop meaning, is hidden.
 */
@Composable
internal fun FeedsPage(
    platform: PlatformKind,
    viewModel: FeedsSettingsViewModel = metroViewModel(),
    notificationRequester: NotificationPermissionRequester? = LocalPlatformActions.current.notifications,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var showImagesDialog by rememberSaveable { mutableStateOf(false) }

    ShowUserMessages(state.messages, viewModel::onMessageShown)

    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.settings_feeds),
            navigation = { SettingsBackButton() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsRow(
                icon = NdIcons.Schedule,
                title = stringResource(Res.string.feeds_refresh_interval),
                summary = feedsIntervalText(state.intervalMinutes).asString(),
                onClick = { showIntervalDialog = true },
            )
            if (platform == PlatformKind.ANDROID) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.feeds_wifi_only),
                    summary = null,
                    checked = state.wifiOnly,
                    onCheckedChange = viewModel::setRefreshWifiOnly,
                )
                SettingsSwitchRow(
                    title = stringResource(Res.string.feeds_on_open),
                    summary = null,
                    checked = state.refreshOnAppOpen,
                    onCheckedChange = viewModel::setRefreshOnAppOpen,
                )
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.feeds_wifi_only)) },
                    supportingContent = {
                        Text(stringResource(Res.string.feeds_not_on_desktop))
                    },
                    leadingContent = { Icon(NdIcons.Wifi, contentDescription = null) },
                )
            }
            SettingsSwitchRow(
                title = stringResource(Res.string.new_episode_notifications),
                summary = null,
                checked = state.notifyNewEpisodes,
                onCheckedChange = { viewModel.setNotifyNewEpisodes(it, notificationRequester) },
            )
            NotificationPermissionNotice(state.notifyNewEpisodes, notificationRequester)
            SettingsSwitchRow(
                title = stringResource(Res.string.feeds_backfill),
                summary = stringResource(Res.string.feeds_backfill_summary),
                checked = state.backfillPagedFeeds,
                onCheckedChange = viewModel::setBackfillPagedFeeds,
            )
            SettingsRow(
                icon = NdIcons.Image,
                title = stringResource(Res.string.feeds_notes_images),
                summary = stringResource(showNotesImagesLabel(state.showNotesImages)),
                onClick = { showImagesDialog = true },
            )
        }
    }

    if (showIntervalDialog) {
        SettingsChoiceDialog(
            icon = NdIcons.Schedule,
            title = stringResource(Res.string.feeds_refresh_interval),
            options = FeedsSettingKeys.REFRESH_INTERVAL_CHOICES,
            selected = state.intervalMinutes,
            label = { feedsIntervalText(it).asString() },
            onSelect = { minutes ->
                showIntervalDialog = false
                viewModel.setRefreshIntervalMinutes(minutes)
            },
            onDismissRequest = { showIntervalDialog = false },
        )
    }
    if (showImagesDialog) {
        SettingsChoiceDialog(
            icon = NdIcons.Image,
            title = stringResource(Res.string.feeds_notes_images),
            options = FeedsSettingKeys.SHOW_NOTES_IMAGES.values,
            selected = state.showNotesImages,
            label = { stringResource(showNotesImagesLabel(it)) },
            onSelect = { choice ->
                showImagesDialog = false
                viewModel.setShowNotesImages(choice)
            },
            onDismissRequest = { showImagesDialog = false },
        )
    }
}

private fun showNotesImagesLabel(choice: ShowNotesImages): StringResource =
    when (choice) {
        ShowNotesImages.ALWAYS -> Res.string.feeds_images_always
        ShowNotesImages.WIFI_ONLY -> Res.string.feeds_images_wifi
        ShowNotesImages.TAP_TO_LOAD -> Res.string.feeds_images_tap
    }
