// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.settings
import ch.lkmc.neutrodyne.feature.settings.resources.settings_about
import ch.lkmc.neutrodyne.feature.settings.resources.settings_about_summary
import ch.lkmc.neutrodyne.feature.settings.resources.settings_appearance
import ch.lkmc.neutrodyne.feature.settings.resources.settings_appearance_summary
import ch.lkmc.neutrodyne.feature.settings.resources.settings_licences
import ch.lkmc.neutrodyne.feature.settings.resources.settings_licences_summary
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.feature.settings.resources.Res as SettingsRes

/**
 * The Settings home list (08 Settings screens; M0a shows the three rows whose pages exist).
 * It is the gear's target, pushed on the selected tab's stack, so it keeps a back arrow.
 */
@Composable
internal fun SettingsHomeRoute() {
    val navigator = LocalAppNavigator.current
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
