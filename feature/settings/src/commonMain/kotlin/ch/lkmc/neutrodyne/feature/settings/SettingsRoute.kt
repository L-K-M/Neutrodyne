// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.appearance_placeholder_body
import ch.lkmc.neutrodyne.feature.settings.resources.settings_appearance
import org.jetbrains.compose.resources.stringResource

/**
 * `SettingsKey(page)` dispatch (08 Settings screens). M0a renders [SettingsPage.ABOUT] and the
 * Appearance placeholder (which also serves the desktop's detail placeholder); pages without an
 * M0a row share the placeholder until their milestone lands.
 */
@Composable
internal fun SettingsRoute(
    key: SettingsKey,
    buildInfo: BuildInfo,
) {
    when (key.page) {
        SettingsPage.ABOUT -> AboutPage(buildInfo)
        else -> AppearancePage()
    }
}

/**
 * The M0a Appearance page: the real theme/dynamic-colour controls arrive with the appearance
 * settings milestone. Also rendered as `SettingsHomeKey`'s two-pane detail placeholder
 * (08 Settings screens).
 */
@Composable
internal fun AppearancePage() {
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.settings_appearance),
            navigation = { SettingsBackButton() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NdEmptyState(
                icon = NdIcons.Palette,
                title = stringResource(Res.string.settings_appearance),
                body = stringResource(Res.string.appearance_placeholder_body),
            )
        }
    }
}
