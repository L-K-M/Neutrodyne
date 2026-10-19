// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.ui.resources.dismiss
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_write_failed
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/** Device preference only. New playback never writes it; failed writes leave the old layout. */
public class PlayerPanelPreference(
    public val hidden: Boolean,
    public val close: () -> Unit,
    public val reopen: () -> Unit,
    internal val writeFailed: Boolean,
    internal val dismissFailure: () -> Unit,
)

@Composable
public fun rememberPlayerPanelPreference(settings: SettingsRepository): PlayerPanelPreference {
    val flow = remember(settings) { settings.observe(AppearanceSettingKeys.PLAYER_PANEL_HIDDEN) }
    // Keep the panel closed until the device file arrives, avoiding a flash of a persisted-hidden panel.
    val hidden by flow.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val write: (Boolean) -> Unit =
        remember(settings, scope) {
            { value ->
                scope.launch {
                    if (settings.set(AppearanceSettingKeys.PLAYER_PANEL_HIDDEN, value) is Outcome.Failure) failed = true
                }
            }
        }
    val close = remember(write) { { write(true) } }
    val reopen = remember(write) { { write(false) } }
    return remember(hidden, close, reopen, failed) {
        PlayerPanelPreference(hidden, close, reopen, failed) { failed = false }
    }
}

/** Composed inside the root so settings failures use the current app theme. */
@Composable
public fun PlayerPanelWriteFeedback(panel: PlayerPanelPreference) {
    if (!panel.writeFailed) return
    NdDialog(
        onDismissRequest = panel.dismissFailure,
        title = stringResource(Res.string.player_write_failed),
        confirm = NdDialogAction(stringResource(UiRes.string.dismiss), onClick = panel.dismissFailure),
    )
}
