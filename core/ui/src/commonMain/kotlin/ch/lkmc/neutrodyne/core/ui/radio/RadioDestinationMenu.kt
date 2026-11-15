// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SleepTimerKey
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_failed_badge
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_sleep
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu_speed
import ch.lkmc.neutrodyne.core.ui.resources.settings
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

public val LocalFailedDownloadCount = compositionLocalOf { 0 }

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
            // The radio is the only full player, so its playback sheets open from here.
            listOf(
                Triple(SpeedKey, Res.string.radio_menu_speed, "radio_speed"),
                Triple(SleepTimerKey, Res.string.radio_menu_sleep, "radio_sleep_timer"),
            ).forEach { (key, label, tag) ->
                TextButton(
                    onClick = {
                        onDismiss()
                        navigator.push(key)
                    },
                    modifier = Modifier.fillMaxWidth().testTag(tag),
                ) { Text(stringResource(label)) }
            }
            HorizontalDivider()
            val destinations =
                listOf(
                    Triple(LibraryKey, Res.string.nav_library, "nav_library"),
                    Triple(UpNextKey, Res.string.nav_up_next, "nav_up_next"),
                    Triple(DownloadsKey, Res.string.nav_downloads, "nav_downloads"),
                    Triple(DiscoverKey, Res.string.nav_discover, "nav_discover"),
                )
            destinations.forEach { (key, label, tag) ->
                val failures = LocalFailedDownloadCount.current.takeIf { key == DownloadsKey } ?: 0
                val description =
                    if (failures >
                        0
                    ) {
                        pluralStringResource(Res.plurals.downloads_failed_badge, failures, failures)
                    } else {
                        null
                    }
                TextButton(
                    onClick = {
                        onDismiss()
                        navigator.selectTab(key)
                    },
                    modifier =
                        Modifier.fillMaxWidth().testTag(tag).semantics {
                            if (description !=
                                null
                            ) {
                                contentDescription = description
                            }
                        },
                ) {
                    Text(stringResource(label) + if (failures > 0) " ($failures)" else "")
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
