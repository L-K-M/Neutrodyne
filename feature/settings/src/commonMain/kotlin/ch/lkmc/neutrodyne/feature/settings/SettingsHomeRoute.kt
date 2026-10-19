// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.navigation.BackupKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.LocalPlatformKind
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.feedsIntervalText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.download_desktop_settings
import ch.lkmc.neutrodyne.core.ui.resources.downloads_settings
import ch.lkmc.neutrodyne.core.ui.resources.settings
import ch.lkmc.neutrodyne.core.ui.resources.settings_feeds
import ch.lkmc.neutrodyne.feature.settings.resources.settings_about
import ch.lkmc.neutrodyne.feature.settings.resources.settings_about_summary
import ch.lkmc.neutrodyne.feature.settings.resources.settings_appearance
import ch.lkmc.neutrodyne.feature.settings.resources.settings_appearance_summary
import ch.lkmc.neutrodyne.feature.settings.resources.settings_backup
import ch.lkmc.neutrodyne.feature.settings.resources.settings_backup_summary
import ch.lkmc.neutrodyne.feature.settings.resources.settings_licences
import ch.lkmc.neutrodyne.feature.settings.resources.settings_licences_summary
import ch.lkmc.neutrodyne.feature.settings.resources.settings_playback
import ch.lkmc.neutrodyne.feature.settings.resources.settings_playback_summary_off
import ch.lkmc.neutrodyne.feature.settings.resources.settings_playback_summary_on
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.feature.settings.resources.Res as SettingsRes

/**
 * The Settings home list (08 Settings screens; M0a shows Appearance and About, M1a adds Feeds).
 * It is the gear's target, pushed on the selected tab's stack, so it keeps a back arrow. The Feeds
 * row's summary is the live refresh interval ("Every 4 hours" in 08's wireframe).
 */
@Composable
internal fun SettingsHomeRoute(settings: SettingsRepository) {
    val navigator = LocalAppNavigator.current
    // observe() builds a fresh, unequal Flow every call — an unremembered instance would
    // restart the collection on each recomposition.
    val intervalFlow = remember(settings) { settings.observe(FeedsSettingKeys.REFRESH_INTERVAL_MINUTES) }
    val interval by
        intervalFlow.collectAsStateWithLifecycle(
            initialValue = FeedsSettingKeys.REFRESH_INTERVAL_MINUTES.default,
        )

    val speedFlow = remember(settings) { settings.observe(PlaybackSettingKeys.SPEED) }
    val silenceFlow = remember(settings) { settings.observe(PlaybackSettingKeys.SKIP_SILENCE) }
    val speed by speedFlow.collectAsStateWithLifecycle(initialValue = PlaybackSettingKeys.SPEED.default)
    val silence by silenceFlow.collectAsStateWithLifecycle(initialValue = PlaybackSettingKeys.SKIP_SILENCE.default)
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.settings),
            navigation = { SettingsBackButton() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsRow(
                icon = NdIcons.Palette,
                title = stringResource(SettingsRes.string.settings_appearance),
                summary = stringResource(SettingsRes.string.settings_appearance_summary),
                onClick = { navigator.push(SettingsKey(SettingsPage.APPEARANCE)) },
            )
            SettingsRow(
                icon = NdIcons.RssFeed,
                title = stringResource(Res.string.settings_feeds),
                summary = feedsIntervalText(interval).asString(),
                onClick = { navigator.push(SettingsKey(SettingsPage.FEEDS)) },
            )
            SettingsRow(
                icon = NdIcons.PlayArrow,
                title = stringResource(SettingsRes.string.settings_playback),
                summary =
                    stringResource(
                        if (silence) {
                            SettingsRes.string.settings_playback_summary_on
                        } else {
                            SettingsRes.string.settings_playback_summary_off
                        },
                        speed.toString(),
                    ),
                onClick = { navigator.push(SettingsKey(SettingsPage.PLAYBACK)) },
            )
            SettingsRow(NdIcons.Download, stringResource(Res.string.downloads_settings), null, {
                navigator.push(SettingsKey(SettingsPage.DOWNLOADS))
            })
            if (LocalPlatformKind.current == PlatformKind.DESKTOP) {
                SettingsRow(NdIcons.Computer, stringResource(Res.string.download_desktop_settings), null, {
                    navigator.push(SettingsKey(SettingsPage.DESKTOP))
                })
            }
            SettingsRow(
                icon = NdIcons.History,
                title = stringResource(SettingsRes.string.settings_backup),
                summary = stringResource(SettingsRes.string.settings_backup_summary),
                onClick = { navigator.push(BackupKey) },
            )
            SettingsRow(
                icon = NdIcons.Info,
                title = stringResource(SettingsRes.string.settings_about),
                summary = stringResource(SettingsRes.string.settings_about_summary),
                onClick = { navigator.push(SettingsKey(SettingsPage.ABOUT)) },
            )
            SettingsRow(
                icon = NdIcons.Article,
                title = stringResource(SettingsRes.string.settings_licences),
                summary = stringResource(SettingsRes.string.settings_licences_summary),
                onClick = { navigator.push(LicencesKey) },
            )
        }
    }
}
