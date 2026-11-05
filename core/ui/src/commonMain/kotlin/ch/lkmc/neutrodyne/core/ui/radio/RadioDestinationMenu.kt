// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu
import ch.lkmc.neutrodyne.core.ui.resources.settings
import org.jetbrains.compose.resources.stringResource

/** Existing destinations retain their keys, stacks and external route handling. */
@Composable
public fun RadioDestinationMenu(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalAppNavigator.current
    NdDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("radio_destinations"),
        title = stringResource(Res.string.radio_menu),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val destinations =
                listOf(
                    Triple(LibraryKey, Res.string.nav_library, "nav_library"),
                    Triple(UpNextKey, Res.string.nav_up_next, "nav_up_next"),
                    Triple(DownloadsKey, Res.string.nav_downloads, "nav_downloads"),
                    Triple(DiscoverKey, Res.string.nav_discover, "nav_discover"),
                )
            destinations.forEach { (key, label, tag) ->
                TextButton(onClick = {
                    onDismiss()
                    navigator.selectTab(key)
                }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                    Text(stringResource(label))
                }
            }
            TextButton(onClick = {
                onDismiss()
                navigator.push(SettingsHomeKey)
            }, modifier = Modifier.fillMaxWidth().testTag("nav_settings")) {
                Text(stringResource(Res.string.settings))
            }
        }
    }
}
