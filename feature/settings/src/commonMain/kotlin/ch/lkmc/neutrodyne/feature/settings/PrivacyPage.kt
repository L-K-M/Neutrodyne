// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.ui.PrivacyInventory
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_intro
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_inventory
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_keyless
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_notes_images
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_off
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_on
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_providers
import ch.lkmc.neutrodyne.feature.settings.resources.privacy_title
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PrivacyPage(viewModel: DiscoverSettingsViewModel = metroViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PrivacyScreen(state)
}

@Composable
internal fun PrivacyScreen(state: DiscoverSettingsState) {
    val navigator = LocalAppNavigator.current
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(title = stringResource(Res.string.privacy_title), navigation = { SettingsBackButton() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(Res.string.privacy_intro))
            Text(stringResource(Res.string.privacy_inventory), style = MaterialTheme.typography.titleLarge)
            PrivacyInventory.destinations.forEach { destination ->
                Column(Modifier.padding(vertical = 8.dp).testTag("privacy-${destination.id}")) {
                    Text(stringResource(destination.title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(destination.body))
                    destination.provider?.let { id ->
                        state.providers.firstOrNull { it.id == id }?.let { provider ->
                            Text(
                                stringResource(
                                    when {
                                        !provider.enabled -> Res.string.privacy_off
                                        !provider.effective -> Res.string.privacy_keyless
                                        else -> Res.string.privacy_on
                                    },
                                ),
                                modifier = Modifier.testTag("privacy-state-${destination.id}"),
                            )
                            providerSummary(provider)?.let { Text(stringResource(it)) }
                        }
                    }
                }
            }
            SettingsRow(
                NdIcons.Explore,
                stringResource(Res.string.privacy_providers),
                null,
                { navigator.push(SettingsKey(SettingsPage.DISCOVER)) },
            )
            SettingsRow(
                NdIcons.Image,
                stringResource(Res.string.privacy_notes_images),
                null,
                { navigator.push(SettingsKey(SettingsPage.FEEDS)) },
            )
        }
    }
}
