// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.queue

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.nav_up_next
import ch.lkmc.neutrodyne.core.ui.resources.up_next_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.up_next_empty_title
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import org.jetbrains.compose.resources.stringResource

/**
 * The M0a Up next destination (01 M0 checklist step 16): a top bar with the Settings gear and the
 * empty state. M4 delivers the queue and the play context (08 Up next).
 */
@Composable
internal fun UpNextRoute() {
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_up_next),
            actions = { SettingsGearButton() },
        )
        NdEmptyState(
            icon = NdIcons.QueueMusic,
            title = stringResource(Res.string.up_next_empty_title),
            body = stringResource(Res.string.up_next_empty_body),
        )
    }
}
