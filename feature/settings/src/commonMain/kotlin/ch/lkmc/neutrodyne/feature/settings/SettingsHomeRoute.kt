// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.EngravedHeading
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.domain.update.AppUpdateChecker
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.core.model.settings.CloseBehaviour
import ch.lkmc.neutrodyne.core.model.settings.DesktopSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.update.UpdateCheckState
import ch.lkmc.neutrodyne.core.navigation.BackupKey
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.SyncSettingsKey
import ch.lkmc.neutrodyne.core.ui.FeedDates
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.SettingsRow
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.feedsIntervalText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_settings
import ch.lkmc.neutrodyne.core.ui.resources.settings
import ch.lkmc.neutrodyne.core.ui.resources.settings_feeds
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_keep
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_keep_mac
import ch.lkmc.neutrodyne.feature.settings.resources.desktop_close_quit
import ch.lkmc.neutrodyne.feature.settings.resources.discover_title
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_title
import ch.lkmc.neutrodyne.feature.settings.resources.settings_about
import ch.lkmc.neutrodyne.feature.settings.resources.settings_appearance
import ch.lkmc.neutrodyne.feature.settings.resources.settings_backup
import ch.lkmc.neutrodyne.feature.settings.resources.settings_desktop
import ch.lkmc.neutrodyne.feature.settings.resources.settings_group_app
import ch.lkmc.neutrodyne.feature.settings.resources.settings_group_library
import ch.lkmc.neutrodyne.feature.settings.resources.settings_group_listening
import ch.lkmc.neutrodyne.feature.settings.resources.settings_licences
import ch.lkmc.neutrodyne.feature.settings.resources.settings_playback
import ch.lkmc.neutrodyne.feature.settings.resources.settings_playback_summary_off
import ch.lkmc.neutrodyne.feature.settings.resources.settings_playback_summary_on
import ch.lkmc.neutrodyne.feature.settings.resources.settings_sync
import ch.lkmc.neutrodyne.feature.settings.resources.settings_sync_linked
import ch.lkmc.neutrodyne.feature.settings.resources.settings_sync_linked_at
import ch.lkmc.neutrodyne.feature.settings.resources.settings_sync_off
import ch.lkmc.neutrodyne.feature.settings.resources.settings_sync_reconnect
import ch.lkmc.neutrodyne.feature.settings.resources.settings_sync_revoked
import ch.lkmc.neutrodyne.feature.settings.resources.settings_sync_unfinished
import ch.lkmc.neutrodyne.feature.settings.resources.updates_available
import ch.lkmc.neutrodyne.feature.settings.resources.updates_title
import ch.lkmc.neutrodyne.feature.settings.resources.youtube_title
import ch.lkmc.neutrodyne.sync.api.SyncController
import ch.lkmc.neutrodyne.sync.api.SyncStatus
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.feature.settings.resources.Res as SettingsRes

/**
 * The Settings home (08 Settings screens; radio slips since PO-55): the pages as paper slips under
 * three engraved headings, Listening, Library and App, each showing its current value where one
 * is cheap to read instead of a description. It is pushed on the selected tab's stack, so it
 * keeps a back key. The Feeds row's value is the live refresh interval ("Every 4 hours"); the
 * Desktop row, desktop only, shows the "When the window is closed" choice. The Sync row (MS2) is
 * the only sync UI while unconfigured (R7.1); [sync] is nullable like `LicencesSource`: the
 * shells always bind it, and previews or test fixtures that omit it simply show no row.
 */
@Composable
internal fun SettingsHomeRoute(
    settings: SettingsRepository,
    checker: AppUpdateChecker? = null,
    platform: PlatformKind = PlatformKind.ANDROID,
    desktopOs: DesktopOs? = null,
    sync: SyncController? = null,
) {
    val navigator = LocalAppNavigator.current
    val updateState = checker?.state?.collectAsStateWithLifecycle()?.value
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
    val closeBehaviourFlow = remember(settings) { settings.observe(DesktopSettingKeys.CLOSE_BEHAVIOUR) }
    val closeBehaviour by
        closeBehaviourFlow.collectAsStateWithLifecycle(initialValue = DesktopSettingKeys.CLOSE_BEHAVIOUR.default)
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.settings),
            navigation = { SettingsBackButton() },
        )
        // 08 Scrollbars: the desktop draws an overlay thumb at the page's end edge.
        val scroll = rememberScrollState()
        val bars = LocalScrollbars.current
        val barColor = ndScrollbarColor(bars)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .ndScrollbar(scroll, bars, barColor),
        ) {
            val headingModifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp)
            EngravedHeading(stringResource(SettingsRes.string.settings_group_listening), headingModifier)
            SettingsRow(
                icon = NdIcons.PlayArrow,
                title = stringResource(SettingsRes.string.settings_playback),
                summary = null,
                onClick = { navigator.push(SettingsKey(SettingsPage.PLAYBACK)) },
                value =
                    stringResource(
                        if (silence) {
                            SettingsRes.string.settings_playback_summary_on
                        } else {
                            SettingsRes.string.settings_playback_summary_off
                        },
                        speed.toString(),
                    ),
            )
            SettingsRow(NdIcons.Download, stringResource(Res.string.downloads_settings), null, {
                navigator.push(SettingsKey(SettingsPage.DOWNLOADS))
            })
            SettingsRow(
                icon = NdIcons.RssFeed,
                title = stringResource(Res.string.settings_feeds),
                summary = null,
                onClick = { navigator.push(SettingsKey(SettingsPage.FEEDS)) },
                value = feedsIntervalText(interval).asString(),
            )
            SettingsRow(NdIcons.OpenInNew, stringResource(SettingsRes.string.youtube_title), null, {
                navigator.push(SettingsKey(SettingsPage.YOUTUBE))
            })

            EngravedHeading(stringResource(SettingsRes.string.settings_group_library), headingModifier)
            if (sync != null) {
                val status by sync.status.collectAsStateWithLifecycle()
                SettingsRow(
                    icon = NdIcons.Sync,
                    title = stringResource(SettingsRes.string.settings_sync),
                    summary = null,
                    onClick = { navigator.push(SyncSettingsKey) },
                    value = syncSummary(status),
                )
            }
            SettingsRow(
                icon = NdIcons.History,
                title = stringResource(SettingsRes.string.settings_backup),
                summary = null,
                onClick = { navigator.push(BackupKey) },
            )
            SettingsRow(
                NdIcons.Explore,
                stringResource(SettingsRes.string.discover_title),
                null,
                { navigator.push(SettingsKey(SettingsPage.DISCOVER)) },
            )

            EngravedHeading(stringResource(SettingsRes.string.settings_group_app), headingModifier)
            SettingsRow(
                icon = NdIcons.Palette,
                title = stringResource(SettingsRes.string.settings_appearance),
                summary = null,
                onClick = { navigator.push(SettingsKey(SettingsPage.APPEARANCE)) },
            )
            // The Desktop row, desktop only, sits with the other app-wide pages.
            if (platform == PlatformKind.DESKTOP) {
                SettingsRow(
                    icon = NdIcons.Computer,
                    title = stringResource(SettingsRes.string.settings_desktop),
                    summary = null,
                    onClick = { navigator.push(SettingsKey(SettingsPage.DESKTOP)) },
                    value =
                        stringResource(
                            when {
                                closeBehaviour == CloseBehaviour.KEEP_RUNNING &&
                                    desktopOs == DesktopOs.MACOS -> {
                                    SettingsRes.string.desktop_close_keep_mac
                                }

                                closeBehaviour == CloseBehaviour.KEEP_RUNNING -> {
                                    SettingsRes.string.desktop_close_keep
                                }

                                else -> {
                                    SettingsRes.string.desktop_close_quit
                                }
                            },
                        ),
                )
            }
            val available = updateState as? UpdateCheckState.Available
            SettingsRow(
                icon = NdIcons.Info,
                title = stringResource(SettingsRes.string.updates_title),
                summary = null,
                onClick = { navigator.push(SettingsKey(SettingsPage.UPDATES)) },
                badge = available != null,
                value = available?.let { stringResource(SettingsRes.string.updates_available, it.info.versionName) },
            )
            SettingsRow(
                NdIcons.Info,
                stringResource(SettingsRes.string.privacy_title),
                null,
                { navigator.push(SettingsKey(SettingsPage.PRIVACY)) },
            )
            SettingsRow(
                icon = NdIcons.Info,
                title = stringResource(SettingsRes.string.settings_about),
                summary = null,
                onClick = { navigator.push(SettingsKey(SettingsPage.ABOUT)) },
            )
            SettingsRow(
                icon = NdIcons.Article,
                title = stringResource(SettingsRes.string.settings_licences),
                summary = null,
                onClick = { navigator.push(LicencesKey) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The Sync row's summary — the short `SyncStatus` text of 08's Sync-status table ("Linked ·
 * synced 2 min", "Off"). `:core:ui`'s `SyncStatusText` mapper is the full MS2 deliverable and
 * cannot host `:sync:api` types under the declared module edges, so the row keeps its own short
 * text here; a `Linked` problem falls back to "Linked" and stays on the detail screen.
 */
@Composable
private fun syncSummary(status: SyncStatus): String =
    when (status) {
        is SyncStatus.NotConfigured -> {
            stringResource(SettingsRes.string.settings_sync_off)
        }

        is SyncStatus.Reconnect -> {
            stringResource(SettingsRes.string.settings_sync_reconnect, hostOf(status.serverUrl))
        }

        is SyncStatus.LinkUnfinished -> {
            stringResource(SettingsRes.string.settings_sync_unfinished)
        }

        is SyncStatus.Linked -> {
            val syncedAt = status.lastSyncAtMs
            if (status.problem == null && syncedAt != null) {
                stringResource(
                    SettingsRes.string.settings_sync_linked_at,
                    FeedDates.relative(syncedAt, LocalUiClock.current.now()).asString(),
                )
            } else {
                stringResource(SettingsRes.string.settings_sync_linked)
            }
        }

        is SyncStatus.Revoked -> {
            stringResource(SettingsRes.string.settings_sync_revoked)
        }
    }

private fun hostOf(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
