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
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.asString
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
import ch.lkmc.neutrodyne.core.ui.resources.settings_feeds
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Feeds (08 Settings screen structure; M1a rows): refresh interval, the two Android
 * triggers, backfill and show-notes images. Notifications and group rows arrive with their
 * milestones (the page's later sections are listed in 08's `SettingsPage` table).
 *
 * On the desktop the Wi-Fi-only row stays visible with 11's "Not used on computers" explanation —
 * users may look for it (08 Shared pages) — while refresh-on-open, an Android foreground trigger
 * with no desktop meaning, is hidden.
 */
@Composable
internal fun FeedsPage(
    platform: PlatformKind,
    viewModel: FeedsSettingsViewModel = metroViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var showImagesDialog by rememberSaveable { mutableStateOf(false) }

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
        FeedsChoiceDialog(
            icon = NdIcons.Schedule,
            title = stringResource(Res.string.feeds_refresh_interval),
            options = REFRESH_INTERVAL_OPTIONS,
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
        FeedsChoiceDialog(
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

/** The interval row's radio list (03 Settings: {0, 60, 120, 240, 480, 720, 1440} minutes). */
private val REFRESH_INTERVAL_OPTIONS =
    listOf(
        FeedsSettingKeys.MANUAL_ONLY_MINUTES,
        MINUTES_PER_HOUR,
        2 * MINUTES_PER_HOUR,
        FeedsSettingKeys.DEFAULT_REFRESH_INTERVAL_MINUTES,
        8 * MINUTES_PER_HOUR,
        12 * MINUTES_PER_HOUR,
        24 * MINUTES_PER_HOUR,
    )

/** "Manual only" / "Every hour" / "Every N hours" — row summary, dialog label and home row. */
internal fun feedsIntervalText(minutes: Int): UiText =
    when (minutes) {
        FeedsSettingKeys.MANUAL_ONLY_MINUTES -> UiText.Res(Res.string.feeds_interval_manual)
        MINUTES_PER_HOUR -> UiText.Res(Res.string.feeds_interval_hour)
        else -> UiText.Res(Res.string.feeds_interval_hours, listOf(minutes / MINUTES_PER_HOUR))
    }

private fun showNotesImagesLabel(choice: ShowNotesImages): StringResource =
    when (choice) {
        ShowNotesImages.ALWAYS -> Res.string.feeds_images_always
        ShowNotesImages.WIFI_ONLY -> Res.string.feeds_images_wifi
        ShowNotesImages.TAP_TO_LOAD -> Res.string.feeds_images_tap
    }

/** The page's radio-row chooser, shared by the interval and show-notes images rows. */
@Composable
private fun <T> FeedsChoiceDialog(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismissRequest: () -> Unit,
) {
    NdDialog(
        onDismissRequest = onDismissRequest,
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
