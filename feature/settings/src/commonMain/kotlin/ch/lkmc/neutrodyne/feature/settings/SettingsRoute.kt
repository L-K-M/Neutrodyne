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

/**
 * `SettingsKey(page)` dispatch (08 Settings screens). [SettingsPage.APPEARANCE] also serves the
 * desktop's detail placeholder; pages without a row are unreachable until their milestone lands.
 * [desktopIntegration] binds only in `DesktopAppGraph` — `null` elsewhere, which hides the
 * Start-at-login row on [DesktopPage].
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
) {
    when (key.page) {
        SettingsPage.APPEARANCE -> AppearancePage(settings, dynamicColorSupport, platform)
        SettingsPage.DOWNLOADS -> DownloadsPage(platform)
        SettingsPage.FEEDS -> FeedsPage(platform)
        SettingsPage.PLAYBACK -> PlaybackPage(platform)
        SettingsPage.UPDATES -> UpdatesPage(buildInfo, updateTarget, key.openRelease)
        SettingsPage.DISCOVER -> DiscoverPage()
        SettingsPage.PRIVACY -> PrivacyPage()
        SettingsPage.DESKTOP -> DesktopPage(settings, desktopIntegration, buildInfo.desktop?.os)
        SettingsPage.ABOUT -> AboutPage(buildInfo)
        else -> AppearancePage(settings, dynamicColorSupport, platform)
    }
}
