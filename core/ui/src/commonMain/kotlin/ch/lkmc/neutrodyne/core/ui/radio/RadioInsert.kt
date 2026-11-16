// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.dismiss
import ch.lkmc.neutrodyne.core.ui.resources.radio_no_playlists
import ch.lkmc.neutrodyne.core.ui.resources.radio_playlists
import org.jetbrains.compose.resources.stringResource

/** Dialog owns modal focus and restores its launcher; only the list scrolls. */
@Composable
internal fun RadioInsertDialog(
    state: RadioState,
    actions: RadioActions,
    programmeBounds: Rect,
    hostWindow: IntSize,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val insetX = (hostWindow.width / density.density - maxWidth.value) / 2
            val insetY = (hostWindow.height / density.density - maxHeight.value) / 2
            MaterialPlate(
                RadioMaterial.PAPER,
                Modifier
                    .offset(
                        x = (programmeBounds.left / density.density - insetX).dp,
                        y = (programmeBounds.top / density.density - insetY).dp,
                    ).width((programmeBounds.width / density.density).dp)
                    .height((programmeBounds.height / density.density).dp)
                    .onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    }.testTag("radio_insert")
                    .border(6.dp, Color(0xFF644724)),
            ) {
                Column(Modifier.fillMaxSize().padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioText(
                            stringResource(Res.string.radio_playlists),
                            20,
                            bold = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onDismiss,
                            // With no playlist to focus, the close key keeps keyboard focus inside the dialog.
                            modifier = if (state.playlists.isEmpty()) Modifier.focusRequester(focus) else Modifier,
                        ) { Icon(NdIcons.Close, stringResource(Res.string.dismiss), tint = Ink) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (state.playlists.isEmpty()) {
                            RadioText(
                                stringResource(Res.string.radio_no_playlists),
                                13,
                                mono = false,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                        state.playlists.forEachIndexed { index, playlist ->
                            RadioText(
                                playlist.name,
                                19,
                                bold = true,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .then(if (index == 0) Modifier.focusRequester(focus) else Modifier)
                                        .focusProperties { canFocus = true }
                                        .clickable {
                                            actions.selectPlaylist(playlist.id)
                                            onDismiss()
                                        }.testTag("radio_playlist_option_${playlist.id}")
                                        .padding(vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(windowFocused, inputMode) {
            if (windowFocused) {
                inputMode.requestInputMode(InputMode.Keyboard)
                withFrameNanos { }
                focus.requestFocus()
            }
        }
    }
}
