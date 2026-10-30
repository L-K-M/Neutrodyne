// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverAspect
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.LocalReducedMotion
import ch.lkmc.neutrodyne.core.designsystem.theme.NeutrodyneMotion
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.PlayerTimeDisplay
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import ch.lkmc.neutrodyne.core.navigation.SettingsPage
import ch.lkmc.neutrodyne.core.navigation.SpeedKey
import ch.lkmc.neutrodyne.core.ui.AttributionText
import ch.lkmc.neutrodyne.core.ui.MeteredConsentDialog
import ch.lkmc.neutrodyne.core.ui.asString
import ch.lkmc.neutrodyne.core.ui.platform.LocalPlatformActions
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.action_share
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
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
import ch.lkmc.neutrodyne.feature.player.resources.player_queue_count
import ch.lkmc.neutrodyne.feature.player.resources.player_rate_limited
import ch.lkmc.neutrodyne.feature.player.resources.player_seconds
import ch.lkmc.neutrodyne.feature.player.resources.player_settings
import ch.lkmc.neutrodyne.feature.player.resources.player_speed
import ch.lkmc.neutrodyne.feature.player.resources.player_stop
import ch.lkmc.neutrodyne.feature.player.resources.player_time_description
import ch.lkmc.neutrodyne.feature.player.resources.player_time_remaining
import ch.lkmc.neutrodyne.feature.player.resources.player_time_total
import ch.lkmc.neutrodyne.feature.player.resources.player_unknown_duration
import ch.lkmc.neutrodyne.feature.player.resources.player_youtube_unavailable
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import ch.lkmc.neutrodyne.core.ui.resources.Res as UiRes

/** One surface and artwork morph continuously between the mini and full layouts. */
@Composable
internal fun PlayerSheet(
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
    notes: @Composable () -> Unit = {},
    consentAlways: () -> Unit = {},
    toggleTime: () -> Unit = {},
    share: (() -> Unit)? = null,
    retry: () -> Unit = toggle,
) {
    val now = ui.now
    val reducedMotion = LocalReducedMotion.current
    val motion = if (reducedMotion) SheetMotion.IMMEDIATE else SheetMotion.ANIMATED
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val panelPeekHeight = playerTabsPeekHeight(density.fontScale)
    val panelState = remember { PlayerSheetState() }
    var selectedTabName by rememberSaveable { mutableStateOf(PlayerTab.UP_NEXT.name) }
    val selectedTab = PlayerTab.entries.firstOrNull { it.name == selectedTabName } ?: PlayerTab.UP_NEXT
    val expanded = state.settledExpanded && now != null
    val settled by rememberUpdatedState(slot.onExpandedSettled)
    val dismissPaused by rememberUpdatedState(dismiss)
    SideEffect { settled(expanded) }
    DisposableEffect(Unit) { onDispose { settled(false) } }
    var consumedExpand by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(slot.expandRequest, now?.episodeId) {
        if (slot.expandRequest > consumedExpand && now != null) {
            consumedExpand = slot.expandRequest
            state.expand(motion)
        }
    }
    var consumedCollapse by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(slot.collapseRequest) {
        if (slot.collapseRequest > consumedCollapse) {
            consumedCollapse = slot.collapseRequest
            state.collapse(motion)
        }
    }
    LaunchedEffect(now == null) {
        if (now == null) state.collapse(SheetMotion.IMMEDIATE)
    }
    LaunchedEffect(expanded) {
        if (!expanded) panelState.collapse(motion)
    }
    LaunchedEffect(state.drag.settledValue) {
        if (state.drag.settledValue == PlayerSheetValue.Dismissed) {
            dismissPaused()
            state.resetDismissed()
        }
    }
    Box(modifier.fillMaxSize()) {
        // Only the frontmost player layer owns Back.
        PlayerBackHandler(
            state,
            if (panelState.canCollapse) PlayerBackAvailability.INACTIVE else PlayerBackAvailability.ACTIVE,
        )
        // Keep Back ownership while the closing panel leaves the visible composition.
        PlayerBackHandler(panelState)
        // Consent may arrive before nowPlaying exists, so it must outlive the visible sheet.
        if (ui.consent != null) MeteredConsentDialog(consent, consentAlways, declineConsent)
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
        val contextTitle = now.context?.takeIf { it.type != ContextType.EXTERNAL }?.title
        val nowPlayingLabel = stringResource(Res.string.player_now)
        val expand: () -> Unit = { scope.launch { state.expand(motion) } }
        val collapse: () -> Unit = { scope.launch { state.collapse(motion) } }
        val navigate: (NavKey) -> Unit = { key ->
            if (key == SpeedKey) {
                slot.navigate(key)
            } else {
                scope.launch {
                    state.collapse(motion)
                    slot.navigate(key)
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().testTag("playerHost")) {
            val heightPx = with(density) { maxHeight.toPx() }
            val bottomInset = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
            val contentBottom = slot.contentBottomPx.takeIf { it > 0f } ?: heightPx
            val collapsedPx = (contentBottom - with(density) { MINI_ANCHOR_SPACE.toPx() }).coerceAtLeast(0f)
            val dismissDistance = with(density) { DISMISS_DISTANCE.toPx() }
            LaunchedEffect(collapsedPx, paused, dismissDistance) {
                state.setAnchors(
                    collapsedPx,
                    dismissDistance,
                    if (paused) SheetDismissal.ALLOWED else SheetDismissal.BLOCKED,
                )
            }
            val progress = state.progress
            val statusBar =
                with(density) {
                    WindowInsets.statusBars
                        .getTop(this)
                        .toDp()
                        .value
                }
            // Measure actual lines; nonlinear Android font scaling changes their height.
            val headerHeight =
                with(density) {
                    val titleHeight =
                        textMeasurer
                            .measure(nowPlayingLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            .size.height
                            .toDp()
                    val contextHeight =
                        contextTitle?.let {
                            textMeasurer
                                .measure(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                .size.height
                                .toDp()
                        } ?: 0.dp
                    (titleHeight + contextHeight).coerceAtLeast(MIN_PLAYER_HEADER_HEIGHT_DP.dp)
                }
            val wideArt = now.sourceType != SourceType.RSS
            val morph =
                PlayerMorph.at(
                    progress,
                    PlayerGeometry(
                        maxWidth.value,
                        maxHeight.value,
                        statusBar,
                        if (wideArt) WIDE_ART_ASPECT else 1f,
                        startInset = (maxWidth.value - slot.layout.contentWidthDp).coerceAtLeast(0f),
                        headerHeight = headerHeight.value,
                    ),
                )
            val panelHeight =
                (maxHeight - statusBar.dp - headerHeight - bottomInset).coerceAtLeast(
                    panelPeekHeight,
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
                        .padding(start = morph.inset.dp, end = morph.endInset.dp)
                        .fillMaxWidth()
                        .height(sheetHeight)
                        .testTag("player_sheet"),
                shape = RoundedCornerShape(morph.corner.dp),
                contentColor = MaterialTheme.colorScheme.onSurface,
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
                                    this[SemanticsProperties.TestTag] = "mini_player"
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
                                }.padding(top = statusBar.dp, bottom = panelPeekHeight + bottomInset)
                                .verticalScroll(rememberScrollState())
                                .testTag("full_player")
                                // The panel owns accessibility while it covers the transport.
                                .then(if (panelState.canCollapse) Modifier.clearAndSetSemantics {} else Modifier),
                        ) {
                            Spacer(Modifier.height(headerHeight + artworkHeight.dp + 16.dp))
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
                                    ui.timeDisplay,
                                    toggleTime,
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
                                    TextButton(onClick = {
                                        selectedTabName = PlayerTab.UP_NEXT.name
                                        scope.launch { panelState.expand(motion) }
                                    }) { Text(stringResource(Res.string.player_queue_count, ui.upNextCount)) }
                                    if (share != null) {
                                        val sharing = LocalPlatformActions.current.share
                                        TextButton(onClick = share) {
                                            Text(
                                                stringResource(
                                                    if (sharing !=
                                                        null
                                                    ) {
                                                        UiRes.string.action_share
                                                    } else {
                                                        UiRes.string.action_copy_link
                                                    },
                                                ),
                                            )
                                        }
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
                                    retry,
                                    declineConsent,
                                    retry,
                                ) { navigate(SettingsKey(SettingsPage.PLAYBACK)) }
                            }
                        }
                    }
                    if (progress > FULL_SEMANTICS_START) {
                        PlayerTabs(
                            state = panelState,
                            selected = selectedTab,
                            onSelect = { selectedTabName = it.name },
                            count = ui.upNextCount,
                            upNext = upNext,
                            notes = notes,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .zIndex(1f)
                                    .graphicsLayer { alpha = morph.controlsAlpha }
                                    .padding(bottom = bottomInset)
                                    .fillMaxWidth()
                                    .height(panelHeight),
                        )
                    }
                    // Keep the header's gesture node alive until collapse settles, even after its contents fade.
                    Row(
                        Modifier
                            .padding(top = statusBar.dp)
                            .fillMaxWidth()
                            .height(headerHeight)
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
                                Text(
                                    nowPlayingLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                contextTitle?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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

/** Honor the host animation policy; elapsedRealtime remains the time base, including sleep. */
@Composable
internal fun PlayerScrubber(
    snapshot: PositionSnapshot,
    positionAt: (PositionSnapshot) -> Long,
    seek: (Long) -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    backMs: Long,
    forwardMs: Long,
    timeDisplay: PlayerTimeDisplay = PlayerTimeDisplay.REMAINING,
    toggleTime: () -> Unit = {},
) {
    var displayed by remember(snapshot) { mutableLongStateOf(positionAt(snapshot)) }
    var dragged by remember(snapshot.episodeId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(snapshot, positionAt) {
        displayed = positionAt(snapshot)
        if (snapshot.advancing) {
            while (true) withInfiniteAnimationFrameMillis { displayed = positionAt(snapshot) }
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
        key(snapshot.episodeId) {
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
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playerTime(current))
            val toggleLabel =
                stringResource(
                    if (timeDisplay == PlayerTimeDisplay.REMAINING) {
                        Res.string.player_time_total
                    } else {
                        Res.string.player_time_remaining
                    },
                )
            Text(
                duration?.let {
                    if (timeDisplay == PlayerTimeDisplay.TOTAL) {
                        playerTime(it)
                    } else {
                        "−${playerTime((it - current).coerceAtLeast(0))}"
                    }
                } ?: durationText,
                Modifier.clickable(enabled = duration != null, onClickLabel = toggleLabel, onClick = toggleTime),
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
private val MINI_ANCHOR_SPACE = 72.dp
private val MINI_TEXT_START = 64.dp
private val DISMISS_DISTANCE = 96.dp
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val WIDE_ART_ASPECT = 16f / 9f
private const val SETTLE_FRACTION = 0.5f
private const val MINI_SEMANTICS_END = 0.2f
private const val FULL_SEMANTICS_START = 0.6f
