// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.navigation.PaneLayout
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.ui.LocalUiClock
import ch.lkmc.neutrodyne.core.ui.root.PlayerSlotState
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import kotlin.test.assertEquals

internal enum class PlayerVisualState {
    MINI,
    MINI_BUFFERING,
    MINI_INSET,
    SPEED,
}

internal enum class PlayerAppearance { LIGHT, DARK, STRESS }

internal enum class PlayerVisual(
    val state: PlayerVisualState,
    val appearance: PlayerAppearance,
) {
    MINI_PAUSED(PlayerVisualState.MINI, PlayerAppearance.LIGHT),
    MINI_BUFFERING_DARK(PlayerVisualState.MINI_BUFFERING, PlayerAppearance.DARK),
    MINI_INSET(PlayerVisualState.MINI_INSET, PlayerAppearance.LIGHT),
    SPEED(PlayerVisualState.SPEED, PlayerAppearance.LIGHT),
    SPEED_DARK(PlayerVisualState.SPEED, PlayerAppearance.DARK),
    SPEED_STRESS(PlayerVisualState.SPEED, PlayerAppearance.STRESS),
}

internal const val PLAYER_DESKTOP_WIDTH_DP = 900
internal const val PLAYER_CAPTURE_HEIGHT_DP = 640
internal const val PLAYER_STRESS_FONT_SCALE = 2f

private const val PLAYER_RAIL_INSET_DP = 96

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
            PlayerVisualMini(visual)
        }
    }
}

@Composable
private fun PlayerVisualMini(visual: PlayerVisual) {
    val now = playerVisualNow(visual)
    BoxWithConstraints {
        val inset = visual.state == PlayerVisualState.MINI_INSET
        val contentWidth = if (inset) maxWidth - PLAYER_RAIL_INSET_DP.dp else maxWidth
        MiniPlayer(
            ui = PlayerUiState(now = now),
            position = now.position,
            slot =
                PlayerSlotState(
                    PaneLayout(partitions = if (inset) 2 else 1, contentWidthDp = contentWidth.value.toInt()),
                    {},
                ),
            toggle = {},
            back = {},
            forward = {},
            dismiss = {},
            consent = {},
            declineConsent = {},
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

/** Static `now` per state; monogram `m-` refs paint the cover live so nothing hits the image loader. */
private fun playerVisualNow(visual: PlayerVisual): NowPlaying =
    when (visual.state) {
        PlayerVisualState.MINI_BUFFERING -> sampleNow(playing = true).copy(phase = PlayerPhase.BUFFERING)
        else -> sampleNow()
    }
