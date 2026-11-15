// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.designsystem.components.CoverArt
import ch.lkmc.neutrodyne.core.designsystem.components.CoverAspect
import ch.lkmc.neutrodyne.core.designsystem.components.CoverTier
import ch.lkmc.neutrodyne.core.designsystem.components.NdIconButton
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.designsystem.theme.ArtworkSurface
import ch.lkmc.neutrodyne.core.designsystem.theme.ArtworkTheme
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.ui.MeteredConsentDialog
import ch.lkmc.neutrodyne.core.ui.onSecondaryClick
import ch.lkmc.neutrodyne.core.ui.rememberMonogram
import ch.lkmc.neutrodyne.core.ui.root.PlayerPresentation
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_back
import ch.lkmc.neutrodyne.feature.player.resources.player_back_value
import ch.lkmc.neutrodyne.feature.player.resources.player_description
import ch.lkmc.neutrodyne.feature.player.resources.player_dismiss
import ch.lkmc.neutrodyne.feature.player.resources.player_episode
import ch.lkmc.neutrodyne.feature.player.resources.player_forward
import ch.lkmc.neutrodyne.feature.player.resources.player_forward_value
import ch.lkmc.neutrodyne.feature.player.resources.player_open_radio
import ch.lkmc.neutrodyne.feature.player.resources.player_pause
import ch.lkmc.neutrodyne.feature.player.resources.player_paused
import ch.lkmc.neutrodyne.feature.player.resources.player_play
import ch.lkmc.neutrodyne.feature.player.resources.player_playing
import ch.lkmc.neutrodyne.feature.player.resources.player_podcast
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The docked mini player shown on every screen except the radio home (08 Mini player). The radio
 * is the only full player: tapping the bar, its Open radio menu item and the player deep link all
 * open the radio home. A paused bar can be swiped down to dismiss it.
 */
@Composable
internal fun MiniPlayer(
    ui: PlayerUiState,
    position: PositionSnapshot?,
    slot: PlayerSlotState,
    toggle: () -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    dismiss: () -> Unit,
    consent: () -> Unit,
    declineConsent: () -> Unit,
    modifier: Modifier = Modifier,
    consentAlways: () -> Unit = {},
) {
    // Consent may arrive before nowPlaying exists and on the radio home, so it outlives the bar.
    if (ui.consent != null) MeteredConsentDialog(consent, consentAlways, declineConsent)
    val now = ui.now ?: return
    if (slot.presentation == PlayerPresentation.RADIO_HOME) return

    ArtworkTheme(ui.colors?.seedArgb, ArtworkSurface.MINI) {
        BoxWithConstraints(modifier.fillMaxSize().testTag("playerHost")) {
            val density = LocalDensity.current
            val heightPx = with(density) { maxHeight.toPx() }
            val contentBottom = slot.contentBottomPx.takeIf { it > 0f } ?: heightPx
            val topPx = (contentBottom - with(density) { MINI_ANCHOR_SPACE.toPx() }).coerceAtLeast(0f)
            val startInset = (maxWidth.value - slot.layout.contentWidthDp).coerceAtLeast(0f).dp
            MiniBar(
                now,
                ui,
                position,
                slot,
                toggle,
                back,
                forward,
                dismiss,
                Modifier
                    .offset { IntOffset(0, topPx.roundToInt()) }
                    .padding(start = startInset + MINI_INSET, end = MINI_INSET),
            )
        }
    }
}

@Composable
private fun MiniBar(
    now: NowPlaying,
    ui: PlayerUiState,
    position: PositionSnapshot?,
    slot: PlayerSlotState,
    toggle: () -> Unit,
    back: () -> Unit,
    forward: () -> Unit,
    dismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val paused = !now.isPlaying && !now.playWhenReady
    val currentDismiss by rememberUpdatedState(dismiss)
    // Plain state, so the release check reads every delta already applied.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissDistance = with(density) { DISMISS_DISTANCE.toPx() }
    var menuOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val openLabel = stringResource(Res.string.player_open_radio)
    val dismissLabel = stringResource(Res.string.player_dismiss)
    val playLabel =
        stringResource(if (now.isPlaying || now.playWhenReady) Res.string.player_pause else Res.string.player_play)
    val backLabel = stringResource(Res.string.player_back, ui.skipBackMs / MILLIS_PER_SECOND)
    val forwardLabel = stringResource(Res.string.player_forward, ui.skipForwardMs / MILLIS_PER_SECOND)
    val description =
        stringResource(
            Res.string.player_description,
            now.title,
            now.podcastTitle,
            stringResource(if (now.isPlaying) Res.string.player_playing else Res.string.player_paused),
        )
    val openRadio: () -> Unit = slot.openRadio
    val navigate: (NavKey) -> Unit = slot.navigate

    Surface(
        modifier =
            modifier
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .fillMaxWidth()
                .height(MINI_HEIGHT)
                .draggable(
                    rememberDraggableState { delta ->
                        dragOffset = (dragOffset + delta).coerceIn(0f, dismissDistance)
                    },
                    Orientation.Vertical,
                    enabled = paused,
                    onDragStopped = {
                        if (dragOffset >= dismissDistance * DISMISS_FRACTION) currentDismiss()
                        animate(dragOffset, 0f) { value, _ -> dragOffset = value }
                    },
                ).testTag("player_sheet"),
        shape = RoundedCornerShape(MINI_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .clickable(onClickLabel = openLabel) {
                        focus.requestFocus()
                        openRadio()
                    }.onSecondaryClick {
                        focus.requestFocus()
                        menuOpen = true
                    }.onPreviewKeyEvent { event ->
                        // 08 Context menus: Menu / Shift+F10 opens the mini player's menu without a pointer.
                        val menuKey = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
                        if (event.type == KeyEventType.KeyDown && menuKey) {
                            menuOpen = true
                            true
                        } else {
                            false
                        }
                    }.clearAndSetSemantics {
                        this[SemanticsProperties.TestTag] = "mini_player"
                        contentDescription = description
                        onClick(openLabel) {
                            openRadio()
                            true
                        }
                        customActions =
                            buildList {
                                add(accessibilityAction(playLabel, toggle))
                                add(accessibilityAction(backLabel, back))
                                add(accessibilityAction(forwardLabel, forward))
                                add(accessibilityAction(openLabel, openRadio))
                                if (paused) add(accessibilityAction(dismissLabel, dismiss))
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
                SkipButton(
                    stringResource(Res.string.player_back_value, ui.skipBackMs / MILLIS_PER_SECOND),
                    backLabel,
                    back,
                )
                PlayButton(now, toggle, playLabel)
                SkipButton(
                    stringResource(Res.string.player_forward_value, ui.skipForwardMs / MILLIS_PER_SECOND),
                    forwardLabel,
                    forward,
                )
            }
            val snapshot = position?.takeIf { it.episodeId == now.episodeId } ?: now.position
            val duration = snapshot.durationMs
            if (duration != null && duration > 0L) {
                LinearProgressIndicator(
                    progress = { (snapshot.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(2.dp)
                            .clearAndSetSemantics {},
                )
            }
            CoverArt(
                ref = now.artwork,
                avgArgb = ui.colors?.avgArgb,
                monogram = rememberMonogram(now.podcastTitle),
                title = now.podcastTitle,
                tier = CoverTier.THUMB,
                aspect = if (now.sourceType != SourceType.RSS) CoverAspect.WIDE_16_9 else CoverAspect.SQUARE,
                shape = RoundedCornerShape(MINI_ART_CORNER),
                modifier =
                    Modifier
                        .offset(MINI_ART_OFFSET, MINI_ART_OFFSET)
                        .size(MINI_ART)
                        .testTag("playerArtwork"),
            )
            if (now.issue != null || now.phase == PlayerPhase.ERROR) {
                Box(
                    Modifier
                        .offset(MINI_ART_OFFSET + 4.dp, MINI_ART_OFFSET + 4.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.error),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.player_episode)) },
                    onClick = {
                        menuOpen = false
                        navigate(EpisodeKey(now.episodeId))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.player_podcast)) },
                    onClick = {
                        menuOpen = false
                        navigate(PodcastKey(now.podcastId))
                    },
                )
                DropdownMenuItem(
                    text = { Text(openLabel) },
                    onClick = {
                        menuOpen = false
                        openRadio()
                    },
                )
                if (paused) {
                    DropdownMenuItem(
                        text = { Text(dismissLabel) },
                        onClick = {
                            menuOpen = false
                            dismiss()
                        },
                    )
                }
            }
        }
    }
}

private fun accessibilityAction(
    label: String,
    action: () -> Unit,
): CustomAccessibilityAction =
    CustomAccessibilityAction(label) {
        action()
        true
    }

@Composable
private fun SkipButton(
    text: String,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        contentPadding = PaddingValues(0.dp),
    ) { Text(text) }
}

@Composable
private fun PlayButton(
    now: NowPlaying,
    toggle: () -> Unit,
    label: String,
) {
    val playing = now.isPlaying || now.playWhenReady
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        NdIconButton(toggle, if (playing) NdIcons.Pause else NdIcons.PlayArrow, label, Modifier.fillMaxSize())
        if (now.phase == PlayerPhase.BUFFERING) {
            CircularProgressIndicator(
                Modifier.fillMaxSize().padding(2.dp).clearAndSetSemantics {},
                strokeWidth = 2.dp,
            )
        }
    }
}

private val MINI_HEIGHT = 64.dp
private val MINI_ANCHOR_SPACE = 72.dp
private val MINI_INSET = 8.dp
private val MINI_CORNER = 16.dp
private val MINI_ART = 48.dp
private val MINI_ART_OFFSET = 8.dp
private val MINI_ART_CORNER = 10.dp
private val MINI_TEXT_START = 64.dp
private val DISMISS_DISTANCE = 96.dp
private const val DISMISS_FRACTION = 0.5f
private const val MILLIS_PER_SECOND = 1_000L
