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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.dismiss
import ch.lkmc.neutrodyne.core.ui.resources.radio_choose_note
import ch.lkmc.neutrodyne.core.ui.resources.radio_create
import ch.lkmc.neutrodyne.core.ui.resources.radio_name
import ch.lkmc.neutrodyne.core.ui.resources.radio_new_playlist
import ch.lkmc.neutrodyne.core.ui.resources.radio_placeholder
import ch.lkmc.neutrodyne.core.ui.resources.radio_playlists
import ch.lkmc.neutrodyne.core.ui.resources.radio_podcasts
import ch.lkmc.neutrodyne.core.ui.root.ndTextFieldFocus
import org.jetbrains.compose.resources.stringResource

internal enum class RadioInsert { CHOOSER, CREATE }

/** Dialog owns modal focus and restores its launcher; only the fields scroll. */
@Composable
internal fun RadioInsertDialog(
    mode: RadioInsert,
    form: RadioProgrammeState,
    state: RadioState,
    actions: RadioActions,
    programmeBounds: Rect,
    hostWindow: IntSize,
    onMode: (RadioInsert) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = form.name
    val chosen = form.chosen
    val canCreate =
        name.trim().isNotEmpty() && name.trim().count { !it.isLowSurrogate() } <= 40 &&
            chosen.isNotEmpty() && !state.creating
    val focus = remember { FocusRequester() }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(state.createdPlaylistId) {
        if (state.createdPlaylistId != null && state.createdPlaylistId != form.creationBaseline) dismiss()
    }
    LaunchedEffect(state.podcasts) {
        form.chosen = form.chosen.intersect(state.podcasts.map { it.id }.toSet())
    }
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
                        y =
                            (
                                programmeBounds.top /
                                    density.density -
                                    insetY
                            ).dp,
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
                            stringResource(
                                if (mode ==
                                    RadioInsert.CHOOSER
                                ) {
                                    Res.string.radio_playlists
                                } else {
                                    Res.string.radio_create
                                },
                            ),
                            20,
                            bold = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onDismiss,
                        ) { Icon(NdIcons.Close, stringResource(Res.string.dismiss), tint = Ink) }
                    }
                    if (mode == RadioInsert.CHOOSER) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            state.playlists.forEachIndexed { index, playlist ->
                                RadioText(
                                    playlist.name,
                                    19,
                                    bold = true,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .then(
                                                if (index ==
                                                    0
                                                ) {
                                                    Modifier.focusRequester(focus)
                                                } else {
                                                    Modifier
                                                },
                                            ).focusProperties { canFocus = true }
                                            .clickable {
                                                actions.selectPlaylist(playlist.id)
                                                onDismiss()
                                            }.testTag("radio_playlist_option_${playlist.id}")
                                            .padding(vertical = 16.dp),
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                onMode(RadioInsert.CREATE)
                            },
                            modifier =
                                if (state.playlists.isEmpty()) {
                                    Modifier.focusRequester(focus).focusProperties {
                                        canFocus =
                                            true
                                    }
                                } else {
                                    Modifier
                                },
                        ) {
                            RadioText(stringResource(Res.string.radio_new_playlist), 14)
                        }
                    } else {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            OutlinedTextField(
                                textStyle = TextStyle(fontFamily = courier(), fontSize = 16.sp, color = Ink),
                                value = name,
                                onValueChange = { form.name = it },
                                singleLine = true,
                                label = { RadioText(stringResource(Res.string.radio_name), 12) },
                                placeholder = { RadioText(stringResource(Res.string.radio_placeholder), 14) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focus)
                                        .testTag("radio_name")
                                        .ndTextFieldFocus(),
                            )
                            RadioText(
                                stringResource(Res.string.radio_podcasts),
                                12,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            state.podcasts.forEach { podcast ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            form.chosen =
                                                if (podcast.id in
                                                    chosen
                                                ) {
                                                    chosen - podcast.id
                                                } else {
                                                    chosen + podcast.id
                                                }
                                        }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioText(podcast.name, 13, bold = true, modifier = Modifier.weight(1f))
                                    Checkbox(checked = podcast.id in chosen, onCheckedChange = { checked ->
                                        form.chosen =
                                            if (checked) chosen + podcast.id else chosen - podcast.id
                                    })
                                }
                            }
                            RadioText(stringResource(Res.string.radio_choose_note), 12, mono = false)
                            state.createError?.let { RadioText(it.asString(), 12) }
                        }
                        TextButton(
                            onClick = { actions.createPlaylist(name, chosen) },
                            enabled = canCreate,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("radio_create"),
                        ) {
                            RadioText(
                                stringResource(Res.string.radio_create),
                                14,
                                bold = true,
                                color = if (canCreate) Ink else Ink.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(mode, windowFocused, inputMode) {
            if (windowFocused) {
                if (mode == RadioInsert.CHOOSER) inputMode.requestInputMode(InputMode.Keyboard)
                withFrameNanos { }
                focus.requestFocus()
            }
        }
    }
}
