// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testMonogramArtwork
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.RowCaps
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import ch.lkmc.neutrodyne.core.ui.UpNextCurrent
import ch.lkmc.neutrodyne.core.ui.UpNextList
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.core.ui.showNotes
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import kotlin.test.assertEquals

internal enum class PlayerVisualState {
    MINI,
    MINI_BUFFERING,
    MINI_INSET,
    FULL,
    PANEL_UP_NEXT,
    PANEL_NOTES,
    WIDE_ART,
    SPEED,
    ;

    /** States that start settled at Expanded; SPEED renders no sheet at all. */
    val expanded: Boolean
        get() = this == FULL || this == PANEL_UP_NEXT || this == PANEL_NOTES || this == WIDE_ART
}

internal enum class PlayerAppearance { LIGHT, DARK, STRESS }

internal enum class PlayerVisual(
    val state: PlayerVisualState,
    val appearance: PlayerAppearance,
) {
    MINI_PAUSED(PlayerVisualState.MINI, PlayerAppearance.LIGHT),
    MINI_BUFFERING_DARK(PlayerVisualState.MINI_BUFFERING, PlayerAppearance.DARK),
    FULL(PlayerVisualState.FULL, PlayerAppearance.LIGHT),
    FULL_DARK(PlayerVisualState.FULL, PlayerAppearance.DARK),
    UP_NEXT_PANEL(PlayerVisualState.PANEL_UP_NEXT, PlayerAppearance.LIGHT),
    NOTES_PANEL(PlayerVisualState.PANEL_NOTES, PlayerAppearance.DARK),
    FULL_STRESS(PlayerVisualState.FULL, PlayerAppearance.STRESS),
    MINI_INSET(PlayerVisualState.MINI_INSET, PlayerAppearance.LIGHT),
    WIDE_ART(PlayerVisualState.WIDE_ART, PlayerAppearance.LIGHT),
    SPEED(PlayerVisualState.SPEED, PlayerAppearance.LIGHT),
    SPEED_DARK(PlayerVisualState.SPEED, PlayerAppearance.DARK),
    SPEED_STRESS(PlayerVisualState.SPEED, PlayerAppearance.STRESS),
}

internal const val PLAYER_DESKTOP_WIDTH_DP = 900
internal const val PLAYER_CAPTURE_HEIGHT_DP = 640
internal const val PLAYER_STRESS_FONT_SCALE = 2f

/**
 * `panelState` stays private to `PlayerSheet`, so the runners open the panel through the strip's
 * own controls; the English literals resolve because the runners pin the locale/qualifiers.
 */
internal const val PLAYER_EXPAND_PANEL_LABEL = "Expand player tabs"
internal const val PLAYER_NOTES_TAB_LABEL = "Notes"

private const val PLAYER_RAIL_INSET_DP = 96
private const val PLAYER_POSITION_SAMPLE_MS = 1_000L

/** Matches `samplePosition`'s 60 s duration so the 0:12 timestamp stays a link. */
private const val PLAYER_NOTES_DURATION_MS = 60_000L
private const val PLAYER_NOTES_PADDING_DP = 16

private val playerVisualQueueRows =
    listOf(
        testEpisodeRow(2, title = "Next in the list", artwork = testMonogramArtwork("queue-2")),
        testEpisodeRow(
            3,
            title = "A YouTube episode",
            sourceType = SourceType.YOUTUBE_CHANNEL,
            externalMediaId = "abcdefghijk",
            artwork = testMonogramArtwork("queue-3"),
        ),
    )

private val playerVisualNotes =
    ShowNotes(
        listOf(
            ShowNoteBlock.Heading(2, listOf(ShowNoteSpan.Text("Fixture notes"))),
            ShowNoteBlock.Paragraph(
                listOf(ShowNoteSpan.Text("Static notes; the panel slot needs no repository.")),
            ),
            ShowNoteBlock.Paragraph(
                listOf(
                    ShowNoteSpan.Timestamp("0:12", 12_000),
                    ShowNoteSpan.Text(" mark, then "),
                    ShowNoteSpan.Link("a fixture link", "https://neutrodyne.ch"),
                ),
            ),
        ),
    )

@Composable
internal fun PlayerVisualContent(visual: PlayerVisual) {
    assertEquals(MaterialTheme.colorScheme.onBackground, LocalContentColor.current)
    val density = LocalDensity.current
    val stress = visual.appearance == PlayerAppearance.STRESS
    CompositionLocalProvider(
        LocalUiClock provides TestClock(),
        LocalDensity provides Density(density.density, if (stress) PLAYER_STRESS_FONT_SCALE else density.fontScale),
        LocalLayoutDirection provides if (stress) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        if (visual.state == PlayerVisualState.SPEED) {
            PlayerVisualSpeed()
        } else {
            PlayerVisualSheet(visual)
        }
    }
}

@Composable
private fun PlayerVisualSheet(visual: PlayerVisual) {
    val now = playerVisualNow(visual)
    val sheetState =
        remember {
            PlayerSheetState(if (visual.state.expanded) PlayerSheetValue.Expanded else PlayerSheetValue.Collapsed)
        }
    BoxWithConstraints {
        val inset = visual.state == PlayerVisualState.MINI_INSET
        val contentWidth = if (inset) maxWidth - PLAYER_RAIL_INSET_DP.dp else maxWidth
        PlayerSheet(
            ui = PlayerUiState(now = now, upNextCount = playerVisualQueueRows.size),
            position = now.position,
            slot =
                PlayerSlotState(
                    PaneLayout(
                        partitions = if (inset) 2 else 1,
                        playerPanel = false,
                        contentWidthDp = contentWidth.value.toInt(),
                    ),
                    {},
                ),
            positionAt = { it.positionAt(PLAYER_POSITION_SAMPLE_MS) },
            toggle = {},
            back = {},
            forward = {},
            previous = {},
            next = {},
            seek = {},
            dismiss = {},
            stop = {},
            markPlayed = {},
            consent = {},
            declineConsent = {},
            state = sheetState,
            upNext = { PlayerVisualUpNext(now) },
            notes = { PlayerVisualNotes() },
        )
    }
}

@Composable
private fun PlayerVisualUpNext(now: NowPlaying) {
    UpNextList(
        current = UpNextCurrent(now.episodeId, now.title, now.podcastTitle, now.artwork, now.isPlaying),
        upNext = playerVisualQueueRows.map { UpNextItem(it.id, it) },
        context = null,
        live = emptyMap(),
        caps = RowCaps.FULL.copy(downloads = false),
        onAction = {},
        onMove = { _, _ -> },
        onRemove = {},
        onStopAfter = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PlayerVisualNotes() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PLAYER_NOTES_PADDING_DP.dp)) {
        showNotes(
            notes = playerVisualNotes,
            imageMode = ShowNotesImageMode.SHOWN,
            durationMs = PLAYER_NOTES_DURATION_MS,
            onLink = {},
            onTimestamp = {},
            onLoadImages = {},
        )
    }
}

@Composable
private fun PlayerVisualSpeed() {
    // The presets editor is the only dialog SpeedSheet opens; this base content is the capture root.
    SpeedSheet(
        now =
            sampleNow(playing = true).copy(
                context = PlayContextInfo(ContextType.GROUP, 3, "news", FeedOrder.NEWEST_FIRST, 0, MediaFilter.ALL),
            ),
        effective =
            EffectivePlayback(
                Effective(1.2f, SettingSource.PodcastOverride),
                Effective(true, SettingSource.Group("u1", "news")),
            ),
        presets = PlaybackSettingKeys.DEFAULT_SPEED_PRESETS,
        onSpeed = { _, _ -> },
        onSkipSilence = { _, _ -> },
        onPresets = {},
        onDismiss = {},
    )
}

/**
 * Static `now` per state; monogram `m-` refs paint the cover live so nothing hits the image
 * loader. STRESS swaps in long copy and a group context so 2x text and RTL exercise real wrapping.
 */
private fun playerVisualNow(visual: PlayerVisual): NowPlaying {
    val now =
        when (visual.state) {
            PlayerVisualState.MINI_BUFFERING -> {
                sampleNow(playing = true).copy(phase = PlayerPhase.BUFFERING)
            }

            PlayerVisualState.WIDE_ART -> {
                sampleNow().copy(
                    podcastTitle = "A video channel",
                    artwork = testMonogramArtwork("player-video"),
                    sourceType = SourceType.YOUTUBE_CHANNEL,
                    isVideo = true,
                )
            }

            else -> {
                sampleNow()
            }
        }
    if (visual.appearance != PlayerAppearance.STRESS) return now
    return now.copy(
        title = "A very long episode title that wraps onto a second line in the expanded player",
        podcastTitle = "A podcast with a very long name for the stress capture",
        context =
            PlayContextInfo(
                ContextType.GROUP,
                1,
                "a long group name that wraps",
                FeedOrder.NEWEST_FIRST,
                0,
                MediaFilter.ALL,
            ),
    )
}
