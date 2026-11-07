// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.domain.DesktopIntegration
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.update.UpdateRuntimeTarget
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `SettingsKey(page)` dispatch (08 Settings screens). [SettingsPage.APPEARANCE] also serves the
 * desktop's detail placeholder; pages without a row are unreachable until their milestone lands.
 * [desktopIntegration] binds only in `DesktopAppGraph` — `null` elsewhere, which hides the
 * Start-at-login row on [DesktopPage]. The Desktop page's downloads-folder section resolves its
 * own view model through `metroViewModel()`, which the shells' `LocalMetroViewModelFactory`
 * provides.
 */
@Composable
internal fun SettingsRoute(
    key: SettingsKey,
    buildInfo: BuildInfo,
    settings: SettingsRepository,
    dynamicColorSupport: DynamicColorSupport,
    platform: PlatformKind,
    updateTarget: UpdateRuntimeTarget,
    desktopIntegration: DesktopIntegration?,
    hasChannels: Flow<Boolean> = flowOf(false),
    capabilities: YouTubeCapabilitiesSource? = null,
) {
    when (key.page) {
        SettingsPage.YOUTUBE -> {
            YouTubePage(settings, capabilities, platform)
        }

        SettingsPage.APPEARANCE -> {
            AppearancePage(settings, dynamicColorSupport, platform, hasChannels)
        }

        SettingsPage.FEEDS -> {
            FeedsPage(platform)
        }

        SettingsPage.PLAYBACK -> {
            PlaybackPage(platform)
        }

        SettingsPage.DOWNLOADS -> {
            DownloadsSettingsPage(platform)
        }

        SettingsPage.UPDATES -> {
            UpdatesPage(buildInfo, updateTarget, key.openRelease)
        }

        SettingsPage.DISCOVER -> {
            DiscoverPage()
        }

        SettingsPage.PRIVACY -> {
            PrivacyPage()
        }

        SettingsPage.DESKTOP -> {
            DesktopPage(
                settings,
                desktopIntegration,
                buildInfo.desktop?.os,
                downloadsFolder = { DesktopDownloadFolderSection() },
            )
        }

        SettingsPage.ABOUT -> {
            AboutPage(buildInfo)
        }

        else -> {
            AppearancePage(settings, dynamicColorSupport, platform, hasChannels)
        }
    }
}
