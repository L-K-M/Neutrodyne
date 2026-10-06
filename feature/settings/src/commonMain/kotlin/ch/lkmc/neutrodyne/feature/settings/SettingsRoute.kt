// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage

/**
 * `SettingsKey(page)` dispatch (08 Settings screens). M0a renders [SettingsPage.ABOUT] and
 * [SettingsPage.APPEARANCE] (which also serves the desktop's detail placeholder); pages without an
 * M0a row are unreachable until their milestone lands.
 */
@Composable
internal fun SettingsRoute(
    key: SettingsKey,
    buildInfo: BuildInfo,
    settings: SettingsRepository,
    dynamicColorSupport: DynamicColorSupport,
) {
    when (key.page) {
        SettingsPage.APPEARANCE -> AppearancePage(settings, dynamicColorSupport)
        SettingsPage.ABOUT -> AboutPage(buildInfo)
        else -> AppearancePage(settings, dynamicColorSupport)
    }
}
