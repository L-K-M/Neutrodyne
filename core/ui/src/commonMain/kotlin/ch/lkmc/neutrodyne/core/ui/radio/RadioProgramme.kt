// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.ReportVisibleEpisodeIds
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_hours_minutes
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_minutes
import ch.lkmc.neutrodyne.core.ui.resources.feeds_load_error
import ch.lkmc.neutrodyne.core.ui.resources.podcast_count
import ch.lkmc.neutrodyne.core.ui.resources.podcast_episode_count
import ch.lkmc.neutrodyne.core.ui.resources.radio_all_clear
import ch.lkmc.neutrodyne.core.ui.resources.radio_discard
import ch.lkmc.neutrodyne.core.ui.resources.radio_episodes
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu
import ch.lkmc.neutrodyne.core.ui.resources.radio_new_playlist
import ch.lkmc.neutrodyne.core.ui.resources.radio_no_episodes
import ch.lkmc.neutrodyne.core.ui.resources.radio_playlists
import ch.lkmc.neutrodyne.core.ui.resources.radio_release
import ch.lkmc.neutrodyne.core.ui.resources.radio_removed
import ch.lkmc.neutrodyne.core.ui.resources.radio_swipe
import ch.lkmc.neutrodyne.core.ui.resources.radio_your_playlist
import ch.lkmc.neutrodyne.core.ui.resources.settings_update_available
import ch.lkmc.neutrodyne.core.ui.root.LocalSettingsBadge
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class RadioRows(
    val minimumHeight: Dp,
    val titleSize: Int,
    val coverSize: Dp,
) {
    COMPACT(64.dp, 13, 22.dp),
    STANDARD(68.dp, 13, 22.dp),
    WIDE(78.dp, 14, 24.dp),
}

@Composable
internal fun RadioProgramme(
    state: RadioState,
    actions: RadioActions,
    form: RadioProgrammeState,
    scroll: LazyListState,
    compact: Boolean,
    rows: RadioRows,
    modifier: Modifier = Modifier,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val windowSize = LocalWindowInfo.current.containerSize
    val settingsBadge = LocalSettingsBadge.current
    val menuLabel = stringResource(Res.string.radio_menu)
    val updateLabel = stringResource(Res.string.settings_update_available)
    val menuNotice =
        if (settingsBadge) {
            Modifier.semantics { contentDescription = "$menuLabel, $updateLabel" }.drawWithContent {
                drawContent()
                drawCircle(Color(0xFFF3881C), 3.dp.toPx(), Offset(size.width - 6.dp.toPx(), 6.dp.toPx()))
            }
        } else {
            Modifier
        }
    val totalMinutes = state.episodes.sumOf { it.durationMs ?: 0 } / 60_000
    val durationText =
        if (totalMinutes < 60) {
            stringResource(Res.string.date_duration_minutes, totalMinutes)
        } else {
            stringResource(Res.string.date_duration_hours_minutes, totalMinutes / 60, totalMinutes % 60)
        }
    val podcastCount = state.playlist?.podcastCount ?: 0
    val episodeCount = if (state.complete) state.episodes.size else state.episodeCount ?: state.episodes.size
    val metadata =
        buildList {
            add(pluralStringResource(Res.plurals.podcast_count, podcastCount, podcastCount))
            if (state.complete || state.episodeCount != null) {
                add(pluralStringResource(Res.plurals.podcast_episode_count, episodeCount, episodeCount))
            }
            if (state.complete && state.episodes.all { it.durationMs != null }) add(durationText)
        }.joinToString(" · ")
    ReportVisibleEpisodeIds(
        scroll,
        { state.episodes.size },
        { state.episodes.getOrNull(it)?.id },
        actions.visibleEpisodes,
    )
    val pickerFocus = remember { FocusRequester() }
    val newFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(form.restoreFocus, windowFocused, inputMode) {
        if (form.restoreFocus && windowFocused) {
            // Android returns from a modal in touch mode; click targets need keyboard focus restored explicitly.
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            if ((if (form.launcher == RadioInsert.CHOOSER) pickerFocus else newFocus).requestFocus()) {
                form.restoreFocus = false
            }
        }
    }
    LaunchedEffect(state.playlistId) {
        val id = state.playlistId
        if (id != null) {
            if (form.playlistId != null && form.playlistId != id) scroll.scrollToItem(0)
            form.playlistId = id
        }
    }
    val undoFocus = remember { FocusRequester() }
    var restoreRowFocus by remember { mutableStateOf<Long?>(null) }
    var previousPlaylist by remember { mutableStateOf(state.playlistId) }
    var previousRemoval by remember { mutableStateOf<RadioEpisode?>(null) }
    LaunchedEffect(state.playlistId, state.removed) {
        val samePlaylist = previousPlaylist == state.playlistId
        val restored = previousRemoval.takeIf { samePlaylist }
        previousRemoval = state.removed
        previousPlaylist = state.playlistId
        if (state.removed == null && restored != null) {
            val index = state.episodes.indexOfFirst { it.id == restored.id }
            if (index >= 0) {
                scroll.scrollToItem(index)
                restoreRowFocus = restored.id
            }
        } else if (state.removed != null && restored?.id != state.removed.id && samePlaylist) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            undoFocus.requestFocus()
        }
    }
    MaterialPlate(
        RadioMaterial.METAL,
        modifier
            .onGloballyPositioned {
                bounds = it.boundsInWindow()
            }.border(1.dp, Brass)
            .padding(6.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    if (!compact) {
                        RadioText(
                            stringResource(Res.string.radio_your_playlist).uppercase(),
                            11,
                            color = Cream,
                            mono = false,
                        )
                    }
                    MaterialPlate(
                        RadioMaterial.PAPER,
                        Modifier
                            .fillMaxWidth()
                            .recess()
                            .focusRequester(pickerFocus)
                            .focusProperties { canFocus = true }
                            .clickable {
                                form.open(
                                    RadioInsert.CHOOSER,
                                    state.createdPlaylistId,
                                )
                            }.testTag("radio_playlist"),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 7.dp).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioText(
                                state.playlist?.name ?: stringResource(Res.string.radio_playlists),
                                if (compact) 17 else 19,
                                modifier = Modifier.weight(1f),
                                bold = true,
                                maxLines = 2,
                            )
                            Icon(NdIcons.ArrowForwardIos, null, Modifier.size(16.dp).rotate(90f), tint = Ink)
                        }
                    }
                }
                SmallRadioKey(
                    stringResource(Res.string.radio_new_playlist),
                    NdIcons.Add,
                    "radio_new_playlist",
                    onClick = { form.open(RadioInsert.CREATE, state.createdPlaylistId) },
                    modifier = Modifier.focusRequester(newFocus),
                )
                SmallRadioKey(menuLabel, NdIcons.DragHandle, "radio_menu", actions.menu, modifier = menuNotice)
            }
            RadioText(
                metadata,
                11,
                color = Cream,
                mono = false,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            MaterialPlate(RadioMaterial.PAPER, Modifier.weight(1f).fillMaxWidth().recess()) {
                Column(Modifier.fillMaxSize()) {
                    if (!compact) {
                        RadioText(
                            stringResource(Res.string.radio_episodes).uppercase(),
                            11,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                    if (state.episodes.isEmpty()) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                            Arrangement.Center,
                            Alignment.CenterHorizontally,
                        ) {
                            if (state.loading) {
                                CircularProgressIndicator(color = Brass, modifier = Modifier.size(24.dp))
                            } else if (!state.loadError) {
                                RadioText(stringResource(Res.string.radio_all_clear), 19, bold = true)
                                RadioText(stringResource(Res.string.radio_no_episodes), 12, mono = false)
                            }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).testTag("radio_episodes"), state = scroll) {
                            itemsIndexed(state.episodes, key = { _, episode -> episode.id }) { index, episode ->
                                LaunchedEffect(index) { actions.loadEpisode(index) }
                                RadioSlip(
                                    episode,
                                    state.selectedId == episode.id,
                                    state.selectedId.takeIf { state.playing },
                                    rows,
                                    actions,
                                    restoreRowFocus,
                                ) {
                                    restoreRowFocus = null
                                }
                            }
                        }
                    }
                    if (state.loadError) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioText(stringResource(Res.string.feeds_load_error), 12, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions.retry, modifier = Modifier.testTag("radio_retry")) {
                                RadioText(stringResource(Res.string.action_retry), 12, bold = true)
                            }
                        }
                    }
                    if (state.removed != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                RadioText(state.removed.title, 11, bold = true, mono = false)
                                RadioText(stringResource(Res.string.radio_removed), 11, mono = false)
                            }
                            TextButton(
                                onClick = actions.undo,
                                modifier =
                                    Modifier
                                        .focusRequester(
                                            undoFocus,
                                        ).focusProperties { canFocus = true }
                                        .testTag("radio_undo"),
                            ) {
                                RadioText(stringResource(Res.string.action_undo), 12, bold = true)
                            }
                        }
                    } else if (!state.loadError) {
                        Row(
                            Modifier.align(Alignment.End).padding(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(NdIcons.ArrowBack, null, Modifier.size(16.dp), tint = Ink)
                            RadioText(stringResource(Res.string.radio_swipe), 11)
                        }
                    }
                }
            }
        }
    }
    form.mode?.let { mode ->
        RadioInsertDialog(
            mode,
            form,
            state,
            actions,
            bounds,
            windowSize,
            onMode = { form.open(it, state.createdPlaylistId) },
            onDismiss = form::close,
        )
    }
}

@Composable
private fun RadioSlip(
    episode: RadioEpisode,
    selected: Boolean,
    playingEpisodeId: Long?,
    rows: RadioRows,
    actions: RadioActions,
    restoreRowFocus: Long?,
    onFocusRestore: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusRestored by rememberUpdatedState(onFocusRestore)
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(restoreRowFocus, inputMode) {
        if (restoreRowFocus == episode.id) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            focus.requestFocus()
            focusRestored()
        }
    }
    val monogram = rememberMonogram(episode.podcast).initials
    val initials = if ('&' in episode.podcast && monogram.length == 2) monogram.toList().joinToString("/") else monogram
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds().background(Color(0xFF38291B))) {
        val density = LocalDensity.current
        val threshold = with(density) { radioDiscardThreshold(maxWidth.value).dp.toPx() }
        var drag by remember { mutableFloatStateOf(0f) }
        val width = maxWidth
        val windowSize = LocalWindowInfo.current.containerSize
        LaunchedEffect(width, windowSize) { drag = 0f }
        Box(Modifier.matchParentSize().alpha(if (-drag >= threshold) 1f else 0f).clearAndSetSemantics {}) {
            Column(
                Modifier.align(Alignment.CenterEnd).width(80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RadioText(stringResource(Res.string.radio_release), 11, color = Cream)
                RadioText(stringResource(Res.string.radio_discard), 11, color = Cream)
            }
        }
        MaterialPlate(
            RadioMaterial.PAPER,
            Modifier
                .fillMaxWidth()
                .testTag("radio_episode_${episode.id}")
                .focusRequester(focus)
                .focusProperties { canFocus = true }
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Delete) {
                        actions.discard(episode.id)
                        true
                    } else {
                        false
                    }
                }.selectable(selected, onClick = { actions.selectEpisode(episode.id) })
                .pointerInput(episode.id, width, windowSize) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var horizontal = false
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                val delta = change.position - down.position
                                if (!horizontal) {
                                    if (change.isConsumed ||
                                        (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x))
                                    ) {
                                        break
                                    }
                                    horizontal =
                                        abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y)
                                }
                                if (horizontal) {
                                    drag = delta.x.coerceIn(-size.width.toFloat(), 0f)
                                    change.consume()
                                }
                                if (!change.pressed) {
                                    if (horizontal && -drag >= threshold) actions.discard(episode.id)
                                    break
                                }
                            }
                        } finally {
                            drag = 0f
                        }
                    }
                }.offset { IntOffset(drag.roundToInt(), 0) }
                .border(width = 1.dp, color = Color(0x887B603F)),
        ) {
            if (selected) Box(Modifier.matchParentSize().background(Color(0x22E4BF71)))
            Row(
                Modifier.heightIn(min = rows.minimumHeight).padding(horizontal = 9.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(rows.coverSize).border(1.dp, Color(0xFF806447), CircleShape).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    // Printed cover marks are decorative, like an icon; only the episode copy scales.
                    Text(
                        initials,
                        color = Ink,
                        fontFamily = courier(),
                        fontWeight = FontWeight.Bold,
                        fontSize = (9f / density.fontScale).sp,
                        lineHeight = (10.8f / density.fontScale).sp,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        RadioText(episode.podcast, 11, modifier = Modifier.weight(1f), maxLines = 1)
                        episode.durationMs?.let {
                            RadioText(
                                stringResource(Res.string.date_duration_minutes, it / 60000),
                                11,
                            )
                        }
                    }
                    RadioText(episode.title, rows.titleSize, bold = true, maxLines = 2)
                }
                if (selected) {
                    Icon(
                        if (playingEpisodeId == episode.id) NdIcons.PlayArrow else NdIcons.Check,
                        null,
                        Modifier.size(15.dp),
                        tint = Color(0xFFA65332),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SmallRadioKey(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelWidth = (48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceAtMost(64.dp)
    Column(
        modifier
            .width(labelWidth)
            .heightIn(min = 48.dp)
            .focusProperties { canFocus = true }
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(
                    28.dp,
                ).background(
                    Brush.radialGradient(listOf(Color(0xFFF6EBD0), Color(0xFFD4BC90))),
                    CircleShape,
                ).border(1.dp, Brass, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(15.dp), tint = Ink) }
        Text(
            label,
            color = Cream,
            fontFamily = barlow(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun RadioText(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = Ink,
    bold: Boolean = false,
    mono: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        modifier,
        color,
        fontSize = size.sp,
        fontFamily = if (mono) courier() else barlow(),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight =
            (
                size *
                    1.2
            ).sp,
    )
}
