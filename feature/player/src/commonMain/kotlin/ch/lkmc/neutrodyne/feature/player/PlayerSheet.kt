// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import ch.lkmc.neutrodyne.core.ui.AttributionText
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resolve
import ch.lkmc.neutrodyne.core.ui.root.LocalSnackbarHost
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.core.ui.root.ShowUserMessages
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_back
import ch.lkmc.neutrodyne.feature.player.resources.player_back_value
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
import ch.lkmc.neutrodyne.feature.player.resources.player_next_symbol
import ch.lkmc.neutrodyne.feature.player.resources.player_not_now
import ch.lkmc.neutrodyne.feature.player.resources.player_now
import ch.lkmc.neutrodyne.feature.player.resources.player_pause
import ch.lkmc.neutrodyne.feature.player.resources.player_paused
import ch.lkmc.neutrodyne.feature.player.resources.player_play
import ch.lkmc.neutrodyne.feature.player.resources.player_playing
import ch.lkmc.neutrodyne.feature.player.resources.player_podcast
import ch.lkmc.neutrodyne.feature.player.resources.player_position
import ch.lkmc.neutrodyne.feature.player.resources.player_previous
import ch.lkmc.neutrodyne.feature.player.resources.player_previous_symbol
import ch.lkmc.neutrodyne.feature.player.resources.player_rate_limited
import ch.lkmc.neutrodyne.feature.player.resources.player_seconds
import ch.lkmc.neutrodyne.feature.player.resources.player_settings
import ch.lkmc.neutrodyne.feature.player.resources.player_speed
import ch.lkmc.neutrodyne.feature.player.resources.player_stop
import ch.lkmc.neutrodyne.feature.player.resources.player_time_description
import ch.lkmc.neutrodyne.feature.player.resources.player_unknown_duration
import ch.lkmc.neutrodyne.feature.player.resources.player_up_next
import ch.lkmc.neutrodyne.feature.player.resources.player_write_failed
import ch.lkmc.neutrodyne.feature.player.resources.player_youtube_unavailable
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

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
    val failed by episodeVm.writeFailed.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val failureText = stringResource(Res.string.player_write_failed)
    LaunchedEffect(failed) {
        if (failed) {
            snackbar.showSnackbar(failureText)
            episodeVm.acknowledgeFailure()
        }
    }
    Box(modifier) {
        ShowUserMessages(ui.messages, vm::onMessageShown) { id ->
            when (vm.onMessageAction(id)) {
                PlayerMessageAction.RETRY -> vm.togglePlayPause()
                PlayerMessageAction.PLAYBACK_SETTINGS -> slot.navigate(SettingsKey(SettingsPage.PLAYBACK))
                null -> Unit
            }
        }
        PlayerSheet(
            ui = ui,
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
) {
    val now = ui.now
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val expanded = state.settledExpanded && now != null
    val settled by rememberUpdatedState(slot.onExpandedSettled)
    val dismissPaused by rememberUpdatedState(dismiss)
    SideEffect { settled(expanded) }
    DisposableEffect(Unit) { onDispose { settled(false) } }
    var consumedExpand by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(slot.expandRequest, now?.episodeId) {
        if (slot.expandRequest > consumedExpand && now != null) {
            consumedExpand = slot.expandRequest
            state.expand(reducedMotion)
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
        PlayerBackHandler(state)
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

        val expandLabel = stringResource(Res.string.player_expand)
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
        val backLabel = stringResource(Res.string.player_back, ui.skipBackMs / MILLIS_PER_SECOND)
        val previousLabel = stringResource(Res.string.player_previous)
        val nextLabel = stringResource(Res.string.player_next)
        val paused = !now.isPlaying && !now.playWhenReady
        val expand: () -> Unit = { scope.launch { state.expand(reducedMotion) } }
        val collapse: () -> Unit = { scope.launch { state.collapse(reducedMotion) } }
        val navigate: (NavKey) -> Unit = { key ->
            if (key == SpeedKey) {
                slot.navigate(key)
            } else {
                scope.launch {
                    state.collapse(reducedMotion)
                    slot.navigate(key)
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().testTag("playerHost")) {
            val upNextMaxHeight = maxHeight * UP_NEXT_HEIGHT_FRACTION
            val heightPx = with(density) { maxHeight.toPx() }
            val contentBottom = slot.contentBottomPx.takeIf { it > 0f } ?: heightPx
            val collapsedPx = (contentBottom - with(density) { MINI_ANCHOR_SPACE.toPx() }).coerceAtLeast(0f)
            val dismissDistance = with(density) { DISMISS_DISTANCE.toPx() }
            LaunchedEffect(collapsedPx, paused, dismissDistance) {
                state.setAnchors(collapsedPx, dismissDistance, paused)
            }
            val progress = state.progress
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
            val dragModifier =
                Modifier.anchoredDraggable(
                    state.drag,
                    Orientation.Vertical,
                    flingBehavior =
                        AnchoredDraggableDefaults.flingBehavior(
                            state = state.drag,
                            positionalThreshold = { it * SETTLE_FRACTION },
                            animationSpec = NeutrodyneMotion.spatial(reducedMotion),
                        ),
                )
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
                        .padding(horizontal = morph.inset.dp)
                        .fillMaxWidth()
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
                    Modifier.fillMaxSize().then(
                        if (state.drag.settledValue != PlayerSheetValue.Expanded) dragModifier else Modifier,
                    ),
                ) {
                    if (progress < MINI_SEMANTICS_END) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(MINI_HEIGHT)
                                .graphicsLayer { alpha = morph.miniAlpha }
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
                                .verticalScroll(rememberScrollState())
                                .testTag("fullPlayer"),
                        ) {
                            Spacer(Modifier.height(PLAYER_HEADER_HEIGHT + artworkHeight.dp + 16.dp))
                            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                                Text(
                                    now.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { navigate(PodcastKey(now.podcastId)) }) { Text(now.podcastTitle) }
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
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically,
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
                                        enabled = now.hasNext,
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
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
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
                                    TextButton(
                                        onClick = { navigate(UpNextKey) },
                                    ) { Text(stringResource(Res.string.player_up_next)) }
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
                                // W10 installs the shared Up next list here; storage and reordering belong to it.
                                Box(Modifier.fillMaxWidth().heightIn(max = upNextMaxHeight)) { upNext() }
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
                            PlayerMenu(now, navigate, markPlayed, stop)
                        }
                    }
                    CoverArt(
                        ref = now.artwork,
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
                                .then(dragModifier)
                                .testTag("playerArtwork"),
                    )
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
) {
    var open by remember { mutableStateOf(false) }
    Box {
        NdIconButton({ open = true }, NdIcons.MoreVert, stringResource(Res.string.player_more))
        DropdownMenu(open, { open = false }) {
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
                Row {
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
                Modifier.testTag("playerScrubber").semantics {
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
private const val MINI_SEMANTICS_END = 0.2f
private const val FULL_SEMANTICS_START = 0.6f
