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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.radio.Brass
import ch.lkmc.neutrodyne.core.designsystem.radio.Cream
import ch.lkmc.neutrodyne.core.designsystem.radio.EnamelSelector
import ch.lkmc.neutrodyne.core.designsystem.radio.Faceplate
import ch.lkmc.neutrodyne.core.designsystem.radio.Ink
import ch.lkmc.neutrodyne.core.designsystem.radio.LocalRadioSounds
import ch.lkmc.neutrodyne.core.designsystem.radio.MaterialPlate
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKey
import ch.lkmc.neutrodyne.core.designsystem.radio.PianoKeySlot
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioMaterial
import ch.lkmc.neutrodyne.core.designsystem.radio.RadioSound
import ch.lkmc.neutrodyne.core.designsystem.radio.SlipKeyCap
import ch.lkmc.neutrodyne.core.designsystem.radio.barlow
import ch.lkmc.neutrodyne.core.designsystem.radio.courier
import ch.lkmc.neutrodyne.core.designsystem.radio.recess
import ch.lkmc.neutrodyne.core.designsystem.radio.rememberClickSound
import ch.lkmc.neutrodyne.core.ui.ReportVisibleEpisodeIds
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_mark_played
import ch.lkmc.neutrodyne.core.ui.resources.action_pause
import ch.lkmc.neutrodyne.core.ui.resources.action_play
import ch.lkmc.neutrodyne.core.ui.resources.action_retry
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.date_duration_minutes
import ch.lkmc.neutrodyne.core.ui.resources.feeds_load_error
import ch.lkmc.neutrodyne.core.ui.resources.import_title
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.resources.radio_all_clear
import ch.lkmc.neutrodyne.core.ui.resources.radio_discard
import ch.lkmc.neutrodyne.core.ui.resources.radio_episodes
import ch.lkmc.neutrodyne.core.ui.resources.radio_menu
import ch.lkmc.neutrodyne.core.ui.resources.radio_no_episodes
import ch.lkmc.neutrodyne.core.ui.resources.radio_no_playlists_empty
import ch.lkmc.neutrodyne.core.ui.resources.radio_playlists
import ch.lkmc.neutrodyne.core.ui.resources.radio_release
import ch.lkmc.neutrodyne.core.ui.resources.radio_removed
import ch.lkmc.neutrodyne.core.ui.resources.settings_update_available
import ch.lkmc.neutrodyne.core.ui.root.LocalSettingsBadge
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class RadioRows(
    val minimumHeight: Dp,
    val titleSize: Int,
    val coverSize: Dp,
) {
    COMPACT(64.dp, 13, 40.dp),
    STANDARD(68.dp, 13, 44.dp),
    WIDE(78.dp, 14, 52.dp),
}

private val SlipCoverShape = RoundedCornerShape(3.dp)

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
    ReportVisibleEpisodeIds(
        scroll,
        { state.episodes.size },
        { state.episodes.getOrNull(it)?.id },
        actions.visibleEpisodes,
    )
    val pickerFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(form.restoreFocus, windowFocused, inputMode) {
        if (form.restoreFocus && windowFocused) {
            // Android returns from a modal in touch mode; click targets need keyboard focus restored explicitly.
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            if (pickerFocus.requestFocus()) {
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
    // Arrow keys tune the programme like a station dial: the selection follows and focus travels
    // with it. Only a focused slip starts it, so the list never takes arrows from a field or a
    // sheet. Ctrl/Cmd/Alt chords pass through untouched for the root's own shortcuts (its
    // volume chords are Ctrl/Cmd+Up/Down); a plain Shift+Arrow still tunes, as it did before.
    // The handler bubbles, so a descendant that learns arrows later wins.
    val scope = rememberCoroutineScope()
    var focusedId by remember { mutableStateOf<Long?>(null) }
    val slipFocus = remember(state.playlistId) { mutableMapOf<Long, FocusRequester>() }

    fun focusFor(id: Long): FocusRequester = slipFocus.getOrPut(id) { FocusRequester() }
    Faceplate(
        modifier
            .onGloballyPositioned {
                bounds = it.boundsInWindow()
            }.padding(6.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Balances the Menu key so the sign stays centred over the slips.
                Spacer(Modifier.width(radioKeyWidth()))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // The playlist selector is an enamel sign above the slips that opens the chooser.
                    EnamelSelector(
                        state.playlist?.name ?: stringResource(Res.string.radio_playlists),
                        onClick = form::open,
                        modifier =
                            Modifier
                                .focusRequester(pickerFocus)
                                .focusProperties { canFocus = true }
                                .testTag("radio_playlist"),
                    )
                }
                SmallRadioKey(menuLabel, NdIcons.DragHandle, "radio_menu", actions.menu, modifier = menuNotice)
            }
            Spacer(Modifier.height(6.dp))
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
                            Modifier.weight(1f).fillMaxWidth().padding(if (compact) 4.dp else 12.dp),
                            Arrangement.Center,
                            Alignment.CenterHorizontally,
                        ) {
                            if (state.loading) {
                                CircularProgressIndicator(color = Brass, modifier = Modifier.size(24.dp))
                            } else if (!state.loadError && state.playlistsLoaded) {
                                // Until the groups are read, an empty paper is neither "none" nor "all clear".
                                if (state.untuned) {
                                    // A first listener has no station to tune yet: offer the
                                    // journeys that fill one instead of an empty paper tray.
                                    BoxWithConstraints(contentAlignment = Alignment.Center) {
                                        EmptySign(
                                            room = constraints.maxHeight,
                                            sign = {
                                                RadioText(
                                                    stringResource(Res.string.radio_no_playlists_empty),
                                                    19,
                                                    Modifier.semantics { heading() },
                                                    bold = true,
                                                    maxLines = 2,
                                                )
                                            },
                                            modifier = Modifier.verticalScroll(rememberScrollState()),
                                        ) {
                                            // The journeys wrap rather than scroll: a sideways bank would
                                            // hide Library past the paper's edge on a narrow phone.
                                            PianoKeySlot {
                                                FlowRow(
                                                    horizontalArrangement =
                                                        Arrangement.spacedBy(
                                                            EMPTY_KEY_GAP,
                                                            Alignment.CenterHorizontally,
                                                        ),
                                                    verticalArrangement = Arrangement.spacedBy(EMPTY_KEY_GAP),
                                                ) {
                                                    PianoKey(
                                                        stringResource(Res.string.nav_discover),
                                                        pressed = false,
                                                        onClick = actions.openDiscover,
                                                        modifier = Modifier.testTag("radio_empty_discover"),
                                                        role = Role.Button,
                                                    )
                                                    PianoKey(
                                                        stringResource(Res.string.import_title),
                                                        pressed = false,
                                                        onClick = actions.openImport,
                                                        modifier = Modifier.testTag("radio_empty_import"),
                                                        role = Role.Button,
                                                    )
                                                    PianoKey(
                                                        stringResource(Res.string.nav_library),
                                                        pressed = false,
                                                        onClick = actions.openLibrary,
                                                        modifier = Modifier.testTag("radio_empty_library"),
                                                        role = Role.Button,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    RadioText(stringResource(Res.string.radio_all_clear), 19, bold = true)
                                    RadioText(stringResource(Res.string.radio_no_episodes), 12, mono = false)
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            Modifier
                                .weight(1f)
                                .testTag("radio_episodes")
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) {
                                        return@onKeyEvent false
                                    }
                                    val delta =
                                        when (event.key) {
                                            Key.DirectionDown -> 1
                                            Key.DirectionUp -> -1
                                            else -> 0
                                        }
                                    if (delta == 0) return@onKeyEvent false
                                    // Anchor on the focused episode's identity, not its index: the
                                    // list shifts under a focused slip when rows are removed.
                                    val current =
                                        focusedId
                                            ?.let { id -> state.episodes.indexOfFirst { it.id == id } }
                                            ?.takeIf { it >= 0 }
                                            ?: state.episodes
                                                .indexOfFirst { it.id == state.selectedId }
                                                .takeIf { it >= 0 }
                                            // Neither the focused nor the selected episode is in
                                            // the list: ignore the press, don't jump to an end.
                                            ?: return@onKeyEvent false
                                    val target = current + delta
                                    if (target !in state.episodes.indices) return@onKeyEvent false
                                    actions.selectEpisode(state.episodes[target].id)
                                    focusedId = state.episodes[target].id
                                    scope.launch {
                                        scroll.animateScrollToItem(target)
                                        withFrameNanos { }
                                        if (scroll.layoutInfo.visibleItemsInfo.any { it.index == target }) {
                                            focusFor(state.episodes[target].id).requestFocus()
                                        }
                                    }
                                    true
                                },
                            state = scroll,
                        ) {
                            itemsIndexed(state.episodes, key = { _, episode -> episode.id }) { index, episode ->
                                LaunchedEffect(index) { actions.loadEpisode(index) }
                                val selected = state.selectedId == episode.id
                                RadioSlip(
                                    episode,
                                    selected,
                                    selected && state.switchOn,
                                    rows,
                                    actions,
                                    restoreRowFocus,
                                    focusFor(episode.id),
                                    { focusedId = episode.id },
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
                    }
                }
            }
        }
    }
    if (form.chooserOpen) RadioInsertDialog(state, actions, bounds, windowSize, onDismiss = form::close)
}

@Composable
private fun RadioSlip(
    episode: RadioEpisode,
    selected: Boolean,
    playing: Boolean,
    rows: RadioRows,
    actions: RadioActions,
    restoreRowFocus: Long?,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onFocusRestore: () -> Unit,
) {
    val focusRestored by rememberUpdatedState(onFocusRestore)
    // The slip slides off the radio with a paper sound, by swipe or by the Delete key. Read through
    // a state: the swipe detector outlives recompositions.
    val sounds by rememberUpdatedState(LocalRadioSounds.current)
    val currentDiscard by rememberUpdatedState(actions.discard)
    val discard = {
        sounds.play(RadioSound.SLIP_DISCARD)
        currentDiscard(episode.id)
    }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(restoreRowFocus, inputMode) {
        if (restoreRowFocus == episode.id) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            focusRequester.requestFocus()
            focusRestored()
        }
    }
    val monogram = rememberMonogram(episode.podcast)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val markPlayed = stringResource(Res.string.action_mark_played)
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
                .onFocusChanged { if (it.isFocused) onFocus() }
                .focusRequester(focusRequester)
                .focusProperties { canFocus = true }
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Delete) {
                        discard()
                        true
                    } else {
                        false
                    }
                }.semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(markPlayed) {
                                discard()
                                true
                            },
                        )
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
                                    val armed = -drag >= threshold
                                    drag = delta.x.coerceIn(-size.width.toFloat(), 0f)
                                    // A tick as the slip crosses into "release to discard".
                                    if (!armed && -drag >= threshold) {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    }
                                    change.consume()
                                }
                                if (!change.pressed) {
                                    if (horizontal && -drag >= threshold) discard()
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
                // The cover is decorative; the podcast name beside it carries the meaning.
                CoverArt(
                    ref = episode.podcastArtwork,
                    monogram = monogram,
                    modifier =
                        Modifier
                            .size(rows.coverSize)
                            .border(1.dp, Color(0xFF806447), SlipCoverShape)
                            .clearAndSetSemantics {},
                    shape = SlipCoverShape,
                    avgArgb = episode.podcastArtworkAvgArgb,
                )
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
                SlipPlayKey(
                    playing = playing,
                    onClick = { actions.playEpisode(episode.id) },
                    modifier = Modifier.testTag("radio_slip_play_${episode.id}"),
                )
            }
        }
    }
}

/** A slip's own play key (the shared ivory slip key); on the slip that is playing it pauses. */
@Composable
private fun SlipPlayKey(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (playing) Res.string.action_pause else Res.string.action_play)
    Box(
        modifier
            .size(SLIP_KEY_TARGET)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = rememberClickSound(RadioSound.KEY_PRESS, onClick),
            ),
        contentAlignment = Alignment.Center,
    ) {
        SlipKeyCap(if (playing) NdIcons.Pause else NdIcons.PlayArrow, null, Modifier.size(SLIP_KEY_CAP))
    }
}

/**
 * The empty radio's [sign] over its [keys], or the keys alone when both would overflow the
 * paper's [room] in px (a short phone, large text): the dial's hint still carries the words.
 * Measuring both, rather than guessing a height, keeps wrapped keys in view. Keys taller than
 * the room scroll with the [modifier]'s scroll rather than clip.
 */
@Composable
private fun EmptySign(
    room: Int,
    sign: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    keys: @Composable () -> Unit,
) {
    Layout(listOf(sign, keys), modifier) { (signs, banks), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bank = banks.single().measure(loose)
        val words = signs.single().measure(loose)
        val gap = EMPTY_SIGN_GAP.roundToPx()
        val signFits = words.height + gap + bank.height <= room
        val width = if (signFits) maxOf(words.width, bank.width) else bank.width
        val height = if (signFits) words.height + gap + bank.height else bank.height
        layout(width, height) {
            if (signFits) words.placeRelative((width - words.width) / 2, 0)
            bank.placeRelative((width - bank.width) / 2, height - bank.height)
        }
    }
}

/** The gap between the empty radio's sign and its keys. */
private val EMPTY_SIGN_GAP = 10.dp

/** The gap between the empty radio's journey keys, matching a key bank's. */
private val EMPTY_KEY_GAP = 6.dp
private val SLIP_KEY_TARGET = 48.dp
private val SLIP_KEY_CAP = 36.dp

@Composable
internal fun SmallRadioKey(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(radioKeyWidth())
            .heightIn(min = 48.dp)
            .focusProperties { canFocus = true }
            .clickable(role = Role.Button, onClick = onClick)
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
private fun radioKeyWidth(): Dp = (48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceAtMost(64.dp)

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
