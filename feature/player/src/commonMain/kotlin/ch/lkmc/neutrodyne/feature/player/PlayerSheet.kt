// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverAspect
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialog
import ch.lkmc.neutrodyne.core.designsystem.components.NdDialogAction
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.ArtworkSurface
import ch.lkmc.neutrodyne.core.designsystem.theme.ArtworkTheme
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.model.HingeOrientation
import ch.lkmc.neutrodyne.core.model.HingeState
import ch.lkmc.neutrodyne.core.model.PlayerPresentation
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WindowPosture
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.SleepTimerKey
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.AttributionText
import ch.lkmc.neutrodyne.core.ui.ChapterArtwork
import ch.lkmc.neutrodyne.core.ui.ChapterList
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.resources.summary_video
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.core.ui.showNotes
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_back
import ch.lkmc.neutrodyne.feature.player.resources.player_back_value
import ch.lkmc.neutrodyne.feature.player.resources.player_chapter
import ch.lkmc.neutrodyne.feature.player.resources.player_chapter_image
import ch.lkmc.neutrodyne.feature.player.resources.player_chapters
import ch.lkmc.neutrodyne.feature.player.resources.player_close_panel
import ch.lkmc.neutrodyne.feature.player.resources.player_collapse
import ch.lkmc.neutrodyne.feature.player.resources.player_continue
import ch.lkmc.neutrodyne.feature.player.resources.player_description
import ch.lkmc.neutrodyne.feature.player.resources.player_dismiss
import ch.lkmc.neutrodyne.feature.player.resources.player_episode
import ch.lkmc.neutrodyne.feature.player.resources.player_error
import ch.lkmc.neutrodyne.feature.player.resources.player_expand
import ch.lkmc.neutrodyne.feature.player.resources.player_file_missing
import ch.lkmc.neutrodyne.feature.player.resources.player_forward
import ch.lkmc.neutrodyne.feature.player.resources.player_forward_value
import ch.lkmc.neutrodyne.feature.player.resources.player_mark_skip
import ch.lkmc.neutrodyne.feature.player.resources.player_metered_blocked
import ch.lkmc.neutrodyne.feature.player.resources.player_minutes
import ch.lkmc.neutrodyne.feature.player.resources.player_mobile_consent
import ch.lkmc.neutrodyne.feature.player.resources.player_more
import ch.lkmc.neutrodyne.feature.player.resources.player_network_lost
import ch.lkmc.neutrodyne.feature.player.resources.player_next
import ch.lkmc.neutrodyne.feature.player.resources.player_next_chapter
import ch.lkmc.neutrodyne.feature.player.resources.player_next_symbol
import ch.lkmc.neutrodyne.feature.player.resources.player_no_notes
import ch.lkmc.neutrodyne.feature.player.resources.player_not_now
import ch.lkmc.neutrodyne.feature.player.resources.player_notes
import ch.lkmc.neutrodyne.feature.player.resources.player_now
import ch.lkmc.neutrodyne.feature.player.resources.player_pause
import ch.lkmc.neutrodyne.feature.player.resources.player_paused
import ch.lkmc.neutrodyne.feature.player.resources.player_play
import ch.lkmc.neutrodyne.feature.player.resources.player_playing
import ch.lkmc.neutrodyne.feature.player.resources.player_podcast
import ch.lkmc.neutrodyne.feature.player.resources.player_position
import ch.lkmc.neutrodyne.feature.player.resources.player_previous
import ch.lkmc.neutrodyne.feature.player.resources.player_previous_chapter
import ch.lkmc.neutrodyne.feature.player.resources.player_previous_symbol
import ch.lkmc.neutrodyne.feature.player.resources.player_rate_limited
import ch.lkmc.neutrodyne.feature.player.resources.player_reopen_panel
import ch.lkmc.neutrodyne.feature.player.resources.player_seconds
import ch.lkmc.neutrodyne.feature.player.resources.player_settings
import ch.lkmc.neutrodyne.feature.player.resources.player_speed
import ch.lkmc.neutrodyne.feature.player.resources.player_stop
import ch.lkmc.neutrodyne.feature.player.resources.player_time_description
import ch.lkmc.neutrodyne.feature.player.resources.player_unknown_duration
import ch.lkmc.neutrodyne.feature.player.resources.player_up_next
import ch.lkmc.neutrodyne.feature.player.resources.player_write_failed
import ch.lkmc.neutrodyne.feature.player.resources.player_youtube_unavailable
import ch.lkmc.neutrodyne.feature.player.resources.sleep_end
import ch.lkmc.neutrodyne.feature.player.resources.sleep_minutes
import ch.lkmc.neutrodyne.feature.player.resources.sleep_title
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/** Window/activity instance, outside NavDisplay. The queue route supplies [upNext] without a feature dependency. */
@Composable
public fun PlayerRoute(
    slot: PlayerSlotState,
    modifier: Modifier = Modifier,
    vm: PlayerViewModel = metroViewModel(),
    episodeVm: PlayerEpisodeViewModel = metroViewModel(),
    upNext: @Composable () -> Unit = {},
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val urls = LocalPlatformActions.current.urls
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copyLabel = stringResource(UiRes.string.action_copy_link)
    val noHandler = stringResource(UiRes.string.no_handler)
    val routeScope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current
    Box(modifier) {
        PlayerSheet(
            ui = ui.copy(consent = null),
            position = position,
            slot = slot,
            positionAt = vm::positionAt,
            toggle = vm::togglePlayPause,
            back = vm::skipBack,
            forward = vm::skipForward,
            previous = vm::previous,
            next = vm::next,
            seek = { value -> ui.now?.let { vm.seekTo(value, it.episodeId) } },
            dismiss = vm::dismissPaused,
            stop = vm::stopAndClose,
            markPlayed = { ui.now?.let { episodeVm.markPlayed(it.episodeId) } },
            consent = vm::continueOnMobileData,
            declineConsent = vm::declineMobileData,
            upNext = upNext,
            nextEpisode = vm::nextEpisode,
            notes = notes,
            onNotesVisible = vm::showNotes,
            onLink = { url ->
                if (urls.open(url) == OpenResult.NO_HANDLER) {
                    routeScope.launch {
                        if (snackbar.showSnackbar(noHandler, copyLabel) ==
                            androidx.compose.material3.SnackbarResult.ActionPerformed
                        ) {
                            clipboard.setText(
                                androidx.compose.ui.text
                                    .AnnotatedString(url),
                            )
                        }
                    }
                }
            },
        )
    }
}

/** Command results outlive the current item; this never collects player UI, artwork, notes or position. */
@Composable
public fun PlayerFeedbackRoute(
    navigate: (NavKey) -> Unit,
    vm: PlayerViewModel = metroViewModel(),
    episodeVm: PlayerEpisodeViewModel = metroViewModel(),
) {
    val messages by vm.feedbackMessages.collectAsStateWithLifecycle()
    val pendingConsent by vm.pendingConsent.collectAsStateWithLifecycle()
    val urls = LocalPlatformActions.current.urls
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copyLabel = stringResource(UiRes.string.action_copy_link)
    val noHandler = stringResource(UiRes.string.no_handler)
    val routeScope = rememberCoroutineScope()
    val failed by episodeVm.writeFailed.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val failureText = stringResource(Res.string.player_write_failed)
    LaunchedEffect(failed) {
        if (failed) {
            snackbar.showSnackbar(failureText)
            episodeVm.acknowledgeFailure()
        }
    }
    ShowUserMessages(messages, vm::onMessageShown) { id ->
        vm.externalOpen.onAction(id)
        when (vm.onMessageAction(id)) {
            PlayerMessageAction.RETRY -> {
                vm.togglePlayPause()
            }

            PlayerMessageAction.PLAYBACK_SETTINGS -> {
                navigate(SettingsKey(SettingsPage.PLAYBACK))
            }

            PlayerMessageAction.WATCH_ON_YOUTUBE -> {
                // Capture before acknowledgement clears the snackbar's action mapping.
                val episodeId = vm.watchEpisode(id)
                if (episodeId != null) {
                    routeScope.launch {
                        vm.watchLink(episodeId)?.let { url ->
                            if (urls.open(url) == OpenResult.OPENED) {
                                vm.externalOpen.opened(episodeId)
                            } else if (snackbar.showSnackbar(noHandler, copyLabel) ==
                                androidx.compose.material3.SnackbarResult.ActionPerformed
                            ) {
                                clipboard.setText(
                                    androidx.compose.ui.text
                                        .AnnotatedString(url),
                                )
                            }
                        }
                    }
                }
            }

            null -> {
                Unit
            }
        }
    }

    if (pendingConsent != null) {
        NdDialog(
            onDismissRequest = vm::declineMobileData,
            title = stringResource(Res.string.player_mobile_consent),
            confirm = NdDialogAction(stringResource(Res.string.player_continue), onClick = vm::continueOnMobileData),
            dismiss = NdDialogAction(stringResource(Res.string.player_not_now), onClick = vm::declineMobileData),
        )
    }
}

/** One surface and artwork morph continuously between the mini and full layouts. */
@Composable
public fun PlayerSheet(
    ui: PlayerUiState,
    position: PositionSnapshot?,
    slot: PlayerSlotState,
    positionAt: (PositionSnapshot) -> Long,
    toggle: () -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    seek: (Long) -> Unit,
    dismiss: () -> Unit,
    stop: () -> Unit,
    markPlayed: () -> Unit,
    consent: () -> Unit,
    declineConsent: () -> Unit,
    modifier: Modifier = Modifier,
    state: PlayerSheetState = rememberPlayerSheetState(),
    upNext: @Composable () -> Unit = {},
    nextEpisode: () -> Unit = next,
    notes: PlayerNotesState = PlayerNotesState(),
    onNotesVisible: (Boolean) -> Unit = {},
    onLink: (String) -> Unit = {},
) {
    ArtworkTheme(
        ui.colors?.seedArgb,
        if (slot.presentation == PlayerPresentation.SIDE_PANEL || state.progress > MINI_SEMANTICS_END) {
            ArtworkSurface.FULL
        } else {
            ArtworkSurface.MINI
        },
    ) {
        PlayerSheetContent(
            ui,
            position,
            slot,
            positionAt,
            toggle,
            back,
            forward,
            previous,
            next,
            seek,
            dismiss,
            stop,
            markPlayed,
            consent,
            declineConsent,
            modifier,
            state,
            upNext,
            nextEpisode,
            notes,
            onNotesVisible,
            onLink,
        )
    }
}

@Composable
private fun PlayerSheetContent(
    ui: PlayerUiState,
    position: PositionSnapshot?,
    slot: PlayerSlotState,
    positionAt: (PositionSnapshot) -> Long,
    toggle: () -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    seek: (Long) -> Unit,
    dismiss: () -> Unit,
    stop: () -> Unit,
    markPlayed: () -> Unit,
    consent: () -> Unit,
    declineConsent: () -> Unit,
    modifier: Modifier = Modifier,
    state: PlayerSheetState = rememberPlayerSheetState(),
    upNext: @Composable () -> Unit = {},
    nextEpisode: () -> Unit = next,
    notes: PlayerNotesState = PlayerNotesState(),
    onNotesVisible: (Boolean) -> Unit = {},
    onLink: (String) -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(PlayerTab.UP_NEXT.name) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    val activeTab = PlayerTab.entries.firstOrNull { it.name == selectedTab } ?: PlayerTab.UP_NEXT
    val now = ui.now
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val moveControls = remember { movableContentOf<@Composable () -> Unit> { content -> content() } }
    val fullScrollState = rememberScrollState()
    val controlsState = rememberSaveableStateHolder()
    val miniFocus = remember { FocusRequester() }
    var wasExpanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val sidePanel = slot.presentation == PlayerPresentation.SIDE_PANEL
    val expanded = (sidePanel || (!slot.reopenPanelFromMini && state.settledExpanded)) && now != null
    LaunchedEffect(state.drag.settledValue, sidePanel, now != null) {
        if (wasExpanded && !sidePanel && state.drag.settledValue == PlayerSheetValue.Collapsed && now != null) {
            withFrameMillis { }
            miniFocus.requestFocus()
        }
        wasExpanded = !sidePanel && state.drag.settledValue == PlayerSheetValue.Expanded
    }
    LaunchedEffect(ui.chapters.isEmpty()) {
        if (ui.chapters.isEmpty() && activeTab == PlayerTab.CHAPTERS) selectedTab = PlayerTab.UP_NEXT.name
    }
    val notesShown = expanded && panelOpen && activeTab == PlayerTab.NOTES
    val reportNotes by rememberUpdatedState(onNotesVisible)
    DisposableEffect(notesShown) {
        reportNotes(notesShown)
        onDispose { reportNotes(false) }
    }
    val settled by rememberUpdatedState(slot.onExpandedSettled)
    val dismissPaused by rememberUpdatedState(dismiss)
    SideEffect { settled(expanded && !sidePanel) }
    var previousPresentation by rememberSaveable { mutableStateOf(slot.presentation.name) }
    LaunchedEffect(slot.presentation) {
        if (sidePanel || previousPresentation != slot.presentation.name) state.collapse(true)
        previousPresentation = slot.presentation.name
    }
    DisposableEffect(Unit) { onDispose { settled(false) } }
    var consumedExpand by rememberSaveable { mutableIntStateOf(slot.initialConsumedExpandRequest) }
    LaunchedEffect(slot.expandRequest, now?.episodeId) {
        if (slot.expandRequest > consumedExpand && now != null) {
            consumedExpand = slot.expandRequest
            slot.onExpandRequestConsumed(consumedExpand)
            if (!sidePanel) {
                if (slot.reopenPanelFromMini) slot.onReopenPanel() else state.expand(reducedMotion)
            }
        }
    }
    var consumedCollapse by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(slot.collapseRequest) {
        if (slot.collapseRequest > consumedCollapse) {
            consumedCollapse = slot.collapseRequest
            state.collapse(reducedMotion)
        }
    }
    LaunchedEffect(now == null) {
        if (now == null) state.collapse(true)
    }
    LaunchedEffect(state.drag.settledValue) {
        if (state.drag.settledValue == PlayerSheetValue.Dismissed) {
            dismissPaused()
            state.resetDismissed()
        }
    }
    Box(modifier.fillMaxSize()) {
        if (!sidePanel && !slot.reopenPanelFromMini) PlayerBackHandler(state, panelOpen) { panelOpen = false }
        // Consent may arrive before nowPlaying exists, so it must outlive the visible sheet.
        if (ui.consent != null) {
            NdDialog(
                onDismissRequest = declineConsent,
                title = stringResource(Res.string.player_mobile_consent),
                confirm = NdDialogAction(stringResource(Res.string.player_continue), onClick = consent),
                dismiss = NdDialogAction(stringResource(Res.string.player_not_now), onClick = declineConsent),
            )
        }
        if (now == null) return@Box

        val expandLabel =
            stringResource(
                if (slot.reopenPanelFromMini) Res.string.player_reopen_panel else Res.string.player_expand,
            )
        val collapseLabel = stringResource(Res.string.player_collapse)
        val dismissLabel = stringResource(Res.string.player_dismiss)
        val playLabel =
            stringResource(
                if (now.isPlaying ||
                    now.playWhenReady
                ) {
                    Res.string.player_pause
                } else {
                    Res.string.player_play
                },
            )
        val forwardLabel = stringResource(Res.string.player_forward, ui.skipForwardMs / MILLIS_PER_SECOND)
        val description =
            stringResource(
                Res.string.player_description,
                now.title,
                now.podcastTitle,
                stringResource(if (now.isPlaying) Res.string.player_playing else Res.string.player_paused),
            )
        val paused = !now.isPlaying && !now.playWhenReady
        val expand: () -> Unit = {
            if (slot.reopenPanelFromMini) slot.onReopenPanel() else scope.launch { state.expand(reducedMotion) }
        }
        val expandArtwork by rememberUpdatedState(expand)
        val collapse: () -> Unit = { scope.launch { state.collapse(reducedMotion) } }
        val navigate: (NavKey) -> Unit = { key ->
            if (sidePanel || key == SpeedKey || key == SleepTimerKey) {
                slot.navigate(key)
            } else {
                scope.launch {
                    state.collapse(reducedMotion)
                    slot.navigate(key)
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .testTag("playerHost"),
        ) {
            val upNextMaxHeight = maxHeight * UP_NEXT_HEIGHT_FRACTION
            val heightPx = with(density) { maxHeight.toPx() }
            val contentBottom = slot.contentBottomPx.takeIf { it > 0f } ?: heightPx
            val collapsedPx = (contentBottom - with(density) { MINI_ANCHOR_SPACE.toPx() }).coerceAtLeast(0f)
            val dismissDistance = with(density) { DISMISS_DISTANCE.toPx() }
            LaunchedEffect(collapsedPx, paused, dismissDistance, sidePanel, slot.reopenPanelFromMini) {
                if (!sidePanel) state.setAnchors(collapsedPx, dismissDistance, paused)
            }
            // Hidden wide panels use the mini affordance; an upward drag must not create a sheet.
            LaunchedEffect(state.drag.settledValue, slot.reopenPanelFromMini, sidePanel) {
                if (!sidePanel && slot.reopenPanelFromMini && state.drag.settledValue == PlayerSheetValue.Expanded) {
                    slot.onReopenPanel()
                    state.collapse(true)
                }
            }
            val dragModifier = playerSheetDrag(state)
            val sharedControls: @Composable () -> Unit = {
                controlsState.SaveableStateProvider("player-controls") {
                    PlayerControls(
                        ui,
                        position,
                        positionAt,
                        toggle,
                        back,
                        forward,
                        previous,
                        next,
                        seek,
                        navigate,
                        consent,
                        declineConsent,
                        upNext,
                        notes,
                        onLink,
                        activeTab,
                        panelOpen,
                        {
                            selectedTab = it.name
                            panelOpen = true
                        },
                        upNextMaxHeight,
                        compactLandscape = maxHeight < 480.dp && maxWidth > maxHeight,
                    )
                }
            }
            val adaptive =
                sidePanel || (
                    state.drag.settledValue == PlayerSheetValue.Expanded && (
                        (maxWidth > maxHeight) ||
                            slot.posture.hinges.any {
                                it.orientation == HingeOrientation.HORIZONTAL && it.state == HingeState.HALF_OPENED
                            }
                    )
                )
            if (adaptive) {
                AdaptivePlayerBody(
                    ui = ui,
                    posture = slot.posture,
                    panel = sidePanel,
                    sheetOffsetPx = if (sidePanel) 0f else collapsedPx * (1f - state.progress),
                    sheetState = if (sidePanel) null else state,
                    close = if (sidePanel) slot.onClosePanel else collapse,
                    menu = { PlayerMenu(now, navigate, markPlayed, stop, ui.chapters.isNotEmpty(), nextEpisode) },
                    artwork = { artModifier ->
                        Box(artModifier) {
                            CoverArt(
                                ref = now.artwork,
                                avgArgb = ui.colors?.avgArgb,
                                monogram = rememberMonogram(now.podcastTitle),
                                title = now.podcastTitle,
                                tier = CoverTier.HERO,
                                aspect =
                                    if (now.sourceType != SourceType.RSS) CoverAspect.WIDE_16_9 else CoverAspect.SQUARE,
                                modifier = Modifier.fillMaxSize().testTag("playerArtwork"),
                            )
                            ui.currentChapter?.chapter?.imageUrl?.let { url ->
                                ChapterArtwork(
                                    url,
                                    stringResource(
                                        Res.string.player_chapter_image,
                                        ui.currentChapter.chapter.title
                                            .orEmpty(),
                                    ),
                                    Modifier.fillMaxSize(),
                                )
                            }
                        }
                    },
                    scrollState = fullScrollState,
                    controls = { moveControls(sharedControls) },
                )
                return@BoxWithConstraints
            }
            val progress = if (slot.reopenPanelFromMini) 0f else state.progress
            val statusBar =
                with(density) {
                    WindowInsets.statusBars
                        .getTop(this)
                        .toDp()
                        .value
                }
            val wideArt = now.sourceType != SourceType.RSS
            val morph =
                PlayerMorph.at(
                    progress,
                    PlayerGeometry(maxWidth.value, maxHeight.value, statusBar, if (wideArt) WIDE_ART_ASPECT else 1f),
                )
            val artworkHeight = morph.artworkHeight
            val topPx =
                collapsedPx * (1f - progress) +
                    (
                        state.drag.offset
                            .takeIf { it.isFinite() && it > collapsedPx }
                            ?.minus(collapsedPx) ?: 0f
                    )
            val sheetHeight = MINI_HEIGHT + (maxHeight - MINI_HEIGHT) * progress
            Surface(
                modifier =
                    Modifier
                        .offset { IntOffset(0, topPx.roundToInt()) }
                        .padding(
                            start = (morph.inset + slot.contentStartDp * (1f - progress)).dp,
                            end = morph.inset.dp,
                        ).fillMaxWidth()
                        .height(sheetHeight)
                        .testTag("playerSheet"),
                shape = RoundedCornerShape(morph.corner.dp),
                color =
                    lerp(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surface,
                        if (reducedMotion) progress.roundToInt().toFloat() else morph.backgroundMix,
                    ),
            ) {
                // Pointer ownership follows the settled anchor, so fading controls cannot cancel a drag.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to
                                    lerp(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        MaterialTheme.colorScheme.primaryContainer,
                                        morph.backgroundMix,
                                    ),
                                0.35f to
                                    lerp(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        MaterialTheme.colorScheme.surface,
                                        morph.backgroundMix,
                                    ),
                            ),
                        ).then(
                            if (state.drag.settledValue != PlayerSheetValue.Expanded) dragModifier else Modifier,
                        ),
                ) {
                    if (progress < MINI_SEMANTICS_END) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(MINI_HEIGHT)
                                .graphicsLayer { alpha = morph.miniAlpha }
                                .focusRequester(miniFocus)
                                .clickable(onClickLabel = expandLabel, onClick = expand)
                                .clearAndSetSemantics {
                                    this[SemanticsProperties.TestTag] = "miniPlayer"
                                    contentDescription = description
                                    onClick(expandLabel) {
                                        expand()
                                        true
                                    }
                                    customActions =
                                        buildList {
                                            add(
                                                CustomAccessibilityAction(playLabel) {
                                                    toggle()
                                                    true
                                                },
                                            )
                                            add(
                                                CustomAccessibilityAction(forwardLabel) {
                                                    forward()
                                                    true
                                                },
                                            )
                                            add(
                                                CustomAccessibilityAction(expandLabel) {
                                                    expand()
                                                    true
                                                },
                                            )
                                            if (paused) {
                                                add(
                                                    CustomAccessibilityAction(dismissLabel) {
                                                        dismiss()
                                                        true
                                                    },
                                                )
                                            }
                                        }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(MINI_TEXT_START))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    now.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    now.podcastTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            PlayButton(now, toggle, playLabel)
                            if (ui.miniSkip) {
                                TextButton(
                                    onClick = forward,
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription =
                                                forwardLabel
                                        },
                                ) {
                                    Text(
                                        stringResource(
                                            Res.string.player_forward_value,
                                            ui.skipForwardMs / MILLIS_PER_SECOND,
                                        ),
                                    )
                                }
                            }
                        }
                        val snapshot = position?.takeIf { it.episodeId == now.episodeId } ?: now.position
                        val duration = snapshot.durationMs
                        if (duration != null && duration > 0L) {
                            LinearProgressIndicator(
                                progress = { (snapshot.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                                modifier =
                                    Modifier
                                        .align(
                                            Alignment.TopStart,
                                        ).padding(top = 62.dp)
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .clearAndSetSemantics {},
                            )
                        }
                    }
                    if (progress > FULL_SEMANTICS_START) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = morph.controlsAlpha
                                    translationY =
                                        morph.controlsOffset.dp.toPx()
                                }.padding(top = statusBar.dp)
                                .windowInsetsPadding(WindowInsets.ime)
                                .verticalScroll(fullScrollState)
                                .testTag("fullPlayer"),
                        ) {
                            Spacer(Modifier.height(PLAYER_HEADER_HEIGHT + artworkHeight.dp + 16.dp))
                            Column(
                                Modifier
                                    .widthIn(max = 720.dp)
                                    .fillMaxWidth()
                                    .align(Alignment.CenterHorizontally)
                                    .padding(horizontal = 24.dp),
                            ) {
                                moveControls(sharedControls)
                                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
                            }
                        }
                    }
                    // Keep the header's gesture node alive until collapse settles, even after its contents fade.
                    Row(
                        Modifier
                            .padding(top = statusBar.dp)
                            .fillMaxWidth()
                            .height(PLAYER_HEADER_HEIGHT)
                            .graphicsLayer { alpha = morph.controlsAlpha }
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = HEADER_SCRIM_ALPHA))
                            .then(
                                if (state.drag.settledValue == PlayerSheetValue.Expanded) {
                                    dragModifier.clickable(onClick = collapse)
                                } else {
                                    Modifier
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (progress > FULL_SEMANTICS_START) {
                            NdIconButton(collapse, NdIcons.Close, collapseLabel)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(Res.string.player_now))
                                now.context
                                    ?.takeIf { it.type != ch.lkmc.neutrodyne.core.model.ContextType.EXTERNAL }
                                    ?.let { Text(it.title, style = MaterialTheme.typography.bodySmall) }
                            }
                            PlayerMenu(now, navigate, markPlayed, stop, ui.chapters.isNotEmpty(), nextEpisode)
                        }
                    }
                    CoverArt(
                        ref = now.artwork,
                        avgArgb = ui.colors?.avgArgb,
                        monogram = rememberMonogram(now.podcastTitle),
                        title = now.podcastTitle,
                        tier = if (progress >= MINI_SEMANTICS_END) CoverTier.HERO else CoverTier.THUMB,
                        aspect = if (wideArt) CoverAspect.WIDE_16_9 else CoverAspect.SQUARE,
                        shape = RoundedCornerShape(morph.artworkCorner.dp),
                        modifier =
                            Modifier
                                .offset(
                                    morph.artworkX.dp,
                                    morph.artworkY.dp,
                                ).size(morph.artworkSize.dp, artworkHeight.dp)
                                .shadow(
                                    (ART_SHADOW_DP * progress).dp,
                                    RoundedCornerShape(morph.artworkCorner.dp),
                                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = ART_SHADOW_ALPHA),
                                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = ART_SHADOW_ALPHA),
                                ).then(dragModifier)
                                .pointerInput(state) {
                                    // Observe the tap without consuming drag ownership or adding an art focus stop.
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        if (waitForUpOrCancellation() != null &&
                                            state.drag.settledValue == PlayerSheetValue.Collapsed
                                        ) {
                                            expandArtwork()
                                        }
                                    }
                                }.testTag("playerArtwork"),
                    )
                    if (expanded) {
                        ui.currentChapter?.chapter?.imageUrl?.let { url ->
                            ChapterArtwork(
                                url,
                                stringResource(
                                    Res.string.player_chapter_image,
                                    ui.currentChapter.chapter.title
                                        .orEmpty(),
                                ),
                                Modifier
                                    .offset(
                                        morph.artworkX.dp,
                                        morph.artworkY.dp,
                                    ).size(morph.artworkSize.dp, artworkHeight.dp)
                                    .clip(RoundedCornerShape(morph.artworkCorner.dp))
                                    .then(dragModifier),
                            )
                        }
                    }
                    if (now.issue != null || now.phase == PlayerPhase.ERROR) {
                        Box(
                            Modifier
                                .offset(
                                    (morph.artworkX + 4).dp,
                                    (morph.artworkY + 4).dp,
                                ).size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.error),
                        )
                    }
                }
            }
        }
    }
}

/** Keep pointer ownership on the art/header; scrolling controls never own sheet dragging. */
@Composable
private fun playerSheetDrag(state: PlayerSheetState?): Modifier {
    if (state == null) return Modifier
    return Modifier.anchoredDraggable(
        state.drag,
        Orientation.Vertical,
        flingBehavior =
            AnchoredDraggableDefaults.flingBehavior(
                state = state.drag,
                positionalThreshold = { it * SETTLE_FRACTION },
                animationSpec = NeutrodyneMotion.spatial(LocalReducedMotion.current),
            ),
    )
}

/** Shared geometry is measured inside safe drawing/IME space; hinge origin stays in window dp. */
@Composable
private fun AdaptivePlayerBody(
    ui: PlayerUiState,
    posture: WindowPosture,
    panel: Boolean,
    sheetOffsetPx: Float,
    close: () -> Unit,
    menu: @Composable () -> Unit,
    artwork: @Composable (Modifier) -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
    sheetState: PlayerSheetState?,
    controls: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val dragModifier = playerSheetDrag(sheetState)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Surface(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, sheetOffsetPx.roundToInt()) }
            .testTag(if (panel) "playerSidePanel" else "fullPlayer"),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface),
                    ),
                ).windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .onGloballyPositioned { origin = it.positionInWindow() },
        ) {
            val geometry =
                adaptivePlayerGeometry(
                    maxWidth.value,
                    maxHeight.value,
                    origin.x / density.density,
                    origin.y / density.density,
                    posture,
                )

            fun region(bounds: ch.lkmc.neutrodyne.core.model.WindowRectDp): Modifier =
                Modifier
                    .offset(bounds.left.dp, bounds.top.dp)
                    .size((bounds.right - bounds.left).dp, (bounds.bottom - bounds.top).dp)
            Box(
                region(geometry.art).padding(16.dp).then(dragModifier).testTag("playerArtRegion"),
                contentAlignment = Alignment.Center,
            ) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val wide = ui.now?.sourceType != SourceType.RSS
                    val aspect = if (wide) WIDE_ART_ASPECT else 1f
                    val width = minOf(maxWidth, maxHeight * aspect, if (panel) 320.dp else 480.dp)
                    artwork(Modifier.size(width, width / aspect))
                }
            }
            Column(
                region(geometry.controls)
                    .testTag("playerControlsRegion")
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().then(dragModifier), verticalAlignment = Alignment.CenterVertically) {
                        NdIconButton(
                            close,
                            NdIcons.Close,
                            stringResource(if (panel) Res.string.player_close_panel else Res.string.player_collapse),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.player_now))
                            ui.now
                                ?.context
                                ?.takeIf { it.type != ch.lkmc.neutrodyne.core.model.ContextType.EXTERNAL }
                                ?.let {
                                    Text(it.title, style = MaterialTheme.typography.bodySmall)
                                }
                        }
                        menu()
                    }
                    controls()
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    ui: PlayerUiState,
    position: PositionSnapshot?,
    positionAt: (PositionSnapshot) -> Long,
    toggle: () -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    seek: (Long) -> Unit,
    navigate: (NavKey) -> Unit,
    consent: () -> Unit,
    declineConsent: () -> Unit,
    upNext: @Composable () -> Unit,
    notes: PlayerNotesState,
    onLink: (String) -> Unit,
    activeTab: PlayerTab,
    panelOpen: Boolean,
    openTab: (PlayerTab) -> Unit,
    upNextMaxHeight: androidx.compose.ui.unit.Dp,
    compactLandscape: Boolean,
) {
    val now = ui.now ?: return
    val playLabel =
        stringResource(
            if (now.isPlaying || now.playWhenReady) Res.string.player_pause else Res.string.player_play,
        )
    val backLabel = stringResource(Res.string.player_back, ui.skipBackMs / MILLIS_PER_SECOND)
    val forwardLabel = stringResource(Res.string.player_forward, ui.skipForwardMs / MILLIS_PER_SECOND)
    val previousLabel =
        stringResource(
            if (ui.chapters.isEmpty()) Res.string.player_previous else Res.string.player_previous_chapter,
        )
    val nextLabel =
        stringResource(
            if (ui.chapters.isEmpty()) Res.string.player_next else Res.string.player_next_chapter,
        )
    Column(Modifier.fillMaxWidth()) {
        Text(
            now.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { navigate(PodcastKey(now.podcastId)) }) { Text(now.podcastTitle) }
        if (now.isVideo) Text(stringResource(UiRes.string.summary_video))
        ui.currentChapter?.let { current ->
            TextButton({
                openTab(PlayerTab.CHAPTERS)
            }) {
                Text(
                    stringResource(
                        Res.string.player_chapter,
                        current.index + 1,
                        current.chapter.title.orEmpty(),
                    ),
                )
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            PlayerScrubber(
                position?.takeIf {
                    it.episodeId == now.episodeId
                } ?: now.position,
                positionAt,
                seek,
                back,
                forward,
                ui.skipBackMs,
                ui.skipForwardMs,
                ui.chapters,
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = back,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                backLabel
                        },
                ) {
                    Text(
                        stringResource(
                            Res.string.player_back_value,
                            ui.skipBackMs / MILLIS_PER_SECOND,
                        ),
                    )
                }
                TextButton(
                    onClick = previous,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                previousLabel
                        },
                ) { Text(stringResource(Res.string.player_previous_symbol)) }
                PlayButton(now, toggle, playLabel, presentation = TransportPresentation.FULL)
                TextButton(
                    onClick = next,
                    enabled = ui.chapters.isNotEmpty() || now.hasNext,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                nextLabel
                        },
                ) { Text(stringResource(Res.string.player_next_symbol)) }
                TextButton(
                    onClick = forward,
                    modifier =
                        Modifier.semantics {
                            contentDescription = forwardLabel
                        },
                ) {
                    Text(
                        stringResource(
                            Res.string.player_forward_value,
                            ui.skipForwardMs / MILLIS_PER_SECOND,
                        ),
                    )
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { navigate(SpeedKey) }) {
                Text(
                    stringResource(
                        Res.string.player_speed,
                        (
                            ui.effective?.speed?.value
                                ?: 1f
                        ).toString(),
                    ),
                )
            }
            TextButton(onClick = { navigate(SleepTimerKey) }) {
                Text(
                    when (val timer = ui.sleepTimer) {
                        SleepTimerState.Off -> {
                            stringResource(Res.string.sleep_title)
                        }

                        is SleepTimerState.Running -> {
                            stringResource(
                                Res.string.sleep_minutes,
                                (
                                    (
                                        timer.remainingMs +
                                            59_999
                                    ) /
                                        60_000
                                ).toInt(),
                            )
                        }

                        is SleepTimerState.EndOfEpisode -> {
                            stringResource(Res.string.sleep_end)
                        }
                    },
                )
            }
            TextButton(onClick = {
                openTab(PlayerTab.UP_NEXT)
            }) {
                Text(stringResource(Res.string.player_up_next))
            }
        }
        ui.effective?.let {
            Text(
                AttributionText.describe(it.speed.source).asString(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PlayerIssue(
            now.issue ?: if (now.phase ==
                PlayerPhase.ERROR
            ) {
                PlaybackIssue.PLAYER_ERROR
            } else {
                null
            },
            consent,
            declineConsent,
            toggle,
        ) { navigate(SettingsKey(SettingsPage.PLAYBACK)) }
        val tabs =
            PlayerTab.entries.filter {
                it != PlayerTab.CHAPTERS ||
                    ui.chapters.isNotEmpty()
            }
        if (!compactLandscape || panelOpen) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                tabs.forEach { tab ->
                    TextButton(
                        onClick = { openTab(tab) },
                        modifier =
                            Modifier
                                .heightIn(min = 48.dp)
                                .background(
                                    if (panelOpen && activeTab == tab) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                    RoundedCornerShape(16.dp),
                                ).semantics {
                                    role = androidx.compose.ui.semantics.Role.Tab
                                    selected = panelOpen && activeTab == tab
                                },
                    ) {
                        Text(
                            when (tab) {
                                PlayerTab.UP_NEXT -> {
                                    stringResource(Res.string.player_up_next)
                                }

                                PlayerTab.CHAPTERS -> {
                                    stringResource(Res.string.player_chapters, ui.chapters.size)
                                }

                                PlayerTab.NOTES -> {
                                    stringResource(Res.string.player_notes)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (panelOpen) {
            Box(Modifier.fillMaxWidth().heightIn(max = upNextMaxHeight)) {
                when (activeTab) {
                    PlayerTab.UP_NEXT -> {
                        upNext()
                    }

                    PlayerTab.CHAPTERS -> {
                        ChapterList(ui.chapters, ui.currentChapter, seek)
                    }

                    PlayerTab.NOTES -> {
                        PlayerNotes(
                            notes,
                            now.position.durationMs,
                            seek,
                            onLink,
                            now.episodeId,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(
    now: NowPlaying,
    toggle: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    presentation: TransportPresentation = TransportPresentation.MINI,
) {
    val playing = now.isPlaying || now.playWhenReady
    val corner by animateDpAsState(if (playing) 16.dp else 36.dp, NeutrodyneMotion.spatial())
    val icon = if (playing) NdIcons.Pause else NdIcons.PlayArrow
    val size = if (presentation == TransportPresentation.FULL) 72.dp else 48.dp
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (presentation == TransportPresentation.FULL) {
            FilledIconButton(onClick = toggle, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(corner)) {
                Icon(icon, contentDescription = label)
            }
        } else {
            NdIconButton(toggle, icon, label, Modifier.fillMaxSize())
        }
        if (now.phase ==
            PlayerPhase.BUFFERING
        ) {
            CircularProgressIndicator(
                Modifier.fillMaxSize().padding(2.dp).clearAndSetSemantics {},
                strokeWidth = 2.dp,
            )
        }
    }
}

private enum class TransportPresentation { MINI, FULL }

@Composable
private fun PlayerMenu(
    now: NowPlaying,
    navigate: (NavKey) -> Unit,
    markPlayed: () -> Unit,
    stop: () -> Unit,
    hasChapters: Boolean,
    nextEpisode: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        NdIconButton({ open = true }, NdIcons.MoreVert, stringResource(Res.string.player_more))
        DropdownMenu(open, { open = false }) {
            if (hasChapters) {
                DropdownMenuItem(text = {
                    Text(
                        stringResource(Res.string.player_next),
                    )
                }, enabled = now.hasNext, onClick = {
                    open = false
                    nextEpisode()
                })
            }
            DropdownMenuItem(text = { Text(stringResource(Res.string.player_episode)) }, onClick = {
                open = false
                navigate(EpisodeKey(now.episodeId))
            })
            DropdownMenuItem(text = { Text(stringResource(Res.string.player_podcast)) }, onClick = {
                open = false
                navigate(PodcastKey(now.podcastId))
            })
            DropdownMenuItem(text = { Text(stringResource(Res.string.player_mark_skip)) }, onClick = {
                open = false
                markPlayed()
            })
            DropdownMenuItem(text = { Text(stringResource(Res.string.player_stop)) }, onClick = {
                open = false
                stop()
            })
        }
    }
}

@Composable
private fun PlayerIssue(
    issue: PlaybackIssue?,
    consent: () -> Unit,
    decline: () -> Unit,
    retry: () -> Unit,
    settings: () -> Unit,
) {
    val text =
        when (issue) {
            null -> return
            PlaybackIssue.NETWORK_LOST -> Res.string.player_network_lost
            PlaybackIssue.METERED_PAUSE -> Res.string.player_mobile_consent
            PlaybackIssue.METERED_BLOCKED -> Res.string.player_metered_blocked
            PlaybackIssue.LOCAL_FILE_MISSING -> Res.string.player_file_missing
            PlaybackIssue.YOUTUBE_RATE_LIMITED -> Res.string.player_rate_limited
            PlaybackIssue.YOUTUBE_BREAKER_OPEN -> Res.string.player_youtube_unavailable
            PlaybackIssue.PLAYER_ERROR -> Res.string.player_error
        }
    Column {
        Text(stringResource(text), color = MaterialTheme.colorScheme.error)
        when (issue) {
            PlaybackIssue.METERED_PAUSE -> {
                FlowRow {
                    TextButton(onClick = consent) { Text(stringResource(Res.string.player_continue)) }
                    TextButton(onClick = decline) { Text(stringResource(Res.string.player_not_now)) }
                }
            }

            PlaybackIssue.METERED_BLOCKED -> {
                TextButton(onClick = settings) { Text(stringResource(Res.string.player_settings)) }
            }

            PlaybackIssue.PLAYER_ERROR -> {
                TextButton(onClick = retry) { Text(stringResource(Res.string.player_play)) }
            }

            else -> {
                Unit
            }
        }
    }
}

/** The frame clock schedules work; elapsedRealtime supplies the sample's time base, including sleep. */
@Composable
internal fun PlayerScrubber(
    snapshot: PositionSnapshot,
    positionAt: (PositionSnapshot) -> Long,
    seek: (Long) -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    backMs: Long,
    forwardMs: Long,
    chapters: List<EpisodeChapter> = emptyList(),
) {
    var displayed by remember(snapshot) { mutableLongStateOf(positionAt(snapshot)) }
    var dragged by remember(snapshot.episodeId) { mutableStateOf<Long?>(null) }
    var showTotal by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(snapshot, positionAt) {
        displayed = positionAt(snapshot)
        if (snapshot.advancing) {
            while (true) withFrameMillis { displayed = positionAt(snapshot) }
        }
    }
    val duration = snapshot.durationMs?.takeIf { it > 0 }
    val current = (dragged ?: displayed).coerceIn(0L, duration ?: Long.MAX_VALUE)
    val backLabel = stringResource(Res.string.player_back, backMs / MILLIS_PER_SECOND)
    val forwardLabel = stringResource(Res.string.player_forward, forwardMs / MILLIS_PER_SECOND)
    val durationText = duration?.let(::playerTime) ?: stringResource(Res.string.player_unknown_duration)
    val description =
        stringResource(
            Res.string.player_position,
            accessiblePlayerTime(current),
            duration?.let { accessiblePlayerTime(it) } ?: durationText,
        )
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Slider(
            value = current.toFloat(),
            onValueChange = { dragged = it.toLong() },
            valueRange = 0f..(duration?.toFloat() ?: 1f),
            enabled = duration != null,
            onValueChangeFinished = {
                dragged?.let(seek)
                dragged = null
            },
            modifier =
                Modifier
                    .testTag("playerScrubber")
                    .drawBehind {
                        if (duration != null) {
                            chapters.forEach { chapter ->
                                if (chapter.startMs in 0..duration) {
                                    val x = size.width * chapter.startMs.toFloat() / duration
                                    drawLine(
                                        tickColor,
                                        Offset(x, size.height / 2 - 5.dp.toPx()),
                                        Offset(
                                            x,
                                            size.height / 2 + 5.dp.toPx(),
                                        ),
                                        1.dp.toPx(),
                                    )
                                }
                            }
                        }
                    }.semantics {
                        stateDescription = description
                        customActions =
                            listOf(
                                CustomAccessibilityAction(backLabel) {
                                    back()
                                    true
                                },
                                CustomAccessibilityAction(forwardLabel) {
                                    forward()
                                    true
                                },
                            )
                    },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playerTime(current))
            Text(
                duration?.let { if (showTotal) playerTime(it) else "−${playerTime((it - current).coerceAtLeast(0))}" }
                    ?: durationText,
                Modifier.clickable { showTotal = !showTotal },
            )
        }
    }
}

@Composable
private fun accessiblePlayerTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / MILLIS_PER_SECOND
    val minutes = (totalSeconds / SECONDS_PER_MINUTE).toInt()
    val seconds = (totalSeconds % SECONDS_PER_MINUTE).toInt()
    val secondsText = pluralStringResource(Res.plurals.player_seconds, seconds, seconds)
    if (minutes == 0) return secondsText

    val minutesText = pluralStringResource(Res.plurals.player_minutes, minutes, minutes)
    if (seconds == 0) return minutesText

    return stringResource(Res.string.player_time_description, minutesText, secondsText)
}

internal fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    return "$minutes:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

private val MINI_HEIGHT = 64.dp
private val PLAYER_HEADER_HEIGHT = 56.dp
private val MINI_ANCHOR_SPACE = 72.dp
private val MINI_TEXT_START = 64.dp
private val DISMISS_DISTANCE = 96.dp
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val WIDE_ART_ASPECT = 16f / 9f
private const val UP_NEXT_HEIGHT_FRACTION = 0.5f
private const val SETTLE_FRACTION = 0.5f
private const val HEADER_SCRIM_ALPHA = 0.8f
private const val ART_SHADOW_DP = 12f
private const val ART_SHADOW_ALPHA = 0.3f
private const val MINI_SEMANTICS_END = 0.2f
private const val FULL_SEMANTICS_START = 0.6f

private enum class PlayerTab { UP_NEXT, CHAPTERS, NOTES }

@Composable
private fun PlayerNotes(
    state: PlayerNotesState,
    durationMs: Long?,
    seek: (Long) -> Unit,
    link: (String) -> Unit,
    episodeId: Long,
) {
    var revealed by rememberSaveable(episodeId) { mutableStateOf(false) }
    val mode =
        if (revealed && !state.offline &&
            state.imageMode == ShowNotesImageMode.TAP_TO_LOAD
        ) {
            ShowNotesImageMode.SHOWN
        } else {
            state.imageMode
        }
    val notes = state.notes.takeIf { state.episodeId == episodeId }
    if (notes == null || notes.blocks.isEmpty()) {
        Text(stringResource(Res.string.player_no_notes))
        return
    }
    LazyColumn {
        showNotes(notes, if (state.offline) ShowNotesImageMode.BLOCKED else mode, durationMs, link, seek, {
            revealed =
                true
        })
    }
}
