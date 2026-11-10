// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.LocalScrollbars
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbar
import ch.lkmc.neutrodyne.core.designsystem.components.ndScrollbarColor
import ch.lkmc.neutrodyne.feature.settings.resources.Res
import ch.lkmc.neutrodyne.feature.settings.resources.sync_account_help_account
import ch.lkmc.neutrodyne.feature.settings.resources.sync_account_help_intro
import ch.lkmc.neutrodyne.feature.settings.resources.sync_account_help_open
import ch.lkmc.neutrodyne.feature.settings.resources.sync_account_help_password
import ch.lkmc.neutrodyne.feature.settings.resources.sync_account_help_sessions
import ch.lkmc.neutrodyne.feature.settings.resources.sync_account_help_title
import org.jetbrains.compose.resources.stringResource

/**
 * The static sync-account help page (08 Sync notices: "Not you?"). It gives the generic steps for
 * the server this device is linked to: review the active web sessions and change the password on
 * the server's account page. It names no contact route and never changes or revokes anything itself.
 */
@Composable
internal fun SyncAccountHelpPage() {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().testTag("sync-account-help-page")) {
        NdTopAppBar(
            title = stringResource(Res.string.sync_account_help_title),
            navigation = { SettingsBackButton() },
        )
        val bars = LocalScrollbars.current
        val barColor = ndScrollbarColor(bars)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .ndScrollbar(scroll, bars, barColor)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.sync_account_help_intro))
            Text(stringResource(Res.string.sync_account_help_open))
            Text(stringResource(Res.string.sync_account_help_account))
            Text(stringResource(Res.string.sync_account_help_sessions))
            Text(stringResource(Res.string.sync_account_help_password))
        }
    }
}
